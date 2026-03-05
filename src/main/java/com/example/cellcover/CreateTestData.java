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
        // 500 cells phủ các huyện/quận NGOẠI THÀNH Hà Nội
        //
        // Vùng bao phủ:
        //  Đông Anh, Gia Lâm, Long Biên, Hoàng Mai, Thanh Trì,
        //  Hà Đông, Nam Từ Liêm, Bắc Từ Liêm, Sóc Sơn, Mê Linh,
        //  Thạch Thất, Quốc Oai, Chương Mỹ, Ba Vì,
        //  Thường Tín, Phú Xuyên, Ứng Hòa, Mỹ Đức
        //
        // {centerLat, centerLon, numCells, scatterKm}
        // ─────────────────────────────────────────────────────────────────
        double[][] clusters = {
            // ── PHÍA BẮC ──
            // [1] Đông Anh – trung tâm huyện
            { 21.1500, 105.8600,  50,  8.0 },
            // [2] Sóc Sơn – thị trấn Sóc Sơn
            { 21.2700, 105.8600,  20, 10.0 },
            // [3] Mê Linh – trung tâm huyện
            { 21.1850, 105.7350,  25,  8.0 },

            // ── PHÍA ĐÔNG ──
            // [4] Gia Lâm – trung tâm huyện
            { 21.0280, 105.9330,  40,  7.0 },
            // [5] Long Biên – phía đông sông Hồng
            { 21.0480, 105.8950,  30,  5.0 },

            // ── PHÍA NAM ──
            // [6] Hoàng Mai – nam thành phố
            { 20.9720, 105.8530,  40,  6.0 },
            // [7] Thanh Trì – giáp ranh Hoàng Mai
            { 20.9200, 105.8550,  30,  6.0 },
            // [8] Thường Tín – nam Thanh Trì
            { 20.8700, 105.8600,  25,  7.0 },
            // [9] Phú Xuyên – far south
            { 20.7400, 105.9100,  20,  8.0 },

            // ── PHÍA TÂY NAM ──
            // [10] Hà Đông – thành phố Hà Đông
            { 20.9700, 105.7750,  50,  8.0 },
            // [11] Chương Mỹ – tây nam Hà Đông
            { 20.9200, 105.7150,  30,  9.0 },
            // [12] Ứng Hòa – cực nam
            { 20.7100, 105.7700,  15, 10.0 },
            // [13] Mỹ Đức – cực tây nam
            { 20.7200, 105.6500,  10, 10.0 },

            // ── PHÍA TÂY ──
            // [14] Nam Từ Liêm – tây nam nội thành
            { 21.0050, 105.7650,  30,  5.0 },
            // [15] Bắc Từ Liêm – tây bắc nội thành
            { 21.0600, 105.7600,  25,  5.0 },
            // [16] Quốc Oai – tây Hà Nội
            { 21.0020, 105.6350,  25,  8.0 },
            // [17] Thạch Thất – far west
            { 21.0350, 105.5600,  20, 10.0 },
            // [18] Ba Vì – cực tây
            { 21.0800, 105.4100,  15, 12.0 },
        };

        System.out.println("=== Generating 500-cell suburban Hanoi distribution ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 2025%n%n", template, prefix);

        String[] labels = {
            "01 – Đông Anh (bắc)",
            "02 – Sóc Sơn (far north)",
            "03 – Mê Linh (tây bắc)",
            "04 – Gia Lâm (đông)",
            "05 – Long Biên (đông sông Hồng)",
            "06 – Hoàng Mai (nam)",
            "07 – Thanh Trì (nam)",
            "08 – Thường Tín (nam xa)",
            "09 – Phú Xuyên (cực nam)",
            "10 – Hà Đông (tây nam)",
            "11 – Chương Mỹ (tây nam xa)",
            "12 – Ứng Hòa (cực nam)",
            "13 – Mỹ Đức (cực tây nam)",
            "14 – Nam Từ Liêm (tây)",
            "15 – Bắc Từ Liêm (tây bắc)",
            "16 – Quốc Oai (tây)",
            "17 – Thạch Thất (far west)",
            "18 – Ba Vì (cực tây)",
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

        // ─────────────────────────────────────────────────────────────────
        // 500 cells phủ các tỉnh VÙng lân cận Hà Nội
        //
        // Tỉnh: Vĩnh Phúc, Phú Thọ, Thái Nguyên, Bắc Giang, Bắc Ninh,
        //        Hưng Yên, Hải Dương, Thái Bình, Hà Nam, Nam Định,
        //        Ninh Bình, Hòa Bình, Tuyên Quang, Hải Phòng, Quảng Ninh
        // ─────────────────────────────────────────────────────────────────
        String prefixVL = "VL";
        double[][] clustersVL = {
            // ── PHÍA TÂY BẮC ──
            // [1] Vĩnh Phúc – TP Vĩnh Yên
            { 21.3100, 105.5970,  40, 12.0 },
            // [2] Phú Thọ – TP Việt Trì
            { 21.3990, 105.2370,  30, 12.0 },
            // [3] Tuyên Quang – TP Tuyên Quang
            { 21.8200, 105.2180,  20, 12.0 },

            // ── PHÍA BẮC ──
            // [4] Thái Nguyên – TP Thái Nguyên
            { 21.5920, 105.8480,  40, 12.0 },

            // ── PHÍA ĐÔNG BẮC ──
            // [5] Bắc Giang – TP Bắc Giang
            { 21.2730, 106.1940,  40, 12.0 },
            // [6] Quảng Ninh – TP Hạ Long
            { 20.9510, 107.0800,  20, 10.0 },

            // ── PHÍA ĐÔNG ──
            // [7] Bắc Ninh – TP Bắc Ninh
            { 21.1860, 106.0760,  35,  8.0 },
            // [8] Hải Dương – TP Hải Dương
            { 20.9400, 106.3320,  40, 10.0 },
            // [9] Hải Phòng – trung tâm
            { 20.8560, 106.6840,  30, 10.0 },

            // ── PHÍA ĐÔNG NAM ──
            // [10] Hưng Yên – TP Hưng Yên
            { 20.6460, 106.0510,  35, 10.0 },
            // [11] Thái Bình – TP Thái Bình
            { 20.4500, 106.3420,  30, 12.0 },

            // ── PHÍA NAM ──
            // [12] Hà Nam – TP Phủ Lý
            { 20.5410, 105.9070,  30, 10.0 },
            // [13] Nam Định – TP Nam Định
            { 20.4220, 106.1770,  35, 12.0 },
            // [14] Ninh Bình – TP Ninh Bình
            { 20.2530, 105.9750,  30, 12.0 },

            // ── PHÍA TÂY NAM ──
            // [15] Hòa Bình – TP Hòa Bình
            { 20.8170, 105.3380,  45, 12.0 },
        };

        String[] labelsVL = {
            "01 – Vĩnh Phúc",
            "02 – Phú Thọ",
            "03 – Tuyên Quang",
            "04 – Thái Nguyên",
            "05 – Bắc Giang",
            "06 – Quảng Ninh",
            "07 – Bắc Ninh",
            "08 – Hải Dương",
            "09 – Hải Phòng",
            "10 – Hưng Yên",
            "11 – Thái Bình",
            "12 – Hà Nam",
            "13 – Nam Định",
            "14 – Ninh Bình",
            "15 – Hòa Bình",
        };

        System.out.println("\n=== Generating 500-cell surrounding-province distribution ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 2025%n%n", template, prefixVL);

        int idxVL = 1;
        for (int ci = 0; ci < clustersVL.length; ci++) {
            double[] c = clustersVL[ci];
            int n = (int) c[2];
            System.out.printf("Cluster %s: %d cells, scatter=%.1f km, center=(%.4f°N, %.4f°E)%n",
                    labelsVL[ci], n, c[3], c[0], c[1]);
            idxVL = generateRandomClusters(template, prefixVL, rng,
                    new double[][]{c}, idxVL);
        }

        int totalVL = idxVL - 1;
        System.out.printf("%n=== Done: %d cells generated (VL_1 … VL_%d) ===%n", totalVL, totalVL);
        System.out.println("Re-import via /api/admin/sync-cells.");

        // ─────────────────────────────────────────────────────────────────
        // 1000 cells bổ sung cho các tỉnh lân cận Hà Nội (đợt 2)
        // Dùng seed khác (2026) để tạo vị trí ngẫu nhiên mới
        // ─────────────────────────────────────────────────────────────────
        Random rng2 = new Random(2026);
        String prefixT2 = "T2";
        double[][] clustersT2 = {
            // Vĩnh Phúc – scatter rộng hơn để phủ toàn tỉnh
            { 21.3100, 105.5970,  80, 14.0 },
            // Phú Thọ
            { 21.3990, 105.2370,  60, 14.0 },
            // Tuyên Quang
            { 21.8200, 105.2180,  40, 14.0 },
            // Thái Nguyên
            { 21.5920, 105.8480,  80, 14.0 },
            // Bắc Giang
            { 21.2730, 106.1940,  80, 14.0 },
            // Quảng Ninh
            { 20.9510, 107.0800,  40, 12.0 },
            // Bắc Ninh
            { 21.1860, 106.0760,  70, 10.0 },
            // Hải Dương
            { 20.9400, 106.3320,  80, 12.0 },
            // Hải Phòng
            { 20.8560, 106.6840,  60, 12.0 },
            // Hưng Yên
            { 20.6460, 106.0510,  70, 12.0 },
            // Thái Bình
            { 20.4500, 106.3420,  60, 14.0 },
            // Hà Nam
            { 20.5410, 105.9070,  60, 12.0 },
            // Nam Định
            { 20.4220, 106.1770,  70, 14.0 },
            // Ninh Bình
            { 20.2530, 105.9750,  60, 14.0 },
            // Hòa Bình
            { 20.8170, 105.3380,  90, 14.0 },
        };

        String[] labelsT2 = {
            "01 – Vĩnh Phúc",
            "02 – Phú Thọ",
            "03 – Tuyên Quang",
            "04 – Thái Nguyên",
            "05 – Bắc Giang",
            "06 – Quảng Ninh",
            "07 – Bắc Ninh",
            "08 – Hải Dương",
            "09 – Hải Phòng",
            "10 – Hưng Yên",
            "11 – Thái Bình",
            "12 – Hà Nam",
            "13 – Nam Định",
            "14 – Ninh Bình",
            "15 – Hòa Bình",
        };

        System.out.println("\n=== Generating 1000-cell surrounding-province distribution (batch 2) ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 2026%n%n", template, prefixT2);

        int idxT2 = 1;
        for (int ci = 0; ci < clustersT2.length; ci++) {
            double[] c = clustersT2[ci];
            int n = (int) c[2];
            System.out.printf("Cluster %s: %d cells, scatter=%.1f km, center=(%.4f°N, %.4f°E)%n",
                    labelsT2[ci], n, c[3], c[0], c[1]);
            idxT2 = generateRandomClusters(template, prefixT2, rng2,
                    new double[][]{c}, idxT2);
        }

        int totalT2 = idxT2 - 1;
        System.out.printf("%n=== Done: %d cells generated (T2_1 … T2_%d) ===%n", totalT2, totalT2);
        System.out.println("Re-import via /api/admin/sync-cells.");
    }
}
