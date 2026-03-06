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

-- pg_tileserv function source
-- Note: must use plain SQL (not PL/pgSQL) so ST_TileEnvelope params bind correctly
CREATE OR REPLACE FUNCTION hex_density_tile(z int, x int, y int)
RETURNS bytea LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT ST_AsMVT(q, 'hex_coverage', 4096, 'geom')
    FROM (
        SELECT
            hex_id,
            count,
            total,
            ST_AsMVTGeom(
                ST_Transform(h3_geom, 3857),
                ST_TileEnvelope(z, x, y),
                4096, 64, true
            ) AS geom
        FROM hex_coverage
        WHERE res = CASE WHEN z <= 7 THEN 7 ELSE 9 END
          AND h3_geom && ST_Transform(ST_TileEnvelope(z, x, y), 4326)
          AND count > 0
    ) q
    WHERE geom IS NOT NULL;
$$;
