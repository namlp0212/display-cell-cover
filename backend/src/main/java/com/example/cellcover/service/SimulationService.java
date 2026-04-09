package com.example.cellcover.service;

import com.example.cellcover.dto.SaveSessionRequest;
import com.example.cellcover.dto.SessionSimulationRequest;
import com.example.cellcover.dto.SimulationRequest;
import com.example.cellcover.dto.UpdateSimulationCellsRequest;
import com.example.cellcover.entity.Simulation;
import com.example.cellcover.entity.SimulationOverride;
import com.example.cellcover.repository.SimulationOverrideRepository;
import com.example.cellcover.repository.SimulationRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationRepository simRepo;
    private final SimulationOverrideRepository overrideRepo;
    private final CellCacheService cacheService;

    public SimulationService(SimulationRepository simRepo,
                             SimulationOverrideRepository overrideRepo,
                             CellCacheService cacheService) {
        this.simRepo = simRepo;
        this.overrideRepo = overrideRepo;
        this.cacheService = cacheService;
    }

    @Transactional
    public Simulation create(SimulationRequest req) {
        Simulation sim = new Simulation();
        sim.setName(req.getName());
        sim.setDescription(req.getDescription());
        sim.setCreatedBy(req.getCreatedBy());
        sim.setEphemeral(false);
        sim = simRepo.save(sim);

        String simId = sim.getId();
        for (String cellId : req.getCellsOff()) {
            SimulationOverride override = new SimulationOverride();
            override.setSimulationId(simId);
            override.setCellId(cellId);
            override.setForcedStatus(false);
            overrideRepo.save(override);
        }

        // Pre-build Redis cache for this simulation
        cacheService.buildSimCache(simId);
        return sim;
    }

    public Simulation get(String simId) {
        return simRepo.findById(simId)
                .orElseThrow(() -> new NoSuchElementException("Simulation not found: " + simId));
    }

    public List<Simulation> listActive() {
        return simRepo.findByStatus(Simulation.SimulationStatus.active);
    }

    @Transactional
    public Simulation end(String simId) {
        Simulation sim = get(simId);
        sim.setStatus(Simulation.SimulationStatus.ended);
        sim.setEndedAt(LocalDateTime.now());
        sim = simRepo.save(sim);

        // Invalidate Redis caches for this simulation
        cacheService.invalidateSim(simId);
        return sim;
    }

    @Transactional
    public void delete(String simId) {
        if (!simRepo.existsById(simId)) {
            throw new NoSuchElementException("Simulation not found: " + simId);
        }
        cacheService.invalidateSim(simId);
        simRepo.deleteById(simId);
    }

    // ── Update cells for a permanent simulation ───────────────────────────────

    @Transactional
    public void updateCells(String simId, List<String> addOff, List<String> removeOff) {
        Simulation sim = get(simId);

        if (addOff != null) {
            for (String cellId : addOff) {
                SimulationOverride.PK pk = new SimulationOverride.PK(simId, cellId);
                if (!overrideRepo.existsById(pk)) {
                    SimulationOverride override = new SimulationOverride();
                    override.setSimulationId(simId);
                    override.setCellId(cellId);
                    override.setForcedStatus(false);
                    overrideRepo.save(override);
                }
            }
        }

        if (removeOff != null) {
            for (String cellId : removeOff) {
                SimulationOverride.PK pk = new SimulationOverride.PK(simId, cellId);
                overrideRepo.deleteById(pk);
            }
        }

        sim.setUpdatedAt(LocalDateTime.now());
        simRepo.save(sim);
        cacheService.invalidateSim(simId);
        cacheService.buildSimCache(simId);
    }

    // ── Ephemeral session management ──────────────────────────────────────────

    @Transactional
    public Simulation createSession(SessionSimulationRequest req) {
        Simulation sim = new Simulation();
        sim.setName(req.getName() != null ? req.getName() : "session-" + System.currentTimeMillis());
        sim.setEphemeral(true);
        sim = simRepo.save(sim);

        String simId = sim.getId();
        if (req.getCellsOff() != null && !req.getCellsOff().isEmpty()) {
            List<SimulationOverride> overrides = req.getCellsOff().stream()
                    .map(cellId -> {
                        SimulationOverride o = new SimulationOverride();
                        o.setSimulationId(simId);
                        o.setCellId(cellId);
                        o.setForcedStatus(false);
                        return o;
                    }).toList();
            overrideRepo.saveAll(overrides);
        }

        // Cache will be built lazily on first tile request
        cacheService.invalidateSim(simId);
        return sim;
    }

    @Transactional
    public void updateSessionCells(String simId, List<String> cellsOff) {
        Simulation sim = get(simId);
        if (!sim.isEphemeral()) {
            throw new IllegalStateException("Simulation " + simId + " is not a session");
        }

        // Replace all overrides in bulk
        overrideRepo.deleteBySimulationId(simId);

        if (cellsOff != null && !cellsOff.isEmpty()) {
            List<SimulationOverride> overrides = cellsOff.stream()
                    .map(cellId -> {
                        SimulationOverride o = new SimulationOverride();
                        o.setSimulationId(simId);
                        o.setCellId(cellId);
                        o.setForcedStatus(false);
                        return o;
                    }).toList();
            overrideRepo.saveAll(overrides);
        }

        sim.setUpdatedAt(LocalDateTime.now());
        simRepo.save(sim);
        cacheService.invalidateSim(simId);
        // Cache rebuilt lazily on first tile request (avoids blocking the response)
    }

    @Transactional
    public void deleteSession(String simId) {
        Simulation sim = get(simId);
        if (!sim.isEphemeral()) {
            throw new IllegalStateException("Simulation " + simId + " is not a session");
        }
        cacheService.invalidateSim(simId);
        simRepo.deleteById(simId);
    }

    @Transactional
    public Simulation saveSession(String simId, String name, String description) {
        Simulation sim = get(simId);
        if (!sim.isEphemeral()) {
            throw new IllegalStateException("Simulation " + simId + " is not a session");
        }
        sim.setEphemeral(false);
        if (name != null && !name.isBlank()) sim.setName(name);
        if (description != null) sim.setDescription(description);
        sim.setUpdatedAt(LocalDateTime.now());
        return simRepo.save(sim);
    }

    // ── Scheduled cleanup ─────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupEphemeralSims() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<Simulation> stale = simRepo.findByStatus(Simulation.SimulationStatus.active).stream()
                .filter(s -> s.isEphemeral() && s.getCreatedAt().isBefore(cutoff))
                .toList();
        log.info("Cleanup: deleting {} stale ephemeral simulations", stale.size());
        for (Simulation sim : stale) {
            cacheService.invalidateSim(sim.getId());
            simRepo.delete(sim);
        }
    }
}
