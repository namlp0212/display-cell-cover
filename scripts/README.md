# Scripts

## compute_hex_coverage.py

Pre-compute H3 hexagon density grid từ raster `.svr.bil` files.
Cần chạy **một lần** sau khi import raster cells vào DB.

### Cài đặt

```bash
pip install h3 rasterio numpy psycopg2-binary tqdm
```

### Chạy

```bash
# Tất cả cells
python scripts/compute_hex_coverage.py

# Chỉ cells HN
python scripts/compute_hex_coverage.py --prefix HN

# Reset và tính lại từ đầu
python scripts/compute_hex_coverage.py --reset

# Custom DB URL
python scripts/compute_hex_coverage.py --db-url postgresql://cellcover:cellcover@localhost:5434/cellcover
```

### Thứ tự setup hoàn chỉnh

```bash
# 1. Start docker services (bao gồm pg_tileserv)
cd docker && docker compose up -d

# 2. Start backend
cd DisplayCellCover_V2 && DB_PORT=5434 mvn spring-boot:run

# 3. Import rasters
curl -X POST http://localhost:8080/api/admin/sync-cells

# 4. Compute H3 hex coverage
python scripts/compute_hex_coverage.py

# 5. Start frontend
cd frontend && npm start
```
