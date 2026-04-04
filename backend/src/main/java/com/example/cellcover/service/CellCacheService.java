package com.example.cellcover.service;

import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.repository.SimulationOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Manages effective_cells sets in Redis.
 *
 * Key schema:
 *   effective_cells:real          → Set of on cell_ids (real_status = TRUE)
 *   effective_cells:real:off      → Set of off cell_ids
 *   effective_cells:sim:{id}:on   → Set of on cell_ids for a simulation
 *   effective_cells:sim:{id}:off  → Set of off cell_ids for a simulation
 *   tile_cache:{z}:{x}:{y}:{mode}:{simId} → bytes (MVT, TTL 60s)
 */
@Service
public class CellCacheService {

    private static final Logger log = LoggerFactory.getLogger(CellCacheService.class);

    private static final String KEY_REAL_ON  = "effective_cells:real";
    private static final String KEY_REAL_OFF = "effective_cells:real:off";
    private static final Duration SIM_TTL    = Duration.ofHours(24);

    private final RedisTemplate<String, String> redis;
    private final CellRepository cellRepo;
    private final SimulationOverrideRepository overrideRepo;

    public CellCacheService(RedisTemplate<String, String> redis,
                            CellRepository cellRepo,
                            SimulationOverrideRepository overrideRepo) {
        this.redis = redis;
        this.cellRepo = cellRepo;
        this.overrideRepo = overrideRepo;
    }

    // ── Real state ────────────────────────────────────────────────────────────

    public Set<String> getRealOnCells() {
        Set<String> cached = redis.opsForSet().members(KEY_REAL_ON);
        if (cached != null && !cached.isEmpty()) return cached;
        return loadRealCells();
    }

    public Set<String> getRealOffCells() {
        Set<String> cached = redis.opsForSet().members(KEY_REAL_OFF);
        if (cached != null && !cached.isEmpty()) return cached;
        loadRealCells();
        Set<String> result = redis.opsForSet().members(KEY_REAL_OFF);
        return result != null ? result : Collections.emptySet();
    }

    private Set<String> loadRealCells() {
        List<String> onIds  = cellRepo.findAllOnCellIds();
        List<String> offIds = cellRepo.findAllOffCellIds();

        redis.delete(KEY_REAL_ON);
        redis.delete(KEY_REAL_OFF);

        if (!onIds.isEmpty()) {
            redis.opsForSet().add(KEY_REAL_ON, onIds.toArray(String[]::new));
        }
        if (!offIds.isEmpty()) {
            redis.opsForSet().add(KEY_REAL_OFF, offIds.toArray(String[]::new));
        }
        log.debug("Loaded real cells into Redis: {} ON, {} OFF", onIds.size(), offIds.size());
        return new HashSet<>(onIds);
    }

    public void invalidateReal() {
        redis.delete(KEY_REAL_ON);
        redis.delete(KEY_REAL_OFF);
        invalidateTileCache("*:*:*:*:null");
    }

    // ── Simulation state ──────────────────────────────────────────────────────

    /**
     * Pre-compute and cache effective on/off sets for a simulation.
     * on_cells  = realOnCells - simOff
     * off_cells = realOffCells ∪ simOff
     */
    public void buildSimCache(String simId) {
        List<String> simOff = overrideRepo.findForcedOffCellIds(simId);
        Set<String> simOffSet = new HashSet<>(simOff);

        Set<String> realOn  = getRealOnCells();
        Set<String> realOff = getRealOffCells();

        Set<String> simOnCells  = new HashSet<>(realOn);
        simOnCells.removeAll(simOffSet);

        Set<String> simOffCells = new HashSet<>(realOff);
        simOffCells.addAll(simOffSet);

        String keyOn  = simOnKey(simId);
        String keyOff = simOffKey(simId);

        redis.delete(keyOn);
        redis.delete(keyOff);

        if (!simOnCells.isEmpty()) {
            redis.opsForSet().add(keyOn, simOnCells.toArray(String[]::new));
            redis.expire(keyOn, SIM_TTL);
        }
        if (!simOffCells.isEmpty()) {
            redis.opsForSet().add(keyOff, simOffCells.toArray(String[]::new));
            redis.expire(keyOff, SIM_TTL);
        }
        log.debug("Built sim cache {}: {} ON, {} OFF", simId, simOnCells.size(), simOffCells.size());
    }

    public Set<String> getSimOnCells(String simId) {
        Set<String> cached = redis.opsForSet().members(simOnKey(simId));
        if (cached != null && !cached.isEmpty()) return cached;
        buildSimCache(simId);
        Set<String> result = redis.opsForSet().members(simOnKey(simId));
        return result != null ? result : Collections.emptySet();
    }

    public Set<String> getSimOffCells(String simId) {
        Set<String> cached = redis.opsForSet().members(simOffKey(simId));
        if (cached != null) return cached;
        buildSimCache(simId);
        Set<String> result = redis.opsForSet().members(simOffKey(simId));
        return result != null ? result : Collections.emptySet();
    }

    public void invalidateSim(String simId) {
        redis.delete(simOnKey(simId));
        redis.delete(simOffKey(simId));
        invalidateTileCache("*:*:*:*:" + simId);
    }

    // ── Tile cache ────────────────────────────────────────────────────────────

    public byte[] getTileCache(int z, int x, int y, String mode, String simId) {
        String key = tileCacheKey(z, x, y, mode, simId);
        String val = redis.opsForValue().get(key);
        return val != null ? Base64.getDecoder().decode(val) : null;
    }

    public void putTileCache(int z, int x, int y, String mode, String simId, byte[] mvt) {
        String key = tileCacheKey(z, x, y, mode, simId);
        redis.opsForValue().set(key, Base64.getEncoder().encodeToString(mvt), Duration.ofSeconds(60));
    }

    private void invalidateTileCache(String pattern) {
        Set<String> keys = redis.keys("tile_cache:" + pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    private static String simOnKey(String simId)  { return "effective_cells:sim:" + simId + ":on"; }
    private static String simOffKey(String simId) { return "effective_cells:sim:" + simId + ":off"; }

    private static String tileCacheKey(int z, int x, int y, String mode, String simId) {
        return "tile_cache:" + z + ":" + x + ":" + y + ":" + mode + ":" + (simId != null ? simId : "null");
    }
}
