package com.example.cellcover.controller;

import com.example.cellcover.entity.Simulation;
import com.example.cellcover.service.SimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SimulationService simService;

    // ── POST /api/simulation ─────────────────────────────────────────────────

    @Test
    void createSimulation_validRequest_returns200WithSimId() throws Exception {
        Simulation sim = new Simulation();
        sim.setId("sim-001");
        sim.setName("Bão số 3");
        sim.setStatus(Simulation.SimulationStatus.active);
        when(simService.create(any())).thenReturn(sim);

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Bão số 3",
                "description", "Test scenario",
                "cellsOff", List.of("CELL001", "CELL002")
        ));

        mockMvc.perform(post("/api/simulation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sim-001"))
                .andExpect(jsonPath("$.name").value("Bão số 3"))
                .andExpect(jsonPath("$.cellsOffCount").value(2));
    }

    @Test
    void createSimulation_missingName_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "cellsOff", List.of("CELL001")
        ));

        mockMvc.perform(post("/api/simulation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(simService);
    }

    @Test
    void createSimulation_missingCellsOff_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Test sim"
        ));

        mockMvc.perform(post("/api/simulation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(simService);
    }

    @Test
    void createSimulation_emptyCellsOff_isAllowed() throws Exception {
        Simulation sim = new Simulation();
        sim.setId("sim-002");
        sim.setName("Empty sim");
        sim.setStatus(Simulation.SimulationStatus.active);
        when(simService.create(any())).thenReturn(sim);

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Empty sim",
                "cellsOff", List.of()
        ));

        mockMvc.perform(post("/api/simulation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellsOffCount").value(0));
    }

    // ── GET /api/simulation ──────────────────────────────────────────────────

    @Test
    void listActive_returnsAllActiveSimulations() throws Exception {
        Simulation s1 = new Simulation();
        s1.setId("sim-001");
        s1.setName("Sim 1");
        Simulation s2 = new Simulation();
        s2.setId("sim-002");
        s2.setName("Sim 2");
        when(simService.listActive()).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("sim-001"))
                .andExpect(jsonPath("$[1].id").value("sim-002"));
    }

    @Test
    void listActive_noSims_returnsEmptyArray() throws Exception {
        when(simService.listActive()).thenReturn(List.of());

        mockMvc.perform(get("/api/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/simulation/{id} ─────────────────────────────────────────────

    @Test
    void getSimulation_exists_returns200() throws Exception {
        Simulation sim = new Simulation();
        sim.setId("sim-001");
        sim.setName("Test");
        sim.setStatus(Simulation.SimulationStatus.active);
        when(simService.get("sim-001")).thenReturn(sim);

        mockMvc.perform(get("/api/simulation/sim-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sim-001"));
    }

    @Test
    void getSimulation_notFound_returns404() throws Exception {
        when(simService.get("unknown")).thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/simulation/unknown"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/simulation/{id}/end ────────────────────────────────────────

    @Test
    void endSimulation_exists_returns200WithEndedStatus() throws Exception {
        Simulation sim = new Simulation();
        sim.setId("sim-001");
        sim.setStatus(Simulation.SimulationStatus.ended);
        sim.setEndedAt(LocalDateTime.now());
        when(simService.end("sim-001")).thenReturn(sim);

        mockMvc.perform(post("/api/simulation/sim-001/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sim-001"))
                .andExpect(jsonPath("$.status").value("ended"))
                .andExpect(jsonPath("$.endedAt").exists());
    }

    @Test
    void endSimulation_notFound_returns404() throws Exception {
        when(simService.end("unknown")).thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(post("/api/simulation/unknown/end"))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/simulation/{id} ──────────────────────────────────────────

    @Test
    void deleteSimulation_exists_returns204() throws Exception {
        doNothing().when(simService).delete("sim-001");

        mockMvc.perform(delete("/api/simulation/sim-001"))
                .andExpect(status().isNoContent());
        verify(simService).delete("sim-001");
    }

    @Test
    void deleteSimulation_notFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("not found")).when(simService).delete("unknown");

        mockMvc.perform(delete("/api/simulation/unknown"))
                .andExpect(status().isNotFound());
    }
}
