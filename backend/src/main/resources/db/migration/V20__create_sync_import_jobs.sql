-- ============================================================
-- V20: Import jobs
-- NOTE: nims_sync_log đã bỏ — log đồng bộ hiển thị trực tiếp cho người dùng
--       (thời gian cập nhật cuối lưu vào file cấu hình, không lưu DB)
-- ============================================================

-- Job upload & giải nén file nguồn (RAR/ZIP/SINGLE)
CREATE TABLE import_jobs (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id          VARCHAR(36)   NOT NULL UNIQUE,
    job_type        VARCHAR(20)   NOT NULL,   -- RAR_UPLOAD|ZIP_UPLOAD|SINGLE_UPLOAD
    network_type    VARCHAR(10)   NOT NULL,
    triggered_by    VARCHAR(200)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'queued', -- queued|running|completed|failed
    total_files     INT           DEFAULT 0,
    processed       INT           DEFAULT 0,
    succeeded       INT           DEFAULT 0,
    failed_count    INT           DEFAULT 0,
    archive_filename VARCHAR(500),            -- Tên file gốc (nếu RAR/ZIP)
    archive_object_key VARCHAR(1000),         -- MinIO key của file đã merge
    started_at      DATETIME(3),
    finished_at     DATETIME(3),
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_import_job_status  (status),
    INDEX idx_import_job_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Chi tiết file lỗi trong mỗi import job
CREATE TABLE import_job_errors (
    id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id        VARCHAR(36)   NOT NULL,
    filename      VARCHAR(500)  NOT NULL,
    cell_id       VARCHAR(50),              -- NULL nếu không resolve được
    error_type    VARCHAR(50)   NOT NULL,   -- FILE_NOT_MATCHED|VALIDATE_HDR|READ_BIL|MINIO_UPLOAD
    error_message TEXT,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ije_job FOREIGN KEY (job_id) REFERENCES import_jobs(job_id),
    INDEX idx_ije_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
