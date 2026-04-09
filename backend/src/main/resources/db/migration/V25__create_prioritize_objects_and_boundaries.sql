-- ============================================================
-- V25: Đối tượng ưu tiên UT1/UT2 & Polygon địa bàn & WorldPop raster
-- ============================================================

CREATE TABLE prioritize_objects (
    id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    object_code  VARCHAR(100)  UNIQUE,
    object_name  VARCHAR(300)  NOT NULL,
    object_type  VARCHAR(20)   NOT NULL,     -- UT1|UT2
    lat          DOUBLE        NOT NULL,
    lon          DOUBLE        NOT NULL,
    geom         GEOMETRY NOT NULL /*!80003 SRID 4326 */,
    address      VARCHAR(500),
    location_id  BIGINT,
    dept_id      BIGINT,
    is_active    TINYINT(1)    NOT NULL DEFAULT 1,
    created_by   VARCHAR(200)  NOT NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
    SPATIAL INDEX idx_pobj_geom        (geom),
    INDEX         idx_pobj_type        (object_type),
    INDEX         idx_pobj_active      (is_active),
    CONSTRAINT fk_pobj_location FOREIGN KEY (location_id) REFERENCES cat_location(LOCATION_ID),
    CONSTRAINT fk_pobj_dept     FOREIGN KEY (dept_id)     REFERENCES cat_department(DEPT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE prioritizes_boundaries (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    boundary_code  VARCHAR(100)  NOT NULL UNIQUE,
    boundary_name  VARCHAR(300)  NOT NULL,
    boundary_type  VARCHAR(20)   NOT NULL,   -- province|district|ward|custom
    parent_id      BIGINT,
    geom           GEOMETRY NOT NULL /*!80003 SRID 4326 */,
    area_km2       DOUBLE,
    geojson_key    VARCHAR(1000),            -- MinIO key file GeoJSON gốc
    created_by     VARCHAR(200)  NOT NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    SPATIAL INDEX idx_prioritizes_boundaries_geom   (geom),
    CONSTRAINT fk_prioritizes_boundaries_parent FOREIGN KEY (parent_id)
        REFERENCES prioritizes_boundaries(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE population_rasters (
    id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
    raster_name   VARCHAR(200)  NOT NULL,
    year          INT           NOT NULL,
    resolution_m  DOUBLE,
    minio_key     VARCHAR(1000) NOT NULL,
    is_active     TINYINT(1)    NOT NULL DEFAULT 1,
    uploaded_by   VARCHAR(200)  NOT NULL,
    uploaded_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_pop_raster_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
