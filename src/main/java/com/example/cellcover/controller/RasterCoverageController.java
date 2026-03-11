package com.example.cellcover.controller;

import com.example.cellcover.dto.RasterCoverageDto;
import com.example.cellcover.service.RasterCoverageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RasterCoverageController {

    private final RasterCoverageService service;
    private final ObjectMapper objectMapper;

    public RasterCoverageController(RasterCoverageService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/layers")
    public List<RasterCoverageDto> getVisibleLayers(
            @RequestParam("minx") @NotNull Double minx,
            @RequestParam("miny") @NotNull Double miny,
            @RequestParam("maxx") @NotNull Double maxx,
            @RequestParam("maxy") @NotNull Double maxy) {
        return service.findVisibleInBbox(minx, miny, maxx, maxy);
    }

    @GetMapping("/hidden-cells")
    public List<String> getHiddenCellIds() {
        return service.findHiddenCellIds();
    }

    @PostMapping("/toggle/{cellId}")
    public ResponseEntity<Map<String, Object>> toggleVisibility(
            @PathVariable("cellId") String cellId,
            @RequestParam("visible") boolean visible) {
        int updated = service.toggleVisibility(cellId, visible);
        return ResponseEntity.ok(Map.of(
                "cellId", cellId,
                "visible", visible,
                "updatedCount", updated
        ));
    }

    /**
     * Returns the GeoJSON geometry of areas covered exclusively by off-cells —
     * i.e. off-cell coverage with all on-cell coverage subtracted out.
     *
     * Pixel-accurate rule:
     *   - Pixel belongs ONLY to off-cells  → included in this geometry (render red)
     *   - Pixel has at least one on-cell   → excluded (render normal coverage colour)
     *
     * Returns 204 No Content when there are no off-cells in the viewport so the
     * frontend can skip rendering without parsing an empty body.
     */
    @GetMapping("/off-only-area")
    public ResponseEntity<JsonNode> getOffOnlyArea(
            @RequestParam("minx") @NotNull Double minx,
            @RequestParam("miny") @NotNull Double miny,
            @RequestParam("maxx") @NotNull Double maxx,
            @RequestParam("maxy") @NotNull Double maxy) throws Exception {

        String geojson = service.findOffOnlyGeometry(minx, miny, maxx, maxy);

        // NULL means no off-cells (or difference is empty after subtraction).
        if (geojson == null) {
            return ResponseEntity.noContent().build();
        }

        // Parse the raw GeoJSON string from ST_AsGeoJSON() into a JsonNode so that
        // Spring serialises it as a proper JSON object, not a double-encoded string.
        JsonNode node = objectMapper.readTree(geojson);
        return ResponseEntity.ok(node);
    }
}
