package com.example.cellcover.service;

import com.example.cellcover.entity.Cell;
import com.example.cellcover.repository.CellRepository;
import com.uber.h3core.H3Core;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds H3 multi-resolution index from raster files into ClickHouse.
 *
 * Supports two modes:
 *  1. buildAll()        — reads COG (_svr.tif) files from cogDir/binary
 *  2. buildAllFromBil() — reads BIL files directly from rasterDir; no COG needed
 *
 * For each valid pixel:
 *   - compute H3 index at res 5–13
 *   - keep MAX signal per (h3_res, h3_index) per cell
 *   - bulk insert into ClickHouse cell_h3_coverage
 *
 * buildAllFromBil() also registers each cell in the MariaDB cells table.
 */
@Service
public class H3IndexPipelineService {

    private static final Logger log = LoggerFactory.getLogger(H3IndexPipelineService.class);

    private static final float NODATA_TOLERANCE = 1e30f;
    private static final float SIGNAL_MIN_DBM   = -140f;
    private static final float SIGNAL_MAX_DBM   = -44f;
    private static final int[] RESOLUTIONS      = {5, 6, 7, 8, 9, 10, 11, 12, 13};
    /**
     * Pixel sampling stride for BIL import.
     * BIL pixels are 2m; H3 res-13 cells are ~43m edge → sample every 4th pixel (8m)
     * gives full coverage with 16x fewer H3/transform operations.
     */
    private static final int BIL_SAMPLE_STRIDE  = 4;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private final ClickHouseService clickHouseService;
    private final CellRepository    cellRepository;
    private final BilToCogConverter bilToCogConverter;
    private final String            cogDir;
    private final String            cogDirBase;
    private final String            rasterDir;
    private final int               parallelThreads;

    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger totalFiles     = new AtomicInteger(0);
    private volatile boolean    running        = false;
    private volatile String     currentMode    = "";

    private final H3Core h3;

    // CRS factories (thread-safe for creating transforms, but transforms themselves are not)
    private final CRSFactory              crsFactory  = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
    private final CoordinateReferenceSystem  wgs84     = crsFactory.createFromName("EPSG:4326");
    private final CoordinateReferenceSystem  utm48N    = crsFactory.createFromName("EPSG:32648");
    private final CoordinateReferenceSystem  utm49N    = crsFactory.createFromName("EPSG:32649");
    // Thread-local transforms: CoordinateTransform is NOT thread-safe
    private final ThreadLocal<CoordinateTransform> tlTransform48N =
            ThreadLocal.withInitial(() -> ctFactory.createTransform(utm48N, wgs84));
    private final ThreadLocal<CoordinateTransform> tlTransform49N =
            ThreadLocal.withInitial(() -> ctFactory.createTransform(utm49N, wgs84));

    public H3IndexPipelineService(ClickHouseService clickHouseService,
                                  CellRepository cellRepository,
                                  BilToCogConverter bilToCogConverter,
                                  @Value("${cellcover.cog-dir}") String cogDir,
                                  @Value("${cellcover.raster-dir}") String rasterDir,
                                  @Value("${cellcover.parallel-threads:4}") int parallelThreads)
            throws IOException {
        this.clickHouseService = clickHouseService;
        this.cellRepository    = cellRepository;
        this.bilToCogConverter = bilToCogConverter;
        this.cogDirBase        = cogDir;
        this.cogDir            = cogDir + "/binary";
        this.rasterDir         = rasterDir;
        this.parallelThreads   = parallelThreads;
        this.h3                = H3Core.newInstance();
        // (ThreadLocal transforms are initialized on first use per thread)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build H3 index for a single cell synchronously from its COG file.
     */
    public int buildForCell(String cellId) throws Exception {
        File tif = new File(cogDir, cellId + "_svr.tif");
        if (!tif.exists()) throw new IllegalArgumentException("COG file not found: " + tif.getAbsolutePath());
        clickHouseService.deleteByCellId(cellId);
        List<Object[]> rows = processCogFile(tif, cellId);
        clickHouseService.insertBatch(rows);
        log.info("buildForCell {}: inserted {} rows", cellId, rows.size());
        return rows.size();
    }

    /**
     * Build H3 index for all COG files in cogDir — runs async.
     */
    @Async
    public CompletableFuture<Void> buildAll() {
        if (running) throw new IllegalStateException("Pipeline is already running");
        running     = true;
        currentMode = "cog";

        File dir = new File(cogDir);
        File[] tifFiles = dir.listFiles(f -> f.getName().endsWith("_svr.tif"));
        if (tifFiles == null || tifFiles.length == 0) {
            running = false;
            return CompletableFuture.completedFuture(null);
        }

        totalFiles.set(tifFiles.length);
        processedFiles.set(0);

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (File tif : tifFiles) {
            String cellId = tif.getName().replace("_svr.tif", "");
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    List<Object[]> rows = processCogFile(tif, cellId);
                    clickHouseService.insertBatch(rows);
                    log.info("COG pipeline {}/{} — {} ({} rows)",
                            processedFiles.incrementAndGet(), totalFiles.get(), cellId, rows.size());
                } catch (Exception e) {
                    log.error("Failed COG {}: {}", cellId, e.getMessage());
                    processedFiles.incrementAndGet();
                }
            }, executor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> {
                    running = false;
                    executor.shutdown();
                    if (t != null) log.error("buildAll error", t);
                    else           log.info("buildAll done: {} files", totalFiles.get());
                });
    }

    /**
     * Build H3 index directly from BIL files in rasterDir — no COG conversion needed.
     * Also upserts each cell into the MariaDB cells table.
     * Runs async; progress tracked via getStatus().
     */
    @Async
    public CompletableFuture<Void> buildAllFromBil() {
        if (running) throw new IllegalStateException("Pipeline is already running");
        running     = true;
        currentMode = "bil";

        // Collect cell folders
        List<Path> cellDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(rasterDir), Files::isDirectory)) {
            for (Path p : stream) cellDirs.add(p);
        } catch (IOException e) {
            log.error("Cannot scan rasterDir {}: {}", rasterDir, e.getMessage());
            running = false;
            return CompletableFuture.completedFuture(null);
        }

        totalFiles.set(cellDirs.size());
        processedFiles.set(0);

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Path cellDir : cellDirs) {
            String cellId = cellDir.getFileName().toString();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    importBilCell(cellId, cellDir);
                    log.info("BIL pipeline {}/{} — {} done",
                            processedFiles.incrementAndGet(), totalFiles.get(), cellId);
                } catch (Exception e) {
                    log.error("Failed BIL {}: {}", cellId, e.getMessage(), e);
                    processedFiles.incrementAndGet();
                }
            }, executor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> {
                    running = false;
                    executor.shutdown();
                    if (t != null) log.error("buildAllFromBil error", t);
                    else           log.info("buildAllFromBil done: {} cells", totalFiles.get());
                });
    }

    /**
     * Build H3 index for a single cell directly from its BIL file — synchronous.
     */
    public int buildFromBilForCell(String cellId) throws Exception {
        Path cellDir = Paths.get(rasterDir, cellId);
        if (!Files.isDirectory(cellDir)) {
            throw new IllegalArgumentException("Cell directory not found: " + cellDir);
        }
        importBilCell(cellId, cellDir);
        return 0; // row count tracked inside
    }

    /**
     * Convert all BIL files to COG and upload to MinIO — runs async.
     * Skips cells whose COG already exists in MinIO (idempotent).
     * Progress tracked via getStatus().
     */
    @Async
    public CompletableFuture<Void> syncMinio() {
        if (running) throw new IllegalStateException("Pipeline is already running");
        running     = true;
        currentMode = "minio";

        List<Path> cellDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(rasterDir), Files::isDirectory)) {
            for (Path p : stream) cellDirs.add(p);
        } catch (IOException e) {
            log.error("Cannot scan rasterDir {}: {}", rasterDir, e.getMessage());
            running = false;
            return CompletableFuture.completedFuture(null);
        }

        totalFiles.set(cellDirs.size());
        processedFiles.set(0);

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Path cellDir : cellDirs) {
            String cellId = cellDir.getFileName().toString();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    syncMinioCell(cellId, cellDir);
                    log.info("MinIO sync {}/{} — {} done",
                            processedFiles.incrementAndGet(), totalFiles.get(), cellId);
                } catch (Exception e) {
                    log.error("MinIO sync failed {}: {}", cellId, e.getMessage(), e);
                    processedFiles.incrementAndGet();
                }
            }, executor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> {
                    running = false;
                    executor.shutdown();
                    if (t != null) log.error("syncMinio error", t);
                    else           log.info("syncMinio done: {} cells", totalFiles.get());
                });
    }

    /**
     * Convert a single cell's BIL to COG and upload to MinIO — synchronous.
     */
    public void syncMinioForCell(String cellId) throws Exception {
        Path cellDir = Paths.get(rasterDir, cellId);
        if (!Files.isDirectory(cellDir)) {
            throw new IllegalArgumentException("Cell directory not found: " + cellDir);
        }
        syncMinioCell(cellId, cellDir);
    }

    private void syncMinioCell(String cellId, Path cellDir) throws Exception {
        Path hdrFile = cellDir.resolve(cellId + ".hdr");
        if (!Files.exists(hdrFile)) {
            log.warn("No HDR file for cell {}, skipping", cellId);
            return;
        }
        Map<String, Double> hdr = parseHdr(hdrFile);
        String epsg = detectEpsg(cellDir, cellId, hdr);
        bilToCogConverter.convert(cellId, cellDir, hdr, epsg, Paths.get(cogDirBase));
    }

    public PipelineStatus getStatus() {
        int done  = processedFiles.get();
        int total = totalFiles.get();
        int pct   = total > 0 ? (done * 100 / total) : 0;
        return new PipelineStatus(running, done, total, pct, currentMode);
    }

    // ── BIL import core ───────────────────────────────────────────────────────

    private void importBilCell(String cellId, Path cellDir) throws Exception {
        Path bilFile = cellDir.resolve(cellId + ".bil");
        Path hdrFile = cellDir.resolve(cellId + ".hdr");

        if (!Files.exists(bilFile) || !Files.exists(hdrFile)) {
            throw new IllegalArgumentException("BIL/HDR not found in " + cellDir);
        }

        Map<String, Double> hdr = parseHdr(hdrFile);
        String epsg = detectEpsg(cellDir, cellId, hdr);
        double[] wgs84Bbox = computeWgs84Bbox(hdr, epsg);

        List<Object[]> rows = extractH3FromBil(cellId, bilFile, hdr, epsg);

        // Only delete existing data when re-importing an already-indexed cell
        if (cellRepository.existsById(cellId)) {
            clickHouseService.deleteByCellId(cellId);
        }
        clickHouseService.insertBatch(rows);

        upsertCell(cellId, wgs84Bbox);

        log.debug("importBilCell {}: {} H3 rows, bbox=[{},{},{},{}]",
                cellId, rows.size(),
                String.format("%.4f", wgs84Bbox[0]), String.format("%.4f", wgs84Bbox[1]),
                String.format("%.4f", wgs84Bbox[2]), String.format("%.4f", wgs84Bbox[3]));
    }

    private List<Object[]> extractH3FromBil(String cellId, Path bilFile,
                                             Map<String, Double> hdr, String epsg)
            throws IOException, SQLException {

        int    ncols  = hdr.get("ncols").intValue();
        int    nrows  = hdr.get("nrows").intValue();
        double ulxmap = hdr.get("ulxmap");
        double ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim");
        double ydim   = hdr.get("ydim");

        CoordinateTransform toWgs84 = "EPSG:32649".equals(epsg) ? tlTransform49N.get() : tlTransform48N.get();

        // Read all pixels into memory
        float[] pixels = readBilPixels(bilFile, ncols, nrows);

        // One map per resolution — Long key avoids string allocation; ~10x faster than "res:h3idx" strings
        @SuppressWarnings("unchecked")
        HashMap<Long, Float>[] resMaps = new HashMap[RESOLUTIONS.length];
        for (int i = 0; i < RESOLUTIONS.length; i++) resMaps[i] = new HashMap<>(4096);

        ProjCoordinate srcPt = new ProjCoordinate();
        ProjCoordinate dstPt = new ProjCoordinate();

        for (int row = 0; row < nrows; row += BIL_SAMPLE_STRIDE) {
            for (int col = 0; col < ncols; col += BIL_SAMPLE_STRIDE) {
                float signal = pixels[row * ncols + col];
                if (signal >= NODATA_TOLERANCE || !Float.isFinite(signal)
                        || signal < SIGNAL_MIN_DBM || signal > SIGNAL_MAX_DBM) continue;

                double srcX = ulxmap + (col + 0.5) * xdim;
                double srcY = ulymap - (row + 0.5) * ydim;

                srcPt.setValue(srcX, srcY);
                toWgs84.transform(srcPt, dstPt);
                double lon = dstPt.x;
                double lat = dstPt.y;

                for (int i = 0; i < RESOLUTIONS.length; i++) {
                    long h3Idx = h3.latLngToCell(lat, lon, RESOLUTIONS[i]);
                    Float existing = resMaps[i].get(h3Idx);
                    if (existing == null || existing < signal) {
                        resMaps[i].put(h3Idx, signal);
                    }
                }
            }
        }

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            int res = RESOLUTIONS[i];
            for (Map.Entry<Long, Float> e : resMaps[i].entrySet()) {
                rows.add(new Object[]{res, e.getKey(), cellId, e.getValue()});
            }
        }
        return rows;
    }

    /** Read BIL file as little-endian float32 array. */
    private float[] readBilPixels(Path bilFile, int ncols, int nrows) throws IOException {
        float[] pixels = new float[ncols * nrows];
        int bufSize = Math.min(ncols * 256, 1 << 20);
        ByteBuffer buf = ByteBuffer.allocate(bufSize * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        try (FileChannel fc = FileChannel.open(bilFile, StandardOpenOption.READ)) {
            int idx = 0;
            long remaining = (long) ncols * nrows;
            while (remaining > 0) {
                int toRead = (int) Math.min(remaining, bufSize);
                buf.clear().limit(toRead * Float.BYTES);
                int bytesRead = fc.read(buf);
                if (bytesRead <= 0) break;
                buf.flip();
                int floatsRead = bytesRead / Float.BYTES;
                for (int i = 0; i < floatsRead; i++) {
                    pixels[idx++] = buf.getFloat();
                }
                remaining -= floatsRead;
            }
        }
        return pixels;
    }

    // ── COG processing (unchanged) ────────────────────────────────────────────

    private List<Object[]> processCogFile(File tifFile, String cellId) throws IOException, SQLException {
        org.geotools.coverage.grid.GridCoverage2D coverage;
        org.geotools.gce.geotiff.GeoTiffReader reader = new org.geotools.gce.geotiff.GeoTiffReader(tifFile);
        try {
            coverage = reader.read(null);
        } finally {
            reader.dispose();
        }

        java.awt.image.RenderedImage image = coverage.getRenderedImage();
        int width  = image.getWidth();
        int height = image.getHeight();

        float[] pixels = new float[width * height];
        image.getData().getSamples(0, 0, width, height, 0, pixels);

        org.geotools.geometry.GeneralBounds env = new org.geotools.geometry.GeneralBounds(coverage.getEnvelope());
        double minX = env.getMinimum(0);
        double maxY = env.getMaximum(1);
        double resX = env.getSpan(0) / width;
        double resY = env.getSpan(1) / height;

        @SuppressWarnings("unchecked")
        HashMap<Long, Float>[] resMaps = new HashMap[RESOLUTIONS.length];
        for (int i = 0; i < RESOLUTIONS.length; i++) resMaps[i] = new HashMap<>(4096);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                float signal = pixels[row * width + col];
                if (Float.isNaN(signal) || !Float.isFinite(signal)) continue;
                double lon = minX + (col + 0.5) * resX;
                double lat = maxY - (row + 0.5) * resY;
                for (int i = 0; i < RESOLUTIONS.length; i++) {
                    long h3Idx = h3.latLngToCell(lat, lon, RESOLUTIONS[i]);
                    Float existing = resMaps[i].get(h3Idx);
                    if (existing == null || existing < signal) {
                        resMaps[i].put(h3Idx, signal);
                    }
                }
            }
        }

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            int res = RESOLUTIONS[i];
            for (Map.Entry<Long, Float> e : resMaps[i].entrySet()) {
                rows.add(new Object[]{res, e.getKey(), cellId, e.getValue()});
            }
        }
        return rows;
    }

    // ── MariaDB upsert ────────────────────────────────────────────────────────

    private void upsertCell(String cellId, double[] bbox) {
        Cell cell = cellRepository.findById(cellId).orElseGet(Cell::new);
        cell.setCellId(cellId);
        cell.setRealStatus(true);

        Polygon polygon = GF.createPolygon(new Coordinate[]{
                new Coordinate(bbox[0], bbox[1]),
                new Coordinate(bbox[2], bbox[1]),
                new Coordinate(bbox[2], bbox[3]),
                new Coordinate(bbox[0], bbox[3]),
                new Coordinate(bbox[0], bbox[1])
        });
        polygon.setSRID(4326);
        cell.setGeom(polygon);

        cellRepository.save(cell);
    }

    // ── HDR / CRS helpers ─────────────────────────────────────────────────────

    Map<String, Double> parseHdr(Path hdrFile) throws IOException {
        Map<String, Double> values = new HashMap<>();
        List<String> required = List.of("ulxmap", "ulymap", "xdim", "ydim", "ncols", "nrows");
        for (String line : Files.readAllLines(hdrFile)) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && required.contains(parts[0].toLowerCase()))
                values.put(parts[0].toLowerCase(), Double.parseDouble(parts[1]));
        }
        for (String key : required)
            if (!values.containsKey(key)) throw new IllegalStateException("Missing HDR field: " + key);
        return values;
    }

    String detectEpsg(Path cellDir, String cellId, Map<String, Double> hdr) {
        // 1. Try PRJ file
        Path prj = cellDir.resolve(cellId + ".prj");
        if (Files.exists(prj)) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("AUTHORITY\\[\"EPSG\",\"(326\\d+)\"\\]\\s*\\]\\s*$")
                        .matcher(Files.readString(prj));
                if (m.find()) return "EPSG:" + m.group(1);
            } catch (IOException e) {
                log.warn("Failed to read PRJ for {}", cellId);
            }
        }
        // 2. Infer from ulxmap easting (zone 48N vs 49N)
        double easting = hdr.get("ulxmap");
        String epsg = easting > 900_000 ? "EPSG:32649" : "EPSG:32648";
        log.debug("Cell {} no PRJ; inferred {} from ulxmap={}", cellId, epsg, easting);
        return epsg;
    }

    double[] computeWgs84Bbox(Map<String, Double> hdr, String epsgCode) {
        double ulxmap = hdr.get("ulxmap"), ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim"),   ydim   = hdr.get("ydim");
        double ncols  = hdr.get("ncols"),   nrows  = hdr.get("nrows");

        double minX = ulxmap - xdim / 2, maxY = ulymap + ydim / 2;
        double maxX = minX + ncols * xdim, minY = maxY - nrows * ydim;

        CoordinateTransform toWgs84 = "EPSG:32649".equals(epsgCode) ? tlTransform49N.get() : tlTransform48N.get();

        ProjCoordinate bl = transform(toWgs84, minX, minY);
        ProjCoordinate br = transform(toWgs84, maxX, minY);
        ProjCoordinate tr = transform(toWgs84, maxX, maxY);
        ProjCoordinate tl = transform(toWgs84, minX, maxY);

        return new double[]{
                Math.min(Math.min(bl.x, tl.x), Math.min(br.x, tr.x)),
                Math.min(Math.min(bl.y, br.y), Math.min(tl.y, tr.y)),
                Math.max(Math.max(bl.x, tl.x), Math.max(br.x, tr.x)),
                Math.max(Math.max(bl.y, br.y), Math.max(tl.y, tr.y))
        };
    }

    private ProjCoordinate transform(CoordinateTransform ct, double x, double y) {
        ProjCoordinate dst = new ProjCoordinate();
        ct.transform(new ProjCoordinate(x, y), dst);
        return dst;
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    public record PipelineStatus(boolean running, int processedFiles, int totalFiles,
                                 int progressPct, String mode) {}
}
