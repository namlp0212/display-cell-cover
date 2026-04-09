package com.example.cellcover.service;

import com.example.cellcover.controller.ClickHouseDictController;
import com.example.cellcover.service.ClickHouseService.TileRow;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds MVT (Mapbox Vector Tile) protobuf from H3 hexagon data stored in ClickHouse.
 *
 * Tile coordinate system: Web Mercator (EPSG:3857), standard XYZ slippy map.
 *
 * Query strategy (production-scale):
 *   Real state  → queryTileReal()     (ClickHouse Dictionary, no large IN list)
 *   Simulation  → queryTileDictSim()  (Dictionary base + small delta IN lists)
 */
@Service
public class H3TileService {

    private static final Logger log = LoggerFactory.getLogger(H3TileService.class);

    private final H3Core h3;
    private final ClickHouseService clickHouseService;
    private final CellCacheService cacheService;

    public H3TileService(ClickHouseService clickHouseService,
                         CellCacheService cacheService) throws IOException {
        this.h3 = H3Core.newInstance();
        this.clickHouseService = clickHouseService;
        this.cacheService = cacheService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Build and return MVT bytes for a tile (legacy signature without networkTypes). */
    public byte[] buildTile(int z, int x, int y, String mode, String simId) {
        return buildTile(z, x, y, mode, null, simId, false);
    }

    /** Build and return MVT bytes for a tile (without baseline). */
    public byte[] buildTile(int z, int x, int y, String mode, String networkTypes, String simId) {
        return buildTile(z, x, y, mode, networkTypes, simId, false);
    }

    /**
     * Build and return MVT bytes for a tile.
     * Results are cached in Redis for 60 seconds.
     *
     * @param z            Zoom level (6–19)
     * @param x            Tile X (Web Mercator)
     * @param y            Tile Y (Web Mercator)
     * @param mode         "coverage" or "signal"
     * @param networkTypes Comma-separated network types to filter (e.g. "4G,5G"), or null for all
     * @param simId        Simulation UUID, or null for real state
     * @param baseline     When true, treats all real-OFF cells as ON (scenario baseline mode)
     */
    public byte[] buildTile(int z, int x, int y, String mode, String networkTypes, String simId, boolean baseline) {
        String networkTypesHash = computeNetworkTypesHash(networkTypes);
        String cacheSimKey = cacheKeySimId(simId, baseline);

        // 1. Check tile cache
        byte[] cached = cacheService.getTileCache(z, x, y, mode, networkTypesHash, cacheSimKey);
        if (cached != null) return cached;

        // 2. Compute H3 resolution, bbox, and tile hex cells
        int h3Res = h3ResForZoom(z);
        double[] bbox = tileToBbox(z, x, y);
        List<Long> tileCells = bboxToH3Cells(bbox, h3Res);

        // 3. Resolve network mask (0xFF = all networks)
        int networkMask = computeNetworkMask(networkTypes);

        // 4. Query ClickHouse via appropriate strategy
        List<TileRow> rows;
        if (baseline) {
            rows = queryBaselineTile(h3Res, tileCells, networkMask, simId);
        } else if (simId == null) {
            rows = clickHouseService.queryTileReal(h3Res, tileCells, networkMask);
        } else {
            rows = querySimTile(h3Res, tileCells, networkMask, simId);
        }

        // 5. Encode to MVT, cache, and return
        byte[] mvt = encodeMvt(rows, bbox, z);
        cacheService.putTileCache(z, x, y, mode, networkTypesHash, cacheSimKey, mvt);
        return mvt;
    }

    private String cacheKeySimId(String simId, boolean baseline) {
        if (!baseline) return simId;
        return simId == null ? "_baseline" : simId + ":b";
    }

    // ── Simulation delta resolution ───────────────────────────────────────────

    /**
     * Baseline tile: all real-OFF cells are forced ON, then scenario overrides applied.
     * Used when entering scenario mode so the user sees a clean "all cells ON" starting state.
     *
     * deltaOn  = realOff cells NOT turned off by the scenario (forced → ON)
     * deltaOff = realOn  cells turned off by the scenario
     */
    private List<TileRow> queryBaselineTile(int h3Res, List<Long> tileCells,
                                             int networkMask, String simId) {
        Set<String> realOff = cacheService.getRealOffCells();

        // deltaOn: force all real-OFF cells to appear ON (baseline = all cells start as ON)
        Set<String> deltaOn  = new HashSet<>(realOff);
        Set<String> deltaOff = new HashSet<>();

        if (simId != null) {
            // getSimOffCells returns (realOff ∪ scenarioDelta) — extract pure scenario delta:
            // scenarioDelta = cells the user explicitly turned OFF, always a subset of realOn
            Set<String> simOffEffective = cacheService.getSimOffCells(simId);
            Set<String> scenarioOff = new HashSet<>(simOffEffective);
            scenarioOff.removeAll(realOff); // scenarioOff = simOffEffective - realOff

            // Real-off cells that the scenario also turns off: already OFF via dict, just don't force ON
            // (scenarioOff ⊆ realOn so there is no overlap with deltaOn — deltaOn stays = realOff)

            // Scenario-off cells (from realOn) must appear OFF
            deltaOff.addAll(scenarioOff);
        }

        if (networkMask != 0xFF) {
            Set<String> netFilter = resolveNetworkFilterCells(networkMask);
            deltaOn.retainAll(netFilter);
            deltaOff.retainAll(netFilter);
        }

        log.debug("[baseline sim:{}] deltaOn={} deltaOff={}", simId, deltaOn.size(), deltaOff.size());
        return clickHouseService.queryTileDictSim(h3Res, tileCells, networkMask, deltaOn, deltaOff);
    }

    /**
     * Computes the delta between real state and simulation state, then queries
     * ClickHouse with only the changed cells — keeping SQL small regardless of
     * total cell count.
     *
     * deltaOn  = cells OFF in real but ON in sim (sim activates them)
     * deltaOff = cells ON  in real but OFF in sim (sim deactivates them)
     */
    private List<TileRow> querySimTile(int h3Res, List<Long> tileCells,
                                        int networkMask, String simId) {
        Set<String> realOn  = cacheService.getRealOnCells();
        Set<String> simOn   = cacheService.getSimOnCells(simId);
        Set<String> simOff  = cacheService.getSimOffCells(simId);

        // deltaOn: sim activates cells that were OFF in real
        Set<String> deltaOn = new HashSet<>(simOn);
        deltaOn.removeAll(realOn);

        // deltaOff: sim deactivates cells that were ON in real
        Set<String> deltaOff = new HashSet<>(simOff);
        deltaOff.retainAll(realOn);

        // Apply network filter to delta lists (base cells filtered via dict inside CH)
        if (networkMask != 0xFF) {
            Set<String> netFilter = resolveNetworkFilterCells(networkMask);
            deltaOn.retainAll(netFilter);
            deltaOff.retainAll(netFilter);
        }

        log.debug("[sim:{}] delta: +{}on -{}off", simId, deltaOn.size(), deltaOff.size());
        return clickHouseService.queryTileDictSim(h3Res, tileCells, networkMask, deltaOn, deltaOff);
    }

    // ── Network mask helpers ──────────────────────────────────────────────────

    /**
     * Convert comma-separated network type string to a bitmask.
     * Null or blank → 0xFF (all networks, no filtering).
     */
    static int computeNetworkMask(String networkTypes) {
        if (networkTypes == null || networkTypes.isBlank()) return 0xFF;
        int mask = 0;
        for (String nt : networkTypes.split(",")) {
            mask |= ClickHouseDictController.networkToMask(nt.trim());
        }
        return mask == 0 ? 0xFF : mask;
    }

    /** Returns set of cell IDs matching the given network mask (from Redis). */
    private Set<String> resolveNetworkFilterCells(int networkMask) {
        Set<String> result = new HashSet<>();
        if ((networkMask & 1) != 0) result.addAll(cacheService.getNetworkCells("2G"));
        if ((networkMask & 2) != 0) result.addAll(cacheService.getNetworkCells("3G"));
        if ((networkMask & 4) != 0) result.addAll(cacheService.getNetworkCells("4G"));
        if ((networkMask & 8) != 0) result.addAll(cacheService.getNetworkCells("5G"));
        return result;
    }

    private String computeNetworkTypesHash(String networkTypes) {
        if (networkTypes == null || networkTypes.isBlank()) return "all";
        return Arrays.stream(networkTypes.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .sorted()
                .collect(Collectors.joining());
    }

    // ── H3 Resolution mapping ─────────────────────────────────────────────────

    public static int h3ResForZoom(int z) {
        if (z <= 6)  return 5;
        if (z == 7)  return 6;
        if (z <= 9)  return 7;
        if (z == 10) return 8;
        if (z <= 12) return 9;
        if (z == 13) return 10;
        if (z == 14) return 11;
        if (z <= 16) return 12;
        return 13;  // zoom 17–19
    }

    // ── Tile bbox computation ─────────────────────────────────────────────────

    /**
     * Convert slippy tile Z/X/Y to WGS84 bounding box.
     * Returns [minLon, minLat, maxLon, maxLat].
     */
    static double[] tileToBbox(int z, int x, int y) {
        double n = Math.pow(2, z);
        double minLon =  x      / n * 360.0 - 180.0;
        double maxLon = (x + 1) / n * 360.0 - 180.0;
        double maxLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 *  y      / n))));
        double minLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 1) / n))));
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    /**
     * Average H3 edge length in km per resolution (res 0–13).
     * Used to compute bbox buffer for boundary hexagon inclusion.
     */
    private static final double[] H3_EDGE_KM = {
        1281.26, 483.06, 182.51, 68.92, 26.03,
        9.854,   3.724,  1.406,  0.531, 0.201,
        0.076,   0.029,  0.011,  0.004
    };

    /**
     * Returns H3 cells covering the tile bbox, expanded by 1 hex edge on each side
     * to eliminate empty borders caused by polygonToCells excluding hexagons whose
     * center lies just outside the tile but whose body overlaps the edge.
     */
    private List<Long> bboxToH3Cells(double[] bbox, int h3Res) {
        // Expand bbox by ~1 hex edge length so boundary hexagons are always included
        double bufDeg = H3_EDGE_KM[h3Res] / 111.0;
        double minLon = bbox[0] - bufDeg, minLat = bbox[1] - bufDeg;
        double maxLon = bbox[2] + bufDeg, maxLat = bbox[3] + bufDeg;

        // CCW winding (GeoJSON exterior ring): SW → SE → NE → NW → SW
        List<com.uber.h3core.util.LatLng> boundary = List.of(
                new com.uber.h3core.util.LatLng(minLat, minLon),  // SW
                new com.uber.h3core.util.LatLng(minLat, maxLon),  // SE
                new com.uber.h3core.util.LatLng(maxLat, maxLon),  // NE
                new com.uber.h3core.util.LatLng(maxLat, minLon),  // NW
                new com.uber.h3core.util.LatLng(minLat, minLon)   // SW (close)
        );

        List<Long> cells = h3.polygonToCells(boundary, Collections.emptyList(), h3Res);
        if (!cells.isEmpty()) return cells;

        // Fallback for very small tiles: sample 5 points (corners + center)
        double centerLat = (minLat + maxLat) / 2;
        double centerLon = (minLon + maxLon) / 2;
        return new ArrayList<>(Set.of(
                h3.latLngToCell(minLat, minLon, h3Res),
                h3.latLngToCell(minLat, maxLon, h3Res),
                h3.latLngToCell(maxLat, minLon, h3Res),
                h3.latLngToCell(maxLat, maxLon, h3Res),
                h3.latLngToCell(centerLat, centerLon, h3Res)
        ));
    }

    // ── MVT encoding ──────────────────────────────────────────────────────────

    private byte[] encodeMvt(List<TileRow> rows, double[] tileBbox, int zoom) {
        List<MvtEncoder.Feature> features = new ArrayList<>();

        for (TileRow row : rows) {
            if (row.onDensity() == 0 && row.offDensity() == 0) continue;

            List<com.uber.h3core.util.LatLng> boundary;
            try {
                boundary = h3.cellToBoundary(row.h3Index());
            } catch (Exception e) {
                log.warn("Invalid H3 index {}: {}", row.h3Index(), e.getMessage());
                continue;
            }

            int n = boundary.size();
            int[][] ring = new int[n + 1][2];
            for (int i = 0; i < n; i++) {
                com.uber.h3core.util.LatLng ll = boundary.get(i);
                double[] px = lonLatToTilePixel(ll.lng, ll.lat, tileBbox, 4096);
                ring[i][0] = (int) Math.round(px[0]);
                ring[i][1] = (int) Math.round(px[1]);
            }
            ring[n] = ring[0]; // close

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("h3_index",    row.h3Index());
            attrs.put("on_density",  row.onDensity());
            attrs.put("on_signal",   row.onSignal());
            attrs.put("off_density", row.offDensity());

            features.add(new MvtEncoder.Feature(attrs, ring));
        }

        try {
            return MvtEncoder.encode("coverage", features);
        } catch (IOException e) {
            log.error("MVT encoding failed: {}", e.getMessage(), e);
            return new byte[0];
        }
    }

    private static double[] lonLatToTilePixel(double lon, double lat, double[] bbox, int tileSize) {
        double px = (lon - bbox[0]) / (bbox[2] - bbox[0]) * tileSize;
        double py = (bbox[3] - lat) / (bbox[3] - bbox[1]) * tileSize;
        return new double[]{px, py};
    }
}
