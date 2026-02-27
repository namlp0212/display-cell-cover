#!/bin/bash
# GeoServer initialization script
# Converts .bil rasters to Cloud Optimized GeoTIFF (COG), then creates workspace, coverage stores, and layers
# Run from host: docker exec cellcover-geoserver /bin/bash /opt/geoserver-init.sh

set -euo pipefail

GEOSERVER_URL="http://localhost:8080/geoserver"
ADMIN_USER="admin"
ADMIN_PASS="${GEOSERVER_ADMIN_PASSWORD:-geoserver}"
WORKSPACE="cellcover"
RASTER_DIR="/data/rasters"
COG_DIR="/data/cog"
NODATA="-3.4028235e+38"

AUTH="${ADMIN_USER}:${ADMIN_PASS}"

echo "=== GeoServer Initialization ==="

# 1. Wait for GeoServer to be healthy
echo "Waiting for GeoServer to be ready..."
until curl -sf "${GEOSERVER_URL}/web/" > /dev/null 2>&1; do
  echo "  GeoServer not ready, retrying in 5s..."
  sleep 5
done
echo "GeoServer is ready."

# 2. Create workspace (ignore error if it already exists)
echo "Creating workspace '${WORKSPACE}'..."
WORKSPACE_JSON='{"workspace":{"name":"'"${WORKSPACE}"'"}}'
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
  -XPOST "${GEOSERVER_URL}/rest/workspaces" \
  -H "Content-Type: application/json" \
  -d "${WORKSPACE_JSON}")

if [ "$HTTP_CODE" = "201" ]; then
  echo "  Workspace created."
elif [ "$HTTP_CODE" = "401" ]; then
  echo "  ERROR: Authentication failed. Check GEOSERVER_ADMIN_PASSWORD." >&2
  exit 1
else
  echo "  Workspace already exists (HTTP ${HTTP_CODE}), continuing."
fi

# 3. Convert .bil files to COG, organized into two mosaic directories
mkdir -p "${COG_DIR}/continuous" "${COG_DIR}/binary"

for BIL_FILE in "${RASTER_DIR}"/*/*.bil; do
  [ -f "$BIL_FILE" ] || continue

  FILENAME=$(basename "$BIL_FILE")

  # Derive layer name: BNH0019_1.bil -> BNH0019_1, BNH0019_1.svr.bil -> BNH0019_1_svr
  LAYER_NAME="${FILENAME%.bil}"
  LAYER_NAME="${LAYER_NAME//./_}"

  # Route SVR files to binary/, standard files to continuous/
  if [[ "${LAYER_NAME}" == *_svr ]]; then
    COG_FILE="${COG_DIR}/binary/${LAYER_NAME}.tif"
  else
    COG_FILE="${COG_DIR}/continuous/${LAYER_NAME}.tif"
  fi

  echo "Converting ${FILENAME} -> ${COG_FILE}..."

  # Convert BIL to COG (skip if already converted)
  if [ ! -f "${COG_FILE}" ]; then
    echo "  Converting to COG..."

    # Fix: GDAL doesn't recognize "datatype R32" in HDR, defaults to UInt32.
    # Create temp copy of HDR with PIXELTYPE FLOAT so GDAL reads as Float32.
    BIL_DIR=$(dirname "$BIL_FILE")
    HDR_FILE="${BIL_DIR}/${FILENAME%.bil}.hdr"
    FIX_DIR="/tmp/bil-fix"
    mkdir -p "${FIX_DIR}"
    ln -sf "$BIL_FILE" "${FIX_DIR}/${FILENAME}"
    if [ -f "$HDR_FILE" ]; then
      cp "$HDR_FILE" "${FIX_DIR}/${FILENAME%.bil}.hdr"
      if ! grep -q "PIXELTYPE" "${FIX_DIR}/${FILENAME%.bil}.hdr"; then
        echo "PIXELTYPE FLOAT" >> "${FIX_DIR}/${FILENAME%.bil}.hdr"
      fi
    fi
    # Also copy .prj file if it exists
    PRJ_FILE="${BIL_DIR}/${FILENAME%.bil}.prj"
    [ -f "$PRJ_FILE" ] && cp "$PRJ_FILE" "${FIX_DIR}/${FILENAME%.bil}.prj"

    if ! gdalwarp -of COG -ot Float32 -t_srs EPSG:4326 \
      -srcnodata "${NODATA}" -dstnodata "${NODATA}" \
      -co COMPRESS=DEFLATE -co OVERVIEW_RESAMPLING=AVERAGE -co BLOCKSIZE=256 \
      -q "${FIX_DIR}/${FILENAME}" "${COG_FILE}" 2>/dev/null; then
      echo "  WARNING: GDAL conversion failed for ${FILENAME}, skipping."
      rm -f "${COG_FILE}"
      rm -rf "${FIX_DIR}"
      continue
    fi
    rm -rf "${FIX_DIR}"
  else
    echo "  COG already exists, reusing."
  fi
done

# 4. Fix directory ownership so GeoServer (runs as geoserveruser) can write index files
echo ""
echo "Fixing mosaic directory ownership for GeoServer..."
chown -R geoserveruser:geoserverusers "${COG_DIR}/continuous" "${COG_DIR}/binary"
echo "  Done."

# 5. Upload and register styles (cellcover-binary + cellcover-continuous)
for STYLE_NAME in cellcover-binary cellcover-continuous; do
  SLD_FILE="/data/styles/${STYLE_NAME}.sld"

  if [ -f "${SLD_FILE}" ]; then
    echo ""
    echo "Registering style '${STYLE_NAME}'..."

    # Step 1: Create the style entry via JSON (ignore if exists)
    STYLE_JSON='{"style":{"name":"'"${STYLE_NAME}"'","filename":"'"${STYLE_NAME}.sld"'"}}'
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
      -XPOST "${GEOSERVER_URL}/rest/styles" \
      -H "Content-Type: application/json" \
      -d "${STYLE_JSON}")

    if [ "$HTTP_CODE" = "201" ]; then
      echo "  Style entry created."
    else
      echo "  Style entry already exists (HTTP ${HTTP_CODE}), will update SLD."
    fi

    # Step 2: Upload SLD content to the style
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
      -XPUT "${GEOSERVER_URL}/rest/styles/${STYLE_NAME}" \
      -H "Content-Type: application/vnd.ogc.sld+xml" \
      -d @"${SLD_FILE}")

    if [ "$HTTP_CODE" = "200" ]; then
      echo "  SLD content uploaded successfully."
    else
      echo "  WARNING: SLD upload returned HTTP ${HTTP_CODE}."
    fi
  else
    echo "WARNING: SLD file not found at ${SLD_FILE}, skipping."
  fi
done

# 6. Create 2 ImageMosaic stores + layers via REST API
echo ""
echo "Creating ImageMosaic stores and layers..."
for MOSAIC_TYPE in continuous binary; do
  MOSAIC_DIR="${COG_DIR}/${MOSAIC_TYPE}"
  STORE_NAME="${MOSAIC_TYPE}"
  LAYER_NAME="${MOSAIC_TYPE}"

  # Skip if no COGs in this directory
  shopt -s nullglob
  COG_FILES=("${MOSAIC_DIR}"/*.tif)
  shopt -u nullglob
  if [ ${#COG_FILES[@]} -eq 0 ]; then
    echo "  No COGs in ${MOSAIC_TYPE}/, skipping."
    continue
  fi

  if [[ "${MOSAIC_TYPE}" == "binary" ]]; then
    STYLE_NAME="cellcover-binary"
  else
    STYLE_NAME="cellcover-continuous"
  fi

  echo "  Creating coverageStore '${STORE_NAME}'..."

  # Create ImageMosaic coverage store (GeoServer auto-scans the directory)
  STORE_JSON='{
    "coverageStore": {
      "name": "'"${STORE_NAME}"'",
      "type": "ImageMosaic",
      "enabled": true,
      "workspace": { "name": "'"${WORKSPACE}"'" },
      "url": "file:///data/cog/'"${MOSAIC_TYPE}"'"
    }
  }'

  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
    -XPOST "${GEOSERVER_URL}/rest/workspaces/${WORKSPACE}/coveragestores" \
    -H "Content-Type: application/json" \
    -d "${STORE_JSON}")

  if [ "$HTTP_CODE" = "201" ]; then
    echo "    Store created."
  else
    echo "    Store creation returned HTTP ${HTTP_CODE} (may already exist)."
  fi

  # Create/publish coverage (GeoServer auto-discovers from the store)
  echo "  Publishing coverage '${LAYER_NAME}'..."
  COVERAGE_JSON='{
    "coverage": {
      "name": "'"${LAYER_NAME}"'",
      "nativeName": "'"${LAYER_NAME}"'",
      "title": "'"${MOSAIC_TYPE}"' mosaic",
      "enabled": true
    }
  }'

  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
    -XPOST "${GEOSERVER_URL}/rest/workspaces/${WORKSPACE}/coveragestores/${STORE_NAME}/coverages" \
    -H "Content-Type: application/json" \
    -d "${COVERAGE_JSON}")

  if [ "$HTTP_CODE" = "201" ]; then
    echo "    Coverage published."
  else
    echo "    Coverage publish returned HTTP ${HTTP_CODE}."
  fi

  # Set MERGE_BEHAVIOR=STACK so overlapping granules composite (darker overlap areas)
  echo "  Setting MERGE_BEHAVIOR=STACK..."
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
    -XPUT "${GEOSERVER_URL}/rest/workspaces/${WORKSPACE}/coveragestores/${STORE_NAME}/coverages/${LAYER_NAME}.json" \
    -H "Content-Type: application/json" \
    -d '{"coverage":{"parameters":{"entry":[{"string":["MERGE_BEHAVIOR","STACK"]},{"string":["BackgroundValues","-3.4028235e+38"]}]}}}')

  if [ "$HTTP_CODE" = "200" ]; then
    echo "    MERGE_BEHAVIOR set."
  else
    echo "    WARNING: MERGE_BEHAVIOR update returned HTTP ${HTTP_CODE}."
  fi

  # Assign style to layer
  echo "  Applying style '${STYLE_NAME}' to layer '${LAYER_NAME}'..."
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "${AUTH}" \
    -XPUT "${GEOSERVER_URL}/rest/layers/${WORKSPACE}:${LAYER_NAME}" \
    -H "Content-Type: application/json" \
    -d '{"layer":{"defaultStyle":{"name":"'"${STYLE_NAME}"'"}}}')

  if [ "$HTTP_CODE" = "200" ]; then
    echo "    Style applied."
  else
    echo "    WARNING: Style apply returned HTTP ${HTTP_CODE}."
  fi
done

# 7. Add cell_id and ovlp_group attributes to GeoServer's auto-generated shapefile indexes
echo ""
echo "Adding cell_id and ovlp_group to mosaic indexes..."
python3 - <<'PYEOF'
import os, json
from osgeo import ogr

num_groups_all = {}

for mosaic_type in ["binary", "continuous"]:
    shp_path = f"/data/cog/{mosaic_type}/{mosaic_type}.shp"
    if not os.path.exists(shp_path):
        print(f"  {mosaic_type}: no index shapefile found, skipping.")
        continue

    ds = ogr.Open(shp_path, 1)
    layer = ds.GetLayer()

    # Add fields if they don't exist
    defn = layer.GetLayerDefn()
    field_names = [defn.GetFieldDefn(i).GetName() for i in range(defn.GetFieldCount())]
    if "cell_id" not in field_names:
        fd = ogr.FieldDefn("cell_id", ogr.OFTString)
        fd.SetWidth(64)
        layer.CreateField(fd)
    if "ovlp_group" not in field_names:
        fd = ogr.FieldDefn("ovlp_group", ogr.OFTInteger)
        layer.CreateField(fd)

    # Read features and assign ovlp_group = index % NUM_GROUPS
    # This cycles 0-7 across all granules. In dense overlap areas, all 8 groups
    # will have at least one cell covering any given point, so all 8 WMS sub-layers
    # contribute opacity → maximum visual density. Sparse areas get fewer groups → lighter.
    NUM_GROUPS = 8
    cell_data = []
    layer.ResetReading()
    feat = layer.GetNextFeature()
    while feat is not None:
        fid = feat.GetFID()
        location = feat.GetField("location")
        basename = os.path.splitext(os.path.basename(location))[0]
        if mosaic_type == "binary" and basename.endswith("_svr"):
            cell_id = basename[:-4]
        else:
            cell_id = basename
        cell_data.append((fid, cell_id))
        feat = layer.GetNextFeature()

    num_groups_all[mosaic_type] = NUM_GROUPS

    for idx, (fid, cell_id) in enumerate(cell_data):
        feat = layer.GetFeature(fid)
        feat.SetField("cell_id", cell_id)
        feat.SetField("ovlp_group", idx % NUM_GROUPS)
        layer.SetFeature(feat)

    ds.FlushCache()
    ds = None
    print(f"  {mosaic_type}: {len(cell_data)} features assigned to {NUM_GROUPS} groups")

# Write the number of groups to a file so the frontend can use it
with open("/data/cog/ovlp_groups.json", "w") as f:
    json.dump(num_groups_all, f)
    print(f"  Wrote ovlp_groups.json: {num_groups_all}")
PYEOF

# Reload GeoServer to pick up the updated shapefile schema
echo "Reloading GeoServer configuration..."
curl -s -o /dev/null -w "" -u "${AUTH}" \
  -XPOST "${GEOSERVER_URL}/rest/reload"
echo "  Done."

echo ""
echo "=== GeoServer initialization complete ==="
echo "Verify at: ${GEOSERVER_URL}/web/"
