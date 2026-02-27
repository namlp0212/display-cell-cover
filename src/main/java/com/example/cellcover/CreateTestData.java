package com.example.cellcover;

import org.locationtech.proj4j.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CreateTestData {

    private static final String BASE_DIR = "/Users/lenam/Documents/DisplayCellCover/data/rasters/";
    private static final CRSFactory crsFactory = new CRSFactory();
    private static final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private static double[] latLonToUtm(int zone, double lat, double lon) {
        CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem utm = crsFactory.createFromName("EPSG:326" + zone);
        CoordinateTransform t = ctFactory.createTransform(wgs84, utm);
        ProjCoordinate src = new ProjCoordinate(lon, lat);
        ProjCoordinate dst = new ProjCoordinate();
        t.transform(src, dst);
        return new double[]{dst.x, dst.y};
    }

    private static int utmZoneFromLon(double lon) {
        return (int) Math.floor((lon + 180) / 6) + 1;
    }

    private static String utmPrjWkt(int zone) {
        int cm = (zone - 1) * 6 - 180 + 3;
        return "PROJCS[\"WGS 84 / UTM zone " + zone + "N\","
                + "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
                + "SPHEROID[\"WGS 84\",6378137,298.257223563,AUTHORITY[\"EPSG\",\"7030\"]],"
                + "AUTHORITY[\"EPSG\",\"6326\"]],"
                + "PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],"
                + "UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],"
                + "AUTHORITY[\"EPSG\",\"4326\"]],"
                + "PROJECTION[\"Transverse_Mercator\"],"
                + "PARAMETER[\"latitude_of_origin\",0],"
                + "PARAMETER[\"central_meridian\"," + cm + "],"
                + "PARAMETER[\"scale_factor\",0.9996],"
                + "PARAMETER[\"false_easting\",500000],"
                + "PARAMETER[\"false_northing\",0],"
                + "UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],"
                + "AXIS[\"Easting\",EAST],AXIS[\"Northing\",NORTH],"
                + "AUTHORITY[\"EPSG\",\"326" + zone + "\"]]";
    }

    /**
     * Generate a single cell at a specific lat/lon position by cloning a template.
     *
     * @param templateCell source cell to clone (e.g. "BNH0034_3")
     * @param outputPrefix prefix for generated cell names
     * @param type         0 = continuous (.bil), 1 = binary (.svr.bil)
     * @param lat          center latitude of the cell (WGS84)
     * @param lon          center longitude of the cell (WGS84)
     * @param idx          index for cell naming
     */
    private static void generateSingleCell(String templateCell, String outputPrefix, int type,
                                            double lat, double lon, int idx) throws IOException {
        String fileType = type == 0 ? "" : ".svr";
        String templateBil = BASE_DIR + templateCell + "/" + templateCell + fileType + ".bil";
        String templateHdr = BASE_DIR + templateCell + "/" + templateCell + fileType + ".hdr";

        List<String> lines = Files.readAllLines(Paths.get(templateHdr));
        int ulxIndex = -1, ulyIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim().toLowerCase();
            if (line.startsWith("ulxmap")) ulxIndex = i;
            if (line.startsWith("ulymap")) ulyIndex = i;
        }
        if (ulxIndex == -1 || ulyIndex == -1) throw new RuntimeException("Missing ulxmap/ulymap in HDR");

        int zone = utmZoneFromLon(lon);
        double[] utm = latLonToUtm(zone, lat, lon);
        String prj = utmPrjWkt(zone);

        String cellName = outputPrefix + "_" + idx;
        String outputDir = BASE_DIR + cellName;
        Files.createDirectories(Paths.get(outputDir));

        List<String> newLines = new ArrayList<>(lines);
        newLines.set(ulxIndex, "ulxmap " + utm[0]);
        newLines.set(ulyIndex, "ulymap " + utm[1]);

        Files.write(Paths.get(outputDir, cellName + fileType + ".hdr"), newLines);
        Files.copy(Paths.get(templateBil),
                Paths.get(outputDir, cellName + fileType + ".bil"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(Paths.get(outputDir, cellName + fileType + ".prj"), prj);
    }

    /**
     * Generate random cells distributed in clusters across Hanoi.
     *
     * Cluster design (each cell is ~6.6km x 5.6km):
     *  - Dense clusters   : scatter < 2km  → cells nearly fully overlap → very high coverage density
     *  - Medium clusters  : scatter 3–5km  → partial overlap
     *  - Sparse clusters  : scatter 6–9km  → some cells touch, some are isolated
     *  - Large empty areas intentionally left uncovered (northeast, far west, far south)
     *
     * @param templateCell source cell to clone
     * @param prefix       output prefix (e.g. "HN")
     * @param rng          seeded random for reproducibility
     * @param clusters     array of {centerLat, centerLon, numCells, scatterKm}
     */
    private static int generateRandomClusters(String templateCell, String prefix,
                                               Random rng, double[][] clusters,
                                               int startIdx) throws IOException {
        int idx = startIdx;
        for (double[] cluster : clusters) {
            double cLat    = cluster[0];
            double cLon    = cluster[1];
            int    n       = (int) cluster[2];
            double scatter = cluster[3]; // km radius

            for (int i = 0; i < n; i++) {
                // Uniform distribution within a disk (not a square) for natural feel
                double angle = rng.nextDouble() * 2 * Math.PI;
                double r     = Math.sqrt(rng.nextDouble()) * scatter; // uniform in disk

                // km offset → degree offset
                double dLat = (r * Math.cos(angle)) / 111.0;
                double dLon = (r * Math.sin(angle)) / (111.0 * Math.cos(Math.toRadians(cLat)));

                double lat = cLat + dLat;
                double lon = cLon + dLon;

                generateSingleCell(templateCell, prefix, 0, lat, lon, idx);  // continuous
                generateSingleCell(templateCell, prefix, 1, lat, lon, idx);  // SVR binary
                idx++;
            }
        }
        return idx;
    }

    public static void main(String[] args) throws IOException {
        // Fixed seed → reproducible layout
        Random rng = new Random(2025);

        String template = "BNH0034_3";
        String prefix   = "HN";

        // ─────────────────────────────────────────────────────────────────
        // Cluster map of Hanoi (each cell ≈ 6.6km × 5.6km)
        //
        // Scatter radius guide vs. visual result:
        //   < 3km  → all cells completely overlap (dark blob)
        //   3–6km  → partial overlap, individual cells still visible
        //   6–9km  → cells mostly separate, occasional touching
        //  > 9km   → cells clearly isolated
        //
        //  [A] Hoàn Kiếm / phố cổ – densest zone
        //      8 cells, scatter 4km → significant overlap in center,
        //      individual cell edges visible around perimeter
        //
        //  [B] Ba Đình – moderately dense
        //      6 cells, scatter 5.5km → partial overlap, clear gaps
        //
        //  [C] Đống Đa / Hai Bà Trưng – medium
        //      5 cells, scatter 8km → mostly separate, 1–2 overlaps
        //
        //  [D] Tây Hồ – sparse
        //      3 cells, scatter 9km → clearly separate cells
        //
        //  [E] Thanh Xuân / Hà Đông – sparse
        //      3 cells, scatter 8km → clearly separate cells
        //
        //  [F] Single outposts – completely isolated
        //      1 cell at Cầu Giấy west, 1 at Hoàng Mai south
        //
        //  Empty zones: Long Biên (east), Gia Lâm (far east),
        //  far west, far south.
        // ─────────────────────────────────────────────────────────────────
        double[][] clusters = {
            // [A] Hoàn Kiếm / phố cổ – still densest, but cells visible
            { 21.0285, 105.8520,   8, 4.0 },

            // [B] Ba Đình
            { 21.0450, 105.8160,   6, 5.5 },

            // [C] Đống Đa / Hai Bà Trưng south
            { 21.0070, 105.8430,   5, 8.0 },

            // [D] Tây Hồ (peninsula, northwest)
            { 21.0720, 105.8265,   3, 9.0 },

            // [E] Thanh Xuân / Hà Đông fringe
            { 20.9850, 105.8100,   3, 8.0 },

            // [F1] Isolated – Cầu Giấy west
            { 21.0330, 105.7850,   1, 0.0 },

            // [F2] Isolated – Hoàng Mai south
            { 20.9700, 105.8530,   1, 0.0 },
        };

        System.out.println("=== Generating random Hanoi cell distribution ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 2025%n%n", template, prefix);

        String[] labels = {
            "A – Hoàn Kiếm (partial overlap)",
            "B – Ba Đình (moderate)",
            "C – Đống Đa/Hai Bà Trưng (sparse)",
            "D – Tây Hồ (isolated)",
            "E – Thanh Xuân/Hà Đông (isolated)",
            "F1 – Cầu Giấy outpost",
            "F2 – Hoàng Mai outpost",
        };

        int idx = 1;
        for (int ci = 0; ci < clusters.length; ci++) {
            double[] c = clusters[ci];
            int n = (int) c[2];
            System.out.printf("Cluster %s: %d cells, scatter=%.1f km, center=(%.4f°N, %.4f°E)%n",
                    labels[ci], n, c[3], c[0], c[1]);
            idx = generateRandomClusters(template, prefix, rng,
                    new double[][]{c}, idx);
        }

        int total = idx - 1;
        System.out.printf("%n=== Done: %d cells generated (HN_1 … HN_%d) ===%n", total, total);
        System.out.println("Delete old HN_* folders then re-import via /admin/sync.");
    }
}
