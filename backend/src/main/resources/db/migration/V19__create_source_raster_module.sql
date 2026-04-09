-- ============================================================
-- V19: Module quản lý file nguồn raster — source_raster_files, upload_sessions, source_file_mappings
-- ============================================================

-- File .bil/.hdr đã upload lên MinIO (1 cell chỉ có 1 bản active tại một thời điểm)
CREATE TABLE source_raster_files (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    cell_id         VARCHAR(50)   NOT NULL,
    bil_object_key  VARCHAR(1000) NOT NULL,         -- MinIO key: source/{cell_id}.bil
    hdr_object_key  VARCHAR(1000) NOT NULL,         -- MinIO key: source/{cell_id}.hdr
    file_size_bytes BIGINT,
    checksum_md5    VARCHAR(64),
    is_active       TINYINT(1)    NOT NULL DEFAULT 1, -- Chỉ 1 bản active/cell
    uploaded_by     VARCHAR(200)  NOT NULL,
    uploaded_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_srf_cell FOREIGN KEY (cell_id) REFERENCES cells(cell_id),
    INDEX idx_srf_cell      (cell_id),
    INDEX idx_srf_active    (cell_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

-- Session upload chunked (RAR/ZIP lớn) — hỗ trợ resume
CREATE TABLE upload_sessions (
    upload_id       VARCHAR(36)   NOT NULL PRIMARY KEY,  -- UUID
    filename        VARCHAR(500)  NOT NULL,
    file_size_bytes BIGINT        NOT NULL,
    total_chunks    INT           NOT NULL,
    chunk_size_bytes INT          NOT NULL,
    received_chunks TEXT                                 NOT NULL, -- Mảng chỉ số chunk đã nhận (JSON array)
    archive_type    VARCHAR(10)   NOT NULL,              -- RAR|ZIP
    network_type    VARCHAR(10)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'uploading', -- uploading|merging|complete|expired
    created_by      VARCHAR(200)  NOT NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at      DATETIME(3)   NOT NULL,              -- upload session TTL 24h
    completed_at    DATETIME(3),
    INDEX idx_upload_status  (status),
    INDEX idx_upload_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

-- Map thủ công: khi tên file không khớp với cell_id trong NIMS
-- (filename_stem != cell_id) → user tự map
CREATE TABLE source_file_mappings (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    filename_stem   VARCHAR(300)  NOT NULL UNIQUE, -- Tên file gốc (không có extension)
    cell_id         VARCHAR(50)   NOT NULL,        -- cell_id được map thủ công
    created_by      VARCHAR(200)  NOT NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_sfm_cell FOREIGN KEY (cell_id) REFERENCES cells(cell_id),
    INDEX idx_sfm_filename (filename_stem)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
