package com.example.cellcover;

import org.locationtech.proj4j.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        // Use RELATIVE symlink so it works both on host and inside Docker container
        Path bilDst = Paths.get(outputDir, cellName + fileType + ".bil");
        if (!Files.exists(bilDst)) {
            Path relTarget = Paths.get(outputDir).toAbsolutePath()
                    .relativize(Paths.get(templateBil).toAbsolutePath());
            Files.createSymbolicLink(bilDst, relTarget);
        }
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
        Random rng = new Random(3000);
        String template = "BNH0034_3";
        String prefix   = "RC";

        // ─────────────────────────────────────────────────────────────────
        // DỮ LIỆU THỰC TẾ – Đồng bằng Bắc bộ + vùng phụ cận
        //
        // Nguyên tắc mật độ:
        //   Lõi đô thị  : scatter  2–4 km, 60–120 cell → phủ dày, nhiều lớp
        //   Ngoại ô/KCN : scatter  4–7 km,  20–55 cell → phủ vừa
        //   Nông thôn đb: scatter  6–10 km, 10–20 cell → thưa, ít chồng
        //   Vùng núi    : scatter  7–12 km,  3–10 cell → rất thưa, ven đường
        //
        // {centerLat, centerLon, numCells, scatterKm}
        // ─────────────────────────────────────────────────────────────────
        double[][] clusters = {

            // ══ HÀ NỘI – LÕI ĐÔ THỊ ══════════════════════════════════════
            { 21.028, 105.854, 120, 2.5 },  // Hoàn Kiếm/Ba Đình – trung tâm lịch sử
            { 21.010, 105.840,  80, 3.5 },  // Đống Đa/Hai Bà Trưng
            { 21.025, 105.875,  60, 3.0 },  // Hoàn Kiếm đông/Long Biên tây
            { 21.048, 105.840,  50, 3.0 },  // Tây Hồ/Ba Đình bắc
            { 20.990, 105.820,  50, 3.5 },  // Thanh Xuân/Cầu Giấy
            { 21.065, 105.800,  40, 3.5 },  // Bắc Từ Liêm/Cầu Giấy bắc

            // ── Hà Nội – Ngoại thành bắc ─────────────────────────────────
            { 21.150, 105.860,  40, 7.0 },  // Đông Anh
            { 21.270, 105.860,  15, 8.0 },  // Sóc Sơn thị trấn
            { 21.185, 105.735,  20, 7.0 },  // Mê Linh
            { 21.028, 105.933,  35, 6.0 },  // Gia Lâm/Long Biên đông

            // ── Hà Nội – Ngoại thành nam ─────────────────────────────────
            { 20.970, 105.853,  35, 5.0 },  // Hoàng Mai/Thanh Trì
            { 20.870, 105.860,  22, 6.0 },  // Thường Tín
            { 20.740, 105.910,  12, 8.0 },  // Phú Xuyên – thưa

            // ── Hà Nội – Ngoại thành tây ─────────────────────────────────
            { 20.970, 105.775,  45, 7.0 },  // Hà Đông/Nam Từ Liêm
            { 20.920, 105.715,  18, 8.0 },  // Chương Mỹ
            { 20.750, 105.700,   7, 9.0 },  // Ứng Hòa/Mỹ Đức – thưa (bán núi)
            { 21.002, 105.635,  20, 7.0 },  // Quốc Oai
            { 21.035, 105.560,  10, 9.0 },  // Thạch Thất
            { 21.080, 105.410,   6,10.0 },  // Ba Vì – núi, rất thưa

            // ══ HẢI PHÒNG ══════════════════════════════════════════════════
            { 20.855, 106.690,  80, 3.0 },  // Trung tâm TP Hải Phòng
            { 20.840, 106.720,  40, 4.5 },  // Hải An/Lê Chân
            { 20.900, 106.670,  30, 5.0 },  // Hồng Bàng bắc
            { 20.800, 106.730,  18, 5.0 },  // Kiến An/Dương Kinh
            { 20.970, 106.580,  12, 5.0 },  // Thủy Nguyên (KCN)
            { 20.720, 107.050,   8, 4.0 },  // Cát Bà (đảo, tourist)
            { 20.600, 106.790,   6, 6.0 },  // Tiên Lãng (nông thôn ven biển)

            // ══ BẮC NINH – KCN dày đặc ════════════════════════════════════
            { 21.186, 106.076,  55, 3.5 },  // TP Bắc Ninh
            { 21.140, 105.990,  50, 4.0 },  // KCN Yên Phong (Samsung) – rất dày
            { 21.120, 105.970,  30, 4.0 },  // TX Từ Sơn (công nghiệp)
            { 21.200, 106.180,  15, 4.0 },  // KCN Quế Võ
            { 21.100, 106.080,  12, 5.0 },  // Gia Bình/Lương Tài (nông thôn)

            // ══ HẢI DƯƠNG ══════════════════════════════════════════════════
            { 20.940, 106.335,  50, 4.0 },  // TP Hải Dương
            { 21.130, 106.380,  25, 4.5 },  // TX Chí Linh
            { 21.000, 106.510,  18, 4.0 },  // TX Kinh Môn
            { 20.860, 106.200,  12, 8.0 },  // Bình Giang/Thanh Miện (nông thôn)
            { 20.780, 106.380,   9, 7.0 },  // Ninh Giang/Gia Lộc (nông thôn)

            // ══ HƯNG YÊN ═══════════════════════════════════════════════════
            { 20.646, 106.051,  40, 3.5 },  // TP Hưng Yên
            { 20.900, 106.040,  30, 3.5 },  // TX Mỹ Hào/Phố Nối (KCN)
            { 20.820, 106.080,  18, 4.5 },  // Văn Giang/Khoái Châu
            { 20.700, 106.100,  12, 6.0 },  // Ân Thi (nông thôn)
            { 20.560, 106.150,   9, 6.0 },  // Kim Động/Phù Cừ (nông thôn)

            // ══ THÁI BÌNH – đồng bằng dày ═════════════════════════════════
            { 20.450, 106.342,  50, 4.0 },  // TP Thái Bình
            { 20.540, 106.430,  14, 4.0 },  // TX Đông Hưng
            { 20.600, 106.200,  10, 5.0 },  // Hưng Hà (nông thôn)
            { 20.460, 106.220,   9, 5.0 },  // Vũ Thư/Kiến Xương
            { 20.350, 106.500,   7, 5.0 },  // Thái Thụy (ven biển)
            { 20.250, 106.430,   6, 5.0 },  // Tiền Hải (ven biển)

            // ══ NAM ĐỊNH ════════════════════════════════════════════════════
            { 20.420, 106.168,  60, 3.5 },  // TP Nam Định
            { 20.480, 106.040,  14, 5.0 },  // TX Mỹ Lộc (phía bắc)
            { 20.350, 106.100,  10, 6.0 },  // Vụ Bản/Ý Yên (nông thôn)
            { 20.380, 106.280,   9, 5.0 },  // Xuân Trường/Trực Ninh
            { 20.290, 106.270,   7, 5.0 },  // Giao Thủy (ven biển)
            { 20.170, 106.200,   5, 6.0 },  // Hải Hậu (ven biển, thưa)

            // ══ HÀ NAM ══════════════════════════════════════════════════════
            { 20.541, 105.907,  35, 3.5 },  // TP Phủ Lý
            { 20.620, 105.980,  15, 4.0 },  // TX Duy Tiên (KCN phía bắc)
            { 20.480, 105.830,  10, 5.0 },  // Kim Bảng/Thanh Liêm (bán núi)
            { 20.380, 106.010,   8, 5.0 },  // Bình Lục/Lý Nhân (nông thôn)

            // ══ NINH BÌNH – du lịch + bán núi ═════════════════════════════
            { 20.253, 105.975,  40, 4.0 },  // TP Ninh Bình
            { 20.210, 105.907,  25, 4.0 },  // TX Tam Điệp
            { 20.330, 105.850,  13, 4.0 },  // Hoa Lư/Tràng An (du lịch)
            { 20.350, 106.030,  10, 5.0 },  // Yên Khánh/Yên Mô (nông thôn)
            { 20.090, 106.170,   7, 5.0 },  // Kim Sơn (ven biển)
            { 20.340, 105.650,   5, 8.0 },  // Nho Quan (núi, rất thưa)

            // ══ VĨNH PHÚC – đô thị + KCN Toyota ══════════════════════════
            { 21.310, 105.597,  45, 4.0 },  // TP Vĩnh Yên
            { 21.250, 105.725,  35, 4.0 },  // TP Phúc Yên (KCN Toyota)
            { 21.370, 105.620,  12, 5.0 },  // Tam Dương/Bình Xuyên
            { 21.300, 105.480,  10, 5.0 },  // Vĩnh Tường/Yên Lạc (nông thôn)
            { 21.460, 105.645,   4, 5.0 },  // Tam Đảo (núi nghỉ dưỡng, rất thưa)

            // ══ THÁI NGUYÊN – đô thị công nghiệp + bán núi ════════════════
            { 21.592, 105.848,  50, 4.0 },  // TP Thái Nguyên
            { 21.480, 105.820,  25, 4.0 },  // TX Sông Công (KCN Samsung)
            { 21.300, 105.850,  25, 4.5 },  // TX Phổ Yên (KCN)
            { 21.700, 105.900,  12, 6.0 },  // Đồng Hỷ/Phú Lương (bán núi)
            { 21.800, 105.600,   5, 8.0 },  // Định Hóa (núi, rất thưa)
            { 21.760, 106.100,   4, 8.0 },  // Võ Nhai (núi, rất thưa)

            // ══ BẮC GIANG – đô thị + KCN VSIP ════════════════════════════
            { 21.273, 106.194,  45, 4.0 },  // TP Bắc Giang
            { 21.280, 106.050,  25, 4.0 },  // TX Việt Yên (KCN VSIP)
            { 21.380, 106.200,  14, 5.0 },  // Lạng Giang
            { 21.450, 106.120,  10, 5.0 },  // Tân Yên/Hiệp Hòa (nông thôn)
            { 21.350, 106.700,   5, 7.0 },  // Sơn Động (núi, rất thưa)
            { 21.500, 106.400,   4, 7.0 },  // Lục Ngạn/Lục Nam (núi)

            // ══ PHÚ THỌ – bán núi, công nghiệp hoá chất ══════════════════
            { 21.399, 105.237,  45, 4.0 },  // TP Việt Trì
            { 21.400, 105.150,  18, 4.0 },  // TX Phú Thọ
            { 21.350, 105.280,  14, 4.5 },  // Lâm Thao (KCN phân bón)
            { 21.280, 105.200,  10, 5.0 },  // Phù Ninh/Sông Lô (nông thôn)
            { 21.000, 105.000,   5, 8.0 },  // Thanh Sơn/Yên Lập (núi, thưa)
            { 21.100, 104.950,   3, 8.0 },  // Tân Sơn (núi sâu, rất thưa)

            // ══ TUYÊN QUANG – bán núi ══════════════════════════════════════
            { 21.820, 105.218,  30, 4.0 },  // TP Tuyên Quang
            { 21.900, 105.200,   9, 6.0 },  // Yên Sơn (bán núi)
            { 21.700, 105.350,   6, 7.0 },  // Chiêm Hóa (núi)
            { 22.100, 105.100,   5, 8.0 },  // Hàm Yên (núi)
            { 22.350, 105.350,   3, 9.0 },  // Nà Hang (núi sâu, rất thưa)

            // ══ HÒA BÌNH – phần lớn là núi ════════════════════════════════
            { 20.817, 105.338,  30, 4.0 },  // TP Hòa Bình
            { 20.980, 105.520,  14, 4.5 },  // Lương Sơn (gần HN, medium)
            { 20.660, 105.550,   7, 6.0 },  // Kim Bôi (thưa)
            { 20.790, 105.200,   4, 7.0 },  // Kỳ Sơn (núi)
            { 20.900, 105.100,   3, 8.0 },  // Đà Bắc (núi sâu, rất thưa)
            { 20.450, 105.550,   4, 7.0 },  // Lạc Sơn (núi)
            { 20.660, 104.980,   4, 6.0 },  // Mai Châu (núi, du lịch)

            // ══ QUẢNG NINH – ven biển + núi ═══════════════════════════════
            { 20.951, 107.080,  70, 3.5 },  // TP Hạ Long
            { 20.940, 106.820,  28, 4.5 },  // TX Quảng Yên
            { 21.010, 106.530,  18, 4.5 },  // TX Đông Triều (KCN)
            { 21.020, 107.300,  28, 4.0 },  // TX Cẩm Phả
            { 21.520, 107.960,  18, 4.5 },  // TP Móng Cái (cửa khẩu)
            { 21.300, 107.390,   8, 6.0 },  // Tiên Yên (thưa)
            { 21.150, 107.180,   5, 6.0 },  // Ba Chẽ (núi, rất thưa)
            { 21.650, 107.700,   6, 5.0 },  // Hải Hà/Đầm Hà (thưa)
            { 21.700, 107.350,   4, 7.0 },  // Bình Liêu (núi, rất thưa)
        };

        System.out.println("=== Generating REALISTIC Red River Delta coverage ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 3000%n", template, prefix);
        System.out.println("Density rule: urban=dense/small-scatter, mountain=sparse/large-scatter");
        System.out.println();

        // Count total expected
        int expected = 0;
        for (double[] c : clusters) expected += (int) c[2];
        System.out.printf("Expected total: %d cells%n%n", expected);

        int idx = 1;
        idx = generateRandomClusters(template, prefix, rng, clusters, idx);

        int total = idx - 1;
        System.out.printf("%n=== Done: %d cells generated (RC_1 … RC_%d) ===%n", total, total);

        // ─────────────────────────────────────────────────────────────────
        // LỚP PHỦ LIỀN MẠCH – Fill gaps giữa các cluster đô thị
        //
        // Đặt ở các khoảng trống giữa thành phố, dọc hành lang giao thông
        // và đồng bằng nông thôn để bản đồ nhìn liền mạch, không bị "bong bóng"
        //
        // Scatter 12–16 km → đĩa phủ Ø 24–32 km → overlap cluster kế cận
        // ─────────────────────────────────────────────────────────────────
        Random rng2 = new Random(3001);
        String prefix2 = "RF";
        double[][] fillClusters = {

            // ══ HÀNH LANG QL5 – Hà Nội → Hải Phòng ══════════════════════
            { 21.020, 106.100,  25, 13.0 },  // km 20  – Trâu Quỳ/Như Quỳnh
            { 21.010, 106.290,  25, 13.0 },  // km 40  – Phố Nối/Từ Lâm
            { 20.960, 106.460,  22, 13.0 },  // km 60  – Sặt/Bình Giang
            { 20.910, 106.600,  20, 12.0 },  // km 80  – Lai Vu/Kim Thành

            // ══ HÀNH LANG QL1A – Hà Nội → Ninh Bình ══════════════════════
            { 20.850, 105.880,  20, 12.0 },  // Thường Tín nam
            { 20.740, 105.890,  20, 12.0 },  // Phú Xuyên trung
            { 20.640, 105.900,  18, 12.0 },  // Hà Nam bắc (Duy Tiên)
            { 20.460, 105.930,  18, 12.0 },  // Giữa Hà Nam và Ninh Bình
            { 20.360, 105.950,  15, 11.0 },  // Ninh Bình bắc

            // ══ HÀNH LANG QL10 – Ninh Bình → Nam Định → Thái Bình ════════
            { 20.330, 106.090,  18, 11.0 },  // Nam Định tây bắc
            { 20.390, 106.230,  18, 12.0 },  // Nam Định – Thái Bình ranh giới
            { 20.430, 106.430,  18, 12.0 },  // Thái Bình tây
            { 20.500, 106.540,  15, 11.0 },  // Thái Bình trung bắc

            // ══ ĐỒNG BẰNG TRUNG TÂM – lấp vùng trống giữa các tỉnh ════════
            { 21.100, 105.950,  22, 13.0 },  // Giữa HN và Bắc Ninh
            { 21.150, 106.130,  20, 13.0 },  // Bắc Ninh bắc / Bắc Giang nam
            { 20.970, 106.200,  22, 13.0 },  // Hải Dương tây / Hưng Yên đông bắc
            { 20.800, 106.180,  20, 13.0 },  // Giữa Hưng Yên và Hải Dương
            { 20.700, 106.250,  18, 12.0 },  // Hưng Yên đông / Hải Dương nam
            { 20.580, 106.120,  18, 12.0 },  // Giữa Hưng Yên và Hà Nam
            { 20.560, 106.350,  16, 12.0 },  // Thái Bình tây nam / Hưng Yên đông nam
            { 20.480, 105.840,  16, 11.0 },  // Hà Nam – Ninh Bình tây

            // ══ VEN BIỂN – dải ven biển đồng bằng ═══════════════════════
            { 20.220, 106.120,  14, 10.0 },  // Nam Định ven biển
            { 20.270, 106.320,  13, 10.0 },  // Giao Thủy / Nghĩa Hưng
            { 20.390, 106.550,  12, 10.0 },  // Thái Bình ven biển bắc
            { 20.280, 106.480,  12, 10.0 },  // Thái Bình ven biển nam

            // ══ HẢI PHÒNG – HẢI DƯƠNG – QUẢNG NINH kết nối ═════════════
            { 20.890, 106.780,  18, 11.0 },  // HP – HD connector bắc
            { 20.830, 106.780,  18, 11.0 },  // HP ngoại vi tây
            { 21.020, 106.720,  15, 10.0 },  // Thủy Nguyên / Đông Triều kết nối
            { 21.050, 106.900,  15, 10.0 },  // Đông Triều – Uông Bí
            { 21.000, 107.170,  15, 10.0 },  // Quảng Ninh ven biển

            // ══ VĨNH PHÚC – THÁI NGUYÊN – BẮC GIANG kết nối ════════════
            { 21.380, 105.850,  18, 12.0 },  // Giữa Vĩnh Phúc và Thái Nguyên
            { 21.420, 106.020,  16, 12.0 },  // Thái Nguyên nam / Bắc Ninh bắc
            { 21.280, 106.320,  15, 11.0 },  // Bắc Giang tây nam
            { 21.180, 106.450,  15, 11.0 },  // Bắc Giang nam / Hải Dương bắc

            // ══ PHÍA TÂY – Hà Nội → Hòa Bình → Phú Thọ kết nối ══════════
            { 21.100, 105.620,  15, 11.0 },  // Sơn Tây / Phúc Thọ
            { 21.200, 105.480,  12, 11.0 },  // Vĩnh Phúc tây / Phú Thọ đông
            { 20.900, 105.450,  12, 11.0 },  // Hòa Bình đông / HN tây nam
        };

        System.out.println("\n=== Generating FILL layer for seamless coverage ===");
        System.out.printf("Template: %s | Prefix: %s | Seed: 3001%n", template, prefix2);

        int expected2 = 0;
        for (double[] c : fillClusters) expected2 += (int) c[2];
        System.out.printf("Expected fill cells: %d%n%n", expected2);

        int idx2 = 1;
        idx2 = generateRandomClusters(template, prefix2, rng2, fillClusters, idx2);

        int total2 = idx2 - 1;
        System.out.printf("%n=== Done: %d fill cells generated (RF_1 … RF_%d) ===%n", total2, total2);
        System.out.printf("Grand total: %d + %d = %d cells%n", total, total2, total + total2);
        System.out.println("Next: run /api/admin/sync-cells");
    }
}
