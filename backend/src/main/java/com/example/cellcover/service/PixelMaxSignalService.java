package com.example.cellcover.service;

import com.example.cellcover.repository.RasterCoverageRepository;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

/**
 * Renders per-pixel MAX signal tiles directly from local COG files.
 *
 * Reads a 256×256 window from each cell's continuous COG using lightweight
 * ImageIO (not GeoTiffReader), composites per-pixel MAX, and applies the
 * cellcover-continuous SLD color ramp to produce an RGBA PNG.
 *
 * Performance strategy:
 *   - TiffMeta (geo-transform) is parsed once per cell via GeoTiffReader and cached.
 *   - All subsequent reads use ImageIO + TIFFImageReader directly, skipping
 *     GeoTiffReader's CRS-parsing and GridCoverage2D overhead (~400ms saved per cell).
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

    @Value("${cellcover.local-cont-dir:/Users/lenam/tools/geoserver/data_dir/data/cellcover/continuous}")
    private String localContDir;

    @Autowired
    private RasterCoverageRepository repository;

    // ── Geo-transform cache ───────────────────────────────────────────────────

    /** Minimal geo-transform metadata extracted from a COG file. */
    private record TiffMeta(double ulLon, double ulLat, double xRes, double yRes) {}

    /** Parsed once per unique cell_id, never evicted (files don't change). */
    private final ConcurrentHashMap<String, TiffMeta> metaCache = new ConcurrentHashMap<>();

    /** Removes the cached geo-transform for a cell. Call after a cell's COG file is replaced. */
    public void invalidateMeta(String cellId) {
        metaCache.remove(cellId);
    }

    private TiffMeta loadMeta(String cellId, File file) {
        return metaCache.computeIfAbsent(cellId, id -> {
            GeoTiffReader r = null;
            try {
                r = new GeoTiffReader(file);
                ReferencedEnvelope env = new ReferencedEnvelope(r.getOriginalEnvelope());
                GridEnvelope2D grid   = (GridEnvelope2D) r.getOriginalGridRange();
                double xRes = (env.getMaxX() - env.getMinX()) / grid.getSpan(0);
                double yRes = (env.getMaxY() - env.getMinY()) / grid.getSpan(1);
                return new TiffMeta(env.getMinX(), env.getMaxY(), xRes, yRes);
            } catch (Exception e) {
                log.warn("Cannot parse metadata for {}: {}", cellId, e.getMessage());
                return null;
            } finally {
                if (r != null) r.dispose();
            }
        });
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Renders a 256×256 RGBA PNG tile using true per-pixel MAX signal strength.
     *
     * Visible (on) cells → color ramp.
     * Hidden  (off) cells → red (#FF0000, alpha 179), only where no on-cell signal.
     */
    public byte[] renderTile(int z, int x, int y) throws Exception {
        double[] bbox = tileToWgs84Bbox(z, x, y);
        double lonMin = bbox[0], latMin = bbox[1], lonMax = bbox[2], latMax = bbox[3];

        List<String> visibleIds = repository.findVisibleCellIdsInBbox(lonMin, latMin, lonMax, latMax);
        List<String> hiddenIds  = repository.findHiddenCellIdsInBbox(lonMin, latMin, lonMax, latMax);

        if (visibleIds.isEmpty() && hiddenIds.isEmpty()) return transparentPng();

        float[]   maxSignal   = new float[TILE_SIZE * TILE_SIZE];
        boolean[] offCoverage = new boolean[TILE_SIZE * TILE_SIZE];
        Arrays.fill(maxSignal, NODATA);

        // Submit all reads in parallel
        List<CompletableFuture<float[]>> visFutures = visibleIds.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> readCogWindow(id, lonMin, latMin, lonMax, latMax), IO_POOL))
                .toList();
        List<CompletableFuture<float[]>> hidFutures = hiddenIds.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> readCogWindow(id, lonMin, latMin, lonMax, latMax), IO_POOL))
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

    // ── COG windowed read (fast path: cached meta + direct ImageIO) ───────────

    /**
     * Reads the tile's geographic window from one cell's COG and returns float[256*256]
     * (row 0 = northernmost, row-major).  Pixels outside the file extent stay NODATA.
     *
     * Hot path: metadata comes from cache; pixel data is read directly via TIFFImageReader
     * without going through GeoTiffReader / GridCoverage2D.
     */
    float[] readCogWindow(String cellId, double lonMin, double latMin,
                          double lonMax, double latMax) {
        File file = new File(localContDir + "/" + cellId + ".tif");
        if (!file.exists()) return null;

        TiffMeta meta = loadMeta(cellId, file);
        if (meta == null) return null;

        // ── pixel window in source coordinates ────────────────────────────────
        // ulLon, ulLat = top-left (NW) corner pixel edge; xRes, yRes = pixel size (degrees)
        // Row 0 = northernmost (ulLat), rows increase southward.
        int srcX0 = (int) Math.floor((lonMin - meta.ulLon()) / meta.xRes());
        int srcY0 = (int) Math.floor((meta.ulLat() - latMax) / meta.yRes());
        int srcX1 = (int) Math.ceil ((lonMax - meta.ulLon()) / meta.xRes());
        int srcY1 = (int) Math.ceil ((meta.ulLat() - latMin) / meta.yRes());

        // Clamp to file extent (TIFFImageReader does NOT do boundless read)
        int fileW = (int) Math.round((meta.xRes() > 0) ? Double.MAX_VALUE : 0); // will derive below
        // Clamp to non-negative, let reader handle out-of-range gracefully
        srcX0 = Math.max(0, srcX0);
        srcY0 = Math.max(0, srcY0);
        if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;

        int srcW = srcX1 - srcX0;
        int srcH = srcY1 - srcY0;

        // ── read via ImageIO (no GeoTiffReader overhead) ───────────────────────
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader ir = readers.next();
            try {
                ir.setInput(iis, true, true);

                // Clamp srcX1/srcY1 to actual image dimensions
                int imgW = ir.getWidth(0);
                int imgH = ir.getHeight(0);
                srcX1 = Math.min(srcX1, imgW);
                srcY1 = Math.min(srcY1, imgH);
                srcX0 = Math.min(srcX0, imgW - 1);
                srcY0 = Math.min(srcY0, imgH - 1);
                if (srcX1 <= srcX0 || srcY1 <= srcY0) return null;
                srcW = srcX1 - srcX0;
                srcH = srcY1 - srcY0;

                ImageReadParam param = ir.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(srcX0, srcY0, srcW, srcH));

                // Read: the result is a BufferedImage with the raw float/int data
                BufferedImage srcImg = ir.read(0, param);
                Raster raster = srcImg.getRaster();

                // ── nearest-neighbour resample into 256×256 tile ──────────────
                float[] data = new float[TILE_SIZE * TILE_SIZE];
                Arrays.fill(data, NODATA);

                // Geographic extent of what we actually read
                double readLonMin = meta.ulLon() + srcX0 * meta.xRes();
                double readLatMax = meta.ulLat() - srcY0 * meta.yRes();

                double dLon = lonMax - lonMin;
                double dLat = latMax - latMin;

                for (int tr = 0; tr < TILE_SIZE; tr++) {
                    double lat = latMax - (tr + 0.5) / TILE_SIZE * dLat;
                    int sr = (int) ((readLatMax - lat) / meta.yRes());
                    if (sr < 0 || sr >= srcH) continue;
                    for (int tc = 0; tc < TILE_SIZE; tc++) {
                        double lon = lonMin + (tc + 0.5) / TILE_SIZE * dLon;
                        int sc = (int) ((lon - readLonMin) / meta.xRes());
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
