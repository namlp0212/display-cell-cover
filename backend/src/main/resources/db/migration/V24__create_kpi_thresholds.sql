-- ============================================================
-- V24: Ngưỡng KPI — kpi_threshold_sets & kpi_thresholds
-- ============================================================

CREATE TABLE kpi_threshold_sets (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    set_name    VARCHAR(200) NOT NULL,
    description TEXT,
    is_default  TINYINT(1)   NOT NULL DEFAULT 0,
    created_by  VARCHAR(200) NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                             ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_kpi_set_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE kpi_thresholds (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    set_id          BIGINT      NOT NULL,
    object_type     VARCHAR(20) NOT NULL,   -- UT1|UT2
    metric          VARCHAR(20) NOT NULL,   -- RSRP|RSRQ
    good_threshold  DOUBLE      NOT NULL,   -- dBm — signal ≥ good → "tốt"
    fair_threshold  DOUBLE      NOT NULL,   -- dBm — signal ≥ fair → "trung bình"
    poor_threshold  DOUBLE      NOT NULL,   -- dBm — signal ≥ poor → "yếu"; < poor → không phủ
    unit            VARCHAR(10) NOT NULL DEFAULT 'dBm',
    reference_std   VARCHAR(100),           -- VD: "3GPP TS 38.133"
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_kpi_threshold_set FOREIGN KEY (set_id)
        REFERENCES kpi_threshold_sets(id) ON DELETE CASCADE,
    UNIQUE KEY uq_kpi_set_type_metric (set_id, object_type, metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed: bộ ngưỡng mặc định theo 3GPP TS 38.133 (NR/5G)
INSERT INTO kpi_threshold_sets (set_name, description, is_default, created_by)
VALUES ('Mặc định — 3GPP TS 38.133', 'Ngưỡng RSRP mặc định cho NR (5G)', 1, 'system');

INSERT INTO kpi_thresholds (set_id, object_type, metric, good_threshold, fair_threshold, poor_threshold, reference_std)
VALUES
    (1, 'UT1', 'RSRP', -95, -110, -125, '3GPP TS 38.133'),
    (1, 'UT2', 'RSRP', -100, -115, -130, '3GPP TS 38.133');
