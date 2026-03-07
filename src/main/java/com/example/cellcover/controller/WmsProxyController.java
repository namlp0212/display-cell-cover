package com.example.cellcover.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
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

    /**
     * WMS tile proxy: builds multi-group CQL_FILTER internally so the client URL stays short.
     *
     * @param bbox   EPSG:3857 bbox string: minx,miny,maxx,maxy
     * @param hidden comma-separated hidden cell IDs (may be empty)
     */
    @GetMapping("/wms-tile")
    public ResponseEntity<byte[]> proxyWmsTile(
            @RequestParam("bbox") String bbox,
            @RequestParam(value = "hidden", required = false, defaultValue = "") String hidden
    ) throws IOException, InterruptedException {

        String hiddenFilter = buildHiddenFilter(hidden);

        String layers = String.join(",", Collections.nCopies(overlapGroups, "cellcover:binary"));
        String styles = String.join(",", Collections.nCopies(overlapGroups, "cellcover-binary"));
        String cqlFilter = IntStream.range(0, overlapGroups)
                .mapToObj(g -> "ovlp_group=" + g + hiddenFilter)
                .collect(Collectors.joining(";"));

        String wmsUrl = geoServerUrl + "/wms"
                + "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                + "&LAYERS=" + URLEncoder.encode(layers, StandardCharsets.UTF_8)
                + "&STYLES=" + URLEncoder.encode(styles, StandardCharsets.UTF_8)
                + "&FORMAT=image%2Fpng&TRANSPARENT=true"
                + "&CQL_FILTER=" + URLEncoder.encode(cqlFilter, StandardCharsets.UTF_8)
                + "&WIDTH=256&HEIGHT=256"
                + "&BBOX=" + URLEncoder.encode(bbox, StandardCharsets.UTF_8)
                + "&SRS=EPSG%3A3857";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wmsUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            return ResponseEntity.status(response.statusCode()).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(response.body());
    }

    private String buildHiddenFilter(String hidden) {
        if (hidden == null || hidden.isBlank()) return "";
        String ids = Arrays.stream(hidden.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(","));
        return ids.isEmpty() ? "" : " AND cell_id NOT IN (" + ids + ")";
    }
}
