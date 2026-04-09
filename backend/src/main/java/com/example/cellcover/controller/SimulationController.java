package com.example.cellcover.controller;

import com.example.cellcover.dto.SaveSessionRequest;
import com.example.cellcover.dto.SessionSimulationRequest;
import com.example.cellcover.dto.SimulationRequest;
import com.example.cellcover.dto.UpdateSimulationCellsRequest;
import com.example.cellcover.entity.Simulation;
import com.example.cellcover.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationService simService;

    public SimulationController(SimulationService simService) {
        this.simService = simService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody @Valid SimulationRequest req) {
        Simulation sim = simService.create(req);
        return ResponseEntity.ok(Map.of(
                "id",            sim.getId(),
                "name",          sim.getName(),
                "status",        sim.getStatus(),
                "cellsOffCount", req.getCellsOff().size()
        ));
    }

    @GetMapping
    public List<Simulation> listActive() {
        return simService.listActive();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(simService.get(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/end")
    public ResponseEntity<?> end(@PathVariable String id) {
        try {
            Simulation sim = simService.end(id);
            return ResponseEntity.ok(Map.of(
                    "id",      sim.getId(),
                    "status",  sim.getStatus(),
                    "endedAt", sim.getEndedAt().toString()
            ));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            simService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Update cells for a permanent simulation (CN-02.x) ────────────────────

    @PutMapping("/{id}/cells")
    public ResponseEntity<?> updateCells(@PathVariable String id,
                                         @RequestBody UpdateSimulationCellsRequest req) {
        try {
            simService.updateCells(id, req.getAddCellsOff(), req.getRemoveCellsOff());
            return ResponseEntity.ok(Map.of("id", id, "updated", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Ephemeral session endpoints ───────────────────────────────────────────

    @PostMapping("/session")
    public ResponseEntity<?> createSession(@RequestBody SessionSimulationRequest req) {
        Simulation sim = simService.createSession(req);
        return ResponseEntity.ok(Map.of(
                "id",        sim.getId(),
                "name",      sim.getName(),
                "ephemeral", sim.isEphemeral()
        ));
    }

    @PutMapping("/session/{id}")
    public ResponseEntity<?> updateSession(@PathVariable String id,
                                           @RequestBody SessionSimulationRequest req) {
        try {
            simService.updateSessionCells(id, req.getCellsOff());
            return ResponseEntity.ok(Map.of("id", id, "updated", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/session/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        try {
            simService.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/session/{id}/save")
    public ResponseEntity<?> saveSession(@PathVariable String id,
                                         @RequestBody SaveSessionRequest req) {
        try {
            Simulation sim = simService.saveSession(id, req.getName(), req.getDescription());
            return ResponseEntity.ok(Map.of(
                    "id",        sim.getId(),
                    "name",      sim.getName(),
                    "ephemeral", sim.isEphemeral()
            ));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
