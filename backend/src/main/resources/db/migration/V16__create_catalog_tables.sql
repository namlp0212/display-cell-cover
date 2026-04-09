-- ============================================================
-- V16: Catalog tables — Đơn vị (cat_department) & Địa bàn (cat_location)
-- Schema đồng bộ từ NIMS — giữ nguyên tên cột NIMS (UPPER_CASE)
-- ============================================================

CREATE TABLE cat_department (
    DEPT_ID           BIGINT        NOT NULL,            -- PK từ NIMS (không AUTO_INCREMENT)
    TENANT_ID         BIGINT,
    DEPT_CODE         VARCHAR(50),                       -- Mã đơn vị
    DEPT_NAME         VARCHAR(255),                      -- Tên đơn vị
    PARENT_ID         BIGINT,                            -- Đơn vị cha (NULL = root)
    -- Materialized path — duyệt cây không cần JOIN đệ quy
    PATH              VARCHAR(255)  COMMENT 'path id',
    PATH_CODE         VARCHAR(255)  COMMENT 'path mã đơn vị',
    PATH_NAME         VARCHAR(255)  COMMENT 'path tên đơn vị',
    -- Cấp tổ chức: 1=quốc gia, 2=khu vực, 3=tỉnh, 4=quận/huyện, 5=phường/xã
    DEPARTMENT_LEVEL  INT(2),
    -- Denormalized location fields (lấy từ NIMS, không cần JOIN)
    COUNTRY_NAME      VARCHAR(255),
    AREA_NAME         VARCHAR(255),
    PROVINCE_NAME     VARCHAR(255),
    DISTRICT_NAME     VARCHAR(255),
    COUNTRY_CODE      VARCHAR(255),
    AREA_CODE         VARCHAR(255),
    PROVINCE_CODE     VARCHAR(255),
    DISTRICT_CODE     VARCHAR(255),
    COUNTRY_ID        BIGINT,
    AREA_ID           BIGINT,
    PROVINCE_ID       BIGINT,
    DISTRICT_ID       BIGINT,
    -- Trạng thái bản ghi: 1=Active, 2=Inactive
    ROW_STATUS        INT(2)        COMMENT 'Trạng thái bản ghi: 1-Active; 2-Inactive',
    CREATE_TIME       DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (DEPT_ID),
    KEY idx_dept_tenant   (TENANT_ID),
    KEY idx_dept_parent   (PARENT_ID),
    KEY idx_dept_code     (DEPT_CODE),
    KEY idx_dept_path     (PATH),
    KEY idx_dept_path_code (PATH_CODE),
    FULLTEXT KEY idx_dept_name (DEPT_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bảng đơn vị tổ chức — sync từ NIMS';

-- ─────────────────────────────────────────────────────────────

CREATE TABLE cat_location (
    LOCATION_ID       BIGINT        NOT NULL,            -- PK từ NIMS
    TENANT_ID         BIGINT        NOT NULL,
    LOCATION_CODE     VARCHAR(50)   NOT NULL             COMMENT 'Mã địa bàn',
    LOCATION_NAME     VARCHAR(100)  NOT NULL             COMMENT 'Tên địa bàn',
    PARENT_ID         BIGINT                             COMMENT 'Id địa bàn cha',
    -- Materialized path
    PATH_ID           VARCHAR(255),
    PATH_CODE         VARCHAR(255)  COMMENT 'path mã địa bàn',
    PATH_NAME         VARCHAR(255)  COMMENT 'path tên địa bàn',
    -- Cấp địa bàn: 1=COUNTRY, 2=KV, 3=Tỉnh, 4=Quận/huyện, 5=Phường/xã, 6=Thôn/tổ
    LOCATION_LEVEL    INT(2)        COMMENT '1=COUNTRY,2=KV,3=Tỉnh,4=Quận/huyện,5=Phường/xã,6=Thôn/tổ',
    -- Denormalized fields
    COUNTRY_NAME      VARCHAR(255),
    AREA_NAME         VARCHAR(255),
    PROVINCE_NAME     VARCHAR(255),
    DISTRICT_NAME     VARCHAR(255),
    VILLAGE_NAME      VARCHAR(255),
    RURAL_NAME        VARCHAR(255),
    COUNTRY_CODE      VARCHAR(255),
    AREA_CODE         VARCHAR(255),
    PROVINCE_CODE     VARCHAR(255),
    DISTRICT_CODE     VARCHAR(255),
    VILLAGE_CODE      VARCHAR(255),
    RURAL_CODE        VARCHAR(255),
    COUNTRY_ID        BIGINT,
    AREA_ID           BIGINT,
    PROVINCE_ID       BIGINT,
    DISTRICT_ID       BIGINT,
    VILLAGE_ID        BIGINT,
    RURAL_ID          BIGINT,
    -- Phân loại địa hình / hành chính (NIMS)
    AREA_TERRAIN          BIGINT,
    COUNTRY_TERRAIN       BIGINT,
    DISTRICT_AD_LEVEL     BIGINT,
    DISTRICT_TERRAIN      BIGINT,
    ADMINISTRATIVE_LEVEL  BIGINT,
    PROVINCE_TERRAIN      BIGINT,
    RURAL_AD_LEVEL        BIGINT,
    RURAL_TERRAIN         BIGINT,
    SALARY_AREA           BIGINT,
    TERRAIN               BIGINT,
    VILLAGE_AD_LEVEL      BIGINT,
    VILLAGE_TERRAIN       BIGINT,
    is_new                TINYINT(4),
    -- Trạng thái bản ghi: 1=Active, 2=Inactive
    ROW_STATUS        INT(2)        COMMENT 'Trạng thái bản ghi: 1-Active; 2-Inactive',
    CREATE_TIME       DATETIME,
    UPDATE_TIME       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (LOCATION_ID),
    KEY idx_location_tenant         (TENANT_ID),
    KEY idx_location_parent         (PARENT_ID),
    KEY idx_location_code           (LOCATION_CODE),
    KEY idx_location_path_id        (PATH_ID),
    KEY idx_location_path_code      (PATH_CODE),
    KEY idx_location_row_status     (ROW_STATUS),
    KEY idx_location_level          (LOCATION_LEVEL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bảng địa bàn hành chính — sync từ NIMS';
