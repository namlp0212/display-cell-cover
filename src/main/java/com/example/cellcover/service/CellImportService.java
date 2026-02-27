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
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class CellImportService {

    private static final Logger log = LoggerFactory.getLogger(CellImportService.class);
    private static final int SRID = 4326;
    private static final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);

    private final CellImportProperties properties;
    private final RasterCoverageRepository repository;
    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
    private final CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");

    public CellImportService(CellImportProperties properties, RasterCoverageRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Transactional
    public CellImportResult syncCells() {
        Path rasterDir = Paths.get(properties.getRasterDir());
        if (!Files.isDirectory(rasterDir)) {
            log.error("Raster directory not found: {}", rasterDir);
            return new CellImportResult(List.of(), List.of(), 0, "Error: raster directory not found");
        }

        // Find all cell folders
        List<String> allCellIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rasterDir, Files::isDirectory)) {
            for (Path dir : stream) {
                allCellIds.add(dir.getFileName().toString());
            }
        } catch (IOException e) {
            log.error("Failed to scan raster directory", e);
            return new CellImportResult(List.of(), List.of(), 0, "Error scanning directory: " + e.getMessage());
        }

        // Get existing cell IDs from DB
        Set<String> existingCellIds = new HashSet<>(repository.findDistinctCellIds());

        List<String> importedCells = new ArrayList<>();
        List<String> skippedCells = new ArrayList<>();

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

                // Detect CRS from .prj file, default to UTM 47N
                String epsgCode = detectEpsg(cellDir, cellId);
                double[] wgs84Bbox = computeWgs84Bbox(hdrValues, epsgCode);

                saveToDatabase(cellId, wgs84Bbox);
                importedCells.add(cellId);
                log.info("Imported cell: {} (CRS: {})", cellId, epsgCode);
            } catch (Exception e) {
                log.error("Failed to import cell {}: {}", cellId, e.getMessage());
                skippedCells.add(cellId);
            }
        }

        // Run GeoServer init script if we imported any cells
        String geoServerOutput = "";
        if (!importedCells.isEmpty()) {
            geoServerOutput = runGeoServerInit();
        }

        return new CellImportResult(importedCells, skippedCells, importedCells.size(), geoServerOutput);
    }

    Map<String, Double> parseHdr(Path hdrFile) throws IOException {
        Map<String, Double> values = new HashMap<>();
        List<String> requiredKeys = List.of("ulxmap", "ulymap", "xdim", "ydim", "ncols", "nrows");

        for (String line : Files.readAllLines(hdrFile)) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && requiredKeys.contains(parts[0].toLowerCase())) {
                values.put(parts[0].toLowerCase(), Double.parseDouble(parts[1]));
            }
        }

        for (String key : requiredKeys) {
            if (!values.containsKey(key)) {
                throw new IllegalStateException("Missing HDR field: " + key);
            }
        }

        return values;
    }

    /**
     * Detect EPSG code from .prj file by looking for AUTHORITY["EPSG","326xx"].
     * Falls back to EPSG:32647 if no .prj found.
     */
    String detectEpsg(Path cellDir, String cellId) {
        Path prjFile = cellDir.resolve(cellId + ".prj");
        if (!Files.exists(prjFile)) {
            prjFile = cellDir.resolve(cellId + ".svr.prj");
        }
        if (Files.exists(prjFile)) {
            try {
                String content = Files.readString(prjFile);
                // Look for AUTHORITY["EPSG","326xx"] at the end (the PROJCS authority)
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("AUTHORITY\\[\"EPSG\",\"(326\\d+)\"\\]\\s*\\]\\s*$")
                        .matcher(content);
                if (m.find()) {
                    return "EPSG:" + m.group(1);
                }
            } catch (IOException e) {
                log.warn("Failed to read PRJ file for cell {}, using default CRS", cellId);
            }
        }
        return "EPSG:32647";
    }

    double[] computeWgs84Bbox(Map<String, Double> hdr, String epsgCode) {
        double ulxmap = hdr.get("ulxmap");
        double ulymap = hdr.get("ulymap");
        double xdim = hdr.get("xdim");
        double ydim = hdr.get("ydim");
        double ncols = hdr.get("ncols");
        double nrows = hdr.get("nrows");

        // HDR parsing formula (verified against gdalinfo)
        double minX = ulxmap - xdim / 2;
        double maxY = ulymap + ydim / 2;
        double maxX = minX + ncols * xdim;
        double minY = maxY - nrows * ydim;

        // Build transform for the detected CRS
        CoordinateReferenceSystem sourceCrs = crsFactory.createFromName(epsgCode);
        CoordinateTransform toWgs84 = ctFactory.createTransform(sourceCrs, wgs84);

        // Transform 4 corners from source CRS to WGS84
        ProjCoordinate bl = transform(toWgs84, minX, minY);
        ProjCoordinate br = transform(toWgs84, maxX, minY);
        ProjCoordinate tr = transform(toWgs84, maxX, maxY);
        ProjCoordinate tl = transform(toWgs84, minX, maxY);

        // Compute WGS84 envelope from transformed corners
        double wgsMinX = Math.min(Math.min(bl.x, tl.x), Math.min(br.x, tr.x));
        double wgsMaxX = Math.max(Math.max(bl.x, tl.x), Math.max(br.x, tr.x));
        double wgsMinY = Math.min(Math.min(bl.y, br.y), Math.min(tl.y, tr.y));
        double wgsMaxY = Math.max(Math.max(bl.y, br.y), Math.max(tl.y, tr.y));

        return new double[]{wgsMinX, wgsMinY, wgsMaxX, wgsMaxY};
    }

    private ProjCoordinate transform(CoordinateTransform ct, double x, double y) {
        ProjCoordinate src = new ProjCoordinate(x, y);
        ProjCoordinate dst = new ProjCoordinate();
        ct.transform(src, dst);
        return dst;
    }

    private static final int OVERLAP_GROUPS = 8;

    private void saveToDatabase(String cellId, double[] bbox) {
        double minX = bbox[0], minY = bbox[1], maxX = bbox[2], maxY = bbox[3];

        Polygon polygon = geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY),
                new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        });
        polygon.setSRID(SRID);

        // Assign overlap group: cycle 0-7 across unique cells already in DB
        int group = (int) (repository.countDistinctCellIds() % OVERLAP_GROUPS);

        // Standard row
        RasterCoverage standard = new RasterCoverage();
        standard.setCellId(cellId);
        standard.setFilePath(cellId + "/" + cellId + ".bil");
        standard.setBbox(polygon);
        standard.setCrs("EPSG:4326");
        standard.setSvr(false);
        standard.setVisible(true);
        standard.setOvlpGroup(group);

        // SVR row
        RasterCoverage svr = new RasterCoverage();
        svr.setCellId(cellId);
        svr.setFilePath(cellId + "/" + cellId + ".svr.bil");
        svr.setBbox(polygon);
        svr.setCrs("EPSG:4326");
        svr.setSvr(true);
        svr.setVisible(true);
        svr.setOvlpGroup(group);

        repository.saveAll(List.of(standard, svr));
    }

    private String runGeoServerInit() {
        String containerName = properties.getGeoserver().getContainerName();
        String initScript = properties.getGeoserver().getInitScript();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", containerName, "/bin/bash", initScript
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String result = output.toString();
            if (exitCode != 0) {
                log.warn("GeoServer init script exited with code {}: {}", exitCode, result);
                return "GeoServer init exited with code " + exitCode + ": " + result;
            }

            log.info("GeoServer init completed successfully");
            return result;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to run GeoServer init script", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error running GeoServer init: " + e.getMessage();
        }
    }
}
