-- ============================================================
-- V28: What-If Sandbox baseline link + Greedy Optimization
-- ============================================================

-- Thêm liên kết kịch bản gốc cho sandbox
ALTER TABLE simulations
    ADD COLUMN baseline_sim_id CHAR(36) NULL AFTER ephemeral;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE optimization_jobs (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id               VARCHAR(36)   NOT NULL UNIQUE,
    uctt_sim_id          VARCHAR(36),             -- Kịch bản UCTT làm điểm xuất phát (NULL = real state)
    threshold_set_id     BIGINT,
    network_types        VARCHAR(50),             -- "4G,5G" — NULL = tất cả
    ut_object_ids        JSON,                    -- Danh sách UT object IDs đưa vào tối ưu
    boundary_ids         JSON,                    -- Danh sách boundary IDs để tính diện tích
    weight_ut1           DOUBLE        NOT NULL DEFAULT 0.4,
    weight_ut2           DOUBLE        NOT NULL DEFAULT 0.4,
    weight_area          DOUBLE        NOT NULL DEFAULT 0.2,
    max_recommendations  INT           NOT NULL DEFAULT 10,
    snapshot_on_cells    JSON,                    -- Snapshot tập cell đang ON tại lúc tạo job
    status               VARCHAR(20)   NOT NULL DEFAULT 'queued', -- queued|running|completed|failed
    total_candidates     INT           DEFAULT 0,
    processed            INT           DEFAULT 0,
    error_message        TEXT,
    triggered_by         VARCHAR(200)  NOT NULL,
    started_at           DATETIME(3),
    finished_at          DATETIME(3),
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_optjob_threshold FOREIGN KEY (threshold_set_id)
        REFERENCES kpi_threshold_sets(id),
    INDEX idx_optjob_status     (status),
    INDEX idx_optjob_created    (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE optimization_recommendations (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    job_id               VARCHAR(36)   NOT NULL,
    `rank`               INT           NOT NULL,   -- 1 = tốt nhất
    cell_id              VARCHAR(50)   NOT NULL,
    delta_ut1_covered    INT           DEFAULT 0,  -- Số UT1 được phủ thêm nếu bật cell này
    delta_ut2_covered    INT           DEFAULT 0,  -- Số UT2 được phủ thêm
    delta_area_km2       DOUBLE        DEFAULT 0,  -- Diện tích phủ thêm (km²)
    cumulative_ut1       INT           DEFAULT 0,  -- Tổng UT1 phủ nếu bật top-rank cells
    cumulative_ut2       INT           DEFAULT 0,
    cumulative_area_km2  DOUBLE        DEFAULT 0,
    impact_score         DOUBLE        NOT NULL,   -- w_ut1*ΔUT1 + w_ut2*ΔUT2 + w_area*Δarea (normalized)
    explanation          TEXT,                     -- Mô tả tự động (VD: "Phủ thêm 12 UT1, 3 UT2, 4.5 km²")
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_optrecc_job FOREIGN KEY (job_id)
        REFERENCES optimization_jobs(job_id) ON DELETE CASCADE,
    INDEX idx_optrecc_job_rank  (job_id, `rank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
