-- ============================================================
-- SEED: Đơn vị → Địa bàn → Nhà trạm → Raster → Cell
-- Dữ liệu giả lập cho vùng Đà Nẵng + Quảng Nam
-- ============================================================

-- ── 1. cat_department ─────────────────────────────────────────
INSERT INTO cat_department
  (DEPT_ID, DEPT_CODE, DEPT_NAME, PARENT_ID, PATH, PATH_CODE, PATH_NAME, DEPARTMENT_LEVEL,
   PROVINCE_NAME, PROVINCE_CODE, ROW_STATUS)
VALUES
  (1, 'VTT',     'Tập đoàn Viettel',        NULL, '/1',       '/VTT',              '/Tập đoàn Viettel',                                      1, NULL,       NULL,  1),
  (2, 'VTT-MT',  'Viettel Khu vực miền Trung', 1, '/1/2',    '/VTT/VTT-MT',       '/Tập đoàn Viettel/Viettel Khu vực miền Trung',           2, NULL,       NULL,  1),
  (3, 'VTT-DN',  'Viettel Đà Nẵng',          2,   '/1/2/3',  '/VTT/VTT-MT/VTT-DN','/Tập đoàn Viettel/Viettel Khu vực miền Trung/Viettel Đà Nẵng', 3, 'Đà Nẵng',  'DN',  1),
  (4, 'VTT-QNM', 'Viettel Quảng Nam',        2,   '/1/2/4',  '/VTT/VTT-MT/VTT-QNM','/Tập đoàn Viettel/Viettel Khu vực miền Trung/Viettel Quảng Nam', 3, 'Quảng Nam','QNM', 1)
ON DUPLICATE KEY UPDATE DEPT_NAME = VALUES(DEPT_NAME);

-- ── 2. cat_location ──────────────────────────────────────────
INSERT INTO cat_location
  (LOCATION_ID, TENANT_ID, LOCATION_CODE, LOCATION_NAME, PARENT_ID,
   PATH_ID, PATH_CODE, PATH_NAME, LOCATION_LEVEL, PROVINCE_NAME, PROVINCE_CODE, ROW_STATUS)
VALUES
  (1, 1, 'VN',  'Việt Nam',    NULL, '/1',     '/VN',          '/Việt Nam',                        1, NULL,       NULL,  1),
  (2, 1, 'MT',  'Miền Trung',  1,    '/1/2',   '/VN/MT',       '/Việt Nam/Miền Trung',             2, NULL,       NULL,  1),
  (3, 1, 'DN',  'Đà Nẵng',     2,    '/1/2/3', '/VN/MT/DN',    '/Việt Nam/Miền Trung/Đà Nẵng',    3, 'Đà Nẵng',  'DN',  1),
  (4, 1, 'QNM', 'Quảng Nam',   2,    '/1/2/4', '/VN/MT/QNM',   '/Việt Nam/Miền Trung/Quảng Nam',  3, 'Quảng Nam','QNM', 1)
ON DUPLICATE KEY UPDATE LOCATION_NAME = VALUES(LOCATION_NAME);

-- ── 3. infra_stations ─────────────────────────────────────────
-- Toạ độ lưu theo convention hiện tại (lat lon) = WKT POINT(lat lon)
INSERT INTO infra_stations
  (STATION_ID, STATION_CODE, GEOMETRY, DEPT_ID, LOCATION_ID,
   STATION_TYPE_NAME, ADDRESS, ROW_STATUS)
VALUES
  (1001, 'DN-HC-01', ST_GeomFromText('POINT(16.0680 108.2200)'), 3, 3, 'MACRO', '12 Trần Phú, Hải Châu, Đà Nẵng',      1),
  (1002, 'DN-TK-01', ST_GeomFromText('POINT(16.0830 108.1930)'), 3, 3, 'MACRO', '45 Nguyễn Tri Phương, Thanh Khê, Đà Nẵng', 1),
  (1003, 'DN-ST-01', ST_GeomFromText('POINT(16.1030 108.2450)'), 3, 3, 'MACRO', '18 Phạm Văn Đồng, Sơn Trà, Đà Nẵng', 1),
  (1004, 'DN-NHS-01',ST_GeomFromText('POINT(16.0130 108.2250)'), 3, 3, 'MACRO', '22 Trường Sa, Ngũ Hành Sơn, Đà Nẵng', 1),
  (1005, 'DN-LC-01', ST_GeomFromText('POINT(16.0930 108.1530)'), 3, 3, 'MACRO', '5 Hoàng Văn Thái, Liên Chiểu, Đà Nẵng',1),
  (2001, 'QNM-HA-01',ST_GeomFromText('POINT(15.8800 108.3350)'), 4, 4, 'MACRO', '88 Trần Hưng Đạo, Hội An, Quảng Nam', 1),
  (2002, 'QNM-TK-01',ST_GeomFromText('POINT(15.5730 108.4730)'), 4, 4, 'MACRO', '15 Hùng Vương, Tam Kỳ, Quảng Nam',   1)
ON DUPLICATE KEY UPDATE STATION_CODE = VALUES(STATION_CODE);

-- ── 4. infra_rasters ─────────────────────────────────────────
-- 3 rasters/station Đà Nẵng (4G, 5G, 3G) + 2/station Quảng Nam (4G, 3G)
INSERT INTO infra_rasters
  (id, raster_code, raster_name, station_id, network_type, azimuth, tilt, power_dbm, row_status)
VALUES
  -- Trạm Hải Châu
  (1,  'DN-HC-01-4G',   'Hải Châu 4G Sector 1',   1001, '4G', 0,   3.0, 43.0, 1),
  (2,  'DN-HC-01-5G',   'Hải Châu 5G Sector 1',   1001, '5G', 120, 3.0, 46.0, 1),
  (3,  'DN-HC-01-3G',   'Hải Châu 3G Sector 1',   1001, '3G', 240, 4.0, 40.0, 1),
  -- Trạm Thanh Khê
  (4,  'DN-TK-01-4G',   'Thanh Khê 4G Sector 1',  1002, '4G', 0,   3.0, 43.0, 1),
  (5,  'DN-TK-01-5G',   'Thanh Khê 5G Sector 1',  1002, '5G', 120, 3.0, 46.0, 1),
  (6,  'DN-TK-01-3G',   'Thanh Khê 3G Sector 1',  1002, '3G', 240, 4.0, 40.0, 1),
  -- Trạm Sơn Trà
  (7,  'DN-ST-01-4G',   'Sơn Trà 4G Sector 1',    1003, '4G', 0,   3.0, 43.0, 1),
  (8,  'DN-ST-01-5G',   'Sơn Trà 5G Sector 1',    1003, '5G', 120, 3.0, 46.0, 1),
  (9,  'DN-ST-01-3G',   'Sơn Trà 3G Sector 1',    1003, '3G', 240, 4.0, 40.0, 1),
  -- Trạm Ngũ Hành Sơn
  (10, 'DN-NHS-01-4G',  'Ngũ Hành Sơn 4G S1',     1004, '4G', 0,   3.0, 43.0, 1),
  (11, 'DN-NHS-01-5G',  'Ngũ Hành Sơn 5G S1',     1004, '5G', 120, 3.0, 46.0, 1),
  (12, 'DN-NHS-01-3G',  'Ngũ Hành Sơn 3G S1',     1004, '3G', 240, 4.0, 40.0, 1),
  -- Trạm Liên Chiểu
  (13, 'DN-LC-01-4G',   'Liên Chiểu 4G Sector 1', 1005, '4G', 0,   3.0, 43.0, 1),
  (14, 'DN-LC-01-5G',   'Liên Chiểu 5G Sector 1', 1005, '5G', 120, 3.0, 46.0, 1),
  (15, 'DN-LC-01-3G',   'Liên Chiểu 3G Sector 1', 1005, '3G', 240, 4.0, 40.0, 1),
  -- Trạm Hội An
  (16, 'QNM-HA-01-4G',  'Hội An 4G Sector 1',     2001, '4G', 0,   3.0, 43.0, 1),
  (17, 'QNM-HA-01-3G',  'Hội An 3G Sector 1',     2001, '3G', 120, 4.0, 40.0, 1),
  -- Trạm Tam Kỳ
  (18, 'QNM-TK-01-4G',  'Tam Kỳ 4G Sector 1',     2002, '4G', 0,   3.0, 43.0, 1),
  (19, 'QNM-TK-01-3G',  'Tam Kỳ 3G Sector 1',     2002, '3G', 120, 4.0, 40.0, 1)
ON DUPLICATE KEY UPDATE raster_name = VALUES(raster_name);

-- ── 5. Gán cells → rasters ───────────────────────────────────
-- Phân bổ EDN cells (2280) đều cho 15 rasters Đà Nẵng (~152/raster)
-- Phân bổ EQM/QNM cells cho 4 rasters Quảng Nam

-- Tạo bảng tạm để gán theo thứ tự
SET @r := 0;

UPDATE cells c
JOIN (
  SELECT cell_id,
    CASE
      WHEN @r < 152  THEN 1
      WHEN @r < 304  THEN 2
      WHEN @r < 456  THEN 3
      WHEN @r < 608  THEN 4
      WHEN @r < 760  THEN 5
      WHEN @r < 912  THEN 6
      WHEN @r < 1064 THEN 7
      WHEN @r < 1216 THEN 8
      WHEN @r < 1368 THEN 9
      WHEN @r < 1520 THEN 10
      WHEN @r < 1672 THEN 11
      WHEN @r < 1824 THEN 12
      WHEN @r < 1976 THEN 13
      WHEN @r < 2128 THEN 14
      ELSE 15
    END AS raster_id,
    @r := @r + 1 AS rn
  FROM cells
  WHERE row_status = 1 AND LEFT(cell_id, 3) = 'EDN'
  ORDER BY cell_id
) t ON c.cell_id = t.cell_id
SET c.raster_id = t.raster_id;

-- EQM cells → rasters 16,17 (Hội An)
SET @r2 := 0;
UPDATE cells c
JOIN (
  SELECT cell_id,
    CASE WHEN @r2 % 2 = 0 THEN 16 ELSE 17 END AS raster_id,
    @r2 := @r2 + 1 AS rn
  FROM cells
  WHERE row_status = 1 AND LEFT(cell_id, 3) = 'EQM'
  ORDER BY cell_id
) t ON c.cell_id = t.cell_id
SET c.raster_id = t.raster_id;

-- QNM cells → rasters 18,19 (Tam Kỳ)
SET @r3 := 0;
UPDATE cells c
JOIN (
  SELECT cell_id,
    CASE WHEN @r3 % 2 = 0 THEN 18 ELSE 19 END AS raster_id,
    @r3 := @r3 + 1 AS rn
  FROM cells
  WHERE row_status = 1 AND LEFT(cell_id, 3) = 'QNM'
  ORDER BY cell_id
) t ON c.cell_id = t.cell_id
SET c.raster_id = t.raster_id;

-- gDN cells → raster 1
UPDATE cells SET raster_id = 1
WHERE row_status = 1 AND LEFT(cell_id, 3) = 'gDN' AND raster_id IS NULL;
