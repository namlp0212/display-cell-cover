package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Manages GeoServer workspace, ImageMosaic stores, styles and granules
 * via the GeoServer REST API. Replaces the curl/Python calls in geoserver-init.sh.
 */
@Service
public class GeoServerService {

    private static final Logger log = LoggerFactory.getLogger(GeoServerService.class);

    /** Shapefile schema for binary store (density stacking, no signal field needed). */
    private static final String BINARY_SCHEMA =
            "*the_geom:Polygon,location:String,cell_id:String,ovlp_group:Integer";

    /**
     * Shapefile schema for continuous store.
     * avg_signal is used to sort granules ASC when serving signal tiles so that
     * the cell with the highest average signal renders last (on top), approximating
     * per-pixel MAX across overlapping granules.
     */
    private static final String CONTINUOUS_SCHEMA =
            "*the_geom:Polygon,location:String,cell_id:String,ovlp_group:Integer,avg_signal:Double";

    private final RestTemplate        restTemplate;
    private final CellImportProperties properties;

    public GeoServerService(RestTemplate restTemplate, CellImportProperties properties) {
        this.restTemplate = restTemplate;
        this.properties   = properties;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Idempotent: ensures workspace, coverage stores, layers and styles exist in GeoServer.
     * Call once before registering granules.
     */
    public void ensureSetup(Path cogDir) {
        try {
            ensureWorkspace();
            ensureIndexerProperties(cogDir.resolve("binary"),     BINARY_SCHEMA);
            ensureIndexerProperties(cogDir.resolve("continuous"), CONTINUOUS_SCHEMA);
            ensureCoverageStore("binary",     cogDir.resolve("binary"));
            ensureCoverageStore("continuous", cogDir.resolve("continuous"));
            ensureCoverage("binary");
            ensureCoverage("continuous");
            ensureStyle("cellcover-binary");
            ensureStyle("cellcover-continuous");
            applyStyle("binary",     "cellcover-binary");
            applyStyle("continuous", "cellcover-continuous");
            setBinaryMergeBehavior();
            setContinuousMergeBehavior();
            ensureAvgSignalColumn(cogDir.resolve("continuous"));
        } catch (Exception e) {
            log.error("GeoServer setup failed: {}", e.getMessage());
        }
    }

    /**
     * Registers a COG pair (binary + continuous) into GeoServer's ImageMosaic shapefile index.
     * COG files are kept as local files — no MinIO upload.
     */
    public void registerGranule(Path cogDir, String cellId, int ovlpGroup, Double avgSignal) {
        registerStoreGranule(cogDir, "binary",     cellId + "_svr.tif", cellId, ovlpGroup, null);
        registerStoreGranule(cogDir, "continuous", cellId + ".tif",      cellId, ovlpGroup, avgSignal);
    }

    private void registerStoreGranule(Path cogDir, String storeType, String fileName,
                                       String cellId, int ovlpGroup, Double avgSignal) {
        Path localTif = cogDir.resolve(storeType).resolve(fileName);
        if (!Files.exists(localTif)) {
            log.warn("COG file not found, skipping GeoServer registration: {}", localTif);
            return;
        }
        try {
            addGranuleToStore(localTif, storeType);
            updateShapefileEntry(cogDir.resolve(storeType), storeType,
                                 fileName, cellId, ovlpGroup,
                                 localTif.toAbsolutePath().toString(), avgSignal);
        } catch (Exception e) {
            log.warn("Failed to register granule {} in store {}: {}", fileName, storeType, e.getMessage());
        }
    }

    /**
     * Resets the native bounding box and grid of both coverage stores by deleting and
     * recreating the coverage layers.  GeoServer computes the extent from the full
     * shapefile index, so the bbox expands to cover all registered granules.
     *
     * Call this after a bulk import is complete to ensure all areas are renderable.
     */
    public void resetCoverageBbox() {
        try {
            resetCoverage("binary",     "cellcover-binary");
            resetCoverage("continuous", "cellcover-continuous");
            setBinaryMergeBehavior();
            setContinuousMergeBehavior();
            log.info("Coverage bounding boxes reset successfully");
        } catch (Exception e) {
            log.error("Failed to reset coverage bounding boxes: {}", e.getMessage());
        }
    }

    private void resetCoverage(String storeType, String styleName) {
        // Delete coverage (cascade to layer)
        try {
            restTemplate.exchange(
                    url("/rest/workspaces/" + ws() + "/coveragestores/" + storeType
                            + "/coverages/" + storeType + ".json?recurse=true"),
                    HttpMethod.DELETE, authEntity(null), String.class);
            log.info("Deleted coverage: {}", storeType);
        } catch (Exception e) {
            log.warn("Delete coverage {} failed (may not exist): {}", storeType, e.getMessage());
        }

        // Recreate — GeoServer recomputes bbox from the full shapefile index
        ensureCoverage(storeType);
        applyStyle(storeType, styleName);
        log.info("Recreated coverage: {} with style {}", storeType, styleName);
    }

    /** Removes a cell's granules from both ImageMosaic stores and deletes local COG files. */
    public void removeGranule(Path cogDir, String cellId) {
        deleteGranuleFromStore("binary",     cellId + "_svr.tif");
        deleteGranuleFromStore("continuous", cellId + ".tif");
        deleteFile(cogDir.resolve("binary").resolve(cellId + "_svr.tif"));
        deleteFile(cogDir.resolve("continuous").resolve(cellId + ".tif"));
    }

    /** Reloads GeoServer configuration (clears in-memory caches). */
    public void reload() {
        try {
            restTemplate.exchange(
                    url("/rest/reload"), HttpMethod.POST, authEntity(null), String.class);
            log.info("GeoServer reloaded");
        } catch (Exception e) {
            log.warn("GeoServer reload failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GeoServer REST helpers
    // -------------------------------------------------------------------------

    private void ensureWorkspace() {
        String body = """
                {"workspace":{"name":"%s"}}""".formatted(ws());
        post("/rest/workspaces", body, MediaType.APPLICATION_JSON);
    }

    private void ensureCoverageStore(String storeType, Path cogDir) {
        String fileUrl = cogDir.toAbsolutePath().toUri().toString();
        String body = """
                {"coverageStore":{"name":"%s","type":"ImageMosaic","enabled":true,\
                "workspace":{"name":"%s"},"url":"%s"}}"""
                .formatted(storeType, ws(), fileUrl);
        post("/rest/workspaces/" + ws() + "/coveragestores", body, MediaType.APPLICATION_JSON);
    }

    private void ensureCoverage(String storeType) {
        String body = """
                {"coverage":{"name":"%s","nativeName":"%s","enabled":true}}"""
                .formatted(storeType, storeType);
        post("/rest/workspaces/" + ws() + "/coveragestores/" + storeType + "/coverages",
                body, MediaType.APPLICATION_JSON);
    }

    private void ensureStyle(String styleName) {
        // 1. Create style entry
        String meta = """
                {"style":{"name":"%s","filename":"%s.sld"}}"""
                .formatted(styleName, styleName);
        post("/rest/styles", meta, MediaType.APPLICATION_JSON);

        // 2. Upload SLD content
        try {
            ClassPathResource sld = new ClassPathResource("styles/" + styleName + ".sld");
            String content = new String(sld.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            put("/rest/styles/" + styleName, content,
                    MediaType.valueOf("application/vnd.ogc.sld+xml"));
        } catch (IOException e) {
            log.warn("SLD file not found for style {}: {}", styleName, e.getMessage());
        }
    }

    private void applyStyle(String layerName, String styleName) {
        String body = """
                {"layer":{"defaultStyle":{"name":"%s"}}}""".formatted(styleName);
        put("/rest/layers/" + ws() + ":" + layerName, body, MediaType.APPLICATION_JSON);
    }

    /** Binary store: STACK merge so alpha accumulates with overlapping cells (density proxy). */
    private void setBinaryMergeBehavior() {
        String body = """
                {"coverage":{"parameters":{"entry":[
                  {"string":["MERGE_BEHAVIOR","STACK"]},
                  {"string":["BackgroundValues","-3.4028235e+38"]}
                ]}}}""";
        put("/rest/workspaces/" + ws() + "/coveragestores/binary/coverages/binary.json",
                body, MediaType.APPLICATION_JSON);
    }

    /**
     * Continuous store: FLAT merge so each pixel shows the signal value from the last
     * rendered granule.  Combined with sortBy=avg_signal+A in WMS requests (weakest cell
     * rendered first → strongest overwrites), this approximates per-pixel MAX signal.
     */
    private void setContinuousMergeBehavior() {
        String body = """
                {"coverage":{"parameters":{"entry":[
                  {"string":["MERGE_BEHAVIOR","FLAT"]},
                  {"string":["BackgroundValues","-3.4028235e+38"]}
                ]}}}""";
        put("/rest/workspaces/" + ws() + "/coveragestores/continuous/coverages/continuous.json",
                body, MediaType.APPLICATION_JSON);
    }

    private void addGranuleToStore(Path cogFile, String storeType) {
        String fileUrl = cogFile.toAbsolutePath().toString();  // plain path, not file:// URI
        try {
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            HttpEntity<String> entity = new HttpEntity<>(fileUrl, headers);
            restTemplate.exchange(
                    url("/rest/workspaces/" + ws() + "/coveragestores/" + storeType + "/external.imagemosaic"),
                    HttpMethod.POST, entity, String.class);
            log.info("Added granule to {}: {}", storeType, cogFile.getFileName());
        } catch (Exception e) {
            log.warn("Failed to add granule {} to {}: {}", cogFile.getFileName(), storeType, e.getMessage());
        }
    }

    private void deleteGranuleFromStore(String storeType, String fileName) {
        try {
            String filterUrl = url("/rest/workspaces/" + ws() + "/coveragestores/" + storeType
                    + "/coverages/" + storeType + "/index/granules"
                    + "?filter=location+like+'%" + fileName + "%'");
            restTemplate.exchange(filterUrl, HttpMethod.DELETE, authEntity(null), String.class);
            log.info("Removed granule {} from {}", fileName, storeType);
        } catch (Exception e) {
            log.warn("Failed to remove granule {} from {}: {}", fileName, storeType, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Shapefile index management (GeoTools)
    // -------------------------------------------------------------------------

    /**
     * Writes indexer.properties into the mosaic directory so GeoServer creates
     * the shapefile with the given schema on first creation.
     */
    private void ensureIndexerProperties(Path mosaicDir, String schema) throws IOException {
        Files.createDirectories(mosaicDir);
        Path indexer = mosaicDir.resolve("indexer.properties");
        if (!Files.exists(indexer)) {
            Files.writeString(indexer, "Schema=" + schema + "\n");
            log.info("Wrote indexer.properties ({}) in {}", schema, mosaicDir.getFileName());
        }
    }

    /**
     * Adds the avg_signal column to the continuous shapefile if it does not already exist.
     * This handles existing shapefiles created before avg_signal was added to the schema.
     * Idempotent — a second run is a no-op (the ALTER TABLE fails silently if the column exists).
     */
    private void ensureAvgSignalColumn(Path continuousMosaicDir) {
        Path shpFile = continuousMosaicDir.resolve("continuous.shp");
        if (!Files.exists(shpFile)) return;
        // OGRSQL (default) supports ALTER TABLE ADD COLUMN for shapefile format.
        // SQLite dialect does NOT support this command.
        // If the column already exists the command fails with a non-zero exit — that's fine.
        String sql = "ALTER TABLE continuous ADD COLUMN avg_signal REAL";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ogrinfo", "-sql", sql,
                    shpFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("Added avg_signal column to continuous shapefile");
            } else {
                log.info("avg_signal column add result (exit {}): {}", exitCode, out.trim());
            }
        } catch (Exception e) {
            log.warn("ensureAvgSignalColumn failed: {}", e.getMessage());
        }
    }

    /**
     * Updates cell_id and ovlp_group for a granule entry in the shapefile index via ogrinfo.
     * GeoTools DataStore writes fail silently on JDK 21+ due to NIO buffer cleanup restrictions.
     * Using GDAL ogrinfo SQL UPDATE avoids this issue entirely.
     */
    private void updateShapefileEntry(Path mosaicDir, String typeName,
                                      String cogFileName, String cellId, int ovlpGroup,
                                      String newLocation, Double avgSignal) {
        Path shpFile = mosaicDir.resolve(typeName + ".shp");
        if (!Files.exists(shpFile)) {
            log.warn("Shapefile not found: {}", shpFile);
            return;
        }
        String sql = avgSignal != null
                ? String.format(
                        "UPDATE %s SET cell_id='%s', ovlp_group=%d, avg_signal=%f, location='%s' WHERE location LIKE '%%%s%%'",
                        typeName, cellId, ovlpGroup, avgSignal, newLocation, cogFileName)
                : String.format(
                        "UPDATE %s SET cell_id='%s', ovlp_group=%d, location='%s' WHERE location LIKE '%%%s%%'",
                        typeName, cellId, ovlpGroup, newLocation, cogFileName);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ogrinfo", "-dialect", "SQLite", "-sql", sql,
                    shpFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("Updated shapefile: {} → cell_id={}, ovlp_group={}", cogFileName, cellId, ovlpGroup);
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to update shapefile for {}: {}", cogFileName, e.getMessage());
            return;
        }
        // First attempt failed (likely avg_signal column missing). Retry without avg_signal.
        if (avgSignal != null) {
            String fallbackSql = String.format(
                    "UPDATE %s SET cell_id='%s', ovlp_group=%d, location='%s' WHERE location LIKE '%%%s%%'",
                    typeName, cellId, ovlpGroup, newLocation, cogFileName);
            try {
                ProcessBuilder pb2 = new ProcessBuilder(
                        "ogrinfo", "-dialect", "SQLite", "-sql", fallbackSql,
                        shpFile.toAbsolutePath().toString());
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                int exitCode2 = p2.waitFor();
                if (exitCode2 == 0) {
                    log.info("Updated shapefile (no avg_signal): {} → cell_id={}", cogFileName, cellId);
                } else {
                    log.warn("Shapefile update failed for {}", cogFileName);
                }
            } catch (Exception e) {
                log.warn("Fallback shapefile update failed for {}: {}", cogFileName, e.getMessage());
            }
        } else {
            log.warn("ogrinfo exited non-zero for shapefile update: {}", cogFileName);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP utilities
    // -------------------------------------------------------------------------

    private void post(String path, String body, MediaType contentType) {
        try {
            HttpEntity<String> entity = new HttpEntity<>(body, authHeaders(contentType));
            restTemplate.postForEntity(url(path), entity, String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409 || e.getStatusCode().value() == 500) {
                log.debug("POST {} → already exists ({})", path, e.getStatusCode());
            } else {
                log.warn("POST {} failed: {} {}", path, e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("POST {} failed: {}", path, e.getMessage());
        }
    }

    private void put(String path, String body, MediaType contentType) {
        try {
            HttpEntity<String> entity = new HttpEntity<>(body, authHeaders(contentType));
            restTemplate.exchange(url(path), HttpMethod.PUT, entity, String.class);
        } catch (Exception e) {
            log.warn("PUT {} failed: {}", path, e.getMessage());
        }
    }

    private HttpHeaders authHeaders(MediaType contentType) {
        HttpHeaders h = authHeaders();
        h.setContentType(contentType);
        return h;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        String creds = properties.getGeoserver().getUsername() + ":" + properties.getGeoserver().getPassword();
        h.set(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    private <T> HttpEntity<T> authEntity(T body) {
        return new HttpEntity<>(body, authHeaders());
    }

    private String url(String path) {
        return properties.getGeoserver().getUrl() + path;
    }

    private String ws() {
        return properties.getGeoserver().getWorkspace();
    }

    private void deleteFile(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }
}
