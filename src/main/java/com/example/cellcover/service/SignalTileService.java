package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.example.cellcover.entity.RasterCoverage;
import com.example.cellcover.repository.RasterCoverageRepository;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Renders a 256×256 signal-strength WMS tile by reading each visible cell's COG
 * file directly and computing a per-pixel MAX signal across all overlapping cells.
 *
 * Strategy: at every pixel, the strongest signal among all visible cells wins.
 * This matches the real-world behaviour of a device connecting to the best cell.
 */
@Service
public class SignalTileService {

    private static final Logger log = LoggerFactory.getLogger(SignalTileService.class);

    private static final int   TILE_SIZE        = 256;
    private static final float NODATA_TOLERANCE = 1e30f;  // |value| >= this → NoData

    // Color ramp — COG continuous values range from ~-189 (weakest) to ~-24 (strongest), mean ~-133
    // Thresholds are lower bounds: value >= threshold → use this color
    private static final float SIGNAL_MIN_DBM = -250f;  // below this → treat as NoData
    private static final float SIGNAL_MAX_DBM =    0f;  // upper bound (values go up to ~-24)

    // Stops calibrated to the actual signal distribution (percentile-based spread)
    private static final float[] STOPS  = {-170f, -150f, -130f, -110f, -95f, -85f, -75f};
    private static final int[]   COLORS = {
        0x00204D,  // < -170   — Cực yếu
        0x00336F,  // -170 to -150
        0x1F4E79,  // -150 to -130
        0x2C788E,  // -130 to -110  ← ~median
        0x5FA060,  // -110 to  -95
        0x9DBA46,  //  -95 to  -85
        0xD2CE3E,  //  -85 to  -75
        0xFDE725   //      >= -75  — Tốt
    };
    private static final int ALPHA = (int) (0.70 * 255); // 179 — matches SLD opacity

    private final RasterCoverageRepository repository;
    private final CellImportProperties     props;

    public SignalTileService(RasterCoverageRepository repository, CellImportProperties props) {
        this.repository = repository;
        this.props      = props;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Render a per-pixel MAX signal tile.
     *
     * @param bbox3857   "minx,miny,maxx,maxy" in EPSG:3857
     * @param hiddenIds  cell IDs to exclude  (used when visibleMode=false)
     * @param visibleIds cell IDs to include  (used when visibleMode=true)
     * @param visibleMode true → keep only cells in visibleIds; false → exclude hiddenIds
     */
    public byte[] renderMaxSignalTile(String bbox3857,
                                      Set<String> hiddenIds,
                                      Set<String> visibleIds,
                                      boolean visibleMode) throws Exception {
        return renderSignalTile(bbox3857, hiddenIds, visibleIds, visibleMode, false);
    }

    /**
     * Render a per-pixel AVERAGE signal tile.
     * At each pixel, averages the signal values of all overlapping cells.
     */
    public byte[] renderAvgSignalTile(String bbox3857,
                                      Set<String> hiddenIds,
                                      Set<String> visibleIds,
                                      boolean visibleMode) throws Exception {
        return renderSignalTile(bbox3857, hiddenIds, visibleIds, visibleMode, true);
    }

    private byte[] renderSignalTile(String bbox3857,
                                     Set<String> hiddenIds,
                                     Set<String> visibleIds,
                                     boolean visibleMode,
                                     boolean useAvg) throws Exception {
        // 1. Convert bbox EPSG:3857 → EPSG:4326
        double[] b    = parseBbox(bbox3857);
        double[] bbox = mercatorToWgs84(b[0], b[1], b[2], b[3]);
        // bbox = [minLon, minLat, maxLon, maxLat]

        // 2. Candidate cells from DB (visible=true, svr=false, intersects tile)
        List<RasterCoverage> candidates = repository.findVisibleNonSvrInBbox(
                bbox[0], bbox[1], bbox[2], bbox[3]);

        // 3. Apply hidden/visible filter
        List<RasterCoverage> cells = candidates.stream()
                .filter(r -> visibleMode
                        ? visibleIds.contains(r.getCellId())
                        : !hiddenIds.contains(r.getCellId()))
                .toList();

        if (cells.isEmpty()) {
            return transparentPng();
        }

        // 4. Build target envelope (EPSG:4326) for 256×256 read
        CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
        ReferencedEnvelope targetEnv = new ReferencedEnvelope(
                bbox[0], bbox[2], bbox[1], bbox[3], wgs84);

        // 5. Read each cell's COG in parallel, collect float grids
        ConcurrentLinkedQueue<float[]> grids = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (RasterCoverage cell : cells) {
            Path cogPath = Paths.get(props.getCogDir(), "continuous", cell.getCellId() + ".tif");
            if (!Files.exists(cogPath)) continue;

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    float[] vals = sampleCog(cogPath.toFile(), targetEnv);
                    if (vals != null) grids.add(vals);
                } catch (Exception e) {
                    log.warn("Failed to sample COG {}: {}", cogPath.getFileName(), e.getMessage());
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 6. Per-pixel MAX or AVG across all cell grids
        float[] resultGrid = new float[TILE_SIZE * TILE_SIZE];
        Arrays.fill(resultGrid, Float.NEGATIVE_INFINITY);

        if (useAvg) {
            float[] sumGrid   = new float[TILE_SIZE * TILE_SIZE];
            int[]   countGrid = new int[TILE_SIZE * TILE_SIZE];
            for (float[] grid : grids) {
                for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                    float v = grid[i];
                    if (Math.abs(v) < NODATA_TOLERANCE && v >= SIGNAL_MIN_DBM && v <= SIGNAL_MAX_DBM) {
                        sumGrid[i]   += v;
                        countGrid[i] += 1;
                    }
                }
            }
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                if (countGrid[i] > 0) resultGrid[i] = sumGrid[i] / countGrid[i];
            }
        } else {
            for (float[] grid : grids) {
                for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                    float v = grid[i];
                    if (Math.abs(v) < NODATA_TOLERANCE && v >= SIGNAL_MIN_DBM && v <= SIGNAL_MAX_DBM
                            && v > resultGrid[i]) {
                        resultGrid[i] = v;
                    }
                }
            }
        }

        // 7. Apply color ramp → PNG
        return applyColorRamp(resultGrid);
    }

    // ── raster sampling ───────────────────────────────────────────────────────

    private float[] sampleCog(File cogFile, ReferencedEnvelope targetEnv) throws Exception {
        GeoTiffReader reader = new GeoTiffReader(cogFile);
        try {
            GridCoverage2D coverage = reader.read(null);
            if (coverage == null) return null;
            try {
                ReferencedEnvelope nativeEnv =
                        new ReferencedEnvelope(coverage.getEnvelope2D());
                CoordinateReferenceSystem nativeCrs = coverage.getCoordinateReferenceSystem2D();
                ReferencedEnvelope nativeTarget = targetEnv.transform(nativeCrs, true);

                if (!nativeEnv.intersects((org.locationtech.jts.geom.Envelope) nativeTarget))
                    return null;

                // Exact geographic overlap between cell and tile
                double overlapMinX = Math.max(nativeEnv.getMinX(), nativeTarget.getMinX());
                double overlapMaxX = Math.min(nativeEnv.getMaxX(), nativeTarget.getMaxX());
                double overlapMinY = Math.max(nativeEnv.getMinY(), nativeTarget.getMinY());
                double overlapMaxY = Math.min(nativeEnv.getMaxY(), nativeTarget.getMaxY());
                if (overlapMaxX <= overlapMinX || overlapMaxY <= overlapMinY) return null;

                Raster raster = coverage.getRenderedImage().getData();
                int nW = raster.getWidth();
                int nH = raster.getHeight();
                int ox = raster.getMinX();
                int oy = raster.getMinY();

                // Map overlap → source pixel region (Y-axis inverted: top = maxY)
                double scaleX = nW / nativeEnv.getWidth();
                double scaleY = nH / nativeEnv.getHeight();

                int srcX = (int) Math.floor((overlapMinX - nativeEnv.getMinX()) * scaleX);
                int srcY = (int) Math.floor((nativeEnv.getMaxY() - overlapMaxY) * scaleY);
                int srcW = (int) Math.ceil((overlapMaxX - overlapMinX) * scaleX);
                int srcH = (int) Math.ceil((overlapMaxY - overlapMinY) * scaleY);

                srcX = Math.max(0, Math.min(srcX, nW - 1));
                srcY = Math.max(0, Math.min(srcY, nH - 1));
                srcW = Math.min(srcW, nW - srcX);
                srcH = Math.min(srcH, nH - srcY);
                if (srcW <= 0 || srcH <= 0) return null;

                float[] srcPixels = new float[srcW * srcH];
                raster.getSamples(ox + srcX, oy + srcY, srcW, srcH, 0, srcPixels);

                // Map overlap → destination pixel region within the 256×256 output tile
                double tileScaleX = TILE_SIZE / nativeTarget.getWidth();
                double tileScaleY = TILE_SIZE / nativeTarget.getHeight();

                int dstX = (int) Math.round((overlapMinX - nativeTarget.getMinX()) * tileScaleX);
                int dstY = (int) Math.round((nativeTarget.getMaxY() - overlapMaxY)  * tileScaleY);
                int dstW = (int) Math.round((overlapMaxX - overlapMinX) * tileScaleX);
                int dstH = (int) Math.round((overlapMaxY - overlapMinY) * tileScaleY);

                dstX = Math.max(0, dstX);
                dstY = Math.max(0, dstY);
                dstW = Math.min(dstW, TILE_SIZE - dstX);
                dstH = Math.min(dstH, TILE_SIZE - dstY);
                if (dstW <= 0 || dstH <= 0) return null;

                // Start with NEGATIVE_INFINITY (= no data for this cell at this pixel).
                // Only the overlap region is filled; areas outside remain no-data and are
                // correctly skipped by the per-pixel MAX accumulation in renderMaxSignalTile.
                float[] result = new float[TILE_SIZE * TILE_SIZE];
                Arrays.fill(result, Float.NEGATIVE_INFINITY);

                // Nearest-neighbour resample: srcW×srcH → dstW×dstH, placed at (dstX, dstY)
                for (int row = 0; row < dstH; row++) {
                    for (int col = 0; col < dstW; col++) {
                        int sx = Math.min((int)(col * srcW / (float) dstW), srcW - 1);
                        int sy = Math.min((int)(row * srcH / (float) dstH), srcH - 1);
                        result[(dstY + row) * TILE_SIZE + (dstX + col)] = srcPixels[sy * srcW + sx];
                    }
                }
                return result;
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }

    // ── color ramp + PNG encoding ─────────────────────────────────────────────

    private byte[] applyColorRamp(float[] maxGrid) throws Exception {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            float v = maxGrid[i];
            if (v == Float.NEGATIVE_INFINITY) continue; // no cell covers this pixel
            if (Math.abs(v) >= NODATA_TOLERANCE) continue; // NoData
            int x = i % TILE_SIZE;
            int y = i / TILE_SIZE;
            img.setRGB(x, y, (ALPHA << 24) | signalToRgb(v));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private int signalToRgb(float v) {
        // STOPS are dBm lower bounds in ascending order; pick highest matching threshold
        // COLORS has one extra entry at index STOPS.length for values >= last stop
        for (int i = STOPS.length - 1; i >= 0; i--) {
            if (v >= STOPS[i]) return COLORS[i + 1];
        }
        return COLORS[0]; // below -130 dBm
    }

    private byte[] transparentPng() throws Exception {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    // ── coordinate helpers ────────────────────────────────────────────────────

    private static double[] parseBbox(String bbox) {
        String[] p = bbox.split(",");
        return new double[]{
            Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
            Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim())
        };
    }

    /** EPSG:3857 bbox → EPSG:4326 [minLon, minLat, maxLon, maxLat] */
    private static double[] mercatorToWgs84(double minX, double minY,
                                             double maxX, double maxY) {
        return new double[]{
            merc2lon(minX), merc2lat(minY), merc2lon(maxX), merc2lat(maxY)
        };
    }

    private static double merc2lon(double x) {
        return x / 20037508.342789244 * 180.0;
    }

    private static double merc2lat(double y) {
        return Math.toDegrees(2 * Math.atan(
                Math.exp(y / 20037508.342789244 * Math.PI)) - Math.PI / 2);
    }
}
