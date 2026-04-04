package com.example.cellcover.controller;

import com.example.cellcover.entity.Cell;
import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.service.CellCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AlertSyncController.class)
class AlertSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CellRepository cellRepo;

    @MockBean
    private CellCacheService cacheService;

    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    // ── POST /api/sync/alerts ─────────────────────────────────────────────────

    @Test
    void syncAlert_knownCell_updatesStatusAndReturns200() throws Exception {
        Cell cell = new Cell();
        cell.setCellId("EDN000231");
        cell.setRealStatus(true);
        when(cellRepo.findById("EDN000231")).thenReturn(Optional.of(cell));
        when(cellRepo.save(any(Cell.class))).thenReturn(cell);

        String body = objectMapper.writeValueAsString(Map.of(
                "cellId", "EDN000231",
                "status", false,
                "timestamp", "2026-04-03T10:30:00Z"
        ));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        // Verify cell status was updated
        verify(cellRepo).save(argThat(c -> !c.isRealStatus()));
        verify(cacheService).invalidateReal();
        verify(messagingTemplate).convertAndSend(eq("/topic/cell-status"), any(Map.class));
    }

    @Test
    void syncAlert_cellTurnedOn_setsRealStatusTrue() throws Exception {
        Cell cell = new Cell();
        cell.setCellId("EDN000231");
        cell.setRealStatus(false);
        when(cellRepo.findById("EDN000231")).thenReturn(Optional.of(cell));
        when(cellRepo.save(any(Cell.class))).thenReturn(cell);

        String body = objectMapper.writeValueAsString(Map.of(
                "cellId", "EDN000231",
                "status", true
        ));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        verify(cellRepo).save(argThat(Cell::isRealStatus));
    }

    @Test
    void syncAlert_unknownCell_returns404() throws Exception {
        when(cellRepo.findById("UNKNOWN_CELL")).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of(
                "cellId", "UNKNOWN_CELL",
                "status", false
        ));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(cacheService, never()).invalidateReal();
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void syncAlert_missingCellId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "status", false
        ));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cellRepo, cacheService, messagingTemplate);
    }

    @Test
    void syncAlert_missingStatus_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "cellId", "EDN000231"
        ));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncAlert_invalidatesRealCacheAfterUpdate() throws Exception {
        Cell cell = new Cell();
        cell.setCellId("CELL001");
        when(cellRepo.findById("CELL001")).thenReturn(Optional.of(cell));
        when(cellRepo.save(any())).thenReturn(cell);

        String body = objectMapper.writeValueAsString(Map.of("cellId", "CELL001", "status", true));

        mockMvc.perform(post("/api/sync/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        var inOrder = inOrder(cellRepo, cacheService);
        inOrder.verify(cellRepo).save(any());
        inOrder.verify(cacheService).invalidateReal();
    }

    // ── POST /api/sync/alerts/batch ───────────────────────────────────────────

    @Test
    void syncAlertBatch_allKnownCells_returnsCorrectCount() throws Exception {
        Cell cell1 = new Cell();
        cell1.setCellId("CELL001");
        Cell cell2 = new Cell();
        cell2.setCellId("CELL002");
        when(cellRepo.findById("CELL001")).thenReturn(Optional.of(cell1));
        when(cellRepo.findById("CELL002")).thenReturn(Optional.of(cell2));
        when(cellRepo.save(any())).thenReturn(cell1, cell2);

        String body = objectMapper.writeValueAsString(List.of(
                Map.of("cellId", "CELL001", "status", false),
                Map.of("cellId", "CELL002", "status", true)
        ));

        mockMvc.perform(post("/api/sync/alerts/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.failed").value(0));

        verify(cacheService).invalidateReal();
        verify(messagingTemplate).convertAndSend(eq("/topic/cell-status-batch"), any(Map.class));
    }

    @Test
    void syncAlertBatch_someUnknownCells_countsFailures() throws Exception {
        Cell cell1 = new Cell();
        cell1.setCellId("CELL001");
        when(cellRepo.findById("CELL001")).thenReturn(Optional.of(cell1));
        when(cellRepo.findById("UNKNOWN")).thenReturn(Optional.empty());
        when(cellRepo.save(any())).thenReturn(cell1);

        String body = objectMapper.writeValueAsString(List.of(
                Map.of("cellId", "CELL001", "status", false),
                Map.of("cellId", "UNKNOWN", "status", true)
        ));

        mockMvc.perform(post("/api/sync/alerts/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed").value(1));
    }

    @Test
    void syncAlertBatch_emptyList_returns0Updated() throws Exception {
        String body = "[]";

        mockMvc.perform(post("/api/sync/alerts/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.failed").value(0));
    }
}
