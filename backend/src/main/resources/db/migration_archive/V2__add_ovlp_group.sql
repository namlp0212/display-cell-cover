-- Add overlap-group column used by the WMS CQL_FILTER in the frontend.
-- Each cell is assigned to one of 8 groups (0-7) based on its id,
-- so that GeoServer stacks 8 sub-layers to accumulate opacity in high-overlap areas.
ALTER TABLE raster_coverages
    ADD COLUMN IF NOT EXISTS ovlp_group INTEGER NOT NULL DEFAULT 0;

-- Assign groups for already-existing rows: group = (per-cell rank) % 8
-- Both rows of the same cell (continuous + SVR) get the same group.
UPDATE raster_coverages rc
SET ovlp_group = sub.grp
FROM (
    SELECT id,
           ((DENSE_RANK() OVER (ORDER BY cell_id) - 1) % 8)::INTEGER AS grp
    FROM raster_coverages
) sub
WHERE rc.id = sub.id;

CREATE INDEX IF NOT EXISTS idx_raster_coverages_ovlp_group
    ON raster_coverages (ovlp_group);
