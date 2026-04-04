# Kiến trúc H3 + ClickHouse — Hệ thống hiển thị vùng phủ sóng cell

> **Phiên bản:** 1.1  
> **Ngày:** 2026-04-04  
> **Trạng thái:** Đã triển khai (xem mục 12 — Ghi chú triển khai thực tế)

---

## 1. Bối cảnh và mục tiêu

### 1.1 Bài toán

Hệ thống hiển thị vùng phủ sóng cell viễn thông phục vụ **công tác ứng cứu thông tin**, với các yêu cầu:

- Hiển thị trạng thái phủ sóng **thực tế** đồng bộ từ hệ thống cảnh báo
- **Giả lập kịch bản ứng cứu**: cho trước danh sách cell bị off → xem bản đồ phủ sóng bị ảnh hưởng như thế nào
- **So sánh** trạng thái thực tế vs kịch bản giả lập
- Hiển thị mượt mà từ zoom toàn quốc (zoom 6) đến chi tiết cấp phố (zoom 19)
- Scale được cho **toàn bộ lãnh thổ Việt Nam**

### 1.2 Hạn chế của kiến trúc hiện tại (COG + GeoServer)

| Vấn đề | Nguyên nhân |
|--------|-------------|
| GeoServer nghẽn ở zoom thấp | Zoom 13: ~1,101 cells/tile × 100 users = quá tải |
| Không scale toàn quốc | GeoServer ImageMosaic không xử lý được hàng tỷ pixel |
| Simulation phức tạp | COG renderer phải re-read toàn bộ file khi toggle |
| Infrastructure nặng | MinIO + GeoServer + Java pixel renderer |

### 1.3 Mục tiêu kiến trúc mới

- Thay GeoServer + Java pixel renderer bằng **H3 hexagon + ClickHouse + MVT**
- Tile query < 100ms ở mọi zoom level, mọi quy mô dữ liệu
- Simulation không mutate database — chỉ thay đổi filter tại query time
- Một pipeline build H3 index từ COG/BIL file, chạy offline

---

## 2. Technology Stack

```
┌─────────────────────────────────────────────────────────────────────┐
│  FRONTEND                                                           │
│  Leaflet + deck.gl H3HexagonLayer                                   │
│  • Render H3 hexagon MVT tiles (zoom 6–19)                         │
│  • Toggle Coverage / Signal mode                                    │
│  • Dropdown kịch bản giả lập                                       │
│  • WebSocket nhận alert sync real-time                              │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP (MVT tiles) / WebSocket
┌──────────────────────────▼──────────────────────────────────────────┐
│  BACKEND — Spring Boot                                              │
│  /api/h3-tile/{z}/{x}/{y}?mode=coverage|signal&sim={id}            │
│  /api/simulation         CRUD kịch bản ứng cứu                     │
│  /api/sync/alerts        nhận trạng thái từ hệ thống cảnh báo      │
│  /api/cells              quản lý cell metadata                      │
│  WebSocket               push alert sync xuống client              │
└──────────┬───────────────┬───────────────────┬──────────────────────┘
           │               │                   │
┌──────────▼───────┐ ┌─────▼───────────┐ ┌────▼─────────────┐
│    MariaDB       │ │   ClickHouse    │ │     Redis        │
│                  │ │                 │ │                  │
│ cells            │ │ cell_h3_coverage│ │ Cache:           │
│ simulations      │ │ (immutable)     │ │  effective_cells │
│ sim_overrides    │ │ res 5–13        │ │  per sim_id      │
│ ~1,100 rows      │ │ ~400M rows      │ │                  │
│                  │ │ (covered area)  │ │ Pub/Sub:         │
│ ACID transact.   │ │ Columnar scan   │ │  alert sync      │
│ cell toggle      │ │ < 100ms/query   │ │  tile invalidate │
└──────────────────┘ └─────────────────┘ └──────────────────┘
```

### 2.1 Vai trò từng thành phần

| Công nghệ | Vai trò | Lý do chọn |
|-----------|---------|------------|
| **ClickHouse** | Lưu H3 index toàn bộ vùng phủ sóng (read-only) | Columnar, range scan 400M rows < 100ms, không cần update |
| **MariaDB** | Cell metadata, simulation CRUD, real_status | ACID, transactional update khi toggle/alert sync; đội đang vận hành sẵn |
| **Redis** | Cache effective_cells theo sim_id, pub/sub | Sub-millisecond lookup, WebSocket bridge cho alert |
| **Spring Boot** | API gateway, tile rendering, alert consumer | Stack hiện tại, tái sử dụng |
| **deck.gl H3HexagonLayer** | Render hexagon MVT phía client | Native H3 support, WebGL — xử lý 5,000+ hex/tile mượt |
| **H3 res 5–13** | Multi-resolution coverage index | Độ chính xác phù hợp từng zoom level |

---

## 3. H3 Resolution — Zoom Level Mapping

### 3.1 Công thức tính toán

```
Tại zoom z, latitude Vietnam (16°N):

  Tile width (km) = (360 / 2^z) × 111.32 × cos(16°)
                  = (360 / 2^z) × 107.01

  Features/tile   = Tile_area (km²) / H3_cell_area (km²)

  Hex size (px)   = (H3_short_diameter_km / Tile_width_km) × 256
```

**Ràng buộc:**
- `Features/tile ≤ 5,000` — giới hạn MapboxGL / deck.gl
- `Hex size ≥ 3.5px` — hexagon vẫn nhìn thấy trên màn hình

### 3.2 Bảng tính chi tiết

| Zoom | Tile width (km) | H3 Res | Cell area (km²) | Features/tile | Hex size (px) | Phạm vi |
|------|----------------|--------|----------------|--------------|---------------|---------|
| 6  | 601.9 | **5**  | 252.903   | ~1,432 | 6.3 px | Toàn quốc |
| 7  | 301.0 | **6**  | 36.129    | ~2,507 | 4.8 px | Vùng/Miền |
| 8  | 150.5 | **7**  | 5.161     | ~4,389 | 3.6 px | Tỉnh |
| 9  | 75.24 | **7**  | 5.161     | ~1,098 | 7.2 px | Tỉnh |
| 10 | 37.62 | **8**  | 0.7373    | ~1,921 | 5.4 px | Huyện |
| 11 | 18.81 | **9**  | 0.10533   | ~3,358 | 4.1 px | Thị xã |
| 12 | 9.405 | **9**  | 0.10533   | ~840   | 8.2 px | Thị xã |
| 13 | 4.702 | **10** | 0.015047  | ~1,469 | 6.2 px | Phường/Xã |
| 14 | 2.351 | **11** | 0.0021496 | ~2,570 | 4.7 px | Khu phố |
| 15 | 1.176 | **12** | 0.00030713| ~4,499 | 3.6 px | Block nhà |
| 16 | 0.588 | **12** | 0.00030713| ~1,125 | 7.1 px | Block nhà |
| 17 | 0.294 | **13** | 0.000043874| ~1,969 | 5.4 px | Chi tiết |
| 18 | 0.147 | **13** | 0.000043874| ~492  | 10.7 px | Chi tiết |
| 19 | 0.074 | **13** | 0.000043874| ~123  | 21.5 px | Chi tiết ~4m |

### 3.3 H3 Resolution tham chiếu

| Res | Area trung bình (km²) | Short diameter (km) | Tương đương |
|-----|----------------------|--------------------|-|
| 5  | 252.903  | 14.798 | Cấp huyện lớn |
| 6  | 36.129   | 5.593  | Cấp xã lớn |
| 7  | 5.161    | 2.114  | Cấp thôn |
| 8  | 0.7373   | 0.7993 | Khu dân cư |
| 9  | 0.10533  | 0.3020 | Block phố |
| 10 | 0.015047 | 0.1141 | Tòa nhà lớn |
| 11 | 0.0021496| 0.04314| Vài nhà |
| 12 | 0.00030713| 0.01631| ~9m |
| 13 | 0.000043874| 0.006166| ~4m (pixel COG) |

### 3.4 Mapping code trong Backend

```java
public static int h3ResForZoom(int z) {
    if (z <= 6)  return 5;
    if (z == 7)  return 6;
    if (z <= 9)  return 7;
    if (z == 10) return 8;
    if (z <= 12) return 9;
    if (z == 13) return 10;
    if (z == 14) return 11;
    if (z <= 16) return 12;
    return 13;  // zoom 17–19
}
```

---

## 4. Data Model

### 4.1 MariaDB

> **Yêu cầu tối thiểu:** MariaDB 10.4+ (hỗ trợ SRID, CHECK constraint, SPATIAL INDEX trên InnoDB)

```sql
-- Cell tower metadata
CREATE TABLE cells (
    cell_id      VARCHAR(50) PRIMARY KEY,           -- EDN000231, QNM0814_1, ...
    geom         GEOMETRY NOT NULL SRID 4326,       -- vùng phủ sóng (polygon)
    cell_name    VARCHAR(200),
    operator     VARCHAR(100),
    band         VARCHAR(20),                       -- 2G / 4G / 5G
    real_status  BOOLEAN NOT NULL DEFAULT TRUE,     -- đồng bộ từ alert system
    synced_at    DATETIME(3),                       -- lần cuối nhận từ alert
    created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    SPATIAL INDEX idx_cells_geom (geom)
) ENGINE=InnoDB;

CREATE INDEX idx_cells_status ON cells(real_status);

-- Kịch bản ứng cứu thông tin
-- Lưu ý: UUID sinh tại application layer (không dùng gen_random_uuid())
CREATE TABLE simulations (
    id           CHAR(36) PRIMARY KEY,              -- UUID sinh từ Java UUID.randomUUID()
    name         VARCHAR(500) NOT NULL,             -- "Phương án bão số 3"
    description  TEXT,
    created_by   VARCHAR(200),
    status       ENUM('active', 'ended') DEFAULT 'active',
    created_at   DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    ended_at     DATETIME(3)
) ENGINE=InnoDB;

-- Danh sách cell bị off trong kịch bản
CREATE TABLE simulation_overrides (
    simulation_id CHAR(36) NOT NULL,
    cell_id       VARCHAR(50) NOT NULL,
    forced_status BOOLEAN NOT NULL DEFAULT FALSE,   -- FALSE = giả lập off
    PRIMARY KEY (simulation_id, cell_id),
    CONSTRAINT fk_sim FOREIGN KEY (simulation_id)
        REFERENCES simulations(id) ON DELETE CASCADE,
    CONSTRAINT fk_cell FOREIGN KEY (cell_id)
        REFERENCES cells(cell_id)
) ENGINE=InnoDB;

CREATE INDEX idx_sim_overrides_sim ON simulation_overrides(simulation_id);
```

**Lưu ý khác biệt so với PostgreSQL:**

| PostgreSQL | MariaDB | Ghi chú |
|---|---|---|
| `TEXT` | `VARCHAR(N)` / `TEXT` | Dùng VARCHAR khi biết max length |
| `GEOMETRY(POLYGON, 4326)` | `GEOMETRY NOT NULL SRID 4326` | SRID được enforce từ MariaDB 10.4 |
| `TIMESTAMPTZ` | `DATETIME(3)` | Không có timezone — handle TZ trong application |
| `UUID PRIMARY KEY DEFAULT gen_random_uuid()` | `CHAR(36) PRIMARY KEY` | UUID sinh trong Java: `UUID.randomUUID().toString()` |
| `TEXT DEFAULT 'active' CHECK (...)` | `ENUM('active','ended')` | ENUM gọn hơn CHECK cho trường hợp này |
| `USING GIST` | `SPATIAL INDEX` (InnoDB R-tree) | Đủ hiệu quả cho ~1,100 rows |

### 4.2 ClickHouse

```sql
-- Bảng chính: mỗi row = 1 tower phủ 1 H3 cell tại 1 resolution
-- KHÔNG bao giờ UPDATE — chỉ INSERT khi import cell mới
CREATE TABLE cell_h3_coverage (
    h3_res      UInt8,                          -- 5 / 6 / 7 / ... / 13
    h3_index    UInt64,                         -- H3 cell index (spatially sorted)
    cell_id     LowCardinality(String),         -- EDN tower ID (~1100 giá trị duy nhất)
    max_signal  Float32                         -- dBm, từ COG file
)
ENGINE = MergeTree()
ORDER BY (h3_res, h3_index, cell_id)            -- primary sort: spatial range scan
SETTINGS index_granularity = 8192;

-- Bloom filter để tăng tốc filter theo cell_id (dùng trong simulation)
ALTER TABLE cell_h3_coverage
    ADD INDEX idx_cell_id (cell_id)
    TYPE bloom_filter(0.01)
    GRANULARITY 4;

-- Partitioning theo h3_res để query riêng từng resolution
-- (ClickHouse MergeTree tự xử lý qua ORDER BY, không cần partition riêng)
```

**Ước tính storage (vùng phủ sóng thực tế ~5,500 km² từ 1,100 towers):**

```
Res 5–10 : tổng ~110M rows
Res 11   : ~750M rows
Res 12   : ~35M rows   (chỉ 5,500 km² covered)
Res 13   : ~250M rows  (chỉ 5,500 km² covered)
─────────────────────────────────────────────
Tổng     ≈ ~1.1B rows  (~16 GB compressed)

Scale toàn quốc (50% coverage):
Res 12+13: ~14.7B rows (~220 GB) — cần hardware planning
```

### 4.3 Redis Key Schema

```
effective_cells:real              → Set<cell_id>  (real_status = TRUE)
effective_cells:sim:{sim_id}:on   → Set<cell_id>  (ON trong kịch bản)
effective_cells:sim:{sim_id}:off  → Set<cell_id>  (OFF trong kịch bản)
tile_cache:{z}:{x}:{y}:{mode}:{sim_id} → bytes   (MVT cache, TTL 60s)
```

---

## 5. Use Cases

### UC-01: Xem bản đồ phủ sóng thực tế

```
Actor   : Người dùng
Trigger : Mở bản đồ / pan / zoom
Pre     : Không có simulation đang active

Flow:
  1. Frontend: GET /api/h3-tile/{z}/{x}/{y}?mode=coverage
  2. Backend:
     a. Tính bbox từ z/x/y (Web Mercator → WGS84)
     b. h3_res = h3ResForZoom(z)
     c. on_cells  ← Redis GET effective_cells:real
        (miss → MariaDB: SELECT cell_id WHERE real_status = TRUE)
     d. bbox_h3_range ← H3 cells giao với bbox (polyfill)
        -- MariaDB spatial query để lấy cell_id trong viewport:
        -- SELECT cell_id FROM cells
        -- WHERE MBRIntersects(geom, ST_GeomFromText('POLYGON((...))'))
     e. Query ClickHouse:
          SELECT h3_index,
                 countIf(cell_id IN on_cells)   AS on_density,
                 maxIf(max_signal, cell_id IN on_cells) AS on_signal,
                 countIf(cell_id NOT IN on_cells) AS off_density
          FROM cell_h3_coverage
          WHERE h3_res = h3_res
            AND h3_index BETWEEN bbox_min AND bbox_max
            AND cell_id IN (on_cells ∪ off_cells_in_bbox)
          GROUP BY h3_index
     f. Encode kết quả → MVT (Mapbox Vector Tile protobuf)
     g. Cache vào Redis, TTL 60s
  3. Frontend deck.gl render hexagon:
     - on_density > 0 → màu density ramp (xanh nhạt → xanh đậm)
     - on_density = 0, off_density > 0 → đỏ (#FF0000, opacity 0.55)
     - không có row → transparent

Output: Bản đồ hexagon hiển thị vùng phủ sóng thực tế
```

### UC-02: Xem bản đồ cường độ tín hiệu

```
Actor   : Người dùng
Trigger : Chuyển mode sang Signal

Flow:
  1. Giống UC-01 nhưng dùng on_signal thay on_density
  2. Frontend tô màu theo SLD ramp:
       -170 dBm → navy blue  (#00204D)
       -150 dBm → dark blue  (#00336F)
       -130 dBm → blue       (#1F4E79)
       -110 dBm → teal       (#2C788E)
        -95 dBm → green-blue (#5FA060)
        -85 dBm → yellow     (#9DBA46)
        -75 dBm → orange     (#D2CE3E)
          0 dBm → yellow     (#FDE725)
  3. Cell off (on_density = 0, off_density > 0) → đỏ

Output: Bản đồ hexagon màu sắc theo cường độ tín hiệu
```

### UC-03: Đồng bộ trạng thái cell từ Alert System

```
Actor   : Alert System (external service)
Trigger : Cell bị sự cố hoặc khôi phục

Flow:
  1. Alert System: POST /api/sync/alerts
     Body: { "cell_id": "EDN000231", "status": false, "timestamp": "..." }
  2. Backend:
     a. UPDATE cells SET real_status = false, synced_at = now()
        WHERE cell_id = 'EDN000231'
     b. Redis DEL effective_cells:real      ← invalidate cache
     c. Redis DEL tile_cache:*              ← invalidate tất cả tile cache
     d. WebSocket PUBLISH "cell_status_changed"
        { cell_id, status, timestamp }  →  tất cả connected clients
  3. Frontend nhận event → re-fetch tile đang hiển thị trong viewport

Output: Bản đồ cập nhật trạng thái thực tế trong < 2s
SLA   : Alert → bản đồ cập nhật < 5s
```

### UC-04: Tạo kịch bản ứng cứu thông tin

```
Actor   : Cán bộ lập phương án
Trigger : Nhận danh sách cell sự cố trong tình huống giả định

Flow:
  1. POST /api/simulation
     Body: {
       "name": "Bão số 3 - tỉnh Quảng Ngãi",
       "description": "Mất điện diện rộng, dự kiến 15 trạm bị ảnh hưởng",
       "cells_off": ["EDN000231", "EDN000411", "EDN000431", ...]
     }
  2. Backend:
     a. INSERT simulations → trả về sim_id (UUID)
     b. INSERT simulation_overrides (sim_id, cell_id, forced_status=false)
        cho từng cell trong cells_off
     c. Tính:
        real_on_cells = Redis GET effective_cells:real
        sim_on_cells  = real_on_cells - cells_off
        sim_off_cells = real_off_cells ∪ cells_off
     d. Redis SET effective_cells:sim:{sim_id}:on  = sim_on_cells
        Redis SET effective_cells:sim:{sim_id}:off = sim_off_cells
        TTL = 24h (tự hết hạn nếu quên end simulation)
  3. Trả về { sim_id, name, cells_off_count }

Output : Kịch bản được tạo, sẵn sàng xem giả lập
```

### UC-05: Xem bản đồ trong kịch bản giả lập

```
Actor   : Cán bộ lập phương án
Trigger : Chọn kịch bản từ dropdown trên UI

Flow:
  1. Frontend: GET /api/h3-tile/{z}/{x}/{y}?mode=coverage&sim={sim_id}
  2. Backend:
     a. on_cells  ← Redis GET effective_cells:sim:{sim_id}:on
     b. off_cells ← Redis GET effective_cells:sim:{sim_id}:off
     c. Query ClickHouse với on_cells / off_cells (giống UC-01)
  3. Frontend render:
     - Hexagon đang ON → xanh (coverage) hoặc gradient (signal)
     - Hexagon chỉ có OFF cell → đỏ (mất phủ sóng)

Output: Bản đồ thể hiện tác động khi các cell trong kịch bản bị mất

Lưu ý: ClickHouse KHÔNG bị thay đổi — chỉ thay đổi on_cells filter
```

### UC-06: So sánh thực tế vs kịch bản

```
Actor   : Cán bộ lập phương án
Trigger : Bật chế độ "So sánh"

Flow:
  1. Frontend gọi song song (2 request):
     GET /api/h3-tile/{z}/{x}/{y}?mode=coverage          → tile thực tế
     GET /api/h3-tile/{z}/{x}/{y}?mode=coverage&sim={id} → tile kịch bản
  2. Frontend merge 2 tile:

     real.on > 0  &  sim.on > 0  →  XANH   (không bị ảnh hưởng)
     real.on > 0  &  sim.on = 0  →  CAM    (mất phủ sóng trong kịch bản)
     real.on = 0  &  sim.off > 0 →  ĐỎ     (vốn đã mất sóng ngoài thực tế)
     không có row                →  transparent

Output: Visualize rõ vùng mất phủ sóng khi xảy ra sự cố

Color legend:
  🟢 Xanh  — Phủ sóng bình thường, không bị ảnh hưởng
  🟠 Cam   — Mất phủ sóng khi kịch bản xảy ra (điểm yếu)
  🔴 Đỏ   — Vốn đã không có phủ sóng (sự cố thực tế)
  ⬜ Trắng — Không có hạ tầng
```

### UC-07: Kết thúc giả lập

```
Actor   : Cán bộ lập phương án
Trigger : Nhấn "Kết thúc giả lập"

Flow:
  1. POST /api/simulation/{id}/end
  2. Backend:
     a. UPDATE simulations SET status='ended', ended_at=now()
     b. Redis DEL effective_cells:sim:{id}:on
        Redis DEL effective_cells:sim:{id}:off
        Redis DEL tile_cache:*:*:*:*:{id}
  3. Frontend xóa sim param, trở về UC-01 (real state)

Output: Bản đồ trở về trạng thái thực tế
```

### UC-08: Import cell mới

```
Actor   : Admin
Trigger : Có file BIL/TIF mới từ hệ thống đo kiểm

Flow:
  1. Admin: POST /api/admin/import (multipart BIL file)
     hoặc chạy pipeline script trực tiếp
  2. Java pipeline (H3IndexPipelineService):
     a. Convert BIL → COG (GeoTools hoặc GDAL)
     b. Mở COG bằng GeoTools GeoTiffReader, đọc từng pixel có giá trị (non-NODATA)
     c. Với mỗi pixel → tính H3 index tại res 5, 6, 7, 8, 9, 10, 11, 12, 13
     d. Group by (h3_res, h3_index) → lấy MAX signal
     e. Bulk INSERT vào ClickHouse qua JDBC batch
        (ReplacingMergeTree xử lý duplicate nếu re-import)
  3. Backend:
     a. INSERT cells (cell_id, geom, ...) vào MariaDB
     b. Redis DEL effective_cells:real
     c. Redis DEL tile_cache:*
  4. WebSocket PUSH "cell_imported" → client refresh

Output: Cell mới xuất hiện trên bản đồ, sẵn sàng trong mọi kịch bản
```

---

## 6. Logic hiển thị ON/OFF cells

### 6.1 Ba trạng thái của 1 H3 hexagon

```
╔══════════════════════════════════════════════════════════════╗
║  Với mỗi H3 cell trong viewport:                            ║
║                                                              ║
║  on_density ≥ 1                →  coverage / signal color   ║
║  on_density = 0, off_density ≥ 1  →  ĐỎ (cell đang off)   ║
║  on_density = 0, off_density = 0  →  transparent            ║
║                                                              ║
║  QUAN TRỌNG: on ≥ 1 VÀ off ≥ 1  →  màu bình thường        ║
║  (vẫn còn cell đang hoạt động phủ vùng đó)                  ║
╚══════════════════════════════════════════════════════════════╝
```

### 6.2 ClickHouse query (1 lần cho cả ON và OFF)

```sql
SELECT
    h3_index,

    -- Coverage mode
    countIf(cell_id IN (:on_cells))                          AS on_density,

    -- Signal mode
    maxIf(max_signal, cell_id IN (:on_cells))                AS on_signal,

    -- Off-cell indicator
    countIf(cell_id IN (:off_cells))                         AS off_density

FROM cell_h3_coverage
WHERE h3_res   = :h3_res
  AND h3_index BETWEEN :bbox_h3_min AND :bbox_h3_max
  AND cell_id  IN (:on_cells_union_off_cells)   -- scan 1 lần

GROUP BY h3_index
```

### 6.3 Xác định on_cells và off_cells theo mode

```
MODE: Thực tế (sim = null)
  ┌─────────────────────────────────────────────────┐
  │ on_cells  = { cell_id | real_status = TRUE  }   │
  │ off_cells = { cell_id | real_status = FALSE }   │
  └─────────────────────────────────────────────────┘

MODE: Simulation (sim = sim_id)
  ┌─────────────────────────────────────────────────┐
  │ real_on   = { cell_id | real_status = TRUE }    │
  │ sim_off   = simulation_overrides(sim_id)        │
  │                                                 │
  │ on_cells  = real_on  ∖  sim_off                 │
  │ off_cells = real_off ∪  sim_off                 │
  └─────────────────────────────────────────────────┘
```

### 6.4 Frontend color logic (JavaScript)

```javascript
// Coverage mode
function getCoverageColor(feature) {
    const { on_density, off_density } = feature.properties;

    if (on_density > 0) {
        // Density ramp: 1 cell = nhạt, 5+ cells = đậm
        const idx = Math.min(on_density - 1, DENSITY_COLORS.length - 1);
        return DENSITY_COLORS[idx];   // blue → green
    }
    if (off_density > 0) {
        return [255, 0, 0, 140];      // đỏ, opacity 0.55
    }
    return [0, 0, 0, 0];              // transparent
}

// Signal mode
function getSignalColor(feature) {
    const { on_signal, off_density } = feature.properties;

    if (on_signal > SIGNAL_FLOOR) {
        return interpolateSignalRamp(on_signal);   // -170 dBm → 0 dBm
    }
    if (off_density > 0) {
        return [255, 0, 0, 140];
    }
    return [0, 0, 0, 0];
}

// Comparison mode (real vs simulation)
function getComparisonColor(real, sim) {
    const realOn = real.on_density > 0;
    const simOn  = sim.on_density  > 0;

    if (realOn  && simOn)  return [0,   200, 0,   180];  // xanh
    if (realOn  && !simOn) return [255, 140, 0,   200];  // cam — mất trong kịch bản
    if (!realOn && sim?.off_density > 0)
                           return [255, 0,   0,   140];  // đỏ — vốn đã mất
    return [0, 0, 0, 0];
}
```

---

## 7. Data Pipeline: COG/BIL → H3 ClickHouse (Java)

> **Ghi chú:** Pipeline sử dụng hoàn toàn Java (Spring Boot service), tận dụng các thư viện đã có sẵn trong `pom.xml`:
> - `org.geotools:gt-geotiff` — đọc COG/GeoTIFF
> - `com.uber:h3:4.1.1` — tính H3 index
> - ClickHouse JDBC — bulk insert
> - Không cần môi trường Python, không cần thư viện ngoài

### 7.1 H3IndexPipelineService (Java)

```java
@Service
public class H3IndexPipelineService {

    private static final float  NODATA      = -3.4028235e+38f;
    private static final int[]  RESOLUTIONS = {5, 6, 7, 8, 9, 10, 11, 12, 13};
    private static final int    BATCH_SIZE  = 1_000_000;

    private final H3Core         h3;
    private final DataSource     clickHouseDataSource;
    private final String         cogDir;
    private final ExecutorService executor;

    // Progress tracking
    private final AtomicInteger  processedFiles  = new AtomicInteger(0);
    private final AtomicInteger  totalFiles      = new AtomicInteger(0);
    private volatile boolean     running         = false;

    /**
     * Build H3 index cho một cell.
     * Đọc COG → pixel array → H3 multi-res → bulk insert ClickHouse.
     */
    public void buildForCell(String cellId) throws Exception {
        Path tifPath = Path.of(cogDir, cellId + "_svr.tif");

        // 1. Đọc COG bằng GeoTools
        GeoTiffReader reader = new GeoTiffReader(tifPath.toFile());
        GridCoverage2D coverage = reader.read(null);
        RenderedImage image = coverage.getRenderedImage();
        int width  = image.getWidth();
        int height = image.getHeight();
        float[] pixels = new float[width * height];
        image.getData().getSamples(0, 0, width, height, 0, pixels);

        // 2. Lấy transform để tính lon/lat từ pixel (row, col)
        MathTransform gridToWorld = coverage.getGridGeometry()
                                            .getGridToCRS2D(PixelInCell.CELL_CENTER);

        // 3. Duyệt từng pixel hợp lệ → H3 index tại 9 resolution
        Map<Long, float[]> rowsByKey = new HashMap<>();
        // key = h3_res * 10^16 + h3_index (packed long pair)

        List<long[]> rowBuffer = new ArrayList<>();  // [h3_res, h3_index_long, max_signal_bits]

        Map<String, Float> maxByKey = new HashMap<>();  // "res:h3idx" → maxSignal

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                float signal = pixels[row * width + col];
                if (signal == NODATA || !Float.isFinite(signal)) continue;

                // pixel → world coords
                DirectPosition2D grid = new DirectPosition2D(col, row);
                DirectPosition2D world = new DirectPosition2D();
                gridToWorld.transform(grid, world);
                double lon = world.x;
                double lat = world.y;

                for (int res : RESOLUTIONS) {
                    String h3Idx = h3.latLngToCellAddress(lat, lon, res);
                    String key   = res + ":" + h3Idx;
                    maxByKey.merge(key, signal, Math::max);
                }
            }
        }

        // 4. Chuẩn bị batch rows: (h3_res, h3_index UInt64, cell_id, max_signal)
        List<Object[]> batch = new ArrayList<>(maxByKey.size());
        for (Map.Entry<String, Float> e : maxByKey.entrySet()) {
            String[] parts   = e.getKey().split(":", 2);
            int      res     = Integer.parseInt(parts[0]);
            long     h3Long  = Long.parseUnsignedLong(parts[1], 16);
            batch.add(new Object[]{res, h3Long, cellId, e.getValue()});
        }

        // 5. Bulk insert vào ClickHouse (batch 1M rows)
        insertBatch(batch);

        reader.dispose();
    }

    /**
     * Build H3 index cho toàn bộ COG files — chạy async.
     */
    @Async
    public CompletableFuture<Void> buildAll() {
        if (running) throw new IllegalStateException("Pipeline đang chạy");
        running = true;

        List<File> tifFiles = Arrays.stream(
                new File(cogDir).listFiles(f -> f.getName().endsWith("_svr.tif"))
        ).collect(Collectors.toList());

        totalFiles.set(tifFiles.size());
        processedFiles.set(0);

        List<CompletableFuture<Void>> futures = tifFiles.stream()
            .map(f -> CompletableFuture.runAsync(() -> {
                String cellId = f.getName().replace("_svr.tif", "");
                try {
                    buildForCell(cellId);
                    processedFiles.incrementAndGet();
                } catch (Exception ex) {
                    log.error("Failed to process cell {}: {}", cellId, ex.getMessage());
                }
            }, executor))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> running = false);
    }

    public PipelineStatus getStatus() {
        return new PipelineStatus(running, processedFiles.get(), totalFiles.get());
    }

    private void insertBatch(List<Object[]> rows) throws SQLException {
        // Chia nhỏ nếu quá BATCH_SIZE
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<Object[]> chunk = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            try (Connection conn = clickHouseDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cell_h3_coverage (h3_res,h3_index,cell_id,max_signal) VALUES (?,?,?,?)")) {
                for (Object[] row : chunk) {
                    ps.setInt(1, (int) row[0]);
                    ps.setLong(2, (long) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setFloat(4, (float) row[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }
}
```

### 7.2 Admin endpoints

```
POST /api/admin/build-h3-index
     → Trigger buildAll() async
     Response: { jobId, status: "started", totalFiles: 1100 }

POST /api/admin/build-h3-index/{cellId}
     → Trigger buildForCell(cellId) sync
     Response: { cellId, rowsInserted: 45231, durationMs: 1800 }

GET  /api/admin/build-h3-index/status
     Response: { running, processedFiles, totalFiles, progressPct }
```

### 7.3 Thời gian ước tính build

```
1,100 COG files × ~113,000 pixels/file × 9 resolutions
= ~1.1 tỷ H3 lookups

Java H3 (JNI) ~150K lookups/s per thread, 8 threads → ~10 phút
Insert 400M rows vào ClickHouse batch (JDBC) → ~20 phút

Tổng: ~30 phút cho lần build đầu (tương đương Python)
Re-import 1 cell: ~2 giây

Ưu điểm Java so với Python:
- Không cần cài Python env, rasterio, h3-py
- Chạy ngay trong Spring Boot (REST trigger, progress API)
- CompletableFuture pool dùng lại thread pool của server
- Dùng GeoTools + H3 Java đã có sẵn trong pom.xml
```

---

## 8. API Design

### 8.1 Tile endpoint

```
GET /api/h3-tile/{z}/{x}/{y}
    ?mode=coverage|signal    (required)
    &sim={uuid}              (optional, nếu đang xem kịch bản)

Routing theo tile zoom z (tile z ≠ Leaflet zoom — xem mục 12.1):

  z  6–16 → H3TileService        → Content-Type: application/vnd.mapbox-vector-tile
  z 17–19 → PixelCoverageService  → Content-Type: image/png
           / PixelMaxSignalService   (đọc COG từ MinIO, render PNG 256×256)

Response chung:
  Cache-Control: public, max-age=60

MVT layer "coverage" (z 6–16):
  Feature properties:
    h3_index:    UInt64  (H3 cell index)
    on_density:  Int32   (số ON cells phủ)
    on_signal:   Float32 (max signal từ ON cells, dBm)
    off_density: Int32   (số OFF cells phủ)

PNG (z 17–19):
  - PixelCoverageService: mỗi pixel tô màu theo on_density (coverage mode)
  - PixelMaxSignalService: mỗi pixel tô màu theo max_signal (signal mode)
  - Đọc binary COG ({cellId}_svr.tif) / continuous COG ({cellId}.tif) từ MinIO
  - NoContent (204) nếu viewport không có cell nào
```

### 8.2 Simulation endpoints

```
POST   /api/simulation
       Body: { name, description, cells_off: [cell_id, ...] }
       Response: { id, name, cells_off_count }

GET    /api/simulation
       Response: [{ id, name, status, created_at, cells_off_count }]

GET    /api/simulation/{id}
       Response: { id, name, cells_off: [cell_id, ...], status }

POST   /api/simulation/{id}/end
       Response: 200 OK

DELETE /api/simulation/{id}
       Response: 204 No Content
```

### 8.3 Alert sync endpoint

```
POST /api/sync/alerts
     Body: {
       "cell_id":   "EDN000231",
       "status":    false,         // true = ON, false = OFF
       "timestamp": "2026-04-03T10:30:00Z",
       "reason":    "power_outage" // optional
     }
     Response: 200 OK

POST /api/sync/alerts/batch
     Body: [{ cell_id, status, timestamp }, ...]
     Response: { updated: 15, failed: 0 }
```

---

## 9. Luồng dữ liệu tổng thể

```
[BIL/COG files]
     │
     │ Java pipeline — H3IndexPipelineService (offline, ~30 phút)
     │ POST /api/admin/build-h3-index → buildAll()
     ▼
[ClickHouse: cell_h3_coverage]
     │                        ↑
     │                        │ never mutated after import
     │                        │
     └─────────────────────────┘

[Alert System]
     │ POST /api/sync/alerts
     ▼
[MariaDB: cells.real_status]
     │ UPDATE
     ▼
[Redis: effective_cells:real]      ← invalidate + rebuild
     │
     ├──────────────────────────────────┐
     │                                  │
     │                          [WebSocket PUSH]
     │                          → Frontend refresh
     │
[API Request: GET /api/h3-tile]
     │
     ├── Redis GET effective_cells → on_cells, off_cells
     │   (miss → MariaDB query)
     │
     ├── ClickHouse query (bbox H3 range + cell filter)
     │   < 100ms
     │
     └── Encode MVT → Frontend
```

---

## 10. Lộ trình triển khai

### Phase 1 — Nền tảng (2 tuần)

- [ ] Cài đặt ClickHouse (Docker hoặc standalone)
- [ ] Tạo schema ClickHouse `cell_h3_coverage`
- [ ] Implement `H3IndexPipelineService` (Java) trong Spring Boot
- [ ] Expose admin endpoints: `POST /api/admin/build-h3-index`, `GET .../status`
- [ ] Chạy build H3 index từ 1,100 COG files hiện có
- [ ] Verify dữ liệu: query ClickHouse vs query COG trực tiếp

### Phase 2 — Backend API (2 tuần)

- [ ] Cấu hình Spring Boot kết nối MariaDB (thay PostgreSQL):
  ```yaml
  # application.yml
  spring:
    datasource:
      url: jdbc:mariadb://${DB_HOST:localhost}:3306/${DB_NAME:cellcover}
      driver-class-name: org.mariadb.jdbc.Driver
      username: ${DB_USER:cellcover}
      password: ${DB_PASSWORD:cellcover}
    jpa:
      properties:
        hibernate:
          dialect: org.hibernate.dialect.MariaDB10Dialect
  ```
  ```xml
  <!-- pom.xml: thay postgresql driver -->
  <dependency>
      <groupId>org.mariadb.jdbc</groupId>
      <artifactId>mariadb-java-client</artifactId>
  </dependency>
  ```
- [ ] Cập nhật Flyway migrations: đổi sang MariaDB SQL syntax
- [ ] Thêm ClickHouseService vào Spring Boot
- [ ] Implement `/api/h3-tile` endpoint (MVT encoding)
- [ ] Implement `h3ResForZoom()` mapping
- [ ] Thêm Redis cache cho effective_cells và tile
- [ ] Migrate simulation API từ MariaDB overlay sang Redis cache

### Phase 3 — Frontend (1 tuần)

- [ ] Thêm deck.gl dependency
- [ ] Implement H3HexagonLayer thay TileLayer hiện tại
- [ ] Color logic: coverage mode, signal mode, comparison mode
- [ ] Dropdown chọn kịch bản giả lập
- [ ] WebSocket listener cho alert sync

### Phase 4 — Tối ưu & Kiểm thử (1 tuần)

- [ ] Load test 100 concurrent users (k6)
- [ ] Verify coverage accuracy: H3 res 13 vs COG pixel
- [ ] Đo tile latency ở các zoom level
- [ ] Tắt GeoServer + Java COG renderer
- [ ] Document và bàn giao

### Milestone chấp nhận

| Tiêu chí | Ngưỡng |
|----------|--------|
| Tile latency p95 (zoom 6–14) | < 200ms |
| Tile latency p95 (zoom 15–19) | < 500ms |
| Alert → bản đồ cập nhật | < 5s |
| Simulation create → tile | < 2s |
| Error rate dưới 100 users | < 1% |

---

## 11. Rủi ro và phương án dự phòng

| Rủi ro | Xác suất | Tác động | Phương án |
|--------|----------|----------|-----------|
| ClickHouse query > 100ms ở res 13 | Trung bình | Cao | Thêm pre-aggregated materialized view |
| MVT tile > 500KB ở zoom 15-16 | Thấp | Trung bình | Giảm xuống res 11 cho zoom 15 |
| Java pipeline chậm (> 2 giờ) | Thấp | Thấp | Tăng thread pool size, tách job theo batch cell_id |
| ClickHouse không có trên server | Cao | Cao | Fallback về MariaDB + spatial query trực tiếp (chậm hơn, chỉ ~1,100 rows nên chấp nhận được) |
| H3 hexagon kém chính xác ở zoom 17+ | Trung bình | Thấp | Giữ COG renderer cho zoom 17+ |

---

---

## 12. Ghi chú triển khai thực tế (2026-04-04)

### 12.1 Lỗi zoom offset: `@maplibre/maplibre-gl-leaflet` áp dụng `-1` zoom

**Triệu chứng:** Ở Leaflet zoom 18–19, layer pixel-raster không hiển thị dù backend trả về PNG hợp lệ.

**Nguyên nhân gốc:** Plugin `@maplibre/maplibre-gl-leaflet` (v0.1.x) áp dụng offset `-1` khi đồng bộ zoom từ Leaflet sang MapLibre GL:

```javascript
// leaflet-maplibre-gl.js:155
zoom: this._map.getZoom() - 1
```

Lý do: MapLibre GL mặc định dùng tile 512px, trong khi Leaflet dùng 256px, nên cần bù `-1` để map khớp viewport. Điều này dẫn đến **tile z được request = Leaflet zoom − 1**:

| Leaflet zoom | MapLibre GL zoom | Tile z request |
|---|---|---|
| 17 | 16 | z=16 → H3 MVT |
| 18 | 17 | z=17 → Pixel PNG |
| 19 | 18 | z=18 → Pixel PNG |

Trước khi fix, cấu hình layer/source dùng `minzoom: 18` (MapLibre) nhưng MapLibre GL chỉ đạt zoom 17 tại Leaflet zoom 18 → layer không bao giờ hiển thị và tiles không được request.

**Giải pháp:** Dịch tất cả ngưỡng zoom xuống 1 đơn vị (cả frontend lẫn backend):

| Thành phần | Trước | Sau |
|---|---|---|
| `H3TileController`: routing pixel | `z >= 18` | `z >= 17` |
| `h3-coverage` source `maxzoom` | `17` | `16` |
| `h3-fill` / `h3-stroke` layer `maxzoom` | `18` | `17` |
| `pixel-coverage` source `minzoom` | `18` | `17` |
| `pixel-raster` layer `minzoom` | `18` | `17` |

**Quy tắc chung khi dùng `@maplibre/maplibre-gl-leaflet`:**

> Mọi ngưỡng `minzoom`/`maxzoom` trong MapLibre GL style = Leaflet zoom − 1.  
> Tương tự, mọi ngưỡng routing tile trên backend cũng cần nhận tile z = Leaflet zoom − 1.

---

### 12.2 Pipeline BIL → ClickHouse (không qua COG)

Thực tế có 2 pipeline song song:

| Pipeline | Endpoint | Dữ liệu | Dùng cho |
|---|---|---|---|
| BIL → H3 → ClickHouse | `POST /api/admin/import-bil` | 443M rows, ~2,347 cells | H3 tile (zoom 6–16) |
| BIL → COG → MinIO | `POST /api/admin/sync-minio` | 2,347 cells × 2 loại COG | Pixel tile (zoom 17–19) |

**Kết quả thực tế (2026-04-04):**
- Import BIL → ClickHouse: 2,347 cells → 443M rows trong ~9 phút
- MinIO: 2,347 × 2 = 4,694 COG objects (binary + continuous)
- Sau import: restart Redis `FLUSHDB` để invalidate cache cũ

---

### 12.3 Hai loại COG trong MinIO

```
cellcover/
  binary/
    {cellId}_svr.tif      ← Float32, binary signal (≥NODATA hoặc giá trị)
                             Dùng cho PixelCoverageService (coverage mode)
  continuous/
    {cellId}.tif          ← Float32 âm dBm liên tục (gdalwarp VRT pipeline)
                             Dùng cho PixelMaxSignalService (signal mode)
```

`BilToCogConverter.convertFile()` (binary): dùng GeoTools + gdalwarp.  
`BilToCogConverter.convertFileContinuous()` (continuous): dùng GDAL VRT pipeline để giữ nguyên giá trị Float32 âm (GeoTools ColorModel normalize về 0 nếu dùng trực tiếp).

---

*Tài liệu này được tổng hợp từ quá trình nghiên cứu kiến trúc tháng 04/2026.*  
*Xem thêm: `research/` directory cho các phân tích chi tiết từng thành phần.*
