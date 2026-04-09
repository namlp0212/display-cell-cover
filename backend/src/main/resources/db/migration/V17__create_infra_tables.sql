-- ============================================================
-- V17: Hạ tầng — Nhà trạm (infra_stations) & Raster/Sector (infra_rasters)
-- Schema đồng bộ từ NIMS — giữ nguyên tên cột NIMS (UPPER_CASE)
-- ============================================================

-- Nhà trạm (site) — mỗi trạm thuộc một đơn vị và một địa bàn
CREATE TABLE infra_stations (
    STATION_ID                  BIGINT          NOT NULL,           -- PK từ NIMS (không AUTO_INCREMENT)
    STATION_CODE                VARCHAR(50),                        -- Mã trạm
    GEOMETRY                    GEOMETRY        NOT NULL            COMMENT 'Tọa độ',
    -- Phân loại trạm
    STATION_TYPE_CODE           VARCHAR(100)                        COMMENT 'Mã loại trạm',
    STATION_TYPE_NAME           VARCHAR(100)                        COMMENT 'Tên loại trạm',
    HOUSE_STATION_TYPE_NAME     VARCHAR(100)                        COMMENT 'Loại nhà trạm',
    STATION_LEVEL_NAME          VARCHAR(100)                        COMMENT 'Phân lớp trạm',
    STATION_FEATURE_NAME        VARCHAR(100)                        COMMENT 'Tính chất nhà trạm',
    DESCRIPTION                 VARCHAR(500)                        COMMENT 'Mô tả',
    ADDRESS                     VARCHAR(200)                        COMMENT 'Địa chỉ',
    TERRAIN                     VARCHAR(100)                        COMMENT 'Phân loại địa hình',
    -- Liên kết đơn vị / địa bàn
    DEPT_ID                     BIGINT                              COMMENT 'Đơn vị - ref cat_department',
    LOCATION_ID                 BIGINT                              COMMENT 'Địa bàn - ref cat_location',
    -- Thông tin hạ tầng
    POSITION                    INT(2)                              COMMENT 'Vị trí: 1=Trạm dưới đất; 2=Trạm trên mái; 3=Trạm trong nhà',
    TRANS_TYPE                  INT(2)                              COMMENT 'Loại truyền dẫn: 1=Cáp đồng; 2=Cáp quang; 3=Viba; 4=Vsat',
    IS_SRC_RING                 INT(2)          DEFAULT 0           COMMENT 'Trạm đầu ring: 1=Có; 0=Không',
    COMMAND_POST                INT(2)                              COMMENT 'Trạm sở chỉ huy (cat_item)',
    IS_RING_ABIS                INT(2)                              COMMENT 'Trạm khai báo vòng hồi Abis2: 1=Có; 0=Không',
    IS_SOS_STATUS               INT(2)                              COMMENT 'Trạng thái có PA UCTT: 1=Active; 2=Inactive',
    TIME_ACCU                   DOUBLE                              COMMENT 'Thời gian xả accu (giờ)',
    IS_ATS                      INT(2)                              COMMENT 'Trạm có ATS: 0=Không; 1=Có',
    ATS_STATUS                  INT(2)                              COMMENT 'Hiện trạng ATS: 1=Tốt; 0=Không dùng/hỏng',
    IS_HOUSE_PUT_GENERATOR      INT(2)                              COMMENT 'Trạm có nhà đặt máy nổ: 1=Có; 0=Không',
    PROVINCE_POWER_LINE_NAME    VARCHAR(200)                        COMMENT 'Tên lộ điện 110kV mức Tỉnh',
    DISTRICT_POWER_LINE_NAME    VARCHAR(200)                        COMMENT 'Tên lộ điện 10/22/35kV mức Huyện',
    HOUSE_OWNER_NAME            VARCHAR(400)                        COMMENT 'Chủ nhà trạm',
    HOUSE_OWNER_PHONE           VARCHAR(100)                        COMMENT 'Số điện thoại chủ nhà trạm',
    POWER_NOMINAL               DOUBLE                              COMMENT 'Tổng công suất trạm (KW)',
    ACCESS_MOBILE_DEVICE        VARCHAR(100)                        COMMENT 'Thiết bị di động',
    CDBR_DEVICE                 VARCHAR(100)                        COMMENT 'Thiết bị CDBR',
    TRAN_DEVICE                 VARCHAR(100)                        COMMENT 'Thiết bị truyền dẫn',
    EMPLOYEE_STAFF_CODE         VARCHAR(100)                        COMMENT 'Mã nhân viên quản lý trạm',
    STATION_STAFF               VARCHAR(100)                        COMMENT 'Nhân viên quản lý nhà trạm',
    STATION_STAFF_PHONE         VARCHAR(20)                         COMMENT 'SĐT nhân viên quản lý nhà trạm',
    -- Loại trạm PCTT: 1=MACRO, 2=RRU, 3=SMALLCELL, 4=REPEATER, 5=FEMTO_PICO, 6=DRILLING_RIG
    pctt_station_solution_type  SMALLINT(6),
    old_location_id             BIGINT                              COMMENT 'Địa bàn cũ: ref cat_location',
    -- Trạng thái & thời gian
    ROW_STATUS                  INT(1)                              COMMENT 'Trạng thái bản ghi NIMS: 1=Active; 2=Inactive',
    SYNC_TIME_NIMS              TIMESTAMP       NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    USER_UPDATE                 VARCHAR(200)                        COMMENT 'Người cập nhật gần nhất',
    CREATE_TIME                 TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Ngày tạo',
    UPDATE_TIME                 TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Ngày cập nhật gần nhất',
    PRIMARY KEY (STATION_ID),
    KEY idx_stations_dept      (DEPT_ID),
    KEY idx_stations_location  (LOCATION_ID),
    KEY idx_stations_code      (STATION_CODE),
    KEY idx_stations_sync_time (SYNC_TIME_NIMS),
    SPATIAL KEY idx_stations_geom (GEOMETRY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bảng danh mục nhà trạm — sync từ NIMS';

-- ─────────────────────────────────────────────────────────────

-- Raster/Sector — mỗi sector thuộc một trạm
-- Một trạm có thể có nhiều sector (theo loại mạng, hướng ăng-ten...)
CREATE TABLE infra_rasters (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    raster_code     VARCHAR(100) NOT NULL UNIQUE,  -- Mã raster/sector từ NIMS
    raster_name     VARCHAR(300),
    station_id      BIGINT       NOT NULL,
    network_type    VARCHAR(10)  NOT NULL,          -- 2G|3G|4G|5G
    azimuth         DOUBLE,                         -- Góc phương vị (độ)
    tilt            DOUBLE,                         -- Electrical tilt (độ)
    power_dbm       DOUBLE,                         -- Công suất phát
    row_status      TINYINT(1)   NOT NULL DEFAULT 1,-- 1=active, 2=deleted
    nims_updated_at DATETIME(3),
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_raster_station FOREIGN KEY (station_id) REFERENCES infra_stations(STATION_ID),
    INDEX idx_rasters_station      (station_id),
    INDEX idx_rasters_network_type (network_type),
    INDEX idx_rasters_row_status   (row_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
