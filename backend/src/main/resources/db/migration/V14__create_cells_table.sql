-- Cell tower metadata
-- real_status is synced from the external alert system
CREATE TABLE cells (
    cell_id     VARCHAR(50)  NOT NULL,
    cell_name   VARCHAR(200),
    operator    VARCHAR(100),
    band        VARCHAR(20),
    geom        GEOMETRY     NOT NULL /*!80003 SRID 4326 */,
    real_status TINYINT(1)   NOT NULL DEFAULT 1,
    synced_at   DATETIME(3),
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (cell_id),
    SPATIAL INDEX idx_cells_geom (geom),
    INDEX idx_cells_status (real_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
