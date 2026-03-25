package com.example.cellcover;

import org.locationtech.proj4j.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic cell coverage data using hexagonal grid placement.
 *
 * Cell template (BNH0034_3) dimensions: 6.6 km × 5.6 km bounding box.
 *
 * Spacing rules (center-to-center):
 *   5.5 km → urban core   : ~15% border overlap (realistic dense network)
 *   7.5 km → suburban/KCN : cells just touch at edges
 *  10.0 km → rural plains  : small gaps between cells
 *  14.0 km → mountain/sparse: large gaps (limited coverage)
 *
 * Cells are placed in a hex grid (not random scatter) so adjacent cells
 * overlap only at their borders — not stacked on top of each other.
 */
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

    private static void generateSingleCell(String templateCell, String outputPrefix, int type,
                                            double lat, double lon, int idx) throws IOException {
        String fileType = type == 0 ? "" : ".svr";
        String templateBil = BASE_DIR + templateCell + "/" + templateCell + fileType + ".bil";
        String templateHdr = BASE_DIR + templateCell + "/" + templateCell + fileType + ".hdr";

        List<String> lines = Files.readAllLines(Paths.get(templateHdr));
        int ulxIndex = -1, ulyIndex = -1;
        double ncolsVal = 0, nrowsVal = 0, xdimVal = 0, ydimVal = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim().toLowerCase();
            if (line.startsWith("ulxmap")) ulxIndex = i;
            if (line.startsWith("ulymap")) ulyIndex = i;
            if (line.startsWith("ncols"))  ncolsVal = Double.parseDouble(line.split("\\s+")[1]);
            if (line.startsWith("nrows"))  nrowsVal = Double.parseDouble(line.split("\\s+")[1]);
            if (line.startsWith("xdim"))   xdimVal  = Double.parseDouble(line.split("\\s+")[1]);
            if (line.startsWith("ydim"))   ydimVal  = Double.parseDouble(line.split("\\s+")[1]);
        }
        if (ulxIndex == -1 || ulyIndex == -1) throw new RuntimeException("Missing ulxmap/ulymap in HDR");

        int zone = utmZoneFromLon(lon);
        double[] utm = latLonToUtm(zone, lat, lon);
        String prj = utmPrjWkt(zone);

        // ulxmap/ulymap is the TOP-LEFT corner of the raster.
        // Subtract half-dimensions so the bounding box CENTER aligns with the target (lat, lon).
        double ulx = utm[0] - (ncolsVal * xdimVal) / 2.0;
        double uly = utm[1] + (nrowsVal * ydimVal) / 2.0;

        String cellName = outputPrefix + "_" + idx;
        String outputDir = BASE_DIR + cellName;
        Files.createDirectories(Paths.get(outputDir));

        List<String> newLines = new ArrayList<>(lines);
        newLines.set(ulxIndex, "ulxmap " + ulx);
        newLines.set(ulyIndex, "ulymap " + uly);

        Files.write(Paths.get(outputDir, cellName + fileType + ".hdr"), newLines);
        Path bilDst = Paths.get(outputDir, cellName + fileType + ".bil");
        if (!Files.exists(bilDst)) {
            Path relTarget = Paths.get(outputDir).toAbsolutePath()
                    .relativize(Paths.get(templateBil).toAbsolutePath());
            Files.createSymbolicLink(bilDst, relTarget);
        }
        Files.writeString(Paths.get(outputDir, cellName + fileType + ".prj"), prj);
    }

    /**
     * Generate hexagonal grid points within a circular zone.
     * Uses a global point list to prevent overlap between adjacent zones.
     *
     * @param centerLat  zone center latitude
     * @param centerLon  zone center longitude
     * @param radiusKm   radius of the zone in km
     * @param spacingKm  center-to-center distance between neighboring cells
     * @param rng        seeded RNG for small positional jitter
     * @param allPoints  all previously placed points (modified in place)
     * @return list of new {lat, lon} points placed in this zone
     */
    private static List<double[]> hexGridPoints(double centerLat, double centerLon,
                                                 double radiusKm, double spacingKm,
                                                 Random rng, List<double[]> allPoints) {
        // Hex grid geometry
        double xStep = spacingKm;
        double yStep = spacingKm * Math.sqrt(3.0) / 2.0;
        // 5% positional jitter for natural appearance
        double jitter = spacingKm * 0.05;
        // 4.5 km exclusion radius:
        // - Same-zone hex cells at 5.5 km spacing: min pair = 5.5 - 2×jitter(0.275) = 4.95 km > 4.5 ✓
        // - Adjacent non-overlapping zones: boundary gap ≈ 5-6 km > 4.5 ✓
        // - Zones wholly inside another zone: all candidates within 3.18 km of existing cells → 0 new cells (correct)
        double minDist2 = 4.5 * 4.5;

        int rows = (int) Math.ceil(radiusKm / yStep);
        int cols = (int) Math.ceil(radiusKm / xStep);

        List<double[]> placed = new ArrayList<>();

        for (int row = -rows; row <= rows; row++) {
            for (int col = -cols; col <= cols; col++) {
                // Offset alternating rows by half a column for hex pattern
                double xKm = col * xStep + ((row & 1) != 0 ? xStep / 2.0 : 0.0);
                double yKm = row * yStep;

                // Skip points outside the circular zone
                if (xKm * xKm + yKm * yKm > radiusKm * radiusKm) continue;

                // Apply small random jitter
                double angle = rng.nextDouble() * 2 * Math.PI;
                double jr = rng.nextDouble() * jitter;
                xKm += jr * Math.cos(angle);
                yKm += jr * Math.sin(angle);

                double lat = centerLat + yKm / 111.0;
                double lon = centerLon + xKm / (111.0 * Math.cos(Math.toRadians(centerLat)));

                // Check minimum distance from all existing points
                boolean tooClose = false;
                for (double[] ex : allPoints) {
                    double dy = (lat - ex[0]) * 111.0;
                    double dx = (lon - ex[1]) * 111.0 * Math.cos(Math.toRadians(lat));
                    if (dy * dy + dx * dx < minDist2) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;

                allPoints.add(new double[]{lat, lon});
                placed.add(new double[]{lat, lon});
            }
        }
        return placed;
    }

    public static void main(String[] args) throws IOException {
        Random rng = new Random(3000);
        String template = "BNH0034_3";
        String prefix   = "RC";

        // Global point registry — prevents any two cells being closer than 0.72×spacing
        List<double[]> allPoints = new ArrayList<>();
        int idx = 1;

        // ─────────────────────────────────────────────────────────────────────
        // Zone definitions: {centerLat, centerLon, radiusKm, spacingKm}
        //
        //  spacingKm  |  density description
        //  -----------+----------------------------------------------
        //    5.5 km   |  Lõi đô thị   (biên cell chồng ~15%) ← DENSE
        //    7.5 km   |  Ngoại ô/KCN  (cell vừa chạm biên)
        //   10.0 km   |  Nông thôn đb (khoảng hở nhỏ giữa cells)
        //   14.0 km   |  Vùng núi     (khoảng hở lớn, thưa)    ← SPARSE
        //
        // Cell template: 6.6km × 5.6km → tại spacing 5.5km chồng lấn ~15%
        // ─────────────────────────────────────────────────────────────────────
        double[][] zones = {

            // ══ HÀ NỘI LÕI ĐÔ THỊ ══════════════════════════════════════════
            // Lõi đô thị HN phủ toàn bộ 12 quận nội thành (r=14 km):
            // Cầu Giấy, Ba Đình, Đống Đa, Hai Bà Trưng, Hoàn Kiếm, Hoàng Mai,
            // Long Biên, Nam Từ Liêm, Tây Hồ, Thanh Xuân + phần trong của Bắc Từ Liêm / Hà Đông
            { 21.028, 105.854, 14.0,  5.5 },

            // ── Hà Nội – huyện ngoại thành (trung tâm đặt ngoài r=14 km) ─
            { 21.185, 105.870, 10.0,  7.5 },  // Đông Anh (centre 17 km bắc)
            { 21.270, 105.855,  8.0, 10.0 },  // Sóc Sơn
            { 21.185, 105.735,  6.0, 10.0 },  // Mê Linh
            { 21.030, 105.935,  8.0,  7.5 },  // Gia Lâm / Long Biên đông
            { 20.870, 105.860,  8.0,  7.5 },  // Thường Tín
            { 20.740, 105.910,  7.0, 10.0 },  // Phú Xuyên
            { 20.970, 105.775, 10.0,  7.5 },  // Hà Đông / Nam Từ Liêm
            { 20.920, 105.715,  7.0, 10.0 },  // Chương Mỹ
            { 20.750, 105.700,  6.0, 14.0 },  // Ứng Hòa / Mỹ Đức (bán núi)
            { 21.002, 105.635,  8.0, 10.0 },  // Quốc Oai
            { 21.035, 105.560,  6.0, 10.0 },  // Thạch Thất
            { 21.080, 105.410,  6.0, 14.0 },  // Ba Vì (núi)

            // ══ HẢI PHÒNG ════════════════════════════════════════════════════
            { 20.855, 106.695, 10.0,  5.5 },  // Trung tâm TP
            { 20.900, 106.660,  8.0,  7.5 },  // Hồng Bàng bắc / Thủy Nguyên
            { 20.800, 106.730,  6.0,  7.5 },  // Kiến An / Dương Kinh
            { 20.720, 107.050,  4.0, 10.0 },  // Cát Bà (đảo)
            { 20.600, 106.790,  5.0, 10.0 },  // Tiên Lãng (nông thôn ven biển)

            // ══ BẮC NINH ═════════════════════════════════════════════════════
            { 21.186, 106.076,  8.0,  5.5 },  // TP Bắc Ninh
            { 21.135, 105.985, 10.0,  5.5 },  // KCN Yên Phong (Samsung) + TX Từ Sơn
            { 21.200, 106.180,  5.0,  7.5 },  // KCN Quế Võ
            { 21.100, 106.080,  7.0, 10.0 },  // Gia Bình / Lương Tài

            // ══ HẢI DƯƠNG ════════════════════════════════════════════════════
            { 20.940, 106.335,  8.0,  5.5 },  // TP Hải Dương
            { 21.130, 106.380,  6.0,  7.5 },  // TX Chí Linh
            { 21.000, 106.510,  5.0,  7.5 },  // TX Kinh Môn
            { 20.860, 106.200,  7.0, 10.0 },  // Bình Giang / Thanh Miện
            { 20.780, 106.380,  6.0, 10.0 },  // Ninh Giang / Gia Lộc

            // ══ HƯNG YÊN ══════════════════════════════════════════════════════
            { 20.646, 106.051,  7.0,  5.5 },  // TP Hưng Yên
            { 20.900, 106.040,  6.0,  5.5 },  // TX Mỹ Hào / Phố Nối (KCN)
            { 20.820, 106.080,  6.0,  7.5 },  // Văn Giang / Khoái Châu
            { 20.700, 106.100,  6.0, 10.0 },  // Ân Thi
            { 20.560, 106.150,  5.0, 10.0 },  // Kim Động / Phù Cừ

            // ══ THÁI BÌNH ═════════════════════════════════════════════════════
            { 20.450, 106.342,  8.0,  5.5 },  // TP Thái Bình
            { 20.540, 106.430,  5.0,  7.5 },  // TX Đông Hưng
            { 20.600, 106.200,  5.0, 10.0 },  // Hưng Hà
            { 20.460, 106.220,  5.0, 10.0 },  // Vũ Thư / Kiến Xương
            { 20.350, 106.500,  5.0, 10.0 },  // Thái Thụy (ven biển)
            { 20.250, 106.430,  5.0, 10.0 },  // Tiền Hải (ven biển)

            // ══ NAM ĐỊNH ══════════════════════════════════════════════════════
            { 20.420, 106.168,  9.0,  5.5 },  // TP Nam Định
            { 20.480, 106.040,  5.0,  7.5 },  // TX Mỹ Lộc
            { 20.350, 106.100,  6.0, 10.0 },  // Vụ Bản / Ý Yên
            { 20.380, 106.280,  5.0, 10.0 },  // Xuân Trường / Trực Ninh
            { 20.290, 106.270,  5.0, 10.0 },  // Giao Thủy (ven biển)
            { 20.170, 106.200,  5.0, 10.0 },  // Hải Hậu (ven biển)

            // ══ HÀ NAM ════════════════════════════════════════════════════════
            { 20.541, 105.907,  7.0,  5.5 },  // TP Phủ Lý
            { 20.620, 105.980,  5.0,  7.5 },  // TX Duy Tiên (KCN phía bắc)
            { 20.480, 105.830,  5.0, 10.0 },  // Kim Bảng / Thanh Liêm (bán núi)
            { 20.380, 106.010,  5.0, 10.0 },  // Bình Lục / Lý Nhân

            // ══ NINH BÌNH ═════════════════════════════════════════════════════
            { 20.253, 105.975,  7.0,  5.5 },  // TP Ninh Bình
            { 20.210, 105.907,  6.0,  5.5 },  // TX Tam Điệp
            { 20.330, 105.850,  5.0,  7.5 },  // Hoa Lư / Tràng An (du lịch)
            { 20.350, 106.030,  5.0, 10.0 },  // Yên Khánh / Yên Mô
            { 20.090, 106.170,  4.0, 10.0 },  // Kim Sơn (ven biển)
            { 20.340, 105.650,  5.0, 14.0 },  // Nho Quan (núi)

            // ══ VĨNH PHÚC ═════════════════════════════════════════════════════
            { 21.310, 105.597,  8.0,  5.5 },  // TP Vĩnh Yên
            { 21.250, 105.725,  7.0,  5.5 },  // TP Phúc Yên (KCN Toyota)
            { 21.370, 105.620,  5.0,  7.5 },  // Tam Dương / Bình Xuyên
            { 21.300, 105.480,  6.0, 10.0 },  // Vĩnh Tường / Yên Lạc
            { 21.460, 105.645,  3.0, 14.0 },  // Tam Đảo (núi nghỉ dưỡng)

            // ══ THÁI NGUYÊN ════════════════════════════════════════════════════
            { 21.592, 105.848,  9.0,  5.5 },  // TP Thái Nguyên
            { 21.480, 105.820,  6.0,  5.5 },  // TX Sông Công (KCN Samsung)
            { 21.300, 105.850,  7.0,  5.5 },  // TX Phổ Yên (KCN)
            { 21.700, 105.900,  7.0, 10.0 },  // Đồng Hỷ / Phú Lương (bán núi)
            { 21.800, 105.600,  5.0, 14.0 },  // Định Hóa (núi)
            { 21.760, 106.100,  5.0, 14.0 },  // Võ Nhai (núi)

            // ══ BẮC GIANG ══════════════════════════════════════════════════════
            { 21.273, 106.194,  8.0,  5.5 },  // TP Bắc Giang
            { 21.280, 106.050,  6.0,  5.5 },  // TX Việt Yên (KCN VSIP)
            { 21.380, 106.200,  5.0,  7.5 },  // Lạng Giang
            { 21.450, 106.120,  5.0,  7.5 },  // Tân Yên / Hiệp Hòa
            { 21.350, 106.700,  5.0, 14.0 },  // Sơn Động (núi)
            { 21.500, 106.400,  5.0, 14.0 },  // Lục Ngạn / Lục Nam (núi)

            // ══ PHÚ THỌ ════════════════════════════════════════════════════════
            { 21.399, 105.237,  8.0,  5.5 },  // TP Việt Trì
            { 21.400, 105.150,  5.0,  7.5 },  // TX Phú Thọ
            { 21.350, 105.280,  5.0,  7.5 },  // Lâm Thao (KCN phân bón)
            { 21.280, 105.200,  5.0, 10.0 },  // Phù Ninh / Sông Lô
            { 21.000, 105.000,  5.0, 14.0 },  // Thanh Sơn / Yên Lập (núi)
            { 21.100, 104.950,  4.0, 14.0 },  // Tân Sơn (núi sâu)

            // ══ TUYÊN QUANG ════════════════════════════════════════════════════
            { 21.820, 105.218,  7.0,  5.5 },  // TP Tuyên Quang
            { 21.900, 105.200,  5.0, 10.0 },  // Yên Sơn (bán núi)
            { 21.700, 105.350,  5.0, 14.0 },  // Chiêm Hóa (núi)
            { 22.100, 105.100,  4.0, 14.0 },  // Hàm Yên (núi)
            { 22.350, 105.350,  3.0, 14.0 },  // Nà Hang (núi sâu)

            // ══ HÒA BÌNH ═══════════════════════════════════════════════════════
            { 20.817, 105.338,  7.0,  5.5 },  // TP Hòa Bình
            { 20.980, 105.520,  5.0,  7.5 },  // Lương Sơn (gần HN)
            { 20.660, 105.550,  5.0, 10.0 },  // Kim Bôi
            { 20.790, 105.200,  4.0, 14.0 },  // Kỳ Sơn (núi)
            { 20.900, 105.100,  4.0, 14.0 },  // Đà Bắc (núi sâu)
            { 20.450, 105.550,  4.0, 14.0 },  // Lạc Sơn (núi)
            { 20.660, 104.980,  4.0, 14.0 },  // Mai Châu (núi, du lịch)

            // ══ QUẢNG NINH ═════════════════════════════════════════════════════
            { 20.951, 107.080, 10.0,  5.5 },  // TP Hạ Long
            { 20.940, 106.820,  7.0,  7.5 },  // TX Quảng Yên
            { 21.010, 106.530,  6.0,  7.5 },  // TX Đông Triều (KCN)
            { 21.020, 107.300,  6.0,  5.5 },  // TX Cẩm Phả (than)
            { 21.520, 107.960,  6.0,  7.5 },  // TP Móng Cái (cửa khẩu)
            { 21.300, 107.390,  4.0, 10.0 },  // Tiên Yên (thưa)
            { 21.150, 107.180,  4.0, 14.0 },  // Ba Chẽ (núi)
            { 21.650, 107.700,  4.0, 10.0 },  // Hải Hà / Đầm Hà
            { 21.700, 107.350,  4.0, 14.0 },  // Bình Liêu (núi)

            // ══ HÀNH LANG GIAO THÔNG (lấp khoảng trống giữa các tỉnh) ══════
            { 21.015, 106.200, 14.0, 10.0 },  // QL5: HN → Hải Phòng (km 20–50)
            { 20.910, 106.500, 12.0, 10.0 },  // QL5: HN → Hải Phòng (km 60–90)
            { 20.720, 105.890, 12.0, 10.0 },  // QL1A: HN → Ninh Bình (trung)
            { 20.480, 105.920, 10.0, 10.0 },  // QL1A: Hà Nam → Ninh Bình
            { 20.370, 106.170, 12.0, 10.0 },  // QL10: Ninh Bình → Nam Định → Thái Bình
            { 20.480, 106.420, 12.0, 10.0 },  // QL10: Nam Định → Thái Bình
            { 21.100, 105.960, 12.0, 10.0 },  // QL18: HN → Bắc Ninh → Bắc Giang
            { 21.180, 106.330, 10.0, 10.0 },  // QL18: Bắc Giang → Hải Dương
            { 20.960, 106.250, 12.0, 10.0 },  // HY – HD trung tâm đồng bằng
            { 20.730, 106.200, 10.0, 10.0 },  // HY – HD phía nam
            { 21.380, 105.820, 10.0, 10.0 },  // Vĩnh Phúc → Thái Nguyên
            { 21.420, 106.000,  9.0, 10.0 },  // Thái Nguyên → Bắc Ninh bắc
            { 21.280, 106.300,  9.0, 10.0 },  // Bắc Giang tây nam
            { 21.100, 105.620,  8.0, 10.0 },  // HN → Sơn Tây / Phúc Thọ
        };

        System.out.println("=== Generating hex-grid cell coverage (Red River Delta + surrounding) ===");
        System.out.println("Template : " + template);
        System.out.println("Prefix   : " + prefix);
        System.out.println("Spacing  : 5.5/7.5/10/14 km (urban/suburban/rural/mountain)");
        System.out.println("Cell size: 6.6 km × 5.6 km → border overlap ~15% at 5.5 km spacing");
        System.out.println();

        long t0 = System.currentTimeMillis();
        for (double[] zone : zones) {
            int before = allPoints.size();
            hexGridPoints(zone[0], zone[1], zone[2], zone[3], rng, allPoints);
            int added = allPoints.size() - before;

            // Generate cell files for the newly added points
            List<double[]> pts = allPoints.subList(allPoints.size() - added, allPoints.size());
            for (double[] pt : pts) {
                generateSingleCell(template, prefix, 0, pt[0], pt[1], idx);
                idx++;
            }
            System.out.printf("  Zone [%.3f, %.3f] r=%.0f s=%.1f → +%d cells (total %d)%n",
                    zone[0], zone[1], zone[2], zone[3], added, idx - 1);
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("%n=== Done: %d cells generated (RC_1 … RC_%d) in %.1f s ===%n",
                idx - 1, idx - 1, elapsed / 1000.0);
        System.out.println("Next step: /api/admin/sync-cells");
    }
}
