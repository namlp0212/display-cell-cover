-- ============================================================
-- V23: Bổ sung trường cho simulations
--   - ephemeral: đánh dấu session temporary simulation (tự động dọn dẹp sau 24h)
--   - updated_at: track lần cuối cập nhật
-- ============================================================

ALTER TABLE simulations
    ADD COLUMN ephemeral  TINYINT(1)  NOT NULL DEFAULT 0
        COMMENT '1 = session tạm thời, tự dọn sau 24h nếu chưa được lưu'
        AFTER status,
    ADD COLUMN updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        AFTER created_at;

ALTER TABLE simulations
    ADD INDEX idx_sim_ephemeral_created (ephemeral, created_at);
