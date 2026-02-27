package com.example.cellcover.controller;

import com.example.cellcover.dto.RasterCoverageDto;
import com.example.cellcover.service.RasterCoverageService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RasterCoverageController {

    private final RasterCoverageService service;

    public RasterCoverageController(RasterCoverageService service) {
        this.service = service;
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
}
