package com.example.cellcover.controller;

import com.example.cellcover.service.H3TileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(H3TileController.class)
class H3TileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private H3TileService tileService;

    // ── Valid requests ─────────────────────────────────────────────────────────

    @Test
    void getTile_validCoverageRequest_returns200WithMvtContent() throws Exception {
        byte[] mvtBytes = new byte[]{0x1A, 0x2B, 0x3C};
        when(tileService.buildTile(12, 3405, 1873, "coverage", null))
                .thenReturn(mvtBytes);

        mockMvc.perform(get("/api/h3-tile/12/3405/1873?mode=coverage"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.mapbox-vector-tile"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(content().bytes(mvtBytes));
    }

    @Test
    void getTile_validSignalRequest_returns200() throws Exception {
        byte[] mvtBytes = new byte[]{0x1A, 0x2B};
        when(tileService.buildTile(8, 100, 50, "signal", null))
                .thenReturn(mvtBytes);

        mockMvc.perform(get("/api/h3-tile/8/100/50?mode=signal"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.mapbox-vector-tile"));
    }

    @Test
    void getTile_withSimId_passesSimToService() throws Exception {
        String simId = "sim-uuid-1234";
        byte[] mvtBytes = new byte[]{0x10};
        when(tileService.buildTile(12, 3405, 1873, "coverage", simId))
                .thenReturn(mvtBytes);

        mockMvc.perform(get("/api/h3-tile/12/3405/1873?mode=coverage&sim=" + simId))
                .andExpect(status().isOk());
    }

    @Test
    void getTile_defaultMode_usescoverage() throws Exception {
        byte[] mvtBytes = new byte[]{0x10};
        when(tileService.buildTile(12, 3405, 1873, "coverage", null))
                .thenReturn(mvtBytes);

        // mode param defaults to "coverage"
        mockMvc.perform(get("/api/h3-tile/12/3405/1873"))
                .andExpect(status().isOk());
    }

    @Test
    void getTile_zoomBoundary6_returns200() throws Exception {
        byte[] mvtBytes = new byte[]{0x01};
        when(tileService.buildTile(6, 0, 0, "coverage", null)).thenReturn(mvtBytes);

        mockMvc.perform(get("/api/h3-tile/6/0/0?mode=coverage"))
                .andExpect(status().isOk());
    }

    @Test
    void getTile_zoomBoundary19_returns200() throws Exception {
        byte[] mvtBytes = new byte[]{0x01};
        when(tileService.buildTile(19, 0, 0, "coverage", null)).thenReturn(mvtBytes);

        mockMvc.perform(get("/api/h3-tile/19/0/0?mode=coverage"))
                .andExpect(status().isOk());
    }

    // ── Invalid zoom level ─────────────────────────────────────────────────────

    @Test
    void getTile_zoomBelowMin_returns400() throws Exception {
        mockMvc.perform(get("/api/h3-tile/5/0/0?mode=coverage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTile_zoomAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/h3-tile/20/0/0?mode=coverage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTile_zoom0_returns400() throws Exception {
        mockMvc.perform(get("/api/h3-tile/0/0/0?mode=coverage"))
                .andExpect(status().isBadRequest());
    }

    // ── Invalid mode ───────────────────────────────────────────────────────────

    @Test
    void getTile_invalidMode_returns400() throws Exception {
        mockMvc.perform(get("/api/h3-tile/12/0/0?mode=heatmap"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTile_modeComparison_returns400() throws Exception {
        mockMvc.perform(get("/api/h3-tile/12/0/0?mode=comparison"))
                .andExpect(status().isBadRequest());
    }

    // ── Empty MVT response ─────────────────────────────────────────────────────

    @Test
    void getTile_emptyMvt_returns204NoContent() throws Exception {
        when(tileService.buildTile(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(new byte[0]);

        mockMvc.perform(get("/api/h3-tile/12/3405/1873?mode=coverage"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getTile_nullMvt_returns204NoContent() throws Exception {
        when(tileService.buildTile(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(null);

        mockMvc.perform(get("/api/h3-tile/12/3405/1873?mode=coverage"))
                .andExpect(status().isNoContent());
    }

    // ── Cache-Control header ───────────────────────────────────────────────────

    @Test
    void getTile_responsHasCacheControlPublicMaxAge60() throws Exception {
        when(tileService.buildTile(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(new byte[]{0x01});

        mockMvc.perform(get("/api/h3-tile/12/3405/1873?mode=signal"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=60"),
                                org.hamcrest.Matchers.containsString("public")
                        )));
    }
}
