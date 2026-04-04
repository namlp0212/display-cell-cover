package com.example.cellcover.controller;

import com.example.cellcover.service.CellCacheService;
import com.example.cellcover.service.H3TileService;
import com.example.cellcover.service.PixelCoverageService;
import com.example.cellcover.service.PixelMaxSignalService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves tiles backed by ClickHouse H3 hexagon data (zoom 6–17)
 * or per-pixel raster PNG (zoom 18–19).
 *
 * GET /api/h3-tile/{z}/{x}/{y}?mode=coverage|signal[&sim={uuid}]
 *
 * Routing:
 *   zoom 6–17  → H3TileService        → MVT (application/vnd.mapbox-vector-tile)
 *   zoom 18–19 → PixelCoverageService / PixelMaxSignalService → PNG (image/png)
 */
@RestController
@RequestMapping("/api/h3-tile")
public class H3TileController {

    private static final MediaType MVT_TYPE =
            MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final H3TileService         h3TileService;
    private final PixelCoverageService  coverageService;
    private final PixelMaxSignalService signalService;
    private final CellCacheService      cellCacheService;

    public H3TileController(H3TileService h3TileService,
                            PixelCoverageService coverageService,
                            PixelMaxSignalService signalService,
                            CellCacheService cellCacheService) {
        this.h3TileService    = h3TileService;
        this.coverageService  = coverageService;
        this.signalService    = signalService;
        this.cellCacheService = cellCacheService;
    }

    @GetMapping("/{z}/{x}/{y}")
    public ResponseEntity<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(defaultValue = "coverage") String mode,
            @RequestParam(required = false) String sim) throws Exception {

        if (z < 6 || z > 19) {
            return ResponseEntity.badRequest().build();
        }
        if (!"coverage".equals(mode) && !"signal".equals(mode)) {
            return ResponseEntity.badRequest().build();
        }

        // ── Zoom 17+: pixel renderer (MapLibre GL applies -1 zoom offset vs Leaflet,
        //    so Leaflet zoom 18 = MapLibre zoom 17 = tile z=17 requested here)
        if (z >= 17) {
            byte[] png;
            if (sim != null) {
                Set<String> onCells  = cellCacheService.getSimOnCells(sim);
                Set<String> offCells = cellCacheService.getSimOffCells(sim);
                png = "signal".equals(mode)
                        ? signalService.renderTile(z, x, y, onCells, offCells)
                        : coverageService.renderTile(z, x, y, onCells, offCells);
            } else {
                png = "signal".equals(mode)
                        ? signalService.renderTile(z, x, y)
                        : coverageService.renderTile(z, x, y);
            }

            if (png == null || png.length == 0) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                    .body(png);
        }

        // ── Zoom 6–17: H3 MVT renderer ────────────────────────────────────────
        byte[] mvt = h3TileService.buildTile(z, x, y, mode, sim);

        if (mvt == null || mvt.length == 0) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok()
                .contentType(MVT_TYPE)
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(mvt);
    }
}
