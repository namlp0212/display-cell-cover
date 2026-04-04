package com.example.cellcover.controller;

import com.example.cellcover.service.CellCacheService;
import com.example.cellcover.service.PixelCoverageService;
import com.example.cellcover.service.PixelMaxSignalService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves per-pixel raster PNG tiles at zoom 18–19 from COG files in MinIO.
 *
 * GET /api/pixel-tile/{z}/{x}/{y}?mode=coverage|signal[&sim={uuid}]
 *
 * Zoom 18–19 complements the H3 MVT layer (zoom 6–17):
 *   - coverage mode  → PixelCoverageService (density colour ramp, red for off-cells)
 *   - signal   mode  → PixelMaxSignalService (per-pixel MAX signal colour ramp)
 *
 * Simulation support: when sim={uuid} is provided, on/off cell sets are resolved
 * from Redis cache (same as H3TileController) and passed to the pixel services,
 * so the raster tiles respect the simulated off-cell scenario.
 */
@RestController
@RequestMapping("/api/pixel-tile")
public class PixelTileController {

    private static final int PIXEL_MIN_ZOOM = 18;
    private static final int PIXEL_MAX_ZOOM = 19;

    private final PixelCoverageService  coverageService;
    private final PixelMaxSignalService signalService;
    private final CellCacheService      cacheService;

    public PixelTileController(PixelCoverageService coverageService,
                               PixelMaxSignalService signalService,
                               CellCacheService cacheService) {
        this.coverageService = coverageService;
        this.signalService   = signalService;
        this.cacheService    = cacheService;
    }

    @GetMapping(value = "/{z}/{x}/{y}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(defaultValue = "coverage") String mode,
            @RequestParam(required = false) String sim) throws Exception {

        if (z < PIXEL_MIN_ZOOM || z > PIXEL_MAX_ZOOM) {
            return ResponseEntity.badRequest().build();
        }
        if (!"coverage".equals(mode) && !"signal".equals(mode)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] png;

        if (sim != null) {
            // Simulation: resolve on/off sets from Redis (same cache as H3 tiles)
            Set<String> onCells  = cacheService.getSimOnCells(sim);
            Set<String> offCells = cacheService.getSimOffCells(sim);
            png = "signal".equals(mode)
                    ? signalService.renderTile(z, x, y, onCells, offCells)
                    : coverageService.renderTile(z, x, y, onCells, offCells);
        } else {
            // Real state: services query MariaDB directly (real_status + spatial bbox)
            png = "signal".equals(mode)
                    ? signalService.renderTile(z, x, y)
                    : coverageService.renderTile(z, x, y);
        }

        if (png == null || png.length == 0) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(png);
    }
}
