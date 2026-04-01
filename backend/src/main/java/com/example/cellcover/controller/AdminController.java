package com.example.cellcover.controller;

import com.example.cellcover.dto.CellImportResult;
import com.example.cellcover.service.CellImportService;
import com.example.cellcover.service.HexCoverageService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CellImportService  cellImportService;
    private final HexCoverageService hexCoverageService;

    public AdminController(CellImportService cellImportService,
                           HexCoverageService hexCoverageService) {
        this.cellImportService  = cellImportService;
        this.hexCoverageService = hexCoverageService;
    }

    @PostMapping("/sync-cells")
    public CellImportResult syncCells() {
        return cellImportService.syncCells();
    }

    /**
     * Registers all existing COG files with GeoServer (idempotent).
     * Use when GeoServer was unavailable during the original sync.
     *
     * POST /api/admin/sync-geoserver
     */
    @PostMapping("/sync-geoserver")
    public Map<String, Object> syncGeoServer() {
        return cellImportService.syncGeoServer();
    }

    /**
     * Recomputes H3 hex coverage for all cells in the raster directory.
     * Use reset=true to truncate hex_coverage and cell_hex_map first.
     *
     * POST /api/admin/sync-hex
     * POST /api/admin/sync-hex?reset=true
     */
    @PostMapping("/sync-hex")
    public Map<String, Object> syncHex(@RequestParam(value = "reset", defaultValue = "false") boolean reset) {
        int processed = hexCoverageService.syncAll(reset);
        return Map.of("processed", processed, "reset", reset);
    }

}
