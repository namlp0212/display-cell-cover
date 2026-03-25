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

    /** Shapefile schema: the_geom + location (GeoServer defaults) + our custom fields */
    private static final String MOSAIC_SCHEMA =
            "*the_geom:Polygon,location:String,cell_id:String,ovlp_group:Integer";

    private final RestTemplate restTemplate;
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
            ensureIndexerProperties(cogDir.resolve("binary"));
            ensureIndexerProperties(cogDir.resolve("continuous"));
            ensureCoverageStore("binary",     cogDir.resolve("binary"));
            ensureCoverageStore("continuous", cogDir.resolve("continuous"));
            ensureCoverage("binary");
            ensureCoverage("continuous");
            ensureStyle("cellcover-binary");
            ensureStyle("cellcover-continuous");
            applyStyle("binary",     "cellcover-binary");
            applyStyle("continuous", "cellcover-continuous");
            setMergeBehavior("binary");
            setMergeBehavior("continuous");
        } catch (Exception e) {
            log.error("GeoServer setup failed: {}", e.getMessage());
        }
    }

    /**
     * Registers a newly converted COG pair (binary + continuous) into GeoServer:
     *  1. POST granule to ImageMosaic store
     *  2. Update cell_id and ovlp_group in the shapefile index
     */
    public void registerGranule(Path cogDir, String cellId, int ovlpGroup) {
        Path binaryTif     = cogDir.resolve("binary").resolve(cellId + "_svr.tif");
        Path continuousTif = cogDir.resolve("continuous").resolve(cellId + ".tif");

        if (Files.exists(binaryTif)) {
            addGranuleToStore(binaryTif, "binary");
            updateShapefileEntry(cogDir.resolve("binary"), "binary",
                    cellId + "_svr.tif", cellId, ovlpGroup);
        }
        if (Files.exists(continuousTif)) {
            addGranuleToStore(continuousTif, "continuous");
            updateShapefileEntry(cogDir.resolve("continuous"), "continuous",
                    cellId + ".tif", cellId, ovlpGroup);
        }
    }

    /** Removes a cell's granules from both ImageMosaic stores. */
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

    private void setMergeBehavior(String storeType) {
        String body = """
                {"coverage":{"parameters":{"entry":[
                  {"string":["MERGE_BEHAVIOR","STACK"]},
                  {"string":["BackgroundValues","-3.4028235e+38"]}
                ]}}}""";
        put("/rest/workspaces/" + ws() + "/coveragestores/" + storeType
                + "/coverages/" + storeType + ".json", body, MediaType.APPLICATION_JSON);
    }

    private void addGranuleToStore(Path cogFile, String storeType) {
        String fileUrl = cogFile.toAbsolutePath().toUri().toString();
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
     * the shapefile with our custom schema (cell_id, ovlp_group) on first creation.
     */
    private void ensureIndexerProperties(Path mosaicDir) throws IOException {
        Files.createDirectories(mosaicDir);
        Path indexer = mosaicDir.resolve("indexer.properties");
        if (!Files.exists(indexer)) {
            Files.writeString(indexer, "Schema=" + MOSAIC_SCHEMA + "\n");
            log.info("Wrote indexer.properties in {}", mosaicDir.getFileName());
        }
    }

    /**
     * Updates cell_id and ovlp_group for a granule entry in the shapefile index via ogrinfo.
     * GeoTools DataStore writes fail silently on JDK 21+ due to NIO buffer cleanup restrictions.
     * Using GDAL ogrinfo SQL UPDATE avoids this issue entirely.
     */
    private void updateShapefileEntry(Path mosaicDir, String typeName,
                                      String cogFileName, String cellId, int ovlpGroup) {
        Path shpFile = mosaicDir.resolve(typeName + ".shp");
        if (!Files.exists(shpFile)) {
            log.warn("Shapefile not found: {}", shpFile);
            return;
        }
        String sql = String.format(
                "UPDATE %s SET cell_id='%s', ovlp_group=%d WHERE location LIKE '%%%s%%'",
                typeName, cellId, ovlpGroup, cogFileName);
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
            } else {
                log.warn("ogrinfo exited {} for shapefile update: {}", exitCode, cogFileName);
            }
        } catch (Exception e) {
            log.warn("Failed to update shapefile for {}: {}", cogFileName, e.getMessage());
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
