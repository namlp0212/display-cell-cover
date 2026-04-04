package com.example.cellcover.service;

import com.example.cellcover.dto.SimulationRequest;
import com.example.cellcover.entity.Simulation;
import com.example.cellcover.entity.SimulationOverride;
import com.example.cellcover.repository.SimulationOverrideRepository;
import com.example.cellcover.repository.SimulationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock
    private SimulationRepository simRepo;
    @Mock
    private SimulationOverrideRepository overrideRepo;
    @Mock
    private CellCacheService cacheService;

    @InjectMocks
    private SimulationService simService;

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_savesSimulationAndOverrides() {
        SimulationRequest req = new SimulationRequest();
        req.setName("Bão số 3");
        req.setDescription("Test");
        req.setCreatedBy("admin");
        req.setCellsOff(List.of("CELL001", "CELL002"));

        Simulation saved = new Simulation();
        saved.setId("sim-uuid-001");
        saved.setName("Bão số 3");
        when(simRepo.save(any(Simulation.class))).thenReturn(saved);

        Simulation result = simService.create(req);

        assertThat(result.getId()).isEqualTo("sim-uuid-001");
        assertThat(result.getName()).isEqualTo("Bão số 3");

        // Should save 2 overrides (one per cell off)
        ArgumentCaptor<SimulationOverride> overrideCaptor =
                ArgumentCaptor.forClass(SimulationOverride.class);
        verify(overrideRepo, times(2)).save(overrideCaptor.capture());
        List<SimulationOverride> overrides = overrideCaptor.getAllValues();
        assertThat(overrides).extracting(SimulationOverride::getCellId)
                .containsExactlyInAnyOrder("CELL001", "CELL002");
        assertThat(overrides).allMatch(o -> !o.isForcedStatus());

        // Should build Redis sim cache
        verify(cacheService).buildSimCache("sim-uuid-001");
    }

    @Test
    void create_withEmptyCellsOff_savesNoCellOverrides() {
        SimulationRequest req = new SimulationRequest();
        req.setName("Empty sim");
        req.setCellsOff(List.of());

        Simulation saved = new Simulation();
        saved.setId("sim-empty");
        when(simRepo.save(any())).thenReturn(saved);

        simService.create(req);

        verify(overrideRepo, never()).save(any());
        verify(cacheService).buildSimCache("sim-empty");
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void get_existingSim_returnsSim() {
        Simulation sim = new Simulation();
        sim.setId("sim-001");
        when(simRepo.findById("sim-001")).thenReturn(Optional.of(sim));

        Simulation result = simService.get("sim-001");

        assertThat(result.getId()).isEqualTo("sim-001");
    }

    @Test
    void get_nonExistingSim_throwsNoSuchElementException() {
        when(simRepo.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> simService.get("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("unknown");
    }

    // ── listActive ────────────────────────────────────────────────────────────

    @Test
    void listActive_delegatesToRepository() {
        Simulation s1 = new Simulation();
        s1.setId("s1");
        Simulation s2 = new Simulation();
        s2.setId("s2");
        when(simRepo.findByStatus(Simulation.SimulationStatus.active))
                .thenReturn(List.of(s1, s2));

        List<Simulation> result = simService.listActive();

        assertThat(result).hasSize(2)
                .extracting(Simulation::getId)
                .containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void listActive_noActiveSims_returnsEmptyList() {
        when(simRepo.findByStatus(Simulation.SimulationStatus.active))
                .thenReturn(List.of());

        List<Simulation> result = simService.listActive();

        assertThat(result).isEmpty();
    }

    // ── end ───────────────────────────────────────────────────────────────────

    @Test
    void end_setsStatusEndedAndEndedAt() {
        Simulation sim = new Simulation();
        sim.setId("sim-001");
        sim.setStatus(Simulation.SimulationStatus.active);
        when(simRepo.findById("sim-001")).thenReturn(Optional.of(sim));
        when(simRepo.save(any(Simulation.class))).thenReturn(sim);

        Simulation result = simService.end("sim-001");

        assertThat(result.getStatus()).isEqualTo(Simulation.SimulationStatus.ended);
        assertThat(result.getEndedAt()).isNotNull();
        assertThat(result.getEndedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(cacheService).invalidateSim("sim-001");
    }

    @Test
    void end_nonExistingSim_throwsNoSuchElementException() {
        when(simRepo.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> simService.end("unknown"))
                .isInstanceOf(NoSuchElementException.class);
        verify(cacheService, never()).invalidateSim(any());
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_invalidatesSimCacheAndDeletesFromDB() {
        when(simRepo.existsById("sim-001")).thenReturn(true);

        simService.delete("sim-001");

        verify(cacheService).invalidateSim("sim-001");
        verify(simRepo).deleteById("sim-001");
    }

    @Test
    void delete_nonExistingSim_throwsNoSuchElementException() {
        when(simRepo.existsById("unknown")).thenReturn(false);

        assertThatThrownBy(() -> simService.delete("unknown"))
                .isInstanceOf(NoSuchElementException.class);
        verify(cacheService, never()).invalidateSim(any());
        verify(simRepo, never()).deleteById(any());
    }

    @Test
    void delete_invalidatesCacheBeforeDeletingFromDB() {
        when(simRepo.existsById("sim-001")).thenReturn(true);

        simService.delete("sim-001");

        // Verify order: invalidate first, then delete
        var inOrder = inOrder(cacheService, simRepo);
        inOrder.verify(cacheService).invalidateSim("sim-001");
        inOrder.verify(simRepo).deleteById("sim-001");
    }
}
