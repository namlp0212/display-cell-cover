-- ─────────────────────────────────────────────────────────────────────────────
-- Hex coverage tables cho Vector Tiles (MapLibre)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS hex_coverage (
    hex_id  VARCHAR(20)  PRIMARY KEY,
    h3_geom GEOMETRY(POLYGON, 4326) NOT NULL,
    res     SMALLINT     NOT NULL,         -- H3 resolution: 7 (zoom 0-7) hoặc 9 (zoom 8-10)
    count   INT          NOT NULL DEFAULT 0, -- số cell visible đang phủ
    total   INT          NOT NULL DEFAULT 0  -- tổng cell (kể cả hidden)
);

CREATE INDEX IF NOT EXISTS idx_hex_coverage_geom ON hex_coverage USING GIST (h3_geom);
CREATE INDEX IF NOT EXISTS idx_hex_coverage_res  ON hex_coverage (res);
CREATE INDEX IF NOT EXISTS idx_hex_coverage_res_count ON hex_coverage (res, count) WHERE count > 0;

-- Mapping cell_id → hex_id (dùng khi toggle để biết update hex nào)
CREATE TABLE IF NOT EXISTS cell_hex_map (
    cell_id VARCHAR(50)  NOT NULL,
    hex_id  VARCHAR(20)  NOT NULL,
    res     SMALLINT     NOT NULL,
    PRIMARY KEY (cell_id, hex_id, res)
);

CREATE INDEX IF NOT EXISTS idx_cell_hex_map_cell ON cell_hex_map (cell_id);
CREATE INDEX IF NOT EXISTS idx_cell_hex_map_hex  ON cell_hex_map (hex_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Function tile cho pg_tileserv
-- Tự động chọn resolution theo zoom: z<=7 dùng res=7, z>7 dùng res=9
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION hex_density_tile(z int, x int, y int)
RETURNS bytea AS $$
DECLARE
    target_res SMALLINT := CASE WHEN z <= 7 THEN 7 ELSE 9 END;
    tile_geom  GEOMETRY := ST_TileEnvelope(z, x, y);
    result     bytea;
BEGIN
    SELECT ST_AsMVT(q, 'hex_coverage', 4096, 'geom')
    INTO result
    FROM (
        SELECT
            hex_id,
            count,
            total,
            ST_AsMVTGeom(h3_geom, tile_geom, 4096, 64, true) AS geom
        FROM hex_coverage
        WHERE res = target_res
          AND h3_geom && tile_geom
          AND count > 0
    ) q
    WHERE geom IS NOT NULL;

    RETURN COALESCE(result, ''::bytea);
END;
$$ LANGUAGE plpgsql STABLE PARALLEL SAFE;

COMMENT ON FUNCTION hex_density_tile(int, int, int)
    IS 'pg_tileserv function source: hex density heatmap, auto-selects H3 resolution by zoom';
