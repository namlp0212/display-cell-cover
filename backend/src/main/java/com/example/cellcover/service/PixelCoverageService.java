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
 * Renders per-pixel coverage density tiles directly from binary COG files stored in MinIO.
 *
 * <p>Uses COG range reads ({@link DefaultCogImageInputStream} + {@link MinioRangeReader}) to
 * fetch only the header (once, then cached) and the exact internal tile byte ranges that
 * overlap the requested viewport.
 *
 * <p>Rendering:
 *   Visible cells → density color ramp (1 cell = very-weak blue → 5+ cells = strong green)
 *   Hidden cells  → red (#FF0000) where no visible cell overlaps
 */
@Service
public class PixelCoverageService {

    private static final Logger log = LoggerFactory.getLogger(PixelCoverageService.class);

    static {
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    private static final int IO_THREADS = Math.min(64, Runtime.getRuntime().availableProcessors() * 8);
    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(IO_THREADS);

    /** Cached SPI — avoids synchronized IIORegistry scan on every readCogWindow call. */
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

    private static final int   TILE_SIZE  = 256;
    private static final float NODATA     = -3.4028235E38f;

    // Density color ramp: index 0 = strongest (5+ cells), index 4 = weakest (1 cell)
    // Matches DENSITY_RGB in WmsProxyController (green → light-blue)
    private static final int[] DENSITY_RGB   = {0x00FF00, 0x00FF99, 0x00FFCC, 0x00CCFF, 0x0099FF};
    private static final int   DENSITY_ALPHA = 140;   // opacity 0.55, matching SLD
    private static final int   OFF_CELL_ARGB = (140 << 24) | 0xFF0000;

    @Autowired
    private MinioStorageService minioStorage;

    @Autowired
    private RasterCoverageRepository repository;

    @Autowired
    private CellRepository cellRepository;

    // ── geo-transform cache ───────────────────────────────────────────────────

    private record TiffMeta(double ulLon, double ulLat, double xRes, double yRes,
                            int width, int height) {}

    private final ConcurrentHashMap<String, TiffMeta> metaCache = new ConcurrentHashMap<>();

    /** Cached number of images per cell — avoids ir.getNumImages(true) on every read. */
    private final ConcurrentHashMap<String, Integer> numImagesCache = new ConcurrentHashMap<>();

    private static final int[] META_HEADER_SIZES = {16 * 1024, 64 * 1024, 256 * 1024};

    private TiffMeta loadMeta(String cellId) {
        return metaCache.computeIfAbsent(cellId, id -> {
            String key = MinioStorageService.binaryKey(id);
            MinioRangeReader rr = minioStorage.createRangeReader(key);

            for (int headerBytes : META_HEADER_SIZES) {
                Path tmp = null;
                GeoTiffReader reader = null;
                try {
                    byte[] headerData = rr.read(new long[]{0, headerBytes - 1}).get(0L);
                    if (headerData == null) return null;

                    tmp = Files.createTempFile("bmeta_" + id + "_", ".tif");
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
                    log.debug("Binary meta parse with {}KB failed for {}: {}", headerBytes / 1024, id, e.getMessage());
                } finally {
                    if (reader != null) reader.dispose();
                    if (tmp    != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                }
            }
            log.warn("Cannot parse binary metadata for {} after all attempts", id);
            return null;
        });
    }

    public void invalidateMeta(String cellId) {
        metaCache.remove(cellId);
        numImagesCache.remove(cellId);
        MinioRangeReader.invalidateHeader(MinioStorageService.binaryKey(cellId));
    }

    // ── public entry points ───────────────────────────────────────────────────

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
     * Simulation-aware render: caller provides explicit on/off cell sets
     * (resolved from Redis simulation cache by PixelTileController).
     * COG bounds-check in readCogWindow filters out cells not covering this tile.
     */
    public byte[] renderTile(int z, int x, int y,
                             java.util.Collection<String> onCells,
                             java.util.Collection<String> offCells) throws Exception {
        if (onCells.isEmpty() && offCells.isEmpty()) return transparentPng();

        double[] bbox = tileToWgs84Bbox(z, x, y);
        double lonMin = bbox[0], latMin = bbox[1], lonMax = bbox[2], latMax = bbox[3];

        int[]     density     = new int[TILE_SIZE * TILE_SIZE];
        boolean[] offCoverage = new boolean[TILE_SIZE * TILE_SIZE];

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
                if (isCovered(data[i])) density[i]++;
            }
        }
        for (CompletableFuture<float[]> f : hidFutures) {
            float[] data = f.join();
            if (data == null) continue;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                if (isCovered(data[i])) offCoverage[i] = true;
            }
        }

        return encodePng(renderPixels(density, offCoverage));
    }

    // ── COG windowed read via range reads ─────────────────────────────────────

    float[] readCogWindow(String cellId, int z,
                          double lonMin, double latMin, double lonMax, double latMax) {
        String key = MinioStorageService.binaryKey(cellId);

        TiffMeta meta = loadMeta(cellId);
        if (meta == null) return null;

        int    level  = overviewLevel(z, meta.xRes());
        double ovXRes = meta.xRes() * Math.pow(2, level);
        double ovYRes = meta.yRes() * Math.pow(2, level);

        // ── fast bounds check using cached COG dimensions (avoids opening the stream) ──
        double cogMaxLon = meta.ulLon() + meta.width()  * meta.xRes();
        double cogMinLat = meta.ulLat() - meta.height() * meta.yRes();
        if (lonMin >= cogMaxLon || latMax <= cogMinLat) return null;

        int srcX0 = (int) Math.floor((lonMin - meta.ulLon()) / ovXRes);
        int srcY0 = (int) Math.floor((meta.ulLat() - latMax) / ovYRes);
        int srcX1 = (int) Math.ceil ((lonMax - meta.ulLon()) / ovXRes);
        int srcY1 = (int) Math.ceil ((meta.ulLat() - latMin) / ovYRes);

        srcX0 = Math.max(0, srcX0);
        srcY0 = Math.max(0, srcY0);
        if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;

        if (COG_READER_SPI == null) return null;
        URI uri = URI.create(minioStorage.httpUrl(key));
        MinioRangeReader rangeReader = minioStorage.createRangeReader(key);

        try (DefaultCogImageInputStream cis = new DefaultCogImageInputStream(uri, rangeReader)) {
            ImageReader ir = COG_READER_SPI.createReaderInstance();
            try {
                ir.setInput(cis, false, true);

                int numImages   = numImagesCache.computeIfAbsent(cellId,
                        k -> { try { return ir.getNumImages(true); } catch (Exception e) { return 5; } });
                int actualLevel = Math.min(level, numImages - 1);
                if (actualLevel != level) {
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

                BufferedImage srcImg = ir.read(actualLevel, param);
                Raster         raster = srcImg.getRaster();

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
            log.debug("Failed to read binary COG window for {}: {}", cellId, e.getMessage());
            return null;
        }
    }

    private static boolean isCovered(float v) {
        return Float.isFinite(v) && v != NODATA;
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private BufferedImage renderPixels(int[] density, boolean[] offCoverage) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            int count = density[i];
            if (count > 0) {
                // index 0 = strongest (5+ cells), index 4 = weakest (1 cell)
                int idx = Math.max(0, DENSITY_RGB.length - count);
                img.setRGB(i % TILE_SIZE, i / TILE_SIZE, (DENSITY_ALPHA << 24) | DENSITY_RGB[idx]);
            } else if (offCoverage[i]) {
                img.setRGB(i % TILE_SIZE, i / TILE_SIZE, OFF_CELL_ARGB);
            }
        }
        return img;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static int overviewLevel(int z, double cogXRes) {
        double tilePixelDeg = 360.0 / Math.pow(2, z) / TILE_SIZE;
        if (tilePixelDeg <= cogXRes) return 0;
        return Math.max(0, (int) (Math.log(tilePixelDeg / cogXRes) / Math.log(2.0)));
    }

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
