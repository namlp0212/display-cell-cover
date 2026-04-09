package com.example.cellcover.controller;

import com.example.cellcover.dto.NimsSyncStatusDto;
import com.example.cellcover.service.NimsSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CN-04.02: NIMS synchronization endpoints.
 *
 * POST /api/admin/nims-sync          → trigger sync (202) or 409 if already running
 * GET  /api/admin/nims-sync/{syncId}/status → sync progress
 * GET  /api/admin/nims-sync/history?page=0&size=20 → recent syncs
 */
@RestController
@RequestMapping("/api/admin/nims-sync")
public class NimsSyncController {

    private final NimsSyncService nimsSyncService;

    public NimsSyncController(NimsSyncService nimsSyncService) {
        this.nimsSyncService = nimsSyncService;
    }

    @PostMapping
    public ResponseEntity<?> triggerSync() {
        if (nimsSyncService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A sync is already running"));
        }
        String syncId = nimsSyncService.startSync();
        return ResponseEntity.accepted()
                .body(Map.of("syncId", syncId, "status", "started"));
    }

    @GetMapping("/{syncId}/status")
    public ResponseEntity<?> getStatus(@PathVariable String syncId) {
        NimsSyncStatusDto dto = nimsSyncService.getStatus(syncId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<NimsSyncStatusDto> history = nimsSyncService.getHistory(page, size);
        return ResponseEntity.ok(Map.of(
                "data", history,
                "page", page,
                "size", size
        ));
    }
}
