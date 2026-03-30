CREATE TABLE raster_coverages (
    id          BIGSERIAL PRIMARY KEY,
    cell_id     VARCHAR(255) NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,
    bbox        GEOMETRY(POLYGON, 4326) NOT NULL,
    crs         VARCHAR(64) NOT NULL DEFAULT 'EPSG:4326',
    is_svr      BOOLEAN NOT NULL DEFAULT FALSE,
    is_visible  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Spatial index on bbox
CREATE INDEX idx_raster_coverages_bbox ON raster_coverages USING GIST (bbox);

-- B-tree index on cell_id
CREATE INDEX idx_raster_coverages_cell_id ON raster_coverages (cell_id);

-- Partial spatial index for visible coverages only
CREATE INDEX idx_raster_coverages_bbox_visible ON raster_coverages USING GIST (bbox) WHERE is_visible = TRUE;
