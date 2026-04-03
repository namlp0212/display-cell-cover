package com.example.cellcover.controller;

import com.example.cellcover.repository.RasterCoverageRepository;
import com.example.cellcover.service.PixelCoverageService;
import com.example.cellcover.service.PixelMaxSignalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api")
public class WmsProxyController {

    @Value("${geoserver.url}")
    private String geoServerUrl;

    @Value("${geoserver.overlap-groups}")
    private int overlapGroups;

    private static final int PIXEL_MAX_ZOOM = 14;

    @Autowired
    private RasterCoverageRepository rasterCoverageRepository;

    @Autowired
    private PixelCoverageService pixelCoverageService;

    @Autowired
    private PixelMaxSignalService pixelMaxSignalService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Off-cell colour: #FF0000
    private static final int OFF_CELL_RGB = 0xFF0000;

    // On-cell density colours (Green → Light Blue, matching hex heatmap scheme).
    // Alpha accumulated by GeoServer's overlap-group stacking is used as density proxy.
    private static final int[] DENSITY_RGB   = { 0x00FF00, 0x00FF99, 0x00FFCC, 0x00CCFF, 0x0099FF };
    // Calibrated for SLD opacity=0.55, 8 overlap groups: α_N = round((1 - 0.45^N) × 255)
    // N=1→140, N=2→203, N=3→232, N=4→245, N=5+→250
    private static final int[] DENSITY_ALPHA = { 250,      245,      232,      203,        0 };

    // ── public endpoints ───────────────────────────────────────────────────────

    /**
     * Composite WMS tile — pixel-accurate rendering matching hex heatmap colour scheme.
     *
     * Strategy:
     *   1. Fetch on-cell tile  (NOT IN hidden) → recolor by density (green→light blue)
     *   2. Fetch off-cell tile (IN hidden)     → recolor all pixels red (#FF0000)
     *   3. Composite pixel-by-pixel: on-cell wins; else off-cell red; else transparent.
     *
     * Density is inferred from the accumulated alpha that GeoServer produces when
     * stacking overlapGroups copies of the binary layer — more overlapping cells
     * produce a higher alpha value at that pixel.
     */
    /** Convert TMS tile x/y/z to EPSG:3857 bbox string "minx,miny,maxx,maxy". */
    private static String tileToBbox(int x, int y, int z) {
        double n = Math.pow(2, z);
        double tileSize = 2 * Math.PI * 6378137 / n;
        double minx = x * tileSize - Math.PI * 6378137;
        double maxx = minx + tileSize;
        double maxy = Math.PI * 6378137 - y * tileSize;
        double miny = maxy - tileSize;
        return String.format("%.4f,%.4f,%.4f,%.4f", minx, miny, maxx, maxy);
    }

    @GetMapping("/wms-tile/{z}/{x}/{y}")
    public ResponseEntity<byte[]> proxyWmsTileCompositeXYZ(
            @PathVariable int z, @PathVariable int x, @PathVariable int y
    ) throws Exception {
        if (z >= PIXEL_MAX_ZOOM) {
            return noStorePng().body(pixelCoverageService.renderTile(z, x, y));
        }
        return proxyWmsTileComposite(tileToBbox(x, y, z));
    }

    @GetMapping("/wms-tile-signal/{z}/{x}/{y}")
    public ResponseEntity<byte[]> proxyWmsTileSignalXYZ(
            @PathVariable int z, @PathVariable int x, @PathVariable int y
    ) throws Exception {
        if (z >= PIXEL_MAX_ZOOM) {
            return noStorePng().body(pixelMaxSignalService.renderTile(z, x, y));
        }
        return proxyWmsTileSignal(tileToBbox(x, y, z));
    }

    @GetMapping("/wms-tile-xyz")
    public ResponseEntity<byte[]> proxyWmsTileCompositeQP(
            @RequestParam int z, @RequestParam int x, @RequestParam int y
    ) throws Exception {
        if (z >= PIXEL_MAX_ZOOM) {
            return noStorePng().body(pixelCoverageService.renderTile(z, x, y));
        }
        return proxyWmsTileComposite(tileToBbox(x, y, z));
    }

    @GetMapping("/wms-tile-signal-xyz")
    public ResponseEntity<byte[]> proxyWmsTileSignalQP(
            @RequestParam int z, @RequestParam int x, @RequestParam int y
    ) throws Exception {
        if (z >= PIXEL_MAX_ZOOM) {
            return noStorePng().body(pixelMaxSignalService.renderTile(z, x, y));
        }
        return proxyWmsTileSignal(tileToBbox(x, y, z));
    }

    @GetMapping("/wms-tile-composite")
    public ResponseEntity<byte[]> proxyWmsTileCompositeGet(
            @RequestParam("bbox") String bbox
    ) throws Exception {
        return proxyWmsTileComposite(bbox);
    }

    private ResponseEntity<byte[]> proxyWmsTileComposite(String bbox) throws Exception {
        double[] wgs = bbox3857toWgs84(bbox);

        String layers = String.join(",", Collections.nCopies(overlapGroups, "cellcover:binary"));
        String styles = String.join(",", Collections.nCopies(overlapGroups, "cellcover-binary"));
        // Fixed short CQL — only ovlp_group filter, no cell-ID list → never causes HTTP 414.
        // GeoServer renders ALL cells (on + off); off-only areas are overridden with red below.
        String cql = buildGroupFilter("");

        // Launch GeoServer request asynchronously while PostGIS query runs on this thread.
        CompletableFuture<HttpResponse<byte[]>> geoFuture = httpClient.sendAsync(
                buildHttpRequest(buildWmsUrl(bbox, layers, styles, cql)),
                HttpResponse.BodyHandlers.ofByteArray());

        // PostGIS: geometry of areas covered ONLY by off-cells (no on-cell overlap).
        String offOnlyGeoJson = rasterCoverageRepository.findOffOnlyGeometry(
                wgs[0], wgs[1], wgs[2], wgs[3]);

        HttpResponse<byte[]> geoResp = geoFuture.join();
        if (geoResp.statusCode() != 200) return ResponseEntity.status(502).build();

        byte[] densityTile = recolorByDensity(geoResp.body());

        if (offOnlyGeoJson == null) {
            // No off-cells in viewport — pure on-cell density display.
            return noStorePng().body(densityTile);
        }

        // Rasterize off-only geometry to a red PNG and composite over density tile.
        byte[] redTile = rasterizeOffCellGeometry(offOnlyGeoJson, bbox);
        return noStorePng()
                .body(compositeOffOverDensity(densityTile, redTile));
    }

    private ResponseEntity<byte[]> transparentResponse() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return noStorePng().body(baos.toByteArray());
    }

    /**
     * Signal strength tile — proxies to GeoServer cellcover:continuous layer.
     *
     * GeoServer renders COG float32 values (dBm) using the cellcover-continuous SLD
     * (color ramp: dark-blue → yellow matching -170 to -75 dBm scale).
     * GDAL selects the appropriate COG overview level automatically based on the
     * requested resolution, so low-zoom tiles read only the compressed overview data
     * (AVERAGE-resampled) rather than the full-resolution raster.
     *
     * Granules are sorted by avg_signal ASC (weakest cell rendered first, strongest last),
     * so the strongest cell's signal value overwrites weaker cells at overlapping pixels —
     * approximating per-pixel MAX signal across all visible cells.
     */
    @GetMapping("/wms-tile-signal")
    public ResponseEntity<byte[]> proxyWmsTileSignalGet(
            @RequestParam("bbox") String bbox
    ) throws Exception {
        return proxyWmsTileSignal(bbox);
    }

    private ResponseEntity<byte[]> proxyWmsTileSignal(String bbox) throws Exception {
        java.util.List<String> hiddenIds = rasterCoverageRepository.findHiddenCellIds();

        if (hiddenIds.isEmpty()) {
            // No hidden cells — return GeoServer tile as-is.
            HttpResponse<byte[]> response = httpClient.send(
                    buildHttpRequest(buildSignalWmsUrl(bbox, null)),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return ResponseEntity.status(502).build();
            return noStorePng().body(response.body());
        }

        String onCqlFilter  = "cell_id NOT IN (" + hiddenIds.stream()
                .map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(",")) + ")";
        String offCqlFilter = "cell_id IN (" + hiddenIds.stream()
                .map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(",")) + ")";

        // Fetch on-cell and off-cell signal tiles in parallel.
        CompletableFuture<HttpResponse<byte[]>> onFuture = httpClient.sendAsync(
                buildHttpRequest(buildSignalWmsUrl(bbox, onCqlFilter)),
                HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture<HttpResponse<byte[]>> offFuture = httpClient.sendAsync(
                buildHttpRequest(buildSignalWmsUrl(bbox, offCqlFilter)),
                HttpResponse.BodyHandlers.ofByteArray());

        HttpResponse<byte[]> onResp  = onFuture.join();
        HttpResponse<byte[]> offResp = offFuture.join();
        if (onResp.statusCode() != 200) return ResponseEntity.status(502).build();

        if (offResp.statusCode() != 200) {
            return noStorePng().body(onResp.body());
        }

        // Recolor off-cell pixels to red, then composite on-cell signal on top.
        // On-cell signal wins where it exists; off-cell footprint shows as red elsewhere.
        byte[] redTile = recolorToRed(offResp.body());
        return noStorePng()
                .body(compositeOnOverOff(onResp.body(), redTile));
    }

    /**
     * Composites on-cell signal tile over a red (off-cell) tile.
     * On-cell pixels win; remaining pixels show red where off-cells had signal.
     */
    private byte[] compositeOnOverOff(byte[] onBytes, byte[] offRedBytes) throws IOException {
        BufferedImage onImg  = ImageIO.read(new ByteArrayInputStream(onBytes));
        BufferedImage offImg = ImageIO.read(new ByteArrayInputStream(offRedBytes));
        if (onImg == null)  return offRedBytes;
        if (offImg == null) return onBytes;

        int w = onImg.getWidth(), h = onImg.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int onPx  = onImg.getRGB(x, y);
                int offPx = offImg.getRGB(x, y);
                // On-cell signal wins; fall back to red off-cell; else transparent.
                if (((onPx >>> 24) & 0xFF) > 0) {
                    result.setRGB(x, y, onPx);
                } else if (((offPx >>> 24) & 0xFF) > 0) {
                    result.setRGB(x, y, offPx);
                }
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(result, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Builds a GeoServer WMS URL for the continuous signal layer.
     * sortBy=avg_signal+A: weakest cell first → strongest last → strongest pixel wins (≈ MAX).
     */
    private String buildSignalWmsUrl(String bbox, String cqlFilter) {
        StringBuilder sb = new StringBuilder(geoServerUrl + "/wms")
                .append("?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap")
                .append("&LAYERS=").append(URLEncoder.encode("cellcover:continuous", StandardCharsets.UTF_8))
                .append("&STYLES=").append(URLEncoder.encode("cellcover-continuous", StandardCharsets.UTF_8))
                .append("&FORMAT=image%2Fpng&TRANSPARENT=true")
                .append("&WIDTH=256&HEIGHT=256")
                .append("&BBOX=").append(URLEncoder.encode(bbox, StandardCharsets.UTF_8))
                .append("&SRS=EPSG%3A3857")
                .append("&sortBy=avg_signal+A");
        if (cqlFilter != null && !cqlFilter.isBlank()) {
            sb.append("&CQL_FILTER=").append(URLEncoder.encode(cqlFilter, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Plain on-cell tile proxy (kept for direct testing).
     */
    @GetMapping("/wms-tile")
    public ResponseEntity<byte[]> proxyWmsTile(
            @RequestParam("bbox") String bbox,
            @RequestParam(value = "hidden", required = false, defaultValue = "") String hidden
    ) throws IOException, InterruptedException {

        String layers = String.join(",", Collections.nCopies(overlapGroups, "cellcover:binary"));
        String styles = String.join(",", Collections.nCopies(overlapGroups, "cellcover-binary"));
        String cql    = buildGroupFilter(buildExcludeFilter(hidden));

        return fetchAndReturn(buildHttpRequest(buildWmsUrl(bbox, layers, styles, cql)));
    }

    // ── coordinate helpers ─────────────────────────────────────────────────────

    private static double[] bbox3857toWgs84(String bbox3857) {
        String[] p = bbox3857.split(",");
        double minX = Double.parseDouble(p[0].trim()), minY = Double.parseDouble(p[1].trim());
        double maxX = Double.parseDouble(p[2].trim()), maxY = Double.parseDouble(p[3].trim());
        double R = 20037508.342789244;
        return new double[]{
            minX / R * 180.0,
            Math.toDegrees(2 * Math.atan(Math.exp(minY / R * Math.PI)) - Math.PI / 2),
            maxX / R * 180.0,
            Math.toDegrees(2 * Math.atan(Math.exp(maxY / R * Math.PI)) - Math.PI / 2)
        };
    }

    // ── pixel operations ───────────────────────────────────────────────────────

    /**
     * Recolors on-cell pixels using the density colour scheme (green → light blue),
     * matching the hex heatmap palette used at low zoom.
     *
     * GeoServer stacks overlapGroups binary layers, so pixel alpha accumulates with
     * the number of overlapping cells — higher alpha → denser coverage → greener.
     *
     *   alpha ≥ 200  →  #00FF00  Very Strong
     *   alpha ≥ 150  →  #00FF99  Strong
     *   alpha ≥ 100  →  #00FFCC  Medium
     *   alpha ≥  60  →  #00CCFF  Weak
     *   alpha  >  0  →  #0099FF  Very Weak
     */
    private byte[] recolorByDensity(byte[] pngBytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (src == null) return pngBytes;

        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = src.getRGB(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha > 0) {
                    int rgb = DENSITY_RGB[DENSITY_RGB.length - 1]; // default: Very Weak
                    for (int i = 0; i < DENSITY_ALPHA.length; i++) {
                        if (alpha >= DENSITY_ALPHA[i]) { rgb = DENSITY_RGB[i]; break; }
                    }
                    dst.setRGB(x, y, (alpha << 24) | rgb);
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Replaces every non-transparent pixel with red (#FF0000 = OFF colour) at the same alpha.
     * Transparent pixels (alpha == 0) stay transparent — they mean "no coverage".
     */
    private byte[] recolorToRed(byte[] pngBytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (src == null) return pngBytes;

        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = src.getRGB(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha > 0) {
                    dst.setRGB(x, y, (alpha << 24) | OFF_CELL_RGB);
                }
                // alpha == 0 → leave transparent (default 0 from new BufferedImage)
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Pixel-by-pixel composite: on-cell pixels take priority over off-cell (red) pixels.
     * This enforces: "any on-cell at this pixel → normal colour, not red."
     */
    private byte[] compositeTiles(byte[] onBytes, byte[] offRedBytes) throws IOException {
        BufferedImage onImg  = ImageIO.read(new ByteArrayInputStream(onBytes));
        BufferedImage offImg = ImageIO.read(new ByteArrayInputStream(offRedBytes));

        if (onImg == null) return offRedBytes;
        if (offImg == null) return onBytes;

        int w = onImg.getWidth(), h = onImg.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int onPixel = onImg.getRGB(x, y);
                if (((onPixel >>> 24) & 0xFF) > 0) {
                    result.setRGB(x, y, onPixel);          // on-cell pixel wins
                } else {
                    int offPixel = offImg.getRGB(x, y);
                    if (((offPixel >>> 24) & 0xFF) > 0) {
                        result.setRGB(x, y, offPixel);     // exclusively off-cell → red
                    }
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(result, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Composite: off-only red pixels override density pixels.
     * - red tile pixel is non-transparent  → paint red (off-only area)
     * - else                               → keep density tile pixel (on-cell or transparent)
     */
    private byte[] compositeOffOverDensity(byte[] densityBytes, byte[] redBytes) throws IOException {
        BufferedImage density = ImageIO.read(new ByteArrayInputStream(densityBytes));
        BufferedImage red     = ImageIO.read(new ByteArrayInputStream(redBytes));
        if (red == null)     return densityBytes;
        if (density == null) return redBytes;

        int w = density.getWidth(), h = density.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int redPx = red.getRGB(x, y);
                result.setRGB(x, y, ((redPx >>> 24) & 0xFF) > 0 ? redPx : density.getRGB(x, y));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(result, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Rasterizes a GeoJSON geometry (EPSG:4326) to a 256×256 red PNG clipped to the
     * given tile bbox (EPSG:3857).  Used for off-only areas returned by PostGIS.
     * Alpha = 140 matches GeoServer SLD opacity 0.55 for a single-cell layer.
     */
    private byte[] rasterizeOffCellGeometry(String geojson, String bbox3857) throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        if (geojson == null || geojson.isBlank()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }

        String[] p = bbox3857.split(",");
        double tileMinX = Double.parseDouble(p[0].trim());
        double tileMinY = Double.parseDouble(p[1].trim());
        double tileMaxX = Double.parseDouble(p[2].trim());
        double tileMaxY = Double.parseDouble(p[3].trim());
        double dX = tileMaxX - tileMinX, dY = tileMaxY - tileMinY;

        JsonNode geo  = MAPPER.readTree(geojson);
        String   type = geo.get("type").asText();

        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setColor(new Color(0xFF, 0x00, 0x00, 140));

        switch (type) {
            case "Polygon" ->
                g2.fill(toPath2D(geo.get("coordinates"), tileMinX, tileMinY, dX, dY));
            case "MultiPolygon" -> {
                for (JsonNode poly : geo.get("coordinates"))
                    g2.fill(toPath2D(poly, tileMinX, tileMinY, dX, dY));
            }
            case "GeometryCollection" -> {
                for (JsonNode sub : geo.get("geometries")) {
                    String st = sub.get("type").asText();
                    if ("Polygon".equals(st))
                        g2.fill(toPath2D(sub.get("coordinates"), tileMinX, tileMinY, dX, dY));
                    else if ("MultiPolygon".equals(st))
                        for (JsonNode poly : sub.get("coordinates"))
                            g2.fill(toPath2D(poly, tileMinX, tileMinY, dX, dY));
                }
            }
        }

        g2.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Converts a GeoJSON Polygon coordinate array (list of rings) to a Path2D in pixel space.
     * Coordinates are WGS-84 (lon/lat) and are projected to EPSG:3857 then mapped to 0–256 pixels.
     * WIND_EVEN_ODD handles holes correctly without extra winding logic.
     */
    private Path2D toPath2D(JsonNode rings, double tileMinX, double tileMinY, double dX, double dY) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (JsonNode ring : rings) {
            boolean first = true;
            for (JsonNode coord : ring) {
                double lon = coord.get(0).asDouble();
                double lat = coord.get(1).asDouble();
                // WGS-84 → EPSG:3857
                double x3857 = lon * 20037508.342789244 / 180.0;
                double y3857 = Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0)) * 6378137.0;
                // 3857 → pixel (y-axis flipped)
                double px = (x3857 - tileMinX) / dX * 256.0;
                double py = (1.0 - (y3857 - tileMinY) / dY) * 256.0;
                if (first) { path.moveTo(px, py); first = false; }
                else         path.lineTo(px, py);
            }
            path.closePath();
        }
        return path;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String buildGroupFilter(String cellFilter) {
        return IntStream.range(0, overlapGroups)
                .mapToObj(g -> "ovlp_group=" + g + cellFilter)
                .collect(Collectors.joining(";"));
    }

    private String buildExcludeFilter(String hidden) {
        if (hidden == null || hidden.isBlank()) return "";
        String ids = quotedIds(hidden);
        return ids.isEmpty() ? "" : " AND cell_id NOT IN (" + ids + ")";
    }

    private String buildIncludeFilter(String hidden) {
        if (hidden == null || hidden.isBlank()) return "";
        String ids = quotedIds(hidden);
        return ids.isEmpty() ? "" : " AND cell_id IN (" + ids + ")";
    }

    private String quotedIds(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(","));
    }

    private String buildWmsUrl(String bbox, String layers, String styles, String cqlFilter) {
        StringBuilder sb = new StringBuilder(geoServerUrl + "/wms")
                .append("?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap")
                .append("&LAYERS=").append(URLEncoder.encode(layers, StandardCharsets.UTF_8))
                .append("&STYLES=").append(URLEncoder.encode(styles, StandardCharsets.UTF_8))
                .append("&FORMAT=image%2Fpng&TRANSPARENT=true");
        if (cqlFilter != null && !cqlFilter.isBlank()) {
            sb.append("&CQL_FILTER=").append(URLEncoder.encode(cqlFilter, StandardCharsets.UTF_8));
        }
        sb.append("&WIDTH=256&HEIGHT=256")
                .append("&BBOX=").append(URLEncoder.encode(bbox, StandardCharsets.UTF_8))
                .append("&SRS=EPSG%3A3857");
        return sb.toString();
    }

    private HttpRequest buildHttpRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    }

    /** Returns a ResponseEntity builder pre-configured with no-store cache control and PNG content type. */
    private ResponseEntity.BodyBuilder noStorePng() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG);
    }

    private ResponseEntity<byte[]> fetchAndReturn(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return ResponseEntity.status(response.statusCode()).build();
        }
        return noStorePng().body(response.body());
    }
}
