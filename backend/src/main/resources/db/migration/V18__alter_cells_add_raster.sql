-- ============================================================
-- V18: Bổ sung cells — liên kết với infra_rasters, thêm row_status
-- row_status: 1 = active, 2 = đã xoá (soft delete từ NIMS)
-- ============================================================

ALTER TABLE cells
    ADD COLUMN raster_id       BIGINT      AFTER cell_id,
    ADD COLUMN row_status      TINYINT(1)  NOT NULL DEFAULT 1 AFTER synced_at,
    ADD COLUMN deleted_at      DATETIME(3) AFTER row_status,
    ADD COLUMN nims_updated_at DATETIME(3) AFTER deleted_at,
    ADD COLUMN updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3) AFTER nims_updated_at;

ALTER TABLE cells
    ADD CONSTRAINT fk_cells_raster FOREIGN KEY (raster_id) REFERENCES infra_rasters(id),
    ADD INDEX idx_cells_raster     (raster_id),
    ADD INDEX idx_cells_row_status (row_status);
