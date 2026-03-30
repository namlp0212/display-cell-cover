package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.example.cellcover.dto.CellImportResult;
import com.example.cellcover.entity.RasterCoverage;
import com.example.cellcover.repository.RasterCoverageRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class CellImportService {

    private static final Logger log = LoggerFactory.getLogger(CellImportService.class);
    private static final int SRID = 4326;
    private static final int OVERLAP_GROUPS = 8;
    private static final float NODATA_TOLERANCE  = 1e30f;
    private static final float SIGNAL_MIN_DBM    = -140f;  // below this → treat as NoData
    private static final float SIGNAL_MAX_DBM    = -44f;   // above this → physically invalid
    private static final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    private final CellImportProperties  properties;
    private final RasterCoverageRepository repository;
    private final HexCoverageService    hexCoverageService;
    private final BilToCogConverter     bilToCogConverter;
    private final GeoServerService      geoServerService;
    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
    private final CoordinateReferenceSystem  wgs84 = crsFactory.createFromName("EPSG:4326");

    public CellImportService(CellImportProperties properties,
                             RasterCoverageRepository repository,
                             HexCoverageService hexCoverageService,
                             BilToCogConverter bilToCogConverter,
                             GeoServerService geoServerService) {
        this.properties        = properties;
        this.repository        = repository;
        this.hexCoverageService = hexCoverageService;
        this.bilToCogConverter  = bilToCogConverter;
        this.geoServerService   = geoServerService;
    }

    public CellImportResult syncCells() {
        Path rasterDir = Paths.get(properties.getRasterDir());
        Path cogDir    = Paths.get(properties.getCogDir());

        if (!Files.isDirectory(rasterDir)) {
            log.error("Raster directory not found: {}", rasterDir);
            return new CellImportResult(List.of(), List.of(), List.of(), 0, 0,
                    "Error: raster directory not found");
        }

        // Ensure GeoServer workspace + stores exist (idempotent)
        geoServerService.ensureSetup(cogDir);

        // Scan cell folders on disk
        List<String> allCellIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rasterDir, Files::isDirectory)) {
            for (Path dir : stream) allCellIds.add(dir.getFileName().toString());
        } catch (IOException e) {
            log.error("Failed to scan raster directory", e);
            return new CellImportResult(List.of(), List.of(), List.of(), 0, 0,
                    "Error scanning directory: " + e.getMessage());
        }

        Set<String> localCellIds    = new HashSet<>(allCellIds);
        Set<String> existingCellIds = new HashSet<>(repository.findDistinctCellIds());

        // --- Cleanup: delete orphan cells (in DB but not on disk) ---
        List<String> deletedCells = new ArrayList<>();
        List<String> orphanIds = existingCellIds.stream()
                .filter(id -> !localCellIds.contains(id)).toList();
        if (!orphanIds.isEmpty()) {
            repository.deleteByCellIds(orphanIds);
            hexCoverageService.deleteForCells(orphanIds);
            orphanIds.forEach(id -> geoServerService.removeGranule(cogDir, id));
            deletedCells.addAll(orphanIds);
            log.info("Deleted {} orphan cell(s): {}", orphanIds.size(), orphanIds);
        }

        // --- Import: add new cells (on disk but not in DB) ---
        List<String> importedCells = new ArrayList<>();
        List<String> skippedCells  = new ArrayList<>();

        // Phase 1 — sequential DB saves (commits immediately per cell)
        List<PendingCell> pending = new ArrayList<>();
        for (String cellId : allCellIds) {
            if (existingCellIds.contains(cellId)) {
                skippedCells.add(cellId);
                continue;
            }

            Path cellDir = rasterDir.resolve(cellId);
            Path hdrFile = cellDir.resolve(cellId + ".hdr");
            if (!Files.exists(hdrFile)) {
                log.warn("HDR file not found for cell {}, skipping", cellId);
                skippedCells.add(cellId);
                continue;
            }

            try {
                Map<String, Double> hdrValues = parseHdr(hdrFile);
                String epsgCode  = detectEpsg(cellDir, cellId);
                double[] wgs84Bbox = computeWgs84Bbox(hdrValues, epsgCode);

                Path bilFile = cellDir.resolve(cellId + ".bil");
                int ncols = hdrValues.get("ncols").intValue();
                int nrows = hdrValues.get("nrows").intValue();

                double xdim = hdrValues.get("xdim");
                double ydim = hdrValues.get("ydim");
                double areaSqM = (double) ncols * xdim * (double) nrows * ydim;
                if (areaSqM < 1_000_000.0) {
                    log.warn("Cell {} has very small raster extent: {}x{} pixels = {} m² ({} km²). " +
                             "Possibly indoor/pico cell or export artifact — importing anyway.",
                            cellId, ncols, nrows,
                            Math.round(areaSqM),
                            String.format("%.4f", areaSqM / 1_000_000.0));
                }

                double[] signalStats = computeSignalStats(bilFile, ncols, nrows);
                Double avgSignal = signalStats != null ? signalStats[0] : null;
                Double maxSignal = signalStats != null ? signalStats[1] : null;

                int ovlpGroup = saveToDatabase(cellId, wgs84Bbox, avgSignal, maxSignal);
                pending.add(new PendingCell(cellId, cellDir, hdrValues, epsgCode, ovlpGroup, avgSignal));
            } catch (Exception e) {
                log.error("Failed to import cell {} (phase 1): {}", cellId, e.getMessage());
                skippedCells.add(cellId);
            }
        }

        // Phase 2 — parallel heavy work (COG conversion, GeoServer, H3)
        ExecutorService pool = Executors.newFixedThreadPool(properties.getParallelThreads());
        Map<String, Future<?>> futures = new LinkedHashMap<>();
        for (PendingCell p : pending) {
            futures.put(p.cellId(), pool.submit(() -> {
                try {
                    bilToCogConverter.convert(p.cellId(), p.cellDir(), p.hdr(), p.epsg(), cogDir);
                    geoServerService.registerGranule(cogDir, p.cellId(), p.ovlpGroup(), p.avgSignal());
                    hexCoverageService.computeForCell(p.cellId(), p.cellDir(), p.hdr(), p.epsg());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Import thread pool interrupted", e);
        }
        for (Map.Entry<String, Future<?>> entry : futures.entrySet()) {
            try {
                entry.getValue().get();
                importedCells.add(entry.getKey());
                log.info("Imported cell: {}", entry.getKey());
            } catch (Exception e) {
                log.error("Failed to import cell {} (phase 2): {}", entry.getKey(), e.getMessage());
                skippedCells.add(entry.getKey());
            }
        }

        if (!importedCells.isEmpty() || !deletedCells.isEmpty()) {
            // Reset coverage bbox so GeoServer expands its native grid to cover all
            // granules, including newly-added cells outside the original bbox.
            geoServerService.resetCoverageBbox();
            geoServerService.reload();
        }

        return new CellImportResult(importedCells, skippedCells, deletedCells,
                importedCells.size(), deletedCells.size(), "done");
    }

    /**
     * Registers all existing COG files with GeoServer (idempotent).
     * Use this when GeoServer was unavailable during the original sync.
     */
    public Map<String, Object> syncGeoServer() {
        Path cogDir = Paths.get(properties.getCogDir());
        geoServerService.ensureSetup(cogDir);

        List<Object[]> rows = repository.findCellRegistrationInfo();
        int registered = 0, failed = 0;
        for (Object[] row : rows) {
            String cellId   = (String) row[0];
            int ovlpGroup   = (int)    row[1];
            Double avgSignal = (Double) row[2];
            try {
                geoServerService.registerGranule(cogDir, cellId, ovlpGroup, avgSignal);
                registered++;
            } catch (Exception e) {
                log.warn("Failed to register GeoServer granule for {}: {}", cellId, e.getMessage());
                failed++;
            }
        }
        geoServerService.resetCoverageBbox();
        geoServerService.reload();
        log.info("GeoServer sync complete: {} registered, {} failed", registered, failed);
        return Map.of("registered", registered, "failed", failed);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    Map<String, Double> parseHdr(Path hdrFile) throws IOException {
        Map<String, Double> values = new HashMap<>();
        List<String> required = List.of("ulxmap", "ulymap", "xdim", "ydim", "ncols", "nrows");
        for (String line : Files.readAllLines(hdrFile)) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && required.contains(parts[0].toLowerCase()))
                values.put(parts[0].toLowerCase(), Double.parseDouble(parts[1]));
        }
        for (String key : required)
            if (!values.containsKey(key)) throw new IllegalStateException("Missing HDR field: " + key);
        return values;
    }

    String detectEpsg(Path cellDir, String cellId) {
        // 1. Try PRJ file (most authoritative)
        Path prj = cellDir.resolve(cellId + ".prj");
        if (Files.exists(prj)) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("AUTHORITY\\[\"EPSG\",\"(326\\d+)\"\\]\\s*\\]\\s*$")
                        .matcher(Files.readString(prj));
                if (m.find()) return "EPSG:" + m.group(1);
            } catch (IOException e) {
                log.warn("Failed to read PRJ for cell {}, falling back to easting detection", cellId);
            }
        }

        // 2. No PRJ — infer UTM zone from ulxmap easting in HDR.
        //    UTM false easting = 500 000 m; typical field easting range per zone:
        //      Zone 48N (CM 105°E): ~166 000 – 834 000  → covers most of Vietnam incl. Hanoi, Da Nang
        //      Zone 49N (CM 111°E): ~100 000 – 900 000  → covers far-east Vietnam
        //    Heuristic: easting > 900 000 → likely zone 49N; otherwise zone 48N.
        Path hdrFile = cellDir.resolve(cellId + ".hdr");
        if (!Files.exists(hdrFile)) hdrFile = cellDir.resolve(cellId + ".svr.hdr");
        if (Files.exists(hdrFile)) {
            try {
                for (String line : Files.readAllLines(hdrFile)) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2 && parts[0].equalsIgnoreCase("ulxmap")) {
                        double easting = Double.parseDouble(parts[1]);
                        String epsg = easting > 900_000 ? "EPSG:32649" : "EPSG:32648";
                        log.info("Cell {} has no PRJ; inferred {} from ulxmap={}", cellId, epsg, easting);
                        return epsg;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read HDR for CRS detection of cell {}", cellId);
            }
        }

        // 3. Last-resort default (zone 48N — covers majority of Vietnam)
        log.warn("Cell {} has no PRJ and no readable HDR; defaulting to EPSG:32648", cellId);
        return "EPSG:32648";
    }

    double[] computeWgs84Bbox(Map<String, Double> hdr, String epsgCode) {
        double ulxmap = hdr.get("ulxmap"), ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim"),   ydim   = hdr.get("ydim");
        double ncols  = hdr.get("ncols"),   nrows  = hdr.get("nrows");

        double minX = ulxmap - xdim / 2, maxY = ulymap + ydim / 2;
        double maxX = minX + ncols * xdim, minY = maxY - nrows * ydim;

        CoordinateReferenceSystem srcCrs = crsFactory.createFromName(epsgCode);
        CoordinateTransform toWgs84 = ctFactory.createTransform(srcCrs, wgs84);

        ProjCoordinate bl = transform(toWgs84, minX, minY);
        ProjCoordinate br = transform(toWgs84, maxX, minY);
        ProjCoordinate tr = transform(toWgs84, maxX, maxY);
        ProjCoordinate tl = transform(toWgs84, minX, maxY);

        return new double[]{
            Math.min(Math.min(bl.x, tl.x), Math.min(br.x, tr.x)),
            Math.min(Math.min(bl.y, br.y), Math.min(tl.y, tr.y)),
            Math.max(Math.max(bl.x, tl.x), Math.max(br.x, tr.x)),
            Math.max(Math.max(bl.y, br.y), Math.max(tl.y, tr.y))
        };
    }

    private ProjCoordinate transform(CoordinateTransform ct, double x, double y) {
        ProjCoordinate dst = new ProjCoordinate();
        ct.transform(new ProjCoordinate(x, y), dst);
        return dst;
    }

    /** Saves cell to DB and returns the assigned ovlp_group. */
    private int saveToDatabase(String cellId, double[] bbox, Double avgSignal, Double maxSignal) {
        double minX = bbox[0], minY = bbox[1], maxX = bbox[2], maxY = bbox[3];
        Polygon polygon = geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        });
        polygon.setSRID(SRID);

        int group = (int) (repository.countDistinctCellIds() % OVERLAP_GROUPS);

        RasterCoverage standard = new RasterCoverage();
        standard.setCellId(cellId); standard.setFilePath(cellId + "/" + cellId + ".bil");
        standard.setBbox(polygon);  standard.setCrs("EPSG:4326");
        standard.setSvr(false);     standard.setVisible(true);
        standard.setOvlpGroup(group); standard.setAvgSignal(avgSignal); standard.setMaxSignal(maxSignal);

        repository.save(standard);
        return group;
    }

    double[] computeSignalStats(Path bilFile, int ncols, int nrows) {
        if (!Files.exists(bilFile)) return null;
        long totalPixels = (long) ncols * nrows;
        if (totalPixels == 0) return null;

        double sum = 0.0, max = Double.NEGATIVE_INFINITY;
        long validCount = 0;
        int bufferPixels = Math.min(ncols * 256, 1 << 20);
        ByteBuffer buf = ByteBuffer.allocate(bufferPixels * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        try (FileChannel fc = FileChannel.open(bilFile, StandardOpenOption.READ)) {
            long remaining = totalPixels;
            while (remaining > 0) {
                int toRead = (int) Math.min(remaining, bufferPixels);
                buf.clear().limit(toRead * Float.BYTES);
                int bytesRead = fc.read(buf);
                if (bytesRead <= 0) break;
                buf.flip();
                int floatsRead = bytesRead / Float.BYTES;
                for (int i = 0; i < floatsRead; i++) {
                    float v = buf.getFloat();
                    if (v < NODATA_TOLERANCE && v >= SIGNAL_MIN_DBM && v <= SIGNAL_MAX_DBM) {
                        sum += v; if (v > max) max = v; validCount++;
                    }
                }
                remaining -= floatsRead;
            }
        } catch (IOException e) {
            log.warn("Failed to read BIL for signal stats: {}", bilFile, e);
            return null;
        }
        return validCount == 0 ? null : new double[]{sum / validCount, max};
    }

    private record PendingCell(String cellId, Path cellDir, Map<String, Double> hdr, String epsg, int ovlpGroup, Double avgSignal) {}
}
