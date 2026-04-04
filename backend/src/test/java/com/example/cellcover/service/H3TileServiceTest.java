package com.example.cellcover.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class H3TileServiceTest {

    @Mock
    private ClickHouseService clickHouseService;

    @Mock
    private CellCacheService cacheService;

    private H3TileService tileService;

    @BeforeEach
    void setUp() throws Exception {
        tileService = new H3TileService(clickHouseService, cacheService);
    }

    // ── h3ResForZoom ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "zoom {0} → H3 res {1}")
    @CsvSource({
        "4,  5",   // zoom < 6 → res 5
        "5,  5",
        "6,  5",   // zoom == 6 → res 5
        "7,  6",   // zoom == 7 → res 6
        "8,  7",   // zoom 8–9 → res 7
        "9,  7",
        "10, 8",   // zoom == 10 → res 8
        "11, 9",   // zoom 11–12 → res 9
        "12, 9",
        "13, 10",  // zoom == 13 → res 10
        "14, 11",  // zoom == 14 → res 11
        "15, 12",  // zoom 15–16 → res 12
        "16, 12",
        "17, 13",  // zoom 17–19 → res 13
        "18, 13",
        "19, 13"
    })
    void h3ResForZoom_returnsCorrectResolution(int zoom, int expectedRes) {
        assertThat(H3TileService.h3ResForZoom(zoom)).isEqualTo(expectedRes);
    }

    // ── tileToBbox ────────────────────────────────────────────────────────────

    @Test
    void tileToBbox_zoom0_returnsWholeWorld() {
        double[] bbox = H3TileService.tileToBbox(0, 0, 0);
        assertThat(bbox[0]).isCloseTo(-180.0, within(0.001));  // minLon
        assertThat(bbox[2]).isCloseTo(180.0,  within(0.001));  // maxLon
        assertThat(bbox[1]).isLessThan(bbox[3]);               // minLat < maxLat
    }

    @Test
    void tileToBbox_returnsCorrectLonRange() {
        // At zoom 1, tile (0,0) covers left half: lon -180 to 0
        double[] bbox = H3TileService.tileToBbox(1, 0, 0);
        assertThat(bbox[0]).isCloseTo(-180.0, within(0.001));
        assertThat(bbox[2]).isCloseTo(0.0,    within(0.001));
    }

    @Test
    void tileToBbox_minLatLessThanMaxLat() {
        double[] bbox = H3TileService.tileToBbox(12, 3405, 1873);
        assertThat(bbox[1]).isLessThan(bbox[3]);
        // bbox: [minLon, minLat, maxLon, maxLat]
        assertThat(bbox[0]).isLessThan(bbox[2]);
    }

    @Test
    void tileToBbox_vietnamTile_inCorrectRange() {
        // Danang area: approx lat 16°N, lon 108°E
        // At zoom 7: x = floor((108+180)/360 * 128) = 102, y = 53
        double[] bbox = H3TileService.tileToBbox(7, 102, 53);
        // Lon should be in 100–115 range (Vietnam)
        assertThat(bbox[0]).isBetween(100.0, 115.0);
        assertThat(bbox[2]).isBetween(100.0, 115.0);
    }

    // ── buildTile — cache hit ─────────────────────────────────────────────────

    @Test
    void buildTile_returnsCachedResult_withoutCallingClickHouse() {
        byte[] cachedBytes = new byte[]{1, 2, 3};
        when(cacheService.getTileCache(12, 100, 200, "coverage", null))
                .thenReturn(cachedBytes);

        byte[] result = tileService.buildTile(12, 100, 200, "coverage", null);

        assertThat(result).isEqualTo(cachedBytes);
        verifyNoInteractions(clickHouseService);
    }

    // ── buildTile — real mode (no sim) ────────────────────────────────────────

    @Test
    void buildTile_realMode_usesRealOnOffCells() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), isNull()))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Set.of("CELL001", "CELL002"));
        when(cacheService.getRealOffCells()).thenReturn(Set.of("CELL003"));
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(12, 3405, 1873, "coverage", null);

        verify(cacheService).getRealOnCells();
        verify(cacheService).getRealOffCells();
        verify(cacheService, never()).getSimOnCells(any());
        verify(cacheService, never()).getSimOffCells(any());
        verify(clickHouseService).queryTile(eq(9), anyLong(), anyLong(), anyCollection(), anyCollection());
        verify(cacheService).putTileCache(eq(12), eq(3405), eq(1873), eq("coverage"), isNull(), any());
    }

    // ── buildTile — simulation mode ───────────────────────────────────────────

    @Test
    void buildTile_simMode_usesSimCells() {
        String simId = "sim-uuid-1234";
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), eq(simId)))
                .thenReturn(null);
        when(cacheService.getSimOnCells(simId)).thenReturn(Set.of("CELL001"));
        when(cacheService.getSimOffCells(simId)).thenReturn(Set.of("CELL002", "CELL003"));
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(12, 3405, 1873, "coverage", simId);

        verify(cacheService).getSimOnCells(simId);
        verify(cacheService).getSimOffCells(simId);
        verify(cacheService, never()).getRealOnCells();
        verify(cacheService, never()).getRealOffCells();
        verify(cacheService).putTileCache(eq(12), eq(3405), eq(1873), eq("coverage"), eq(simId), any());
    }

    // ── buildTile — signal mode ───────────────────────────────────────────────

    @Test
    void buildTile_signalMode_passesCorrectModeToCache() {
        when(cacheService.getTileCache(12, 3405, 1873, "signal", null))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Set.of("CELL001"));
        when(cacheService.getRealOffCells()).thenReturn(Collections.emptySet());
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(12, 3405, 1873, "signal", null);

        verify(cacheService).putTileCache(eq(12), eq(3405), eq(1873), eq("signal"), isNull(), any());
    }

    // ── buildTile — ClickHouse query uses correct H3 resolution ──────────────

    @Test
    void buildTile_zoom6_queriesH3Res5() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Set.of("C1"));
        when(cacheService.getRealOffCells()).thenReturn(Collections.emptySet());
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(6, 0, 0, "coverage", null);

        verify(clickHouseService).queryTile(eq(5), anyLong(), anyLong(), anyCollection(), anyCollection());
    }

    @Test
    void buildTile_zoom13_queriesH3Res10() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Set.of("C1"));
        when(cacheService.getRealOffCells()).thenReturn(Collections.emptySet());
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(13, 6810, 3744, "coverage", null);

        verify(clickHouseService).queryTile(eq(10), anyLong(), anyLong(), anyCollection(), anyCollection());
    }

    // ── buildTile — both cells empty ─────────────────────────────────────────

    @Test
    void buildTile_emptyCells_stillCachesEmptyMvt() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Collections.emptySet());
        when(cacheService.getRealOffCells()).thenReturn(Collections.emptySet());
        when(clickHouseService.queryTile(anyInt(), anyLong(), anyLong(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        byte[] result = tileService.buildTile(12, 3405, 1873, "coverage", null);

        assertThat(result).isNotNull();
        verify(cacheService).putTileCache(anyInt(), anyInt(), anyInt(), anyString(), any(), any());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
