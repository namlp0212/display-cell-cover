-- Remove Res10: revert hex_density_tile to 2-tier resolution
-- Zoom 7-10 → H3 Res7, Zoom 11+ → H3 Res9

CREATE OR REPLACE FUNCTION hex_density_tile(z int, x int, y int)
RETURNS bytea LANGUAGE sql VOLATILE PARALLEL SAFE AS $$
    SELECT ST_AsMVT(q, 'hex_coverage', 4096, 'geom')
    FROM (
        SELECT
            h.hex_id,
            COUNT(CASE WHEN rc.is_visible THEN 1 END)::int AS count,
            COUNT(rc.cell_id)::int                          AS total,
            AVG(CASE WHEN rc.is_visible AND rc.avg_signal IS NOT NULL THEN rc.avg_signal END) AS avg_signal,
            MAX(CASE WHEN rc.is_visible AND rc.max_signal IS NOT NULL THEN rc.max_signal END) AS max_signal,
            ST_AsMVTGeom(
                ST_Transform(h.h3_geom, 3857),
                ST_TileEnvelope(z, x, y),
                4096, 64, true
            ) AS geom
        FROM hex_coverage h
        JOIN cell_hex_map    m  ON m.hex_id  = h.hex_id AND m.res = h.res
        JOIN raster_coverages rc ON rc.cell_id = m.cell_id
        WHERE h.res = CASE WHEN z <= 10 THEN 7 ELSE 9 END
          AND h.h3_geom && ST_Transform(ST_TileEnvelope(z, x, y), 4326)
        GROUP BY h.hex_id, h.h3_geom
        HAVING COUNT(CASE WHEN rc.is_visible THEN 1 END) > 0
    ) q
    WHERE geom IS NOT NULL;
$$;

-- Clean up Res10 hex data from DB
DELETE FROM cell_hex_map  WHERE res = 10;
DELETE FROM hex_coverage  WHERE res = 10;
