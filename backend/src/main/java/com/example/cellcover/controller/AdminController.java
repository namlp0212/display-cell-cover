package com.example.cellcover.controller;

import com.example.cellcover.dto.CellImportResult;
import com.example.cellcover.service.CellImportService;
import com.example.cellcover.service.H3IndexPipelineService;
import com.example.cellcover.service.HexCoverageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CellImportService       cellImportService;
    private final HexCoverageService      hexCoverageService;
    private final H3IndexPipelineService  pipelineService;

    public AdminController(CellImportService cellImportService,
                           HexCoverageService hexCoverageService,
                           H3IndexPipelineService pipelineService) {
        this.cellImportService  = cellImportService;
        this.hexCoverageService = hexCoverageService;
        this.pipelineService    = pipelineService;
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

    // ── H3 ClickHouse pipeline ────────────────────────────────────────────────

    /**
     * Trigger async build of H3 index for all COG files.
     * POST /api/admin/build-h3-index
     */
    @PostMapping("/build-h3-index")
    public ResponseEntity<Map<String, Object>> buildH3Index() {
        H3IndexPipelineService.PipelineStatus status = pipelineService.getStatus();
        if (status.running()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Pipeline is already running",
                    "progress", status.progressPct() + "%"
            ));
        }
        pipelineService.buildAll();
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "H3 index build triggered asynchronously — poll /api/admin/build-h3-index/status"
        ));
    }

    /**
     * Build H3 index for a single cell synchronously.
     * POST /api/admin/build-h3-index/{cellId}
     */
    @PostMapping("/build-h3-index/{cellId}")
    public ResponseEntity<Map<String, Object>> buildH3IndexForCell(@PathVariable String cellId) {
        try {
            long start = System.currentTimeMillis();
            int rows = pipelineService.buildForCell(cellId);
            long ms = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "cellId", cellId,
                    "rowsInserted", rows,
                    "durationMs", ms
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get H3 index build pipeline status.
     * GET /api/admin/build-h3-index/status
     */
    @GetMapping("/build-h3-index/status")
    public H3IndexPipelineService.PipelineStatus getH3IndexStatus() {
        return pipelineService.getStatus();
    }

    // ── Direct BIL → ClickHouse import (no COG) ───────────────────────────────

    /**
     * Import all cells from BIL files in raster-dir directly into ClickHouse.
     * Also registers each cell in the MariaDB cells table.
     * No COG conversion or GeoServer interaction.
     *
     * POST /api/admin/import-bil
     */
    @PostMapping("/import-bil")
    public ResponseEntity<Map<String, Object>> importBil() {
        H3IndexPipelineService.PipelineStatus status = pipelineService.getStatus();
        if (status.running()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Pipeline is already running",
                    "mode", status.mode(),
                    "progress", status.progressPct() + "%"
            ));
        }
        pipelineService.buildAllFromBil();
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "BIL import triggered asynchronously — poll /api/admin/import-bil/status"
        ));
    }

    /**
     * Import a single cell from its BIL file — synchronous.
     * POST /api/admin/import-bil/{cellId}
     */
    @PostMapping("/import-bil/{cellId}")
    public ResponseEntity<Map<String, Object>> importBilForCell(@PathVariable String cellId) {
        try {
            long start = System.currentTimeMillis();
            pipelineService.buildFromBilForCell(cellId);
            long ms = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of("cellId", cellId, "durationMs", ms, "status", "done"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/import-bil/status — same status as build-h3-index/status.
     */
    @GetMapping("/import-bil/status")
    public H3IndexPipelineService.PipelineStatus getImportBilStatus() {
        return pipelineService.getStatus();
    }

    // ── MinIO COG sync ────────────────────────────────────────────────────────

    /**
     * Convert all BIL files to COG and upload to MinIO (idempotent — skips cells already in MinIO).
     * POST /api/admin/sync-minio
     */
    @PostMapping("/sync-minio")
    public ResponseEntity<Map<String, Object>> syncMinio() {
        H3IndexPipelineService.PipelineStatus status = pipelineService.getStatus();
        if (status.running()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Pipeline is already running",
                    "mode", status.mode(),
                    "progress", status.progressPct() + "%"
            ));
        }
        pipelineService.syncMinio();
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "MinIO COG sync triggered — poll /api/admin/sync-minio/status"
        ));
    }

    /**
     * Convert a single cell's BIL to COG and upload to MinIO — synchronous.
     * POST /api/admin/sync-minio/{cellId}
     */
    @PostMapping("/sync-minio/{cellId}")
    public ResponseEntity<Map<String, Object>> syncMinioForCell(@PathVariable String cellId) {
        try {
            long start = System.currentTimeMillis();
            pipelineService.syncMinioForCell(cellId);
            long ms = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of("cellId", cellId, "durationMs", ms, "status", "done"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/sync-minio/status
     */
    @GetMapping("/sync-minio/status")
    public H3IndexPipelineService.PipelineStatus getSyncMinioStatus() {
        return pipelineService.getStatus();
    }

}
