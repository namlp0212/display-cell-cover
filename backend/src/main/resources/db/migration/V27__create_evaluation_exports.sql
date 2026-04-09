-- ============================================================
-- V27: Lịch sử các lần export báo cáo từ evaluation jobs
-- ============================================================

CREATE TABLE evaluation_exports (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    report_id            VARCHAR(36)   NOT NULL UNIQUE,
    report_name          VARCHAR(500)  NOT NULL,
    job_id               VARCHAR(36),             -- Evaluation job nguồn
    sim_id               VARCHAR(36),             -- Kịch bản tại thời điểm xuất
    excel_key            VARCHAR(1000),            -- MinIO: evaluation-data/exports/{id}/report.xlsx
    map_screenshot_key   VARCHAR(1000),            -- MinIO: evaluation-data/exports/{id}/map.png
    screenshot_source    VARCHAR(10),              -- CLIENT|SERVER
    status               VARCHAR(20)   NOT NULL DEFAULT 'pending', -- pending|completed|failed
    error_message        TEXT,
    created_by           VARCHAR(200)  NOT NULL,
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_eval_exports_created (created_at),
    INDEX idx_eval_exports_status  (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
