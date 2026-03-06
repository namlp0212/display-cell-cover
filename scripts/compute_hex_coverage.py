#!/usr/bin/env python3
"""
Pre-compute H3 hexagon coverage từ raster .bil files.

Cài đặt:
    pip install h3 rasterio numpy psycopg2-binary tqdm

Chạy:
    python3.9 scripts/compute_hex_coverage.py \
        --raster-dir data/rasters \
        --db-url postgresql://cellcover:cellcover@localhost:5434/cellcover
"""

import argparse, sys
import numpy as np
import psycopg2, psycopg2.extras
from pathlib import Path

import h3
import rasterio
from rasterio.crs import CRS
from rasterio.warp import transform as reproject_coords

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(it, **kw): return it

RESOLUTIONS  = [7, 9]   # res=7 → zoom 0-7, res=9 → zoom 8-10
MAX_PIXELS   = 40_000   # sample tối đa / cell
WGS84        = CRS.from_epsg(4326)


def read_prj_crs(prj_path: Path):
    """Đọc CRS từ file .prj nếu rasterio không nhận được."""
    if prj_path.exists():
        try:
            return CRS.from_wkt(prj_path.read_text())
        except Exception:
            pass
    return None


def h3_boundary_wkt(hex_id: str) -> str:
    """Chuyển H3 hex boundary → WKT POLYGON."""
    # h3 >= 4.x dùng cell_to_boundary, trả [[lat,lng],...]
    try:
        boundary = h3.cell_to_boundary(hex_id)  # [[lat,lng],...]
    except AttributeError:
        boundary = h3.h3_to_geo_boundary(hex_id) # h3 3.x fallback
    coords = [(lng, lat) for lat, lng in boundary]
    ring   = coords + [coords[0]]
    return 'POLYGON((' + ','.join(f'{x} {y}' for x, y in ring) + '))'


def geo_to_h3(lat: float, lon: float, res: int) -> str:
    """Gọi đúng API cho cả h3 3.x và 4.x."""
    try:
        return h3.latlng_to_cell(lat, lon, res)     # h3 >= 4.x
    except AttributeError:
        return h3.geo_to_h3(lat, lon, res)           # h3 3.x


def get_wgs84_pixels(bil_path: Path):
    """
    Đọc .bil, lấy pixel hợp lệ, reproject sang WGS84.
    Trả về (lats, lons) numpy arrays.
    """
    prj_path = bil_path.with_suffix('.prj')
    with rasterio.open(bil_path) as src:
        data      = src.read(1)
        nodata    = src.nodata
        transform = src.transform
        src_crs   = src.crs or read_prj_crs(prj_path)

    # Mask pixel hợp lệ
    if nodata is not None:
        valid = data != nodata
    else:
        valid = np.ones(data.shape, dtype=bool)
    # Loại NaN / Inf
    valid &= np.isfinite(data)

    rows, cols = np.where(valid)
    if len(rows) == 0:
        return np.array([]), np.array([])

    # Downsample
    if len(rows) > MAX_PIXELS:
        idx  = np.random.choice(len(rows), MAX_PIXELS, replace=False)
        rows, cols = rows[idx], cols[idx]

    xs, ys = rasterio.transform.xy(transform, rows, cols)
    xs, ys = np.array(xs, dtype=float), np.array(ys, dtype=float)

    # Reproject → WGS84
    if src_crs and src_crs != WGS84:
        lons, lats = reproject_coords(src_crs, WGS84, xs, ys)
        return np.array(lats, dtype=float), np.array(lons, dtype=float)
    else:
        # Nếu không có CRS, giả sử xs=lon, ys=lat (WGS84)
        return ys, xs


def compute_cell(cell_id: str, svr_bil: Path, cur) -> int:
    """Tính H3 cho một cell, upsert vào DB. Trả về số hex đã insert."""
    lats, lons = get_wgs84_pixels(svr_bil)
    if len(lats) == 0:
        return 0

    total = 0
    for res in RESOLUTIONS:
        hex_ids = set()
        for lat, lon in zip(lats, lons):
            try:
                hex_ids.add(geo_to_h3(float(lat), float(lon), res))
            except Exception:
                continue
        if not hex_ids:
            continue

        rows = [(hid, h3_boundary_wkt(hid), res) for hid in hex_ids]

        # Upsert hex_coverage
        psycopg2.extras.execute_values(cur, """
            INSERT INTO hex_coverage (hex_id, h3_geom, res, count, total)
            VALUES %s
            ON CONFLICT (hex_id) DO UPDATE SET
                count = hex_coverage.count + 1,
                total = hex_coverage.total + 1
        """, rows, template="(%s, ST_GeomFromText(%s, 4326), %s, 1, 1)")

        # Insert mapping
        psycopg2.extras.execute_values(cur, """
            INSERT INTO cell_hex_map (cell_id, hex_id, res)
            VALUES %s ON CONFLICT DO NOTHING
        """, [(cell_id, hid, res) for hid in hex_ids])

        total += len(hex_ids)

    return total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--raster-dir', default='data/rasters')
    parser.add_argument('--db-url',
        default='postgresql://cellcover:cellcover@localhost:5434/cellcover')
    parser.add_argument('--prefix', default=None)
    parser.add_argument('--reset', action='store_true')
    args = parser.parse_args()

    conn = psycopg2.connect(args.db_url)
    cur  = conn.cursor()

    if args.reset:
        print("Resetting hex_coverage and cell_hex_map...")
        cur.execute("TRUNCATE hex_coverage, cell_hex_map")
        conn.commit()

    raster_dir = Path(args.raster_dir)
    cell_dirs  = sorted(d for d in raster_dir.iterdir() if d.is_dir())
    if args.prefix:
        cell_dirs = [d for d in cell_dirs if d.name.startswith(args.prefix)]

    print(f"Found {len(cell_dirs)} cells to process")
    skipped = errors = 0
    total_hex = 0

    for cell_dir in tqdm(cell_dirs, desc='Processing cells'):
        cell_id = cell_dir.name
        svr_bil = cell_dir / f'{cell_id}.svr.bil'
        if not svr_bil.exists():
            skipped += 1
            continue
        try:
            n = compute_cell(cell_id, svr_bil, cur)
            conn.commit()
            total_hex += n
        except Exception as e:
            conn.rollback()
            errors += 1
            print(f"\nERROR {cell_id}: {e}", file=sys.stderr)

    cur.close()
    conn.close()
    print(f"\nDone: {len(cell_dirs)-skipped-errors} cells OK, "
          f"{skipped} skipped, {errors} errors, {total_hex} hex rows")


if __name__ == '__main__':
    main()
