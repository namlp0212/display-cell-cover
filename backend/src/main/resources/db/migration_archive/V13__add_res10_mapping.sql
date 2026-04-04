-- 5-tier zoom→resolution mapping (adds Res10 for zoom 11-12)
-- z 0-7   → Res6
-- z 8     → Res7
-- z 9     → Res8
-- z 10    → Res9
-- z 11-12 → Res10  (hex heatmap max detail before WMS at z13+)

CREATE OR REPLACE FUNCTION hex_density_tile(z int, x int, y int)
RETURNS bytea LANGUAGE sql VOLATILE PARALLEL SAFE AS $$
    SELECT ST_AsMVT(q, 'hex_coverage', 4096, 'geom')
    FROM (
        SELECT
            h.hex_id,
            COUNT(CASE WHEN rc.is_visible THEN 1 END)::int AS count,
            COUNT(rc.cell_id)::int                          AS total,
            AVG(CASE WHEN rc.is_visible AND m.avg_signal IS NOT NULL
                     THEN m.avg_signal END)                 AS avg_signal,
            MAX(CASE WHEN rc.is_visible AND m.max_signal IS NOT NULL
                     THEN m.max_signal END)                 AS max_signal,
            ST_AsMVTGeom(
                ST_Transform(h.h3_geom, 3857),
                ST_TileEnvelope(z, x, y),
                4096, 64, true
            ) AS geom
        FROM hex_coverage h
        JOIN cell_hex_map    m  ON m.hex_id  = h.hex_id AND m.res = h.res
        JOIN raster_coverages rc ON rc.cell_id = m.cell_id
        WHERE h.res = CASE
                          WHEN z <= 7  THEN 6
                          WHEN z  = 8  THEN 7
                          WHEN z  = 9  THEN 8
                          WHEN z  = 10 THEN 9
                          ELSE 10
                      END
          AND h.h3_geom && ST_Transform(ST_TileEnvelope(z, x, y), 4326)
        GROUP BY h.hex_id, h.h3_geom
        HAVING COUNT(rc.cell_id) > 0
    ) q
    WHERE geom IS NOT NULL;
$$;
