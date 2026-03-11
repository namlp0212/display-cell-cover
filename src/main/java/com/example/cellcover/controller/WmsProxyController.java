package com.example.cellcover.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Off-cell colour: #FF0000
    private static final int OFF_CELL_RGB = 0xFF0000;

    // On-cell density colours (Green → Light Blue, matching hex heatmap scheme).
    // Alpha accumulated by GeoServer's overlap-group stacking is used as density proxy.
    private static final int[] DENSITY_RGB   = { 0x00FF00, 0x00FF99, 0x00FFCC, 0x00CCFF, 0x0099FF };
    private static final int[] DENSITY_ALPHA = { 200,      150,      100,       60,        0 };

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
    @GetMapping("/wms-tile-composite")
    public ResponseEntity<byte[]> proxyWmsTileComposite(
            @RequestParam("bbox") String bbox,
            @RequestParam(value = "hidden", required = false, defaultValue = "") String hidden
    ) throws Exception {

        String layers = String.join(",", Collections.nCopies(overlapGroups, "cellcover:binary"));
        String styles = String.join(",", Collections.nCopies(overlapGroups, "cellcover-binary"));

        if (hidden == null || hidden.isBlank()) {
            // No hidden cells — recolor on-cell tile by density and return.
            String cql = buildGroupFilter(buildExcludeFilter(""));
            HttpResponse<byte[]> onResp = httpClient.send(
                    buildHttpRequest(buildWmsUrl(bbox, layers, styles, cql)),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (onResp.statusCode() != 200) return ResponseEntity.status(502).build();
            byte[] recolored = recolorByDensity(onResp.body());
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(recolored);
        }

        String onCql  = buildGroupFilter(buildExcludeFilter(hidden));
        String offCql = buildGroupFilter(buildIncludeFilter(hidden));

        CompletableFuture<HttpResponse<byte[]>> onFuture  = httpClient.sendAsync(
                buildHttpRequest(buildWmsUrl(bbox, layers, styles, onCql)),  HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture<HttpResponse<byte[]>> offFuture = httpClient.sendAsync(
                buildHttpRequest(buildWmsUrl(bbox, layers, styles, offCql)), HttpResponse.BodyHandlers.ofByteArray());

        HttpResponse<byte[]> onResp  = onFuture.join();
        HttpResponse<byte[]> offResp = offFuture.join();

        if (onResp.statusCode() != 200 || offResp.statusCode() != 200) {
            return ResponseEntity.status(502).build();
        }

        byte[] onDensity  = recolorByDensity(onResp.body());
        byte[] offRed     = recolorToRed(offResp.body());
        byte[] composited = compositeTiles(onDensity, offRed);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(composited);
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
        return new StringBuilder(geoServerUrl + "/wms")
                .append("?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap")
                .append("&LAYERS=").append(URLEncoder.encode(layers, StandardCharsets.UTF_8))
                .append("&STYLES=").append(URLEncoder.encode(styles, StandardCharsets.UTF_8))
                .append("&FORMAT=image%2Fpng&TRANSPARENT=true")
                .append("&CQL_FILTER=").append(URLEncoder.encode(cqlFilter, StandardCharsets.UTF_8))
                .append("&WIDTH=256&HEIGHT=256")
                .append("&BBOX=").append(URLEncoder.encode(bbox, StandardCharsets.UTF_8))
                .append("&SRS=EPSG%3A3857")
                .toString();
    }

    private HttpRequest buildHttpRequest(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    }

    private ResponseEntity<byte[]> fetchAndReturn(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return ResponseEntity.status(response.statusCode()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(response.body());
    }
}
