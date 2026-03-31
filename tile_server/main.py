#!/usr/bin/env python3.9
"""
Per-pixel MAX signal tile server.
Endpoint: GET /signal-tile/{z}/{x}/{y}
Port: 8082
"""
import io
import math
import os

import numpy as np
import psycopg2
import psycopg2.pool
from fastapi import FastAPI, Response
from PIL import Image
from rasterio import open as rio_open
from rasterio.enums import Resampling
from rasterio.windows import from_bounds

# ── Config ────────────────────────────────────────────────────────────────────
DB_URL         = "postgresql://cellcover:cellcover@localhost:5432/cellcover"
LOCAL_CONT_DIR = "/Users/lenam/tools/geoserver/data_dir/data/cellcover/continuous"
TILE_SIZE      = 256
NODATA         = np.float32(-3.4028235e+38)
SIGNAL_FLOOR   = np.float32(-170.0)  # below this → transparent

# ── SLD color ramp (from cellcover-continuous.sld) ───────────────────────────
# (dBm_value, R, G, B, alpha_0_to_255)
COLOR_STOPS = [
    (-170, 0x00, 0x20, 0x4D, 179),   # dark navy,  opacity 0.70
    (-150, 0x00, 0x33, 0x6F, 179),
    (-130, 0x1F, 0x4E, 0x79, 179),
    (-110, 0x2C, 0x78, 0x8E, 179),
    ( -95, 0x5F, 0xA0, 0x60, 179),
    ( -85, 0x9D, 0xBA, 0x46, 179),
    ( -75, 0xD2, 0xCE, 0x3E, 179),
    (   0, 0xFD, 0xE7, 0x25, 179),   # bright yellow
]
_stops_v  = np.array([s[0] for s in COLOR_STOPS], dtype=np.float32)
_stops_r  = np.array([s[1] for s in COLOR_STOPS], dtype=np.uint8)
_stops_g  = np.array([s[2] for s in COLOR_STOPS], dtype=np.uint8)
_stops_b  = np.array([s[3] for s in COLOR_STOPS], dtype=np.uint8)
_stops_a  = np.array([s[4] for s in COLOR_STOPS], dtype=np.uint8)

# ── DB connection pool ────────────────────────────────────────────────────────
_pool = psycopg2.pool.ThreadedConnectionPool(2, 10, DB_URL)

app = FastAPI()

# ── Helpers ───────────────────────────────────────────────────────────────────

def tile_to_bbox_4326(z: int, x: int, y: int):
    """XYZ tile → (lon_min, lat_min, lon_max, lat_max) in EPSG:4326."""
    n = 2 ** z
    lon_min = x / n * 360.0 - 180.0
    lon_max = (x + 1) / n * 360.0 - 180.0
    lat_max = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    lat_min = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return lon_min, lat_min, lon_max, lat_max


def apply_color_ramp(arr: np.ndarray) -> np.ndarray:
    """Map float32 dBm array → RGBA uint8 (256×256×4) using SLD stops."""
    rgba = np.zeros((TILE_SIZE, TILE_SIZE, 4), dtype=np.uint8)
    valid = (arr > SIGNAL_FLOOR) & np.isfinite(arr) & (arr != NODATA)
    if not np.any(valid):
        return rgba

    v = arr[valid]
    # Clamp to ramp range
    v_clamped = np.clip(v, _stops_v[0], _stops_v[-1])

    # Vectorised piecewise linear interpolation
    idx = np.searchsorted(_stops_v, v_clamped, side='right')
    idx = np.clip(idx, 1, len(_stops_v) - 1)
    lo, hi = idx - 1, idx

    t = (v_clamped - _stops_v[lo]) / (_stops_v[hi] - _stops_v[lo] + 1e-9)
    t = np.clip(t, 0.0, 1.0)

    r = (_stops_r[lo] + t * (_stops_r[hi] - _stops_r[lo])).astype(np.uint8)
    g = (_stops_g[lo] + t * (_stops_g[hi] - _stops_g[lo])).astype(np.uint8)
    b = (_stops_b[lo] + t * (_stops_b[hi] - _stops_b[lo])).astype(np.uint8)
    a = (_stops_a[lo] + t * (_stops_a[hi] - _stops_a[lo])).astype(np.uint8)

    rgba[valid, 0] = r
    rgba[valid, 1] = g
    rgba[valid, 2] = b
    rgba[valid, 3] = a
    return rgba


def encode_png(rgba: np.ndarray) -> bytes:
    img = Image.fromarray(rgba, "RGBA")
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=False)
    return buf.getvalue()


def transparent_tile() -> bytes:
    return encode_png(np.zeros((TILE_SIZE, TILE_SIZE, 4), dtype=np.uint8))


# ── Endpoint ──────────────────────────────────────────────────────────────────

@app.get("/signal-tile/{z}/{x}/{y}")
def signal_tile(z: int, x: int, y: int):
    lon_min, lat_min, lon_max, lat_max = tile_to_bbox_4326(z, x, y)

    # Query visible cells intersecting this tile bbox
    conn = _pool.getconn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT cell_id
                FROM raster_coverages
                WHERE is_visible = true
                  AND bbox && ST_MakeEnvelope(%s, %s, %s, %s, 4326)
                ORDER BY avg_signal ASC NULLS FIRST
            """, (lon_min, lat_min, lon_max, lat_max))
            cells = [r[0] for r in cur.fetchall()]
    finally:
        _pool.putconn(conn)

    if not cells:
        return Response(content=transparent_tile(), media_type="image/png")

    # Per-pixel MAX composite
    result = np.full((TILE_SIZE, TILE_SIZE), NODATA, dtype=np.float32)

    for cell_id in cells:
        path = os.path.join(LOCAL_CONT_DIR, f"{cell_id}.tif")
        if not os.path.exists(path):
            continue
        try:
            with rio_open(path) as src:
                win = from_bounds(lon_min, lat_min, lon_max, lat_max, src.transform)
                data = src.read(
                    1,
                    window=win,
                    out_shape=(TILE_SIZE, TILE_SIZE),
                    resampling=Resampling.bilinear,
                    boundless=True,
                    fill_value=NODATA,
                )
                valid = (data > SIGNAL_FLOOR) & np.isfinite(data) & (data != NODATA)
                # True per-pixel MAX
                result = np.where(valid & (data > result), data, result)
        except Exception:
            continue

    rgba = apply_color_ramp(result)
    return Response(content=encode_png(rgba), media_type="image/png")


@app.get("/health")
def health():
    return {"status": "ok"}
