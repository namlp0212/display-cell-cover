#!/usr/bin/env bash
# import-coverages.sh — Parse .hdr files and populate raster_coverages table
# Run from the project root: bash docker/import-coverages.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RASTERS_DIR="$PROJECT_DIR/data/rasters"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5434}"
DB_NAME="${DB_NAME:-cellcover}"
DB_USER="${DB_USER:-cellcover}"
export PGPASSWORD="${DB_PASSWORD:-cellcover}"

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -v ON_ERROR_STOP=1"

echo "=== Importing raster coverages ==="
echo "Database: $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
echo "Rasters:  $RASTERS_DIR"

# Truncate for idempotency
$PSQL -c "TRUNCATE raster_coverages RESTART IDENTITY;"
echo "Truncated raster_coverages table."

count=0

for hdr in "$RASTERS_DIR"/*/*.hdr; do
    # Skip .svr.hdr files — they have identical coordinates
    case "$hdr" in
        *.svr.hdr) continue ;;
    esac

    dir_name="$(basename "$(dirname "$hdr")")"
    cell_id="$dir_name"
    base_name="$(basename "$hdr" .hdr)"

    # Parse spatial fields from .hdr
    ulxmap=$(awk '/^ulxmap/ {print $2}' "$hdr")
    ulymap=$(awk '/^ulymap/ {print $2}' "$hdr")
    xdim=$(awk '/^xdim/ {print $2}' "$hdr")
    ydim=$(awk '/^ydim/ {print $2}' "$hdr")
    ncols=$(awk '/^ncols/ {print $2}' "$hdr")
    nrows=$(awk '/^nrows/ {print $2}' "$hdr")

    # Compute UTM bbox
    bbox_values=$(awk -v ulx="$ulxmap" -v uly="$ulymap" \
        -v xd="$xdim" -v yd="$ydim" \
        -v nc="$ncols" -v nr="$nrows" \
        'BEGIN {
            minx = ulx
            maxy = uly
            maxx = ulx + nc * xd
            miny = uly - nr * yd
            printf "%.6f %.6f %.6f %.6f", minx, miny, maxx, maxy
        }')

    minx=$(echo "$bbox_values" | awk '{print $1}')
    miny=$(echo "$bbox_values" | awk '{print $2}')
    maxx=$(echo "$bbox_values" | awk '{print $3}')
    maxy=$(echo "$bbox_values" | awk '{print $4}')

    echo "  $cell_id: UTM bbox ($minx, $miny, $maxx, $maxy)"

    # Insert row for .bil (is_svr = false)
    bil_path="data/rasters/${dir_name}/${base_name}.bil"
    $PSQL -c "INSERT INTO raster_coverages (cell_id, file_path, bbox, crs, is_svr, is_visible)
        VALUES (
            '${cell_id}',
            '${bil_path}',
            ST_Transform(ST_MakeEnvelope(${minx}, ${miny}, ${maxx}, ${maxy}, 32647), 4326),
            'EPSG:4326',
            false,
            true
        );"

    # Insert row for .svr.bil (is_svr = true)
    svr_path="data/rasters/${dir_name}/${base_name}.svr.bil"
    $PSQL -c "INSERT INTO raster_coverages (cell_id, file_path, bbox, crs, is_svr, is_visible)
        VALUES (
            '${cell_id}',
            '${svr_path}',
            ST_Transform(ST_MakeEnvelope(${minx}, ${miny}, ${maxx}, ${maxy}, 32647), 4326),
            'EPSG:4326',
            true,
            true
        );"

    count=$((count + 1))
done

total=$((count * 2))
echo "=== Done: inserted $total rows ($count cells x 2) ==="

# Verify
echo ""
echo "Verification:"
$PSQL -c "SELECT cell_id, is_svr, file_path, ST_AsText(ST_SnapToGrid(bbox, 0.0001)) AS bbox_wgs84 FROM raster_coverages ORDER BY cell_id, is_svr;"
