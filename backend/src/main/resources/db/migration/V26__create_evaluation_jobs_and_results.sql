-- ============================================================
-- V26: Evaluation jobs & results (điểm + vùng)
-- ============================================================

CREATE TABLE evaluation_jobs (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    job_id            VARCHAR(36)  NOT NULL UNIQUE,
    job_type          VARCHAR(20)  NOT NULL,   -- POINT|AREA|COMPARISON
    sim_id            VARCHAR(36),             -- Kịch bản A (NULL = real state)
    sim_id_b          VARCHAR(36),             -- Kịch bản B (chỉ dùng cho COMPARISON)
    network_types     VARCHAR(50),             -- "4G,5G" — NULL = tất cả
    threshold_set_id  BIGINT,
    population_raster_id BIGINT,              -- Dùng cho job type AREA
    status            VARCHAR(20)  NOT NULL DEFAULT 'queued',
    total_items       INT          DEFAULT 0,
    processed         INT          DEFAULT 0,
    error_message     TEXT,
    triggered_by      VARCHAR(200) NOT NULL,
    started_at        DATETIME(3),
    finished_at       DATETIME(3),
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_eval_threshold  FOREIGN KEY (threshold_set_id)
        REFERENCES kpi_threshold_sets(id),
    CONSTRAINT fk_eval_pop_raster FOREIGN KEY (population_raster_id)
        REFERENCES population_rasters(id),
    INDEX idx_eval_status (status),
    INDEX idx_eval_type   (job_type),
    INDEX idx_eval_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE evaluation_results_point (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    job_id          VARCHAR(36) NOT NULL,
    prioritize_object_id BIGINT      NOT NULL,
    rsrp_dbm             DOUBLE,                 -- NULL = không phủ
    best_cell_id         VARCHAR(50),            -- Cell cho tín hiệu tốt nhất
    result               VARCHAR(20) NOT NULL,   -- GOOD|FAIR|POOR|NO_COVERAGE
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_erp_job  FOREIGN KEY (job_id)               REFERENCES evaluation_jobs(job_id),
    CONSTRAINT fk_erp_pobj FOREIGN KEY (prioritize_object_id) REFERENCES prioritize_objects(id),
    INDEX idx_erp_job    (job_id),
    INDEX idx_erp_result (result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE evaluation_results_area (
    id                       BIGINT      AUTO_INCREMENT PRIMARY KEY,
    job_id                   VARCHAR(36) NOT NULL,
    boundary_id              BIGINT      NOT NULL,
    sim_label                VARCHAR(5)  NOT NULL DEFAULT 'A', -- A|B (cho COMPARISON)
    -- Diện tích
    total_area_km2           DOUBLE,
    covered_area_km2         DOUBLE,
    good_area_km2            DOUBLE,
    fair_area_km2            DOUBLE,
    poor_area_km2            DOUBLE,
    coverage_area_pct        DOUBLE,
    -- Dân số
    total_population         BIGINT,
    covered_population       BIGINT,
    coverage_population_pct  DOUBLE,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_era_job      FOREIGN KEY (job_id)      REFERENCES evaluation_jobs(job_id),
    CONSTRAINT fk_era_boundary FOREIGN KEY (boundary_id) REFERENCES prioritizes_boundaries(id),
    INDEX idx_era_job      (job_id),
    INDEX idx_era_boundary (boundary_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
