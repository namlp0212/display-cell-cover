package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.DoubleSummaryStatistics;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes H3 hexagon coverage from .svr.bil raster files and persists
 * results into hex_coverage and cell_hex_map tables.
 *
 * Mirrors the logic in scripts/compute_hex_coverage.py.
 */
@Service
public class HexCoverageService {

    private static final Logger log = LoggerFactory.getLogger(HexCoverageService.class);

    private static final int[]  RESOLUTIONS     = {6, 7, 8, 9};
    private static final int    MAX_PIXELS      = 40_000;
    /** Pixels at or below this threshold are treated as NoData. */
    private static final float  NODATA_THRESHOLD = -1e30f;
    private static final float  SIGNAL_MIN_DBM   = -140f;  // below this → NoData
    private static final float  SIGNAL_MAX_DBM   = -44f;   // above this → physically invalid

    private final JdbcTemplate         jdbc;
    private final CellImportProperties properties;
    private final H3Core               h3;
    private final CRSFactory           crsFactory  = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
    private final CoordinateReferenceSystem  wgs84;

    public HexCoverageService(JdbcTemplate jdbc, CellImportProperties properties) throws IOException {
        this.jdbc       = jdbc;
        this.properties = properties;
        this.h3         = H3Core.newInstance();
        this.wgs84      = crsFactory.createFromName("EPSG:4326");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes H3 hex coverage for a single cell and upserts into DB.
     * Reads the continuous .bil file for signal values (falls back to .svr.bil).
     * Stores avg_signal and max_signal per (cell, hex) pair in cell_hex_map.
     *
     * @param cellId   cell identifier (e.g. "RC_100")
     * @param cellDir  directory containing the raster files
     * @param hdr      parsed HDR values (ulxmap, ulymap, xdim, ydim, ncols, nrows)
     * @param epsgCode source CRS (e.g. "EPSG:32648")
     */
    public void computeForCell(String cellId, Path cellDir, Map<String, Double> hdr, String epsgCode) {
        // Prefer continuous .bil for real signal values; fall back to binary .svr.bil
        Path bilFile = cellDir.resolve(cellId + ".bil");
        if (!Files.exists(bilFile)) bilFile = cellDir.resolve(cellId + ".svr.bil");
        if (!Files.exists(bilFile)) {
            log.warn("No BIL file found for cell {}, skipping hex coverage", cellId);
            return;
        }

        List<double[]> pixels = sampleValidPixels(bilFile, hdr, epsgCode);
        if (pixels.isEmpty()) {
            log.warn("No valid pixels found for cell {}", cellId);
            return;
        }

        int totalHex = 0;
        for (int res : RESOLUTIONS) {
            Map<String, DoubleSummaryStatistics> hexStats = pixelsToHexStats(pixels, res);
            if (hexStats.isEmpty()) continue;
            upsertHexGeometry(hexStats.keySet(), res);
            insertCellHexMapWithSignal(cellId, hexStats, res);
            totalHex += hexStats.size();
        }
        log.info("Cell {}: upserted {} hex entries across {} resolutions", cellId, totalHex, RESOLUTIONS.length);
    }

    /**
     * Removes cell_hex_map rows for the given cells (called when cells are deleted).
     * hex_coverage rows are shared across cells so they are not removed.
     */
    public void deleteForCells(List<String> cellIds) {
        if (cellIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(cellIds.size(), "?"));
        int deleted = jdbc.update(
                "DELETE FROM cell_hex_map WHERE cell_id IN (" + placeholders + ")",
                cellIds.toArray());
        log.info("Removed {} cell_hex_map rows for {} deleted cells", deleted, cellIds.size());
    }

    /**
     * Recomputes hex coverage for ALL cells found in the raster directory.
     * If reset=true, truncates both tables first.
     *
     * @return number of cells processed successfully
     */
    @Transactional
    public int syncAll(boolean reset) {
        if (reset) {
            jdbc.execute("TRUNCATE hex_coverage, cell_hex_map");
            log.info("Truncated hex_coverage and cell_hex_map");
        }

        Path rasterDir = Paths.get(properties.getRasterDir());
        if (!Files.isDirectory(rasterDir)) {
            log.error("Raster directory not found: {}", rasterDir);
            return 0;
        }

        List<Path> cellDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rasterDir, Files::isDirectory)) {
            for (Path dir : stream) cellDirs.add(dir);
        } catch (IOException e) {
            log.error("Failed to scan raster directory", e);
            return 0;
        }

        int ok = 0, skipped = 0, errors = 0;
        for (Path cellDir : cellDirs) {
            String cellId = cellDir.getFileName().toString();
            Path hdrFile  = cellDir.resolve(cellId + ".hdr");
            if (!Files.exists(hdrFile)) { skipped++; continue; }

            try {
                Map<String, Double> hdr = parseHdr(hdrFile);
                String epsgCode = detectEpsg(cellDir, cellId);
                computeForCell(cellId, cellDir, hdr, epsgCode);
                ok++;
            } catch (Exception e) {
                log.error("Error processing cell {}: {}", cellId, e.getMessage());
                errors++;
            }
        }

        log.info("syncAll complete: {} ok, {} skipped, {} errors", ok, skipped, errors);
        return ok;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Reads a Float32 BIL raster, reprojects valid pixels to WGS84,
     * and returns up to MAX_PIXELS [lat, lng, signalValue] triples using
     * reservoir sampling. Pixels with value <= NODATA_THRESHOLD or == 0 are skipped.
     */
    private List<double[]> sampleValidPixels(Path bilFile, Map<String, Double> hdr, String epsgCode) {
        double ulxmap = hdr.get("ulxmap");
        double ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim");
        double ydim   = hdr.get("ydim");
        int    ncols  = hdr.get("ncols").intValue();
        int    nrows  = hdr.get("nrows").intValue();

        CoordinateReferenceSystem sourceCrs = crsFactory.createFromName(epsgCode);
        CoordinateTransform       toWgs84   = ctFactory.createTransform(sourceCrs, wgs84);

        // Reservoir sampling: keep at most MAX_PIXELS valid pixels in O(N) time
        List<double[]> reservoir = new ArrayList<>(MAX_PIXELS);
        Random rng = new Random(42);

        int bufferPixels = Math.min(ncols * 64, 1 << 20);
        ByteBuffer buf = ByteBuffer.allocate(bufferPixels * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        try (FileChannel fc = FileChannel.open(bilFile, StandardOpenOption.READ)) {
            int  pixelIdx   = 0;
            int  validCount = 0;
            long remaining  = (long) ncols * nrows;

            while (remaining > 0) {
                int toRead = (int) Math.min(remaining, bufferPixels);
                buf.clear().limit(toRead * Float.BYTES);
                int bytesRead = fc.read(buf);
                if (bytesRead <= 0) break;
                buf.flip();

                int floatsRead = bytesRead / Float.BYTES;
                for (int i = 0; i < floatsRead; i++) {
                    float v = buf.getFloat();
                    if (v > NODATA_THRESHOLD && Float.isFinite(v) && v >= SIGNAL_MIN_DBM && v <= SIGNAL_MAX_DBM) {
                        int row = pixelIdx / ncols;
                        int col = pixelIdx % ncols;

                        double nativeX = ulxmap + col * xdim;
                        double nativeY = ulymap - row * ydim;

                        ProjCoordinate dst = new ProjCoordinate();
                        toWgs84.transform(new ProjCoordinate(nativeX, nativeY), dst);
                        double[] point = {dst.y, dst.x, v}; // [lat, lng, signalValue]

                        validCount++;
                        if (reservoir.size() < MAX_PIXELS) {
                            reservoir.add(point);
                        } else {
                            int j = rng.nextInt(validCount);
                            if (j < MAX_PIXELS) reservoir.set(j, point);
                        }
                    }
                    pixelIdx++;
                }
                remaining -= floatsRead;
            }
        } catch (IOException e) {
            log.warn("Failed to read BIL file: {}", bilFile, e);
        }

        return reservoir;
    }

    /**
     * Groups pixels by H3 hex and computes DoubleSummaryStatistics (avg, max) per hex.
     * Each point is double[]{lat, lng, signalValue}.
     */
    private Map<String, DoubleSummaryStatistics> pixelsToHexStats(List<double[]> pixels, int res) {
        Map<String, DoubleSummaryStatistics> stats = new HashMap<>();
        for (double[] pt : pixels) {
            try {
                String hexId = h3.latLngToCellAddress(pt[0], pt[1], res);
                stats.computeIfAbsent(hexId, k -> new DoubleSummaryStatistics()).accept(pt[2]);
            } catch (Exception ignored) {}
        }
        return stats;
    }

    /** Upserts hex geometry into hex_coverage — does NOT update count/total (computed dynamically). */
    private void upsertHexGeometry(Set<String> hexIds, int res) {
        List<Object[]> rows = new ArrayList<>(hexIds.size());
        for (String hexId : hexIds) {
            String wkt = buildPolygonWkt(hexId);
            if (wkt != null) rows.add(new Object[]{hexId, wkt, res});
        }
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO hex_coverage (hex_id, h3_geom, res, count, total) " +
                "VALUES (?, ST_GeomFromText(?, 4326), ?, 0, 0) " +
                "ON CONFLICT (hex_id) DO NOTHING",
                rows);
    }

    /** Inserts/updates cell_hex_map rows with per-hex avg_signal and max_signal. */
    private void insertCellHexMapWithSignal(String cellId,
                                            Map<String, DoubleSummaryStatistics> hexStats,
                                            int res) {
        List<Object[]> rows = hexStats.entrySet().stream()
                .map(e -> new Object[]{
                        cellId, e.getKey(), res,
                        e.getValue().getAverage(),
                        e.getValue().getMax()
                })
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO cell_hex_map (cell_id, hex_id, res, avg_signal, max_signal) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (cell_id, hex_id, res) DO UPDATE SET " +
                "avg_signal = EXCLUDED.avg_signal, max_signal = EXCLUDED.max_signal",
                rows);
    }

    private String buildPolygonWkt(String hexId) {
        try {
            List<LatLng> boundary = h3.cellToBoundary(hexId);
            StringBuilder sb = new StringBuilder("POLYGON((");
            for (LatLng ll : boundary) {
                sb.append(ll.lng).append(' ').append(ll.lat).append(',');
            }
            LatLng first = boundary.get(0);
            sb.append(first.lng).append(' ').append(first.lat).append("))");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // HDR / CRS helpers (duplicated from CellImportService to avoid circular dep)
    // -------------------------------------------------------------------------

    private Map<String, Double> parseHdr(Path hdrFile) throws IOException {
        Map<String, Double> values = new HashMap<>();
        List<String> required = List.of("ulxmap", "ulymap", "xdim", "ydim", "ncols", "nrows");
        for (String line : Files.readAllLines(hdrFile)) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && required.contains(parts[0].toLowerCase())) {
                values.put(parts[0].toLowerCase(), Double.parseDouble(parts[1]));
            }
        }
        for (String key : required) {
            if (!values.containsKey(key))
                throw new IllegalStateException("Missing HDR field: " + key);
        }
        return values;
    }

    private String detectEpsg(Path cellDir, String cellId) {
        // 1. Try PRJ file
        Path prj = cellDir.resolve(cellId + ".prj");
        if (!Files.exists(prj)) prj = cellDir.resolve(cellId + ".svr.prj");
        if (Files.exists(prj)) {
            try {
                Matcher m = Pattern.compile("AUTHORITY\\[\"EPSG\",\"(326\\d+)\"\\]\\s*\\]\\s*$")
                        .matcher(Files.readString(prj));
                if (m.find()) return "EPSG:" + m.group(1);
            } catch (IOException ignored) {}
        }

        // 2. Infer UTM zone from ulxmap easting in HDR (easting > 900 000 → zone 49N, else 48N)
        Path hdrFile = cellDir.resolve(cellId + ".hdr");
        if (!Files.exists(hdrFile)) hdrFile = cellDir.resolve(cellId + ".svr.hdr");
        if (Files.exists(hdrFile)) {
            try {
                for (String line : Files.readAllLines(hdrFile)) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2 && parts[0].equalsIgnoreCase("ulxmap")) {
                        double easting = Double.parseDouble(parts[1]);
                        return easting > 900_000 ? "EPSG:32649" : "EPSG:32648";
                    }
                }
            } catch (Exception ignored) {}
        }

        return "EPSG:32648";
    }
}
