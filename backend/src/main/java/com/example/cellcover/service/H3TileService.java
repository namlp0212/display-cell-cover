package com.example.cellcover.service;

import com.example.cellcover.service.ClickHouseService.TileRow;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Builds MVT (Mapbox Vector Tile) protobuf from H3 hexagon data stored in ClickHouse.
 *
 * Tile coordinate system: Web Mercator (EPSG:3857), standard XYZ slippy map.
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

    /**
     * Build and return MVT bytes for a tile.
     * Results are cached in Redis for 60 seconds.
     *
     * @param z     Zoom level (6–19)
     * @param x     Tile X (Web Mercator)
     * @param y     Tile Y (Web Mercator)
     * @param mode  "coverage" or "signal"
     * @param simId Simulation UUID, or null for real state
     */
    public byte[] buildTile(int z, int x, int y, String mode, String simId) {
        // 1. Check tile cache
        byte[] cached = cacheService.getTileCache(z, x, y, mode, simId);
        if (cached != null) return cached;

        // 2. Resolve on/off cell sets
        Set<String> onCells, offCells;
        if (simId != null) {
            onCells  = cacheService.getSimOnCells(simId);
            offCells = cacheService.getSimOffCells(simId);
        } else {
            onCells  = cacheService.getRealOnCells();
            offCells = cacheService.getRealOffCells();
        }

        // 3. Compute H3 resolution and bbox
        int h3Res = h3ResForZoom(z);
        double[] bbox = tileToBbox(z, x, y);  // [minLon, minLat, maxLon, maxLat]

        // 4. Get exact H3 cells covering the bbox (replaces BETWEEN range)
        List<Long> tileCells = bboxToH3Cells(bbox, h3Res);

        // 5. Query ClickHouse
        List<TileRow> rows = clickHouseService.queryTile(h3Res, tileCells, onCells, offCells);

        // 6. Encode to MVT
        byte[] mvt = encodeMvt(rows, bbox, z);

        // 7. Cache and return
        cacheService.putTileCache(z, x, y, mode, simId, mvt);
        return mvt;
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
     * Returns the exact set of H3 cells at h3Res that cover the tile bbox.
     * Uses polygonToCells (polyfill) so ClickHouse can use IN (exact list)
     * instead of BETWEEN — avoiding full-resolution range scans.
     */
    private List<Long> bboxToH3Cells(double[] bbox, int h3Res) {
        double minLon = bbox[0], minLat = bbox[1], maxLon = bbox[2], maxLat = bbox[3];

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

            // Convert H3 boundary to tile pixel coordinates (closed ring)
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

    /**
     * Convert WGS84 lon/lat to tile pixel coordinates [px, py] in range [0, tileSize].
     */
    private static double[] lonLatToTilePixel(double lon, double lat, double[] bbox, int tileSize) {
        double px = (lon - bbox[0]) / (bbox[2] - bbox[0]) * tileSize;
        double py = (bbox[3] - lat) / (bbox[3] - bbox[1]) * tileSize;
        return new double[]{px, py};
    }
}
