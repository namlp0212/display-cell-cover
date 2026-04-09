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
        "4,  5",
        "5,  5",
        "6,  5",
        "7,  6",
        "8,  7",
        "9,  7",
        "10, 8",
        "11, 9",
        "12, 9",
        "13, 10",
        "14, 11",
        "15, 12",
        "16, 12",
        "17, 13",
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
        assertThat(bbox[0]).isCloseTo(-180.0, within(0.001));
        assertThat(bbox[2]).isCloseTo(180.0,  within(0.001));
        assertThat(bbox[1]).isLessThan(bbox[3]);
    }

    @Test
    void tileToBbox_returnsCorrectLonRange() {
        double[] bbox = H3TileService.tileToBbox(1, 0, 0);
        assertThat(bbox[0]).isCloseTo(-180.0, within(0.001));
        assertThat(bbox[2]).isCloseTo(0.0,    within(0.001));
    }

    @Test
    void tileToBbox_minLatLessThanMaxLat() {
        double[] bbox = H3TileService.tileToBbox(12, 3405, 1873);
        assertThat(bbox[1]).isLessThan(bbox[3]);
        assertThat(bbox[0]).isLessThan(bbox[2]);
    }

    @Test
    void tileToBbox_vietnamTile_inCorrectRange() {
        double[] bbox = H3TileService.tileToBbox(7, 102, 53);
        assertThat(bbox[0]).isBetween(100.0, 115.0);
        assertThat(bbox[2]).isBetween(100.0, 115.0);
    }

    // ── computeNetworkMask ────────────────────────────────────────────────────

    @Test
    void computeNetworkMask_null_returnsAllFF() {
        assertThat(H3TileService.computeNetworkMask(null)).isEqualTo(0xFF);
    }

    @Test
    void computeNetworkMask_blank_returnsAllFF() {
        assertThat(H3TileService.computeNetworkMask("")).isEqualTo(0xFF);
    }

    @Test
    void computeNetworkMask_4G_returns4() {
        assertThat(H3TileService.computeNetworkMask("4G")).isEqualTo(4);
    }

    @Test
    void computeNetworkMask_4Gand5G_returns12() {
        assertThat(H3TileService.computeNetworkMask("4G,5G")).isEqualTo(12);
    }

    // ── buildTile — cache hit ─────────────────────────────────────────────────

    @Test
    void buildTile_returnsCachedResult_withoutCallingClickHouse() {
        byte[] cachedBytes = new byte[]{1, 2, 3};
        when(cacheService.getTileCache(12, 100, 200, "coverage", "all", null))
                .thenReturn(cachedBytes);

        byte[] result = tileService.buildTile(12, 100, 200, "coverage", null);

        assertThat(result).isEqualTo(cachedBytes);
        verifyNoInteractions(clickHouseService);
    }

    // ── buildTile — real mode (no sim) ────────────────────────────────────────

    @Test
    void buildTile_realMode_callsQueryTileReal_notQueryTile() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), isNull()))
                .thenReturn(null);
        when(clickHouseService.queryTileReal(anyInt(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(12, 3405, 1873, "coverage", null);

        verify(clickHouseService).queryTileReal(eq(9), anyList(), eq(0xFF));
        verify(clickHouseService, never()).queryTile(anyInt(), anyList(), anyCollection(), anyCollection());
        verify(cacheService, never()).getSimOnCells(any());
        verify(cacheService, never()).getSimOffCells(any());
        verify(cacheService).putTileCache(eq(12), eq(3405), eq(1873), eq("coverage"), eq("all"), isNull(), any());
    }

    // ── buildTile — simulation mode ───────────────────────────────────────────

    @Test
    void buildTile_simMode_computesDeltaAndCallsQueryTileDictSim() {
        String simId = "sim-uuid-1234";
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), eq(simId)))
                .thenReturn(null);
        when(cacheService.getRealOnCells()).thenReturn(Set.of("CELL001", "CELL002"));
        when(cacheService.getSimOnCells(simId)).thenReturn(Set.of("CELL001"));     // CELL002 removed → deltaOff
        when(cacheService.getSimOffCells(simId)).thenReturn(Set.of("CELL002", "CELL003"));
        when(clickHouseService.queryTileDictSim(anyInt(), anyList(), anyInt(), any(), any()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(12, 3405, 1873, "coverage", simId);

        verify(cacheService).getRealOnCells();
        verify(cacheService).getSimOnCells(simId);
        verify(cacheService).getSimOffCells(simId);
        verify(clickHouseService).queryTileDictSim(anyInt(), anyList(), anyInt(), any(), any());
        verify(clickHouseService, never()).queryTileReal(anyInt(), anyList(), anyInt());
        verify(cacheService).putTileCache(eq(12), eq(3405), eq(1873), eq("coverage"), eq("all"), eq(simId), any());
    }

    // ── buildTile — zoom → H3 res passed to ClickHouse ───────────────────────

    @Test
    void buildTile_zoom6_queriesH3Res5() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(clickHouseService.queryTileReal(anyInt(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(6, 0, 0, "coverage", null);

        verify(clickHouseService).queryTileReal(eq(5), anyList(), anyInt());
    }

    @Test
    void buildTile_zoom13_queriesH3Res10() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(clickHouseService.queryTileReal(anyInt(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        tileService.buildTile(13, 6810, 3744, "coverage", null);

        verify(clickHouseService).queryTileReal(eq(10), anyList(), anyInt());
    }

    // ── buildTile — always caches result ─────────────────────────────────────

    @Test
    void buildTile_alwaysCachesResult() {
        when(cacheService.getTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(clickHouseService.queryTileReal(anyInt(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        byte[] result = tileService.buildTile(12, 3405, 1873, "coverage", null);

        assertThat(result).isNotNull();
        verify(cacheService).putTileCache(anyInt(), anyInt(), anyInt(), anyString(), anyString(), any(), any());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
