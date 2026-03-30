#!/usr/bin/env python3
"""
restructure_raster_dir.py
--------------------------
Reorganizes a flat raster directory into per-cell subdirectories expected
by CellImportService:

  BEFORE (flat):
    cell-data/
      DNG0002_1.bil
      DNG0002_1.hdr
      DNG0008_1.bil
      DNG0008_1.hdr
      ...

  AFTER (structured):
    cell-data/
      DNG0002_1/
        DNG0002_1.bil
        DNG0002_1.hdr
      DNG0008_1/
        DNG0008_1.bil
        DNG0008_1.hdr
      ...

Usage:
    # Dry run (preview only, no files moved):
    python3 restructure_raster_dir.py /path/to/cell-data

    # Actually move files:
    python3 restructure_raster_dir.py /path/to/cell-data --apply
"""

import argparse
import sys
from pathlib import Path


RASTER_EXTENSIONS = {".bil", ".hdr", ".prj", ".blw", ".stx", ".aux"}


def collect_cells(raster_dir: Path) -> dict[str, list[Path]]:
    """Returns a dict of cellId -> list of associated files (all extensions)."""
    cells: dict[str, list[Path]] = {}
    for f in raster_dir.iterdir():
        if not f.is_file():
            continue
        if f.suffix.lower() not in RASTER_EXTENSIONS:
            continue
        cell_id = f.stem
        cells.setdefault(cell_id, []).append(f)
    return cells


def restructure(raster_dir: Path, apply: bool) -> None:
    if not raster_dir.is_dir():
        print(f"ERROR: directory not found: {raster_dir}", file=sys.stderr)
        sys.exit(1)

    cells = collect_cells(raster_dir)
    if not cells:
        print("No raster files found.")
        return

    # Only process cells that have a .bil file
    bil_cells = {cid: files for cid, files in cells.items()
                 if any(f.suffix.lower() == ".bil" for f in files)}
    skipped = set(cells) - set(bil_cells)

    print(f"Found {len(bil_cells)} cell(s) with .bil files.")
    if skipped:
        print(f"Skipped {len(skipped)} stem(s) with no .bil (e.g. {next(iter(skipped))!r})")
    print(f"Mode: {'APPLY (moving files)' if apply else 'DRY RUN (no changes)'}")
    print()

    moved = 0
    errors = 0

    for cell_id, files in sorted(bil_cells.items()):
        target_dir = raster_dir / cell_id

        # Skip cells already in their own subdirectory
        if all(f.parent == target_dir for f in files):
            continue

        print(f"  {cell_id}/")
        for f in sorted(files):
            dest = target_dir / f.name
            print(f"    {f.name} -> {cell_id}/{f.name}")
            if apply:
                try:
                    target_dir.mkdir(exist_ok=True)
                    f.rename(dest)
                    moved += 1
                except Exception as e:
                    print(f"    ERROR moving {f.name}: {e}", file=sys.stderr)
                    errors += 1

    print()
    if apply:
        print(f"Done. Moved {moved} file(s), {errors} error(s).")
    else:
        print("Dry run complete. Run with --apply to move files.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Restructure flat raster directory into per-cell subdirectories."
    )
    parser.add_argument("raster_dir", help="Path to the flat raster directory")
    parser.add_argument(
        "--apply", action="store_true",
        help="Actually move files (default is dry run)"
    )
    args = parser.parse_args()

    restructure(Path(args.raster_dir).expanduser().resolve(), args.apply)


if __name__ == "__main__":
    main()
