-- ============================================================
-- V21: Pipeline xử lý — BIL/HDR → COG + H3 (tách riêng khỏi import)
-- ============================================================

-- Job convert BIL→COG và build H3 index
CREATE TABLE processing_jobs (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id          VARCHAR(36)   NOT NULL UNIQUE,
    trigger_type    VARCHAR(30)   NOT NULL,   -- MANUAL|AUTO_AFTER_IMPORT
    network_type    VARCHAR(10),              -- NULL = xử lý tất cả network types
    cell_ids        JSON,                     -- NULL = xử lý tất cả cells có source file
    status          VARCHAR(20)   NOT NULL DEFAULT 'queued', -- queued|running|completed|failed
    total_cells     INT           DEFAULT 0,
    processed       INT           DEFAULT 0,
    succeeded       INT           DEFAULT 0,
    failed_count    INT           DEFAULT 0,
    triggered_by    VARCHAR(200)  NOT NULL,
    started_at      DATETIME(3),
    finished_at     DATETIME(3),
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_proc_job_status  (status),
    INDEX idx_proc_job_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Chi tiết lỗi mỗi cell trong processing job
CREATE TABLE processing_job_errors (
    id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id        VARCHAR(36)   NOT NULL,
    cell_id       VARCHAR(50)   NOT NULL,
    error_stage   VARCHAR(50)   NOT NULL,   -- COG_CONVERT|MINIO_UPLOAD|H3_INDEX_BUILD
    error_message TEXT,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_pje_job FOREIGN KEY (job_id) REFERENCES processing_jobs(job_id),
    INDEX idx_pje_job     (job_id),
    INDEX idx_pje_cell_id (cell_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
