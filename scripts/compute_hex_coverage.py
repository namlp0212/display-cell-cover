#!/usr/bin/env python3
"""
Pre-compute H3 hexagon coverage từ raster .bil files.

Cài đặt:
    pip install h3 rasterio numpy psycopg2-binary tqdm

Chạy:
    python scripts/compute_hex_coverage.py \
        --raster-dir data/rasters \
        --db-url postgresql://cellcover:cellcover@localhost:5434/cellcover

Thời gian ước tính: ~5-10 phút cho 2000 cells.
"""

import argparse
import os
import sys
import h3
import numpy as np
import psycopg2
import psycopg2.extras
from pathlib import Path

try:
    import rasterio
    from rasterio.crs import CRS
    from rasterio.warp import transform as reproject_coords
except ImportError:
    print("ERROR: pip install rasterio", file=sys.stderr)
    sys.exit(1)

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(it, **kw):  # fallback no-op
        return it

# H3 resolution theo zoom range
RESOLUTIONS = [7, 9]  # res=7 cho zoom 0-7, res=9 cho zoom 8-10
MAX_PIXELS   = 30_000  # sample tối đa để tăng tốc

WGS84 = CRS.from_epsg(4326)


def h3_boundary_to_wkt(hex_id: str) -> str:
    """Chuyển H3 hexagon boundary sang WKT POLYGON."""
    boundary = h3.h3_to_geo_boundary(hex_id, geo_json=True)  # [[lng, lat], ...]
    coords   = [(lng, lat) for lng, lat in boundary]
    ring     = coords + [coords[0]]
    return 'POLYGON((' + ','.join(f'{x} {y}' for x, y in ring) + '))'


def get_coverage_pixels(bil_path: str):
    """
    Trả về mảng (lats, lons) của các pixel có giá trị hợp lệ (vùng phủ sóng).
    Tự reproject sang WGS84 nếu cần.
    """
    with rasterio.open(bil_path) as src:
        data     = src.read(1)
        nodata   = src.nodata
        src_crs  = src.crs
        transform = src.transform

        # Mask pixel hợp lệ
        if nodata is not None:
            mask = (data != nodata) & np.isfinite(data)
        else:
            mask = np.isfinite(data)

        rows, cols = np.where(mask)
        if len(rows) == 0:
            return np.array([]), np.array([])

        # Sample ngẫu nhiên nếu quá nhiều
        if len(rows) > MAX_PIXELS:
            idx  = np.random.choice(len(rows), MAX_PIXELS, replace=False)
            rows, cols = rows[idx], cols[idx]

        # Pixel → native CRS coordinates
        xs, ys = rasterio.transform.xy(transform, rows, cols)

        # Reproject sang WGS84 nếu CRS khác
        if src_crs and src_crs != WGS84:
            lons, lats = reproject_coords(src_crs, WGS84, xs, ys)
        else:
            lons, lats = xs, ys

        return np.array(lats), np.array(lons)


def compute_cell(cell_id: str, svr_bil: str, conn) -> int:
    """
    Tính H3 hex cho một cell và upsert vào DB.
    Trả về tổng số hex đã xử lý.
    """
    lats, lons = get_coverage_pixels(svr_bil)
    if len(lats) == 0:
        return 0

    cur = conn.cursor()
    total_hex = 0

    for res in RESOLUTIONS:
        hex_ids = set()
        for lat, lon in zip(lats, lons):
            try:
                hex_ids.add(h3.geo_to_h3(float(lat), float(lon), res))
            except Exception:
                continue

        if not hex_ids:
            continue

        # Batch upsert hex_coverage
        hex_rows = []
        for hid in hex_ids:
            wkt = h3_boundary_to_wkt(hid)
            hex_rows.append((hid, wkt, res))

        psycopg2.extras.execute_values(cur, """
            INSERT INTO hex_coverage (hex_id, h3_geom, res, count, total)
            VALUES %s
            ON CONFLICT (hex_id) DO UPDATE SET
                count = hex_coverage.count + 1,
                total = hex_coverage.total + 1
        """, [(hid, f'ST_GeomFromText(\'{wkt}\', 4326)', res)
              for hid, wkt, res in hex_rows],
             template="(%s, ST_GeomFromText(%s, 4326), %s, 1, 1)")

        # Batch insert cell_hex_map
        psycopg2.extras.execute_values(cur, """
            INSERT INTO cell_hex_map (cell_id, hex_id, res)
            VALUES %s
            ON CONFLICT DO NOTHING
        """, [(cell_id, hid, res) for hid in hex_ids])

        total_hex += len(hex_ids)

    conn.commit()
    return total_hex


def main():
    parser = argparse.ArgumentParser(description='Pre-compute H3 hex coverage')
    parser.add_argument('--raster-dir', default='data/rasters',
                        help='Thư mục chứa các cell raster (default: data/rasters)')
    parser.add_argument('--db-url',
                        default='postgresql://cellcover:cellcover@localhost:5434/cellcover',
                        help='PostgreSQL connection URL')
    parser.add_argument('--prefix', default=None,
                        help='Chỉ xử lý cell có prefix này (vd: HN, VL, T2)')
    parser.add_argument('--reset', action='store_true',
                        help='Xóa hex_coverage và cell_hex_map trước khi tính lại')
    args = parser.parse_args()

    conn = psycopg2.connect(args.db_url)

    if args.reset:
        print("Resetting hex_coverage and cell_hex_map...")
        cur = conn.cursor()
        cur.execute("TRUNCATE hex_coverage, cell_hex_map")
        conn.commit()

    raster_dir = Path(args.raster_dir)
    cell_dirs  = sorted(d for d in raster_dir.iterdir() if d.is_dir())

    if args.prefix:
        cell_dirs = [d for d in cell_dirs if d.name.startswith(args.prefix)]

    print(f"Found {len(cell_dirs)} cells to process")
    skipped = 0
    total_hex = 0

    for cell_dir in tqdm(cell_dirs, desc='Processing cells'):
        cell_id = cell_dir.name

        # Tìm file .svr.bil
        svr_bil = cell_dir / f'{cell_id}.svr.bil'
        if not svr_bil.exists():
            skipped += 1
            continue

        try:
            n = compute_cell(cell_id, str(svr_bil), conn)
            total_hex += n
        except Exception as e:
            print(f"\nERROR processing {cell_id}: {e}", file=sys.stderr)
            conn.rollback()

    conn.close()
    print(f"\nDone. Processed {len(cell_dirs) - skipped} cells, "
          f"skipped {skipped}, total hex insertions: {total_hex}")


if __name__ == '__main__':
    main()
