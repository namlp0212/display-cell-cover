package com.example.cellcover.controller;

import com.example.cellcover.dto.CellImportResult;
import com.example.cellcover.service.CellImportService;
import com.example.cellcover.service.HexCoverageService;
import com.example.cellcover.service.OverviewRebuildService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CellImportService      cellImportService;
    private final HexCoverageService     hexCoverageService;
    private final OverviewRebuildService overviewRebuildService;

    public AdminController(CellImportService cellImportService,
                           HexCoverageService hexCoverageService,
                           OverviewRebuildService overviewRebuildService) {
        this.cellImportService      = cellImportService;
        this.hexCoverageService     = hexCoverageService;
        this.overviewRebuildService = overviewRebuildService;
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

    /**
     * Starts a background job that rebuilds GDAL overview pyramids for all COG
     * files in MinIO, adding levels [2,4,8,16,32,64,128,256].
     * Idempotent — returns status=already_running if a job is in progress.
     *
     * POST /api/admin/rebuild-overviews
     */
    @PostMapping("/rebuild-overviews")
    public Map<String, Object> rebuildOverviews() {
        boolean started = overviewRebuildService.start();
        return Map.of(
                "status",  started ? "started" : "already_running",
                "message", started
                        ? "Overview rebuild job started in background. Poll /api/admin/rebuild-overviews/status for progress."
                        : "A rebuild job is already running."
        );
    }

    /**
     * Returns the current state of the overview rebuild job.
     *
     * GET /api/admin/rebuild-overviews/status
     */
    @GetMapping("/rebuild-overviews/status")
    public Map<String, Object> rebuildOverviewsStatus() {
        return overviewRebuildService.status();
    }
}
