#!/usr/bin/env python3
"""
Converts missing .bil files to continuous COG .tif files.
Mirrors BilToCogConverter.convertFileContinuous() logic.
"""

import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

RASTER_DIR = Path("data/rasters")
COG_DIR    = Path("data/cog/continuous")
NODATA     = "-3.4028235e+38"
THREADS    = 8


NUMERIC_HDR_KEYS = {"ncols", "nrows", "ulxmap", "ulymap", "xdim", "ydim"}

def parse_hdr(hdr_path: Path) -> dict:
    values = {}
    for line in hdr_path.read_text().splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            key = parts[0].lower()
            if key in NUMERIC_HDR_KEYS:
                values[key] = float(parts[1])
    for key in NUMERIC_HDR_KEYS:
        if key not in values:
            raise ValueError(f"Missing HDR field: {key}")
    return values


def detect_epsg(cell_dir: Path, cell_id: str) -> str:
    prj = cell_dir / f"{cell_id}.prj"
    if prj.exists():
        txt = prj.read_text()
        m = re.search(r'AUTHORITY\["EPSG","(326\d+)"\]\s*\]\s*$', txt)
        if m:
            return f"EPSG:{m.group(1)}"
    # Infer from ulxmap
    hdr = cell_dir / f"{cell_id}.hdr"
    if hdr.exists():
        for line in hdr.read_text().splitlines():
            parts = line.strip().split(None, 1)
            if parts and parts[0].lower() == "ulxmap":
                easting = float(parts[1])
                return "EPSG:32649" if easting > 900_000 else "EPSG:32648"
    return "EPSG:32648"


def convert_cell(cell_id: str) -> str:
    cell_dir = RASTER_DIR / cell_id
    bil      = cell_dir / f"{cell_id}.bil"
    out_tif  = COG_DIR / f"{cell_id}.tif"

    if not bil.exists():
        return f"SKIP (no .bil): {cell_id}"

    hdr    = parse_hdr(cell_dir / f"{cell_id}.hdr")
    epsg   = detect_epsg(cell_dir, cell_id)
    ncols  = int(hdr["ncols"])
    nrows  = int(hdr["nrows"])
    xdim   = hdr["xdim"]
    ydim   = hdr["ydim"]
    min_x  = hdr["ulxmap"] - xdim / 2.0
    max_y  = hdr["ulymap"] + ydim / 2.0

    vrt_xml = (
        f'<VRTDataset rasterXSize="{ncols}" rasterYSize="{nrows}">\n'
        f'  <SRS dataAxisToSRSAxisMapping="1,2">{epsg}</SRS>\n'
        f'  <GeoTransform>{min_x:.6f}, {xdim:.6f}, 0.0, {max_y:.6f}, 0.0, -{ydim:.6f}</GeoTransform>\n'
        f'  <VRTRasterBand dataType="Float32" band="1" subClass="VRTRawRasterBand">\n'
        f'    <SourceFilename relativeToVRT="0">{bil.resolve()}</SourceFilename>\n'
        f'    <ByteOrder>LSB</ByteOrder>\n'
        f'    <ImageOffset>0</ImageOffset>\n'
        f'    <PixelOffset>4</PixelOffset>\n'
        f'    <LineOffset>{ncols * 4}</LineOffset>\n'
        f'  </VRTRasterBand>\n'
        f'</VRTDataset>\n'
    )

    with tempfile.NamedTemporaryFile(suffix=".vrt", mode="w", delete=False) as f:
        f.write(vrt_xml)
        vrt_path = f.name

    try:
        env = os.environ.copy()
        env["GDAL_VRT_RAWRASTERBAND_ALLOWED_SOURCE"] = "ALL"

        result = subprocess.run(
            ["gdalwarp",
             "-t_srs", "EPSG:4326",
             "-r", "bilinear",
             "-ot", "Float32",
             "-dstnodata", NODATA,
             "-of", "GTiff",
             "-co", "TILED=YES",
             "-co", "BLOCKXSIZE=256",
             "-co", "BLOCKYSIZE=256",
             "-co", "COMPRESS=DEFLATE",
             vrt_path,
             str(out_tif)],
            capture_output=True, text=True, env=env
        )
        if result.returncode != 0:
            raise RuntimeError(f"gdalwarp failed: {result.stdout}{result.stderr}")

        result2 = subprocess.run(
            ["gdaladdo", "--config", "COMPRESS_OVERVIEW", "DEFLATE",
             "-r", "average",
             str(out_tif), "2", "4", "8"],
            capture_output=True, text=True
        )
        if result2.returncode != 0:
            raise RuntimeError(f"gdaladdo failed: {result2.stdout}{result2.stderr}")

    finally:
        os.unlink(vrt_path)

    return f"OK: {cell_id}"


def main():
    COG_DIR.mkdir(parents=True, exist_ok=True)

    existing = {p.stem for p in COG_DIR.glob("*.tif")}
    all_cells = [
        d.name for d in RASTER_DIR.iterdir()
        if d.is_dir() and (d / f"{d.name}.bil").exists()
    ]
    missing = [c for c in all_cells if c not in existing]

    print(f"Total cells with .bil: {len(all_cells)}")
    print(f"Already converted:     {len(existing)}")
    print(f"To convert:            {len(missing)}")

    if not missing:
        print("Nothing to do.")
        return

    done = 0
    errors = []

    with ThreadPoolExecutor(max_workers=THREADS) as executor:
        futures = {executor.submit(convert_cell, cid): cid for cid in missing}
        for fut in as_completed(futures):
            cid = futures[fut]
            try:
                msg = fut.result()
                done += 1
                if done % 50 == 0 or done == len(missing):
                    print(f"  [{done}/{len(missing)}] {msg}")
            except Exception as e:
                errors.append(cid)
                print(f"  ERROR {cid}: {e}", file=sys.stderr)

    print(f"\nDone: {done - len(errors)} converted, {len(errors)} errors.")
    if errors:
        print("Failed cells:", errors)


if __name__ == "__main__":
    main()
