# Thiết kế Công nghệ: Hiển thị Hàng trăm nghìn vùng phủ raster (.bil / .svr.bil) trên Web Map

**Tech stack hiện tại**: Angular + Leaflet (frontend), Java Spring Boot (backend)  
**Database mới**: PostgreSQL + PostGIS  
**Mục tiêu**: Hiển thị vùng phủ của các cell trên bản đồ web, hỗ trợ toggle bật/tắt (toàn bộ hoặc theo cell/nhóm), hiệu suất cao với dữ liệu lớn.

## 1. Phân tích yêu cầu & Thách thức

- **Dữ liệu**: Hàng trăm nghìn file raster (.bil + .hdr, .svr.bil + .svr.hdr) – mỗi file là vùng phủ của một cell.
- **Yêu cầu**:
    - Hiển thị chồng lớp trên bản đồ (web).
    - Toggle bật/tắt vùng phủ.
    - Hiệu suất: Không load hết file cùng lúc.
- **Thách thức**:
    - Số lượng file cực lớn → Không thể add từng layer riêng.
    - Raster binary (.bil) → Cần serve tiled (WMS/WMTS).
    - Filter theo viewport (zoom/pan) → Cần spatial query nhanh.
    - Toggle động → Cần metadata + trạng thái visible.

**Giải pháp chính**:
- **GeoServer** + **ImageMosaic** để serve raster tiled.
- **PostGIS** để lưu metadata + spatial query filter bbox viewport.
- **Leaflet** để hiển thị WMS/WMTS + toggle layer.

## 2. Architecture Tổng thể
[Frontend: Angular + Leaflet]
└─ Map Component → L.tileLayer.wms / wmts
└─ ApiService → Gọi Spring để lấy list cell visible theo bbox
└─ Toggle UI → Checkbox / Tree cho cell / nhóm
[Backend: Java Spring Boot]
└─ API: /api/layers (filter PostGIS bbox + is_visible)
└─ /api/toggle/{cell_id} → Update is_visible
└─ Optional: GeoServer REST client
[GeoServer]
└─ ImageMosaic Store → Group .bil / .svr.bil thành coverage lớn
└─ WMS / WMTS (cached bởi GeoWebCache)
[Database: PostgreSQL + PostGIS]
└─ Table raster_coverages (bbox GEOMETRY, spatial GiST index)
[Storage]
└─ Filesystem / S3 → Lưu file .bil + .hdr
text## 3. Chi tiết Thiết kế

### 3.1. Database (PostgreSQL + PostGIS)

CREATE TABLE raster_coverages (
  id SERIAL PRIMARY KEY,
  cell_id VARCHAR(50) NOT NULL,
  file_path VARCHAR(500) NOT NULL,
  bbox GEOMETRY(POLYGON, 4326) NOT NULL,   -- Hoặc SRID phù hợp (ví dụ 4756 cho VN-2000)
  crs INTEGER,
  is_svr BOOLEAN DEFAULT FALSE,
  is_visible BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Spatial index (rất quan trọng!)
CREATE INDEX idx_bbox ON raster_coverages USING GIST(bbox);

-- Index bổ sung nếu cần
CREATE INDEX idx_cell_id ON raster_coverages(cell_id);
3.2. GeoServer Configuration

Store: ImageMosaic
Chọn thư mục chứa tất cả file .bil (hoặc .svr.bil riêng).
GeoServer tự scan .hdr để lấy bbox, CRS → Tạo index granules.

Coverage: Publish mosaic → Tên ví dụ: workspace:full_coverage_mosaic
Bật GeoWebCache → Cache tiles WMTS (tăng tốc đáng kể).
Filter nếu cần: Sử dụng CQL_FILTER trong WMS params (ví dụ: cell_id IN ('cell1','cell2')).

3.3. Backend (Java Spring Boot)
Dependencies:

spring-boot-starter-data-jpa
org.postgresql:postgresql
org.hibernate:hibernate-spatial
GeoTools (nếu parse .hdr thủ công)

Entity (JPA):
Java@Entity
@Table(name = "raster_coverages")
public class RasterCoverage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cell_id")
    private String cellId;

    @Column(name = "file_path")
    private String filePath;

    @Column(columnDefinition = "geometry(Polygon,4326)")
    private Geometry bbox;  // org.locationtech.jts.geom.Geometry

    private Integer crs;
    private Boolean isSvr;
    private Boolean isVisible = true;

    // Getters, Setters...
}
API ví dụ:

GET /api/layers?minx=...&miny=...&maxx=...&maxy=...&zoom=...
→ Query PostGIS: ST_Intersects(bbox, ST_GeomFromText('POLYGON(...)', 4326)) AND is_visible = TRUE
→ Trả JSON: list cellId + metadata.
POST /api/toggle/{cell_id} → Update is_visible → Refresh frontend.

3.4. Frontend (Angular + Leaflet)
Thêm layer WMS:
TypeScriptimport * as L from 'leaflet';

const mosaicLayer = L.tileLayer.wms('http://geoserver:8080/geoserver/wms', {
  layers: 'workspace:full_coverage_mosaic',
  format: 'image/png',
  transparent: true,
  opacity: 0.6,
  tiled: true
});

const overlays = {
  'Vùng phủ toàn bộ': mosaicLayer
};

L.control.layers(null, overlays, { position: 'topright' }).addTo(map);
Toggle động:

Khi map moveend/zoomend → Lấy bounds → Gọi API /api/layers → Cập nhật CQL_FILTER hoặc add/remove layer con.

Opacity & Blend: Đặt opacity 0.4–0.7 để thấy vùng chồng.