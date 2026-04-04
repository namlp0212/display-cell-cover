package com.example.cellcover.service;

import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.repository.RasterCoverageRepository;
import it.geosolutions.imageioimpl.plugins.cog.DefaultCogImageInputStream;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * Renders per-pixel MAX signal tiles directly from continuous COG files stored in MinIO.
 *
 * Uses COG range reads ({@link DefaultCogImageInputStream} + {@link MinioRangeReader}) to
 * fetch only the header (once, then cached) and the exact internal tile byte ranges that
 * overlap the requested viewport — instead of downloading the full file.
 *
 * Overview selection:
 *   The overview level is chosen so that the COG resolution matches the map tile resolution
 *   at the requested zoom.  This means zoom-13 reads the 14.4 m overview rather than the
 *   full 8 m data, reducing data transferred by ~4×.
 *
 * Performance strategy:
 *   - TiffMeta (geo-transform) is parsed once per cell via GeoTiffReader (downloads full COG
 *     to a temp file once, then caches).  Subsequent reads use COG range reads only.
 *   - Cells in each tile are read in parallel using a dedicated thread pool.
 */
@Service
public class PixelMaxSignalService {

    private static final Logger log = LoggerFactory.getLogger(PixelMaxSignalService.class);

    static {
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    private static final int IO_THREADS = Math.min(64, Runtime.getRuntime().availableProcessors() * 8);
    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(IO_THREADS);

    /**
     * Cached SPI for CogImageReader — avoids the synchronized IIORegistry scan that
     * {@link ImageIO#getImageReaders} performs on every call.  Populated once at startup.
     */
    private static final ImageReaderSpi COG_READER_SPI = findCogReaderSpi();

    private static ImageReaderSpi findCogReaderSpi() {
        ImageIO.scanForPlugins();
        var it = IIORegistry.getDefaultInstance()
                .getServiceProviders(ImageReaderSpi.class, true);
        while (it.hasNext()) {
            ImageReaderSpi spi = it.next();
            if (spi.getClass().getName().endsWith("CogImageReaderSpi")) return spi;
        }
        return null;
    }

    private static final int   TILE_SIZE    = 256;
    private static final float NODATA       = -3.4028235E38f;
    private static final float SIGNAL_FLOOR = -170.0f;

    // ── SLD color ramp (cellcover-continuous.sld, opacity 0.70 = alpha 179) ───
    private static final float[] STOP_V = {-170, -150, -130, -110,  -95,  -85,  -75,    0};
    private static final int[]   STOP_R = {0x00, 0x00, 0x1F, 0x2C, 0x5F, 0x9D, 0xD2, 0xFD};
    private static final int[]   STOP_G = {0x20, 0x33, 0x4E, 0x78, 0xA0, 0xBA, 0xCE, 0xE7};
    private static final int[]   STOP_B = {0x4D, 0x6F, 0x79, 0x8E, 0x60, 0x46, 0x3E, 0x25};
    private static final int     STOP_A = 179;
    private static final int     OFF_CELL_ARGB = (STOP_A << 24) | 0xFF0000;

    @Autowired
    private MinioStorageService minioStorage;

    @Autowired
    private RasterCoverageRepository repository;

    @Autowired
    private CellRepository cellRepository;

    // ── Geo-transform cache ───────────────────────────────────────────────────

    /**
     * Geo-transform + extent metadata extracted from a COG file.
     * {@code width}/{@code height} are the full-resolution pixel dimensions, used for a
     * fast right/bottom bounds check before opening the COG stream.
     */
    private record TiffMeta(double ulLon, double ulLat, double xRes, double yRes,
                            int width, int height) {}

    /** Parsed once per unique cell_id, never evicted (files don't change). */
    private final ConcurrentHashMap<String, TiffMeta> metaCache = new ConcurrentHashMap<>();

    /**
     * Number of images (full-res + overviews) in the COG — constant for the file's lifetime.
     * Cached after the first {@link ImageReader#getNumImages} call so subsequent reads skip it.
     */
    private final ConcurrentHashMap<String, Integer> numImagesCache = new ConcurrentHashMap<>();

    /** Removes all cached state for a cell. Call after a cell's COG file is replaced. */
    public void invalidateMeta(String cellId) {
        metaCache.remove(cellId);
        numImagesCache.remove(cellId);
        MinioRangeReader.invalidateHeader(MinioStorageService.continuousKey(cellId));
    }

    /**
     * Loads geo-transform metadata from a COG file in MinIO via a range read.
     *
     * <p>A COG stores all TIFF IFD metadata (ModelPixelScale, ModelTiepoint, GeoKeys) at the
     * front of the file.  We download only the first {@code HEADER_BYTES} bytes into a temp
     * file and parse with GeoTiffReader — avoiding full-file downloads for large COGs (some are
     * 30 MB+).  The first 16 KB is sufficient for typical COG metadata; we retry with 64 KB if
     * it fails.
     *
     * <p>Result is cached so MinIO is only hit once per cell lifetime.
     */
    private static final int[] META_HEADER_SIZES = {16 * 1024, 64 * 1024, 256 * 1024};

    private TiffMeta loadMeta(String cellId) {
        return metaCache.computeIfAbsent(cellId, id -> {
            String key = MinioStorageService.continuousKey(id);
            MinioRangeReader rr = minioStorage.createRangeReader(key);

            for (int headerBytes : META_HEADER_SIZES) {
                Path tmp = null;
                GeoTiffReader reader = null;
                try {
                    byte[] headerData = rr.read(new long[]{0, headerBytes - 1}).get(0L);
                    if (headerData == null) return null;

                    tmp = Files.createTempFile("meta_" + id + "_", ".tif");
                    Files.write(tmp, headerData);

                    reader = new GeoTiffReader(tmp.toFile());
                    ReferencedEnvelope env  = new ReferencedEnvelope(reader.getOriginalEnvelope());
                    GridEnvelope2D     grid = (GridEnvelope2D) reader.getOriginalGridRange();
                    double xRes = (env.getMaxX() - env.getMinX()) / grid.getSpan(0);
                    double yRes = (env.getMaxY() - env.getMinY()) / grid.getSpan(1);
                    int    w    = grid.getSpan(0);
                    int    h    = grid.getSpan(1);
                    return new TiffMeta(env.getMinX(), env.getMaxY(), xRes, yRes, w, h);
                } catch (Exception e) {
                    log.debug("Meta parse with {}KB failed for {}: {}", headerBytes / 1024, id, e.getMessage());
                } finally {
                    if (reader != null) reader.dispose();
                    if (tmp    != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                }
            }
            log.warn("Cannot parse metadata for {} after all attempts", id);
            return null;
        });
    }

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Real-state render: resolves on/off cells from MariaDB (real_status).
     */
    public byte[] renderTile(int z, int x, int y) throws Exception {
        double[] bbox = tileToWgs84Bbox(z, x, y);
        double lonMin = bbox[0], latMin = bbox[1], lonMax = bbox[2], latMax = bbox[3];

        List<String> visibleIds = cellRepository.findOnCellIdsInBbox(lonMin, latMin, lonMax, latMax);
        List<String> hiddenIds  = cellRepository.findOffCellIdsInBbox(lonMin, latMin, lonMax, latMax);

        return renderTile(z, x, y, visibleIds, hiddenIds);
    }

    /**
     * Simulation-aware render: caller provides explicit on/off cell sets.
     * COG bounds-check in readCogWindow filters out cells not covering this tile.
     */
    public byte[] renderTile(int z, int x, int y,
                             java.util.Collection<String> onCells,
                             java.util.Collection<String> offCells) throws Exception {
        if (onCells.isEmpty() && offCells.isEmpty()) return transparentPng();

        double[] bbox = tileToWgs84Bbox(z, x, y);
        double lonMin = bbox[0], latMin = bbox[1], lonMax = bbox[2], latMax = bbox[3];

        float[]   maxSignal   = new float[TILE_SIZE * TILE_SIZE];
        boolean[] offCoverage = new boolean[TILE_SIZE * TILE_SIZE];
        Arrays.fill(maxSignal, NODATA);

        List<CompletableFuture<float[]>> visFutures = onCells.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> readCogWindow(id, z, lonMin, latMin, lonMax, latMax), IO_POOL))
                .toList();
        List<CompletableFuture<float[]>> hidFutures = offCells.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> readCogWindow(id, z, lonMin, latMin, lonMax, latMax), IO_POOL))
                .toList();

        for (CompletableFuture<float[]> f : visFutures) {
            float[] data = f.join();
            if (data == null) continue;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                float v = data[i];
                if (v > SIGNAL_FLOOR && Float.isFinite(v) && v != NODATA
                        && (maxSignal[i] == NODATA || v > maxSignal[i])) {
                    maxSignal[i] = v;
                }
            }
        }
        for (CompletableFuture<float[]> f : hidFutures) {
            float[] data = f.join();
            if (data == null) continue;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                float v = data[i];
                if (v > SIGNAL_FLOOR && Float.isFinite(v) && v != NODATA) offCoverage[i] = true;
            }
        }

        return encodePng(renderPixels(maxSignal, offCoverage));
    }

    // ── COG windowed read via range reads ─────────────────────────────────────

    /**
     * Reads the COG window for the given tile bbox using HTTP range reads.
     *
     * <p>Overview selection: picks the lowest-resolution overview whose pixel size is still
     * smaller than (or equal to) the map tile's pixel size, so we read the minimum data
     * needed for correct rendering at the requested zoom.
     *
     * <p>Returns float[256*256] (row 0 = northernmost, row-major).
     * Pixels outside the cell's extent stay NODATA.
     */
    float[] readCogWindow(String cellId, int z,
                          double lonMin, double latMin, double lonMax, double latMax) {
        String key = MinioStorageService.continuousKey(cellId);

        TiffMeta meta = loadMeta(cellId);
        if (meta == null) return null;

        // ── overview level: match map resolution to avoid reading full-res data at low zoom ──
        int   level   = overviewLevel(z, meta.xRes());
        double ovXRes = meta.xRes() * Math.pow(2, level);
        double ovYRes = meta.yRes() * Math.pow(2, level);

        // ── fast bounds check using cached COG dimensions (avoids opening the stream) ──
        double cogMaxLon = meta.ulLon() + meta.width()  * meta.xRes();
        double cogMinLat = meta.ulLat() - meta.height() * meta.yRes();
        if (lonMin >= cogMaxLon || latMax <= cogMinLat) return null;

        // ── pixel window in overview coordinates ──────────────────────────────
        int srcX0 = (int) Math.floor((lonMin - meta.ulLon()) / ovXRes);
        int srcY0 = (int) Math.floor((meta.ulLat() - latMax) / ovYRes);
        int srcX1 = (int) Math.ceil ((lonMax - meta.ulLon()) / ovXRes);
        int srcY1 = (int) Math.ceil ((meta.ulLat() - latMin) / ovYRes);

        srcX0 = Math.max(0, srcX0);
        srcY0 = Math.max(0, srcY0);
        if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;

        // ── COG range reads: header (cached) + tile ranges for this window only ──
        if (COG_READER_SPI == null) return null;
        URI uri = URI.create(minioStorage.httpUrl(key));
        MinioRangeReader rangeReader = minioStorage.createRangeReader(key);

        try (DefaultCogImageInputStream cis = new DefaultCogImageInputStream(uri, rangeReader)) {
            // Use the cached SPI directly — avoids the synchronized IIORegistry scan
            // that ImageIO.getImageReaders() performs on every invocation.
            ImageReader ir = COG_READER_SPI.createReaderInstance();
            try {
                ir.setInput(cis, false, true);

                // getNumImages(true) forces a full IFD chain scan — cache the result
                // so it is only paid once per cell across all tile requests.
                int numImages   = numImagesCache.computeIfAbsent(cellId,
                        k -> { try { return ir.getNumImages(true); } catch (Exception e) { return 5; } });
                int actualLevel = Math.min(level, numImages - 1);
                if (actualLevel != level) {
                    // Recompute source region at the actual (coarser) level
                    ovXRes = meta.xRes() * Math.pow(2, actualLevel);
                    ovYRes = meta.yRes() * Math.pow(2, actualLevel);
                    srcX0  = (int) Math.floor((lonMin - meta.ulLon()) / ovXRes);
                    srcY0  = (int) Math.floor((meta.ulLat() - latMax) / ovYRes);
                    srcX1  = (int) Math.ceil ((lonMax - meta.ulLon()) / ovXRes);
                    srcY1  = (int) Math.ceil ((meta.ulLat() - latMin) / ovYRes);
                    srcX0  = Math.max(0, srcX0);
                    srcY0  = Math.max(0, srcY0);
                    if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;
                }

                int imgW = ir.getWidth(actualLevel);
                int imgH = ir.getHeight(actualLevel);
                srcX1 = Math.min(srcX1, imgW);
                srcY1 = Math.min(srcY1, imgH);
                srcX0 = Math.min(srcX0, imgW - 1);
                srcY0 = Math.min(srcY0, imgH - 1);
                if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;
                int srcW = srcX1 - srcX0;
                int srcH = srcY1 - srcY0;

                ImageReadParam param = ir.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(srcX0, srcY0, srcW, srcH));

                // CogImageReader fetches only the COG tiles overlapping sourceRegion
                BufferedImage srcImg = ir.read(actualLevel, param);
                Raster         raster = srcImg.getRaster();

                // ── nearest-neighbour resample into 256×256 tile ──────────────
                float[] data = new float[TILE_SIZE * TILE_SIZE];
                Arrays.fill(data, NODATA);

                double readLonMin = meta.ulLon() + srcX0 * ovXRes;
                double readLatMax = meta.ulLat() - srcY0 * ovYRes;
                double dLon = lonMax - lonMin;
                double dLat = latMax - latMin;

                for (int tr = 0; tr < TILE_SIZE; tr++) {
                    double lat = latMax - (tr + 0.5) / TILE_SIZE * dLat;
                    int    sr  = (int) ((readLatMax - lat) / ovYRes);
                    if (sr < 0 || sr >= srcH) continue;
                    for (int tc = 0; tc < TILE_SIZE; tc++) {
                        double lon = lonMin + (tc + 0.5) / TILE_SIZE * dLon;
                        int    sc  = (int) ((lon - readLonMin) / ovXRes);
                        if (sc < 0 || sc >= srcW) continue;
                        data[tr * TILE_SIZE + tc] = raster.getSampleFloat(sc, sr, 0);
                    }
                }
                return data;
            } finally {
                ir.dispose();
            }
        } catch (Exception e) {
            log.debug("Failed to read COG window for {}: {}", cellId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the COG overview level whose resolution best matches the map tile pixel size.
     *
     * <p>Level 0 = full resolution (cogXRes), level N = cogXRes × 2^N.
     * We choose the highest level (coarsest) where the overview is still finer than
     * the map tile resolution, so we don't over-sample.
     *
     * @param z       map zoom level
     * @param cogXRes full-resolution pixel size in degrees
     */
    static int overviewLevel(int z, double cogXRes) {
        // Map tile pixel size in degrees
        double tilePixelDeg = 360.0 / Math.pow(2, z) / TILE_SIZE;
        if (tilePixelDeg <= cogXRes) return 0;
        // level = floor(log2(tilePixelDeg / cogXRes)), minimum 0
        return Math.max(0, (int) (Math.log(tilePixelDeg / cogXRes) / Math.log(2.0)));
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private BufferedImage renderPixels(float[] maxSignal, boolean[] offCoverage) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            float v = maxSignal[i];
            boolean hasSignal = v > SIGNAL_FLOOR && Float.isFinite(v) && v != NODATA;
            if (hasSignal) {
                img.setRGB(i % TILE_SIZE, i / TILE_SIZE, signalToArgb(v));
            } else if (offCoverage[i]) {
                img.setRGB(i % TILE_SIZE, i / TILE_SIZE, OFF_CELL_ARGB);
            }
        }
        return img;
    }

    private int signalToArgb(float v) {
        float vc = Math.max(STOP_V[0], Math.min(STOP_V[STOP_V.length - 1], v));
        int lo = STOP_V.length - 2;
        for (int s = 0; s < STOP_V.length - 1; s++) {
            if (STOP_V[s + 1] >= vc) { lo = s; break; }
        }
        int hi = lo + 1;
        float t = Math.max(0f, Math.min(1f, (vc - STOP_V[lo]) / (STOP_V[hi] - STOP_V[lo])));
        int r = Math.round(STOP_R[lo] + t * (STOP_R[hi] - STOP_R[lo]));
        int g = Math.round(STOP_G[lo] + t * (STOP_G[hi] - STOP_G[lo]));
        int b = Math.round(STOP_B[lo] + t * (STOP_B[hi] - STOP_B[lo]));
        return (STOP_A << 24) | (r << 16) | (g << 8) | b;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static double[] tileToWgs84Bbox(int z, int x, int y) {
        double n = Math.pow(2, z);
        double lonMin = x / n * 360.0 - 180.0;
        double lonMax = (x + 1) / n * 360.0 - 180.0;
        double latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / n))));
        double latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 1) / n))));
        return new double[]{lonMin, latMin, lonMax, latMax};
    }

    private byte[] encodePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16 * 1024);
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] transparentPng() throws Exception {
        return encodePng(new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB));
    }
}
