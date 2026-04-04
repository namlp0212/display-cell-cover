package com.example.cellcover.service;

import com.example.cellcover.dto.SimulationRequest;
import com.example.cellcover.entity.Simulation;
import com.example.cellcover.entity.SimulationOverride;
import com.example.cellcover.repository.SimulationOverrideRepository;
import com.example.cellcover.repository.SimulationRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SimulationService {

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
}
