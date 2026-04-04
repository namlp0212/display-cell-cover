package com.example.cellcover.service;

import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.repository.SimulationOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CellCacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CellRepository cellRepo;
    @Mock
    private SimulationOverrideRepository overrideRepo;

    private CellCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        cacheService = new CellCacheService(redis, cellRepo, overrideRepo);
    }

    // ── getRealOnCells ────────────────────────────────────────────────────────

    @Test
    void getRealOnCells_cacheHit_returnsRedisSet() {
        when(setOps.members("effective_cells:real"))
                .thenReturn(Set.of("CELL001", "CELL002"));

        Set<String> result = cacheService.getRealOnCells();

        assertThat(result).containsExactlyInAnyOrder("CELL001", "CELL002");
        verifyNoInteractions(cellRepo);
    }

    @Test
    void getRealOnCells_cacheMiss_loadsFromDB() {
        // Redis returns null (cache miss)
        when(setOps.members("effective_cells:real")).thenReturn(null);
        when(cellRepo.findAllOnCellIds()).thenReturn(List.of("CELL001", "CELL002"));
        when(cellRepo.findAllOffCellIds()).thenReturn(List.of("CELL003"));

        Set<String> result = cacheService.getRealOnCells();

        assertThat(result).containsExactlyInAnyOrder("CELL001", "CELL002");
        verify(cellRepo).findAllOnCellIds();
        verify(setOps).add(eq("effective_cells:real"), any(String[].class));
        verify(setOps).add(eq("effective_cells:real:off"), any(String[].class));
    }

    @Test
    void getRealOnCells_emptyCache_loadsFromDB() {
        // Redis returns empty set (also a miss)
        when(setOps.members("effective_cells:real")).thenReturn(Collections.emptySet());
        when(cellRepo.findAllOnCellIds()).thenReturn(List.of("CELL001"));
        when(cellRepo.findAllOffCellIds()).thenReturn(Collections.emptyList());

        Set<String> result = cacheService.getRealOnCells();

        assertThat(result).containsExactly("CELL001");
        verify(cellRepo).findAllOnCellIds();
    }

    // ── getRealOffCells ───────────────────────────────────────────────────────

    @Test
    void getRealOffCells_cacheHit_returnsRedisSet() {
        when(setOps.members("effective_cells:real:off"))
                .thenReturn(Set.of("CELL003"));

        Set<String> result = cacheService.getRealOffCells();

        assertThat(result).containsExactly("CELL003");
        verifyNoInteractions(cellRepo);
    }

    @Test
    void getRealOffCells_cacheMiss_loadsAndReturnsOffSet() {
        // First call for :real:off (miss), then loads cells
        when(setOps.members("effective_cells:real:off")).thenReturn(null);
        when(setOps.members("effective_cells:real")).thenReturn(null);
        when(cellRepo.findAllOnCellIds()).thenReturn(List.of("CELL001"));
        when(cellRepo.findAllOffCellIds()).thenReturn(List.of("CELL003"));

        // After loadRealCells, second call for :real:off
        when(setOps.members("effective_cells:real:off"))
                .thenReturn(null)
                .thenReturn(Set.of("CELL003"));

        Set<String> result = cacheService.getRealOffCells();

        assertThat(result).containsExactly("CELL003");
        verify(cellRepo).findAllOffCellIds();
    }

    // ── invalidateReal ────────────────────────────────────────────────────────

    @Test
    void invalidateReal_deletesRealKeys() {
        when(redis.keys(anyString())).thenReturn(Collections.emptySet());

        cacheService.invalidateReal();

        verify(redis).delete("effective_cells:real");
        verify(redis).delete("effective_cells:real:off");
    }

    // ── buildSimCache ─────────────────────────────────────────────────────────

    @Test
    void buildSimCache_computesOnAndOffSetsCorrectly() {
        String simId = "sim-001";
        // simOff = [CELL002] (cells turned off in scenario)
        when(overrideRepo.findForcedOffCellIds(simId)).thenReturn(List.of("CELL002"));
        // real state: CELL001 ON, CELL002 ON, CELL003 OFF
        when(setOps.members("effective_cells:real")).thenReturn(Set.of("CELL001", "CELL002"));
        when(setOps.members("effective_cells:real:off")).thenReturn(Set.of("CELL003"));

        cacheService.buildSimCache(simId);

        // sim on  = realOn - simOff = {CELL001, CELL002} - {CELL002} = {CELL001}
        // sim off = realOff ∪ simOff = {CELL003} ∪ {CELL002} = {CELL002, CELL003}
        // Verify add is called for the on key with exactly 1 cell (CELL001)
        ArgumentCaptor<String[]> onCaptor = ArgumentCaptor.forClass(String[].class);
        verify(setOps).add(eq("effective_cells:sim:sim-001:on"), onCaptor.capture());
        assertThat(onCaptor.getValue()).containsExactly("CELL001");

        ArgumentCaptor<String[]> offCaptor = ArgumentCaptor.forClass(String[].class);
        verify(setOps).add(eq("effective_cells:sim:sim-001:off"), offCaptor.capture());
        assertThat(offCaptor.getValue()).containsExactlyInAnyOrder("CELL002", "CELL003");
    }

    @Test
    void buildSimCache_allCellsTurnedOff_simOnIsEmpty() {
        String simId = "sim-002";
        when(overrideRepo.findForcedOffCellIds(simId)).thenReturn(List.of("CELL001", "CELL002"));
        when(setOps.members("effective_cells:real")).thenReturn(Set.of("CELL001", "CELL002"));
        when(setOps.members("effective_cells:real:off")).thenReturn(Collections.emptySet());

        cacheService.buildSimCache(simId);

        // sim on = {} (empty) → no add call for :on key
        verify(setOps, never()).add(eq("effective_cells:sim:sim-002:on"), any(String[].class));
        verify(setOps).add(eq("effective_cells:sim:sim-002:off"), any(String[].class));
    }

    // ── getSimOnCells ─────────────────────────────────────────────────────────

    @Test
    void getSimOnCells_cacheHit_returnsWithoutRebuild() {
        String simId = "sim-001";
        when(setOps.members("effective_cells:sim:sim-001:on"))
                .thenReturn(Set.of("CELL001"));

        Set<String> result = cacheService.getSimOnCells(simId);

        assertThat(result).containsExactly("CELL001");
        verifyNoInteractions(overrideRepo);
    }

    @Test
    void getSimOnCells_cacheMiss_rebuildsCache() {
        String simId = "sim-001";
        // First members call returns null (miss)
        when(setOps.members("effective_cells:sim:sim-001:on"))
                .thenReturn(null)
                .thenReturn(Set.of("CELL001"));
        when(overrideRepo.findForcedOffCellIds(simId)).thenReturn(List.of("CELL002"));
        when(setOps.members("effective_cells:real")).thenReturn(Set.of("CELL001", "CELL002"));
        when(setOps.members("effective_cells:real:off")).thenReturn(Collections.emptySet());

        Set<String> result = cacheService.getSimOnCells(simId);

        verify(overrideRepo).findForcedOffCellIds(simId);
        assertThat(result).isNotNull();
    }

    // ── getSimOffCells ────────────────────────────────────────────────────────

    @Test
    void getSimOffCells_cacheHit_returns() {
        String simId = "sim-001";
        when(setOps.members("effective_cells:sim:sim-001:off"))
                .thenReturn(Set.of("CELL002", "CELL003"));

        Set<String> result = cacheService.getSimOffCells(simId);

        assertThat(result).containsExactlyInAnyOrder("CELL002", "CELL003");
        verifyNoInteractions(overrideRepo);
    }

    // ── invalidateSim ─────────────────────────────────────────────────────────

    @Test
    void invalidateSim_deletesSimKeys() {
        String simId = "sim-001";
        when(redis.keys(anyString())).thenReturn(Collections.emptySet());

        cacheService.invalidateSim(simId);

        verify(redis).delete("effective_cells:sim:sim-001:on");
        verify(redis).delete("effective_cells:sim:sim-001:off");
    }

    // ── getTileCache ──────────────────────────────────────────────────────────

    @Test
    void getTileCache_hit_returnsDecodedBytes() {
        byte[] original = new byte[]{10, 20, 30};
        String encoded = Base64.getEncoder().encodeToString(original);
        when(valueOps.get("tile_cache:12:100:200:coverage:null")).thenReturn(encoded);

        byte[] result = cacheService.getTileCache(12, 100, 200, "coverage", null);

        assertThat(result).isEqualTo(original);
    }

    @Test
    void getTileCache_miss_returnsNull() {
        when(valueOps.get(anyString())).thenReturn(null);

        byte[] result = cacheService.getTileCache(12, 100, 200, "coverage", null);

        assertThat(result).isNull();
    }

    @Test
    void getTileCache_withSimId_usesCorrectKey() {
        when(valueOps.get("tile_cache:12:100:200:coverage:sim-123")).thenReturn(null);

        cacheService.getTileCache(12, 100, 200, "coverage", "sim-123");

        verify(valueOps).get("tile_cache:12:100:200:coverage:sim-123");
    }

    // ── putTileCache ──────────────────────────────────────────────────────────

    @Test
    void putTileCache_storesBase64WithTTL() {
        byte[] mvt = new byte[]{1, 2, 3, 4};
        String expectedEncoded = Base64.getEncoder().encodeToString(mvt);

        cacheService.putTileCache(12, 100, 200, "coverage", null, mvt);

        verify(valueOps).set(
                eq("tile_cache:12:100:200:coverage:null"),
                eq(expectedEncoded),
                argThat(d -> d.getSeconds() == 60)
        );
    }

    @Test
    void putTileCache_withSimId_usesCorrectKey() {
        byte[] mvt = new byte[]{5, 6};

        cacheService.putTileCache(12, 100, 200, "signal", "sim-abc", mvt);

        verify(valueOps).set(eq("tile_cache:12:100:200:signal:sim-abc"), anyString(), any());
    }
}
