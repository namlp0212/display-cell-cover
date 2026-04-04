package com.example.cellcover.controller;

import com.example.cellcover.dto.AlertSyncRequest;
import com.example.cellcover.entity.Cell;
import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.service.CellCacheService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Receives cell status updates from the external Alert System.
 * Updates MariaDB, invalidates Redis caches, pushes WebSocket event.
 *
 * POST /api/sync/alerts
 */
@RestController
@RequestMapping("/api/sync")
public class AlertSyncController {

    private final CellRepository cellRepo;
    private final CellCacheService cacheService;
    private final SimpMessagingTemplate messagingTemplate;

    public AlertSyncController(CellRepository cellRepo,
                               CellCacheService cacheService,
                               SimpMessagingTemplate messagingTemplate) {
        this.cellRepo = cellRepo;
        this.cacheService = cacheService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/alerts")
    public ResponseEntity<?> syncAlert(@RequestBody @Valid AlertSyncRequest req) {
        Optional<Cell> cellOpt = cellRepo.findById(req.getCellId());
        if (cellOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Cell cell = cellOpt.get();
        cell.setRealStatus(Boolean.TRUE.equals(req.getStatus()));
        cell.setSyncedAt(LocalDateTime.now());
        cellRepo.save(cell);

        // Invalidate real-state cache so next tile request reloads from DB
        cacheService.invalidateReal();

        // Push event to all connected WebSocket clients
        messagingTemplate.convertAndSend("/topic/cell-status", Map.of(
                "cellId",    req.getCellId(),
                "status",    req.getStatus(),
                "timestamp", req.getTimestamp() != null ? req.getTimestamp() : LocalDateTime.now().toString()
        ));

        return ResponseEntity.ok(Map.of("updated", 1, "failed", 0));
    }

    /**
     * Batch alert sync: update multiple cells at once.
     */
    @PostMapping("/alerts/batch")
    public ResponseEntity<?> syncAlertBatch(@RequestBody @Valid java.util.List<AlertSyncRequest> requests) {
        int updated = 0, failed = 0;
        for (AlertSyncRequest req : requests) {
            Optional<Cell> cellOpt = cellRepo.findById(req.getCellId());
            if (cellOpt.isEmpty()) {
                failed++;
                continue;
            }
            Cell cell = cellOpt.get();
            cell.setRealStatus(Boolean.TRUE.equals(req.getStatus()));
            cell.setSyncedAt(LocalDateTime.now());
            cellRepo.save(cell);
            updated++;
        }

        cacheService.invalidateReal();

        messagingTemplate.convertAndSend("/topic/cell-status-batch", Map.of(
                "updated", updated,
                "failed",  failed
        ));

        return ResponseEntity.ok(Map.of("updated", updated, "failed", failed));
    }
}
