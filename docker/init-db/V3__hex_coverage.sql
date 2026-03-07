CREATE TABLE IF NOT EXISTS hex_coverage (
    hex_id  VARCHAR(20)  PRIMARY KEY,
    h3_geom GEOMETRY(POLYGON, 4326) NOT NULL,
    res     SMALLINT     NOT NULL,
    count   INT          NOT NULL DEFAULT 0,
    total   INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_hex_coverage_geom ON hex_coverage USING GIST (h3_geom);
CREATE INDEX IF NOT EXISTS idx_hex_coverage_res  ON hex_coverage (res);
CREATE INDEX IF NOT EXISTS idx_hex_coverage_res_count ON hex_coverage (res, count) WHERE count > 0;

CREATE TABLE IF NOT EXISTS cell_hex_map (
    cell_id VARCHAR(50)  NOT NULL,
    hex_id  VARCHAR(20)  NOT NULL,
    res     SMALLINT     NOT NULL,
    PRIMARY KEY (cell_id, hex_id, res)
);

CREATE INDEX IF NOT EXISTS idx_cell_hex_map_cell ON cell_hex_map (cell_id);
CREATE INDEX IF NOT EXISTS idx_cell_hex_map_hex  ON cell_hex_map (hex_id);

-- pg_tileserv function source: tính động count từ raster_coverages.visible
-- VOLATILE vì kết quả thay đổi theo trạng thái visible của raster_coverages
-- Note: must use plain SQL (not PL/pgSQL) so ST_TileEnvelope params bind correctly
CREATE OR REPLACE FUNCTION hex_density_tile(z int, x int, y int)
RETURNS bytea LANGUAGE sql VOLATILE PARALLEL SAFE AS $$
    SELECT ST_AsMVT(q, 'hex_coverage', 4096, 'geom')
    FROM (
        SELECT
            h.hex_id,
            COUNT(CASE WHEN rc.is_visible THEN 1 END)::int AS count,
            COUNT(rc.cell_id)::int                      AS total,
            ST_AsMVTGeom(
                ST_Transform(h.h3_geom, 3857),
                ST_TileEnvelope(z, x, y),
                4096, 64, true
            ) AS geom
        FROM hex_coverage h
        JOIN cell_hex_map   m  ON m.hex_id  = h.hex_id AND m.res = h.res
        JOIN raster_coverages rc ON rc.cell_id = m.cell_id
        WHERE h.res = CASE WHEN z <= 9 THEN 7 ELSE 9 END
          AND h.h3_geom && ST_Transform(ST_TileEnvelope(z, x, y), 4326)
        GROUP BY h.hex_id, h.h3_geom
        HAVING COUNT(CASE WHEN rc.is_visible THEN 1 END) > 0
    ) q
    WHERE geom IS NOT NULL;
$$;
