# Kế hoạch xây dựng: Hiển thị vùng phủ Cell trên Web Map

**Mục tiêu**: Hiển thị hàng trăm nghìn vùng phủ raster (`.bil` / `.svr.bil`) lên bản đồ web,
hỗ trợ toggle bật/tắt từng cell, hiệu suất cao nhờ WMS tiled + PostGIS spatial query.

**Điều kiện ban đầu**: Đã có GeoServer chạy bằng Docker, Spring Boot project, Angular project.

---

## Kiến trúc tổng thể

```
[Angular + Leaflet]
    │  map moveend → GET /api/layers?bbox=...
    │  WMS tile request → GeoServer (8 sub-layers / overlap group)
    ▼
[Spring Boot :8080]
    │  /api/layers     → PostGIS spatial query → trả danh sách cell trong viewport
    │  /api/toggle/:id → UPDATE is_visible
    │  /admin/sync     → Scan data/rasters/, import HDR → DB, trigger GeoServer init
    ▼
[PostgreSQL + PostGIS]
    │  Bảng raster_coverages (bbox GEOMETRY, GiST index, ovlp_group)
    ▼
[GeoServer :8081]
    │  ImageMosaic store (đọc COG files)
    │  WMS với CQL_FILTER: ovlp_group=N AND cell_id NOT IN (...)
    │  SLD style: cellcover-binary, cellcover-continuous
    ▼
[data/rasters/]      data/cog/
  BNH0019_1/           binary/       ← .svr.bil → COG (DEFLATE)
  HN_1/                continuous/   ← .bil     → COG (DEFLATE)
  HN_2/ ...
```

---

## Phase 1 — Hạ tầng Docker

### 1.1 Docker Compose

Tạo `docker/docker-compose.yml` với 2 service:

```yaml
services:
  postgis:
    image: postgis/postgis:16-3.4-alpine
    container_name: cellcover-db
    environment:
      POSTGRES_DB: cellcover
      POSTGRES_USER: cellcover
      POSTGRES_PASSWORD: cellcover
    ports:
      - "5434:5432"          # 5434 trên host để tránh xung đột port local
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init-db:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cellcover -d cellcover"]
      interval: 5s
      retries: 5

  geoserver:
    image: kartoza/geoserver:2.25.0
    container_name: cellcover-geoserver
    environment:
      GEOSERVER_ADMIN_PASSWORD: geoserver
      GEOSERVER_ADMIN_USER: admin
    ports:
      - "8081:8080"
    volumes:
      - geoserver_data:/opt/geoserver/data_dir
      - ../data:/data                      # bind mount rasters + COG output
      - ./geoserver-init.sh:/opt/geoserver-init.sh
      - ./styles:/data/styles
    depends_on:
      postgis:
        condition: service_healthy

volumes:
  pgdata:
  geoserver_data:
```

> **Lưu ý quan trọng**:
> - PostGIS dùng port **5434** (không phải 5432) để tránh xung đột với PostgreSQL local.
> - GeoServer mount `../data` vào `/data` trong container — path này dùng cho cả raster lẫn COG output.
> - Khi `data/rasters/` bị xóa và tạo lại, bind mount bị lỗi → restart container GeoServer.

### 1.2 Init SQL cho PostGIS

Tạo `docker/init-db/01-init-postgis.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

---

## Phase 2 — Cơ sở dữ liệu (PostGIS + Flyway)

### 2.1 Migration V1 — Tạo bảng

`src/main/resources/db/migration/V1__create_raster_coverages.sql`:

```sql
CREATE TABLE raster_coverages (
    id          BIGSERIAL PRIMARY KEY,
    cell_id     VARCHAR(255) NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,
    bbox        GEOMETRY(POLYGON, 4326) NOT NULL,
    crs         VARCHAR(64) NOT NULL DEFAULT 'EPSG:4326',
    is_svr      BOOLEAN NOT NULL DEFAULT FALSE,
    is_visible  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Spatial index (bắt buộc cho ST_Intersects)
CREATE INDEX idx_raster_coverages_bbox ON raster_coverages USING GIST (bbox);

-- B-tree index trên cell_id
CREATE INDEX idx_raster_coverages_cell_id ON raster_coverages (cell_id);

-- Partial spatial index chỉ cho visible rows (tối ưu query thường gặp)
CREATE INDEX idx_raster_coverages_bbox_visible
    ON raster_coverages USING GIST (bbox) WHERE is_visible = TRUE;
```

### 2.2 Migration V2 — Thêm cột ovlp_group

`src/main/resources/db/migration/V2__add_ovlp_group.sql`:

```sql
ALTER TABLE raster_coverages
    ADD COLUMN IF NOT EXISTS ovlp_group INTEGER NOT NULL DEFAULT 0;

-- Gán group cho các row đã có: group = (rank cell) % 8
UPDATE raster_coverages rc
SET ovlp_group = sub.grp
FROM (
    SELECT id,
           ((DENSE_RANK() OVER (ORDER BY cell_id) - 1) % 8)::INTEGER AS grp
    FROM raster_coverages
) sub
WHERE rc.id = sub.id;

CREATE INDEX IF NOT EXISTS idx_raster_coverages_ovlp_group
    ON raster_coverages (ovlp_group);
```

> **Tại sao cần `ovlp_group`?** Xem phần thiết kế overlap groups ở Phase 5.

---

## Phase 3 — Backend Spring Boot

### 3.1 Dependencies (pom.xml)

```xml
<!-- Web + JPA + Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- PostgreSQL driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Hibernate Spatial — hỗ trợ ST_Intersects trong JPQL -->
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-spatial</artifactId>
</dependency>

<!-- JTS — kiểu Geometry dùng trong Java -->
<dependency>
    <groupId>org.locationtech.jts</groupId>
    <artifactId>jts-core</artifactId>
    <version>1.20.0</version>
</dependency>

<!-- Serialize Geometry → GeoJSON cho Angular -->
<dependency>
    <groupId>org.n52.jackson</groupId>
    <artifactId>jackson-datatype-jts</artifactId>
    <version>1.2.10</version>
</dependency>

<!-- CRS transform: UTM → WGS84 khi đọc .hdr -->
<dependency>
    <groupId>org.locationtech.proj4j</groupId>
    <artifactId>proj4j</artifactId>
    <version>1.3.0</version>
</dependency>
<dependency>
    <groupId>org.locationtech.proj4j</groupId>
    <artifactId>proj4j-epsg</artifactId>
    <version>1.3.0</version>
</dependency>

<!-- Flyway migration -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

> **Pitfall**: Phải có cả `proj4j` và `proj4j-epsg`. Thiếu `proj4j-epsg` thì không resolve được EPSG codes.

### 3.2 application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:cellcover}
    username: ${DB_USER:cellcover}
    password: ${DB_PASSWORD:cellcover}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate          # Flyway quản lý schema, JPA chỉ validate
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.spatial.dialect.postgis.PostgisPG10Dialect
  flyway:
    enabled: true

server:
  port: ${SERVER_PORT:8080}

cellcover:
  raster-dir: data/rasters        # Thư mục chứa các cell folder
  geoserver:
    container-name: cellcover-geoserver
    init-script: /opt/geoserver-init.sh
```

> **Khởi động backend**: `DB_PORT=5434 mvn spring-boot:run` (nếu PostGIS expose port 5434).

### 3.3 JacksonConfig — Serialize Geometry → GeoJSON

```java
@Configuration
public class JacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer addJtsModule() {
        return builder -> builder.modules(new JtsModule());
    }
}
```

> Không có bean này thì `bbox` field trả về dạng WKB base64 thay vì GeoJSON.

### 3.4 CorsConfig

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:4200")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

### 3.5 Entity

```java
@Entity
@Table(name = "raster_coverages")
public class RasterCoverage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cell_id", nullable = false)
    private String cellId;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "bbox", nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Geometry bbox;       // org.locationtech.jts.geom.Geometry

    @Column(name = "crs", nullable = false, length = 64)
    private String crs = "EPSG:4326";

    @Column(name = "is_svr", nullable = false)
    private boolean svr;

    @Column(name = "is_visible", nullable = false)
    private boolean visible = true;

    @Column(name = "ovlp_group", nullable = false)
    private int ovlpGroup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
    // getters/setters...
}
```

### 3.6 Repository

```java
public interface RasterCoverageRepository extends JpaRepository<RasterCoverage, Long> {

    // Chỉ lấy SVR rows (là vùng phủ binary hiển thị)
    @Query("SELECT r FROM RasterCoverage r WHERE r.svr = true AND ST_Intersects(r.bbox, :viewport) = true")
    List<RasterCoverage> findInViewport(@Param("viewport") Geometry viewport);

    @Query("SELECT DISTINCT r.cellId FROM RasterCoverage r")
    List<String> findDistinctCellIds();

    @Query("SELECT COUNT(DISTINCT r.cellId) FROM RasterCoverage r")
    long countDistinctCellIds();

    @Query("SELECT DISTINCT r.cellId FROM RasterCoverage r WHERE r.visible = false")
    List<String> findHiddenCellIds();

    @Modifying
    @Query("UPDATE RasterCoverage r SET r.visible = :visible WHERE r.cellId = :cellId")
    int toggleVisibility(@Param("cellId") String cellId, @Param("visible") boolean visible);
}
```

> **Pitfall Spring 6**: `@RequestParam` trong Controller **phải** có tên tường minh:
> `@RequestParam("minx")` — không để mặc định sẽ báo lỗi `Name for argument not specified`.
> Hoặc thêm flag `-parameters` vào Maven compiler plugin.

### 3.7 Controller

```java
@RestController
@RequestMapping("/api")
public class RasterCoverageController {

    @GetMapping("/layers")
    public List<RasterCoverageDto> getVisibleLayers(
            @RequestParam("minx") Double minx,
            @RequestParam("miny") Double miny,
            @RequestParam("maxx") Double maxx,
            @RequestParam("maxy") Double maxy) {
        return service.findVisibleInBbox(minx, miny, maxx, maxy);
    }

    @GetMapping("/hidden-cells")
    public List<String> getHiddenCellIds() {
        return service.findHiddenCellIds();
    }

    @PostMapping("/toggle/{cellId}")
    public ResponseEntity<Map<String, Object>> toggleVisibility(
            @PathVariable("cellId") String cellId,
            @RequestParam("visible") boolean visible) {
        int updated = service.toggleVisibility(cellId, visible);
        return ResponseEntity.ok(Map.of("cellId", cellId, "visible", visible, "updatedCount", updated));
    }
}
```

### 3.8 CellImportService — Import HDR → DB

Khi gọi `/admin/sync`, service này:

1. Scan tất cả thư mục con trong `data/rasters/`
2. Với mỗi cell chưa có trong DB:
   - Đọc file `.hdr` → parse `ulxmap`, `ulymap`, `xdim`, `ydim`, `ncols`, `nrows`
   - Đọc file `.prj` → detect EPSG code (regex `AUTHORITY["EPSG","326xx"]`)
   - Transform 4 góc bbox từ UTM → WGS84 dùng **Proj4J**
   - Tính `ovlp_group = countDistinctCellIds() % 8`
   - Lưu 2 row: 1 row standard (`.bil`), 1 row SVR (`.svr.bil`)
3. Sau khi import xong → chạy `geoserver-init.sh` trong Docker container

**Công thức tính bbox từ HDR**:
```
minX = ulxmap - xdim/2
maxY = ulymap + ydim/2
maxX = minX + ncols * xdim
minY = maxY - nrows * ydim
```

> **Pitfall**: GDAL dùng `ulxmap/ulymap` là tâm pixel góc trên-trái.
> Phải trừ/cộng half-pixel (`xdim/2`) để ra cạnh thực của raster.

### 3.9 AdminController

```java
@RestController
@RequestMapping("/admin")
public class AdminController {
    @PostMapping("/sync")
    public ResponseEntity<CellImportResult> sync() {
        return ResponseEntity.ok(cellImportService.syncCells());
    }
}
```

---

## Phase 4 — Chuẩn bị raster data & GeoServer

### 4.1 Cấu trúc thư mục raster

```
data/
├── rasters/
│   ├── BNH0019_1/
│   │   ├── BNH0019_1.bil       ← raster chính (Float32, UTM)
│   │   ├── BNH0019_1.hdr       ← metadata: ncols, nrows, ulxmap, ...
│   │   ├── BNH0019_1.svr.bil   ← raster binary (0/1 signal coverage)
│   │   └── BNH0019_1.svr.hdr
│   └── HN_1/
│       ├── HN_1.bil
│       ├── HN_1.hdr
│       ├── HN_1.prj            ← WKT CRS (UTM zone)
│       ├── HN_1.svr.bil
│       └── HN_1.svr.prj
└── cog/                        ← tạo bởi geoserver-init.sh
    ├── continuous/             ← .bil → COG DEFLATE EPSG:4326
    └── binary/                 ← .svr.bil → COG DEFLATE EPSG:4326
```

### 4.2 GeoServer Init Script (`docker/geoserver-init.sh`)

Script này chạy **bên trong GeoServer container** sau khi `/admin/sync` được gọi.
Các bước:

**Bước 1 — Chờ GeoServer ready**
```bash
until curl -sf "http://localhost:8080/geoserver/web/" > /dev/null; do sleep 5; done
```

**Bước 2 — Tạo workspace `cellcover`**
```bash
curl -u admin:geoserver -XPOST .../rest/workspaces \
  -H "Content-Type: application/json" \
  -d '{"workspace":{"name":"cellcover"}}'
```

**Bước 3 — Convert BIL → COG**

```bash
# Fix: GDAL không đọc được "datatype R32" trong HDR chuẩn
# → Copy HDR vào /tmp, thêm dòng "PIXELTYPE FLOAT"
echo "PIXELTYPE FLOAT" >> /tmp/bil-fix/${name}.hdr

gdalwarp -of COG -ot Float32 -t_srs EPSG:4326 \
  -srcnodata "-3.4028235e+38" -dstnodata "-3.4028235e+38" \
  -co COMPRESS=DEFLATE \
  -co OVERVIEW_RESAMPLING=AVERAGE \
  -co BLOCKSIZE=256 \
  input.bil output.tif
```

> **Pitfall GDAL**: BIL file với `datatype R32` trong HDR không được GDAL nhận là Float32 —
> GDAL đọc nhầm thành UInt32. Phải thêm `PIXELTYPE FLOAT` vào HDR trước khi chạy gdalwarp.

**Bước 4 — Fix quyền thư mục COG**
```bash
chown -R geoserveruser:geoserverusers /data/cog/continuous /data/cog/binary
```
GeoServer cần write vào thư mục để tạo file index shapefile.

**Bước 5 — Upload SLD styles**
```bash
# Tạo style entry
curl -u admin:geoserver -XPOST .../rest/styles \
  -d '{"style":{"name":"cellcover-binary","filename":"cellcover-binary.sld"}}'

# Upload SLD content
curl -u admin:geoserver -XPUT .../rest/styles/cellcover-binary \
  -H "Content-Type: application/vnd.ogc.sld+xml" \
  -d @/data/styles/cellcover-binary.sld
```

**Bước 6 — Tạo ImageMosaic store**
```bash
curl -u admin:geoserver -XPOST .../rest/workspaces/cellcover/coveragestores \
  -d '{
    "coverageStore": {
      "name": "binary",
      "type": "ImageMosaic",
      "enabled": true,
      "workspace": {"name": "cellcover"},
      "url": "file:///data/cog/binary"
    }
  }'
```

**Bước 7 — Publish coverage + set MERGE_BEHAVIOR=STACK**
```bash
curl -u admin:geoserver -XPUT .../coveragestores/binary/coverages/binary.json \
  -d '{"coverage":{"parameters":{"entry":[
    {"string":["MERGE_BEHAVIOR","STACK"]},
    {"string":["BackgroundValues","-3.4028235e+38"]}
  ]}}}'
```

> `MERGE_BEHAVIOR=STACK` khiến GeoServer composite các granules chồng nhau
> theo thứ tự source-over alpha — vùng nhiều cell chồng nhau sẽ sậm hơn.

**Bước 8 — Thêm `cell_id` và `ovlp_group` vào shapefile index**

GeoServer tự tạo file index `.shp` khi scan COG directory.
Script dùng Python/GDAL để thêm 2 trường này:

```python
from osgeo import ogr

ds = ogr.Open("/data/cog/binary/binary.shp", 1)
layer = ds.GetLayer()

# Thêm fields
layer.CreateField(ogr.FieldDefn("cell_id", ogr.OFTString))
layer.CreateField(ogr.FieldDefn("ovlp_group", ogr.OFTInteger))

# Gán giá trị: ovlp_group = idx % 8
for idx, feat in enumerate(layer):
    basename = os.path.splitext(os.path.basename(feat.GetField("location")))[0]
    feat.SetField("cell_id", basename.replace("_svr", ""))
    feat.SetField("ovlp_group", idx % 8)
    layer.SetFeature(feat)
```

> **Pitfall**: `ovlp_group` phải có trong **cả hai** nơi:
> 1. Bảng `raster_coverages` trong PostGIS (để backend biết gán group cho cell mới)
> 2. Shapefile index của GeoServer (để CQL_FILTER `ovlp_group=N` hoạt động)
>
> Nếu thiếu ở GeoServer shapefile → WMS trả về trắng hoàn toàn không có lỗi rõ ràng.

### 4.3 SLD Styles

**`cellcover-binary.sld`** (cho `.svr.bil` — giá trị 0/1):
```xml
<RasterSymbolizer>
  <ColorMap type="values">
    <ColorMapEntry color="#000000" quantity="0" opacity="0"/>
    <ColorMapEntry color="#FF4500" quantity="1" opacity="0.65"/>
  </ColorMap>
</RasterSymbolizer>
```

**`cellcover-continuous.sld`** (cho `.bil` — dải màu Cividis):
```xml
<RasterSymbolizer>
  <ColorMap type="ramp">
    <ColorMapEntry color="#00204D" quantity="-1e+37" opacity="0"/>
    <ColorMapEntry color="#00204D" quantity="0"    opacity="0.70"/>
    <!-- ... 6 stops ... -->
    <ColorMapEntry color="#FDE725" quantity="8000"  opacity="0.70"/>
  </ColorMap>
</RasterSymbolizer>
```

---

## Phase 5 — Frontend Angular

### 5.1 Cấu trúc

```
frontend/src/app/
├── components/
│   ├── map/
│   │   ├── map.component.ts      ← Leaflet map + WMS layers + CQL_FILTER
│   │   └── map.component.html
│   └── sidebar/
│       ├── sidebar.component.ts  ← Toggle UI
│       └── sidebar.component.html
├── services/
│   └── layer.service.ts          ← HTTP calls đến Spring API
├── models/
│   └── raster-coverage.model.ts
└── environments/
    ├── environment.ts            ← apiUrl, geoServerUrl
    └── environment.prod.ts
```

### 5.2 environment.ts

```typescript
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api',
  geoServerUrl: 'http://localhost:8081/geoserver'
};
```

### 5.3 LayerService

```typescript
@Injectable({ providedIn: 'root' })
export class LayerService {
  constructor(private http: HttpClient) {}

  getVisibleLayers(minx: number, miny: number, maxx: number, maxy: number) {
    const params = new HttpParams()
      .set('minx', minx).set('miny', miny)
      .set('maxx', maxx).set('maxy', maxy);
    return this.http.get<RasterCoverage[]>(`${environment.apiUrl}/layers`, { params });
  }

  toggleVisibility(cellId: string, visible: boolean) {
    return this.http.post<ToggleResponse>(
      `${environment.apiUrl}/toggle/${cellId}`,
      null,
      { params: { visible: visible.toString() } }
    );
  }

  getHiddenCellIds() {
    return this.http.get<string[]>(`${environment.apiUrl}/hidden-cells`);
  }
}
```

### 5.4 MapComponent — Thiết kế Overlap Groups

**Vấn đề cần giải quyết**: Vùng phủ nhiều cell chồng nhau cần hiển thị đậm hơn, nhưng WMS chỉ trả 1 ảnh tổng hợp — làm sao tích luỹ opacity?

**Giải pháp Overlap Groups**:
- Chia toàn bộ cells thành 8 nhóm (`ovlp_group` = 0 → 7)
- Gửi **1 WMS request** nhưng repeat layer 8 lần, mỗi lần filter 1 group
- GeoServer composite 8 sub-layer server-side theo thứ tự source-over alpha
- Vùng có N cells chồng nhau sẽ có N sub-layer đóng góp opacity → sậm hơn

```typescript
private static readonly OVERLAP_GROUPS = 8;

private updateWmsLayers(): void {
  const n = MapComponent.OVERLAP_GROUPS;

  // Repeat layer name 8 lần
  const layers = Array(n).fill('cellcover:binary').join(',');
  const styles = Array(n).fill('cellcover-binary').join(',');

  // Mỗi sub-layer filter 1 group, đều loại bỏ hidden cells
  const hiddenFilter = this.hiddenCellIds.length > 0
    ? ` AND cell_id NOT IN (${this.hiddenCellIds.map(id => `'${id}'`).join(',')})`
    : '';

  const cqlParts = Array.from({ length: n }, (_, g) => `ovlp_group=${g}${hiddenFilter}`);
  const cqlFilter = cqlParts.join(';');   // GeoServer dùng ';' để phân tách CQL cho multi-layer

  this.wmsLayer = L.tileLayer.wms(`${environment.geoServerUrl}/wms`, {
    layers,
    styles,
    format: 'image/png',
    transparent: true,
    version: '1.1.1',
    pane: 'wmsPane',
    tiled: true,
    tileSize: 512,
    CQL_FILTER: cqlFilter
  } as any);
}
```

**Khởi tạo map**:
```typescript
private initMap(): void {
  this.map = L.map('map', {
    center: [21.028, 105.854],    // Hà Nội
    zoom: 12
  });

  // Tạo pane riêng cho WMS để blend mode chỉ áp dụng giữa các WMS layer
  this.map.createPane('wmsPane');
  this.map.getPane('wmsPane')!.style.zIndex = '450';

  // Debounce moveend để tránh spam API khi pan/zoom nhanh
  this.subscription = this.moveEnd$.pipe(
    debounceTime(150),
    switchMap(bounds => {
      const sw = bounds.getSouthWest();
      const ne = bounds.getNorthEast();
      return this.layerService.getVisibleLayers(sw.lng, sw.lat, ne.lng, ne.lat);
    })
  ).subscribe(coverages => this.updateLayers(coverages));

  this.map.on('moveend', () => this.moveEnd$.next(this.map.getBounds()));
}
```

**Retry tile lỗi**:
```typescript
this.wmsLayer.on('tileerror', (event: any) => {
  const tile = event.tile;
  if (!tile._retryCount) tile._retryCount = 0;
  if (tile._retryCount < 3) {
    tile._retryCount++;
    setTimeout(() => { tile.src = tile.src; }, 1000 * tile._retryCount);
  }
});
```

**Quản lý hidden cells ngoài viewport**:
```typescript
// Khi user toggle cell, cell đó có thể bị scroll ra khỏi viewport
// → Phải ghi nhớ hidden cells ở cả trong và ngoài viewport
private updateLayers(coverages: RasterCoverage[]): void {
  const inViewportIds = new Set(coverages.map(c => c.cellId));
  const inViewportHidden = coverages.filter(c => !c.visible).map(c => c.cellId);
  const outOfViewportHidden = this.hiddenCellIds.filter(id => !inViewportIds.has(id));
  this.hiddenCellIds = [...new Set([...inViewportHidden, ...outOfViewportHidden])];
  this.updateWmsLayers();
}
```

---

## Phase 6 — Tối ưu hoá lưu trữ

### 6.1 COG + DEFLATE (đã thực hiện trong geoserver-init.sh)

| Thước đo | BIL gốc | COG DEFLATE |
|----------|---------|-------------|
| 1 cell | ~2.8 MB | ~0.8–1.2 MB |
| 100k cells | ~280 GB | ~85–110 GB |

Options COG đã dùng:
- `COMPRESS=DEFLATE` — nén lossless ~50-60%
- `BLOCKSIZE=256` — tile-aligned, tối ưu WMS tile request
- `OVERVIEW_RESAMPLING=AVERAGE` — overview tích hợp trong file, không cần file riêng

### 6.2 Cấu hình GeoWebCache (chưa thực hiện)

Thêm vào GeoServer config để chỉ cache zoom thấp, tránh tốn ~200GB:

```xml
<!-- geowebcache-layers.xml hoặc cấu hình qua GeoServer Admin UI -->
<gridSubset>
  <gridSetName>EPSG:900913</gridSetName>
  <zoomStart>10</zoomStart>
  <zoomStop>13</zoomStop>    <!-- Không cache zoom 14+ -->
</gridSubset>
```

Zoom 10–13 cache toàn quốc ≈ 15–20 GB (hợp lý). Zoom 14+ render on-demand.

---

## Phase 7 — Quy trình vận hành

### Khởi động hệ thống từ đầu

```bash
# 1. Khởi động PostGIS + GeoServer
cd docker
docker compose up -d

# 2. Khởi động Spring Boot
cd ..
DB_PORT=5434 mvn spring-boot:run

# 3. Khởi động Angular
cd frontend
npm install
ng serve

# 4. Import cell data + trigger GeoServer init
curl -X POST http://localhost:8080/admin/sync

# 5. Mở browser
# http://localhost:4200
```

### Thêm cell mới

```bash
# Copy thư mục cell mới vào data/rasters/
cp -r /path/to/NEW_CELL/ data/rasters/

# Gọi sync — tự động import vào DB và update GeoServer
curl -X POST http://localhost:8080/admin/sync
```

### Tái tạo test data (Hà Nội)

```bash
# Xoá HN test cells
rm -rf data/rasters/HN_*/

# Tái tạo 62 cells từ template BNH0034_3
mvn compile exec:java -Dexec.mainClass=com.example.cellcover.CreateTestData

# Sync lại
curl -X POST http://localhost:8080/admin/sync
```

---

## Các pitfall quan trọng (tổng hợp)

| # | Vấn đề | Giải pháp |
|---|--------|-----------|
| 1 | Spring 6 `@RequestParam` không có tên | Thêm tên tường minh: `@RequestParam("minx")` |
| 2 | GDAL đọc BIL Float32 nhầm thành UInt32 | Thêm `PIXELTYPE FLOAT` vào HDR trước khi convert |
| 3 | `ovlp_group` không có trong GeoServer shapefile index | Chạy script Python thêm field sau khi GeoServer tạo index |
| 4 | Bind mount bị lỗi sau khi xóa/tạo lại `data/rasters/` | Restart GeoServer container |
| 5 | COG cũ vẫn còn trong Docker volume sau khi xoá rasters | Xóa thủ công trong `/data/cog/` hoặc recreate volume |
| 6 | PostGIS port conflict | Dùng `5434:5432` trong docker-compose, start backend với `DB_PORT=5434` |
| 7 | `proj4j-epsg` thiếu trong pom.xml | Thêm cả `proj4j` và `proj4j-epsg` v1.3.0 |
| 8 | Geometry serialized thành WKB thay vì GeoJSON | Đăng ký `JtsModule` bean trong JacksonConfig |
| 9 | Hidden cells bị mất khi pan ra khỏi viewport | Gộp `inViewportHidden` + `outOfViewportHidden` trong `updateLayers()` |
| 10 | `findDistinctCellIds()` thiếu trong Repository | Khai báo tường minh với `@Query` |
