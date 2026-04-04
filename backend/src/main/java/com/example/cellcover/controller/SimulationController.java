package com.example.cellcover.controller;

import com.example.cellcover.dto.SimulationRequest;
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
}
