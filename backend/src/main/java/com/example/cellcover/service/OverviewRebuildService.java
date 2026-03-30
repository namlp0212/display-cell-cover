package com.example.cellcover.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts all COG files in MinIO to true Cloud-Optimized GeoTIFF layout.
 *
 * Strategy per COG:
 *   1. Download from MinIO → temp source file
 *   2. Run gdal_translate -of COG → second temp file (overviews-first layout)
 *   3. Re-upload the COG temp file (overwrite) to MinIO
 *   4. Delete both temp files
 *
 * Using gdal_translate -of COG instead of gdaladdo ensures overview IFDs are
 * written BEFORE the full-res data, which is required for efficient HTTP range
 * reads via GDAL /vsis3/ (MinIO). gdaladdo appends overviews after full-res,
 * producing an invalid COG layout.
 *
 * Runs as a background job; progress can be queried via {@link #status()}.
 * Only one rebuild job can run at a time (subsequent POST calls are no-ops while running).
 */
@Service
public class OverviewRebuildService {

    private static final Logger log = LoggerFactory.getLogger(OverviewRebuildService.class);

    /** Number of COGs processed in parallel. */
    private static final int PARALLELISM = 4;

    private final MinioStorageService minioStorage;

    // ── Job state ─────────────────────────────────────────────────────────────

    private final AtomicBoolean  running  = new AtomicBoolean(false);
    private final AtomicInteger  total    = new AtomicInteger(0);
    private final AtomicInteger  done     = new AtomicInteger(0);
    private final AtomicInteger  failed   = new AtomicInteger(0);
    private volatile String      phase    = "idle";   // idle | scanning | rebuilding | done
    private volatile long        startedAt;

    public OverviewRebuildService(MinioStorageService minioStorage) {
        this.minioStorage = minioStorage;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the rebuild job in the background.
     * Returns false immediately if a job is already running.
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            log.info("Rebuild already in progress — ignoring duplicate start request");
            return false;
        }
        total.set(0);
        done.set(0);
        failed.set(0);
        phase = "scanning";
        startedAt = System.currentTimeMillis();

        Thread t = new Thread(this::runJob, "overview-rebuild");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Returns a snapshot of current job state. */
    public Map<String, Object> status() {
        int t = total.get(), d = done.get(), f = failed.get();
        long elapsed = running.get() ? System.currentTimeMillis() - startedAt : 0;
        return Map.of(
                "running",   running.get(),
                "phase",     phase,
                "total",     t,
                "done",      d,
                "failed",    f,
                "remaining", Math.max(0, t - d - f),
                "elapsedMs", elapsed
        );
    }

    // ── Job execution ─────────────────────────────────────────────────────────

    private void runJob() {
        try {
            log.info("Overview rebuild started");

            // 1. Collect all COG keys from both prefixes
            phase = "scanning";
            List<String> keys = new ArrayList<>();
            keys.addAll(minioStorage.listKeys("continuous/"));
            keys.addAll(minioStorage.listKeys("binary/"));
            total.set(keys.size());
            log.info("Found {} COG files to rebuild", keys.size());

            // 2. Process in parallel with bounded thread pool
            phase = "rebuilding";
            ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
            List<Future<?>> futures = new ArrayList<>(keys.size());

            for (String key : keys) {
                futures.add(pool.submit(() -> rebuildOne(key)));
            }

            pool.shutdown();
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

            phase = "done";
            log.info("Overview rebuild complete — done={} failed={} total={}",
                    done.get(), failed.get(), total.get());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            phase = "interrupted";
            log.warn("Overview rebuild interrupted");
        } catch (Exception e) {
            phase = "error";
            log.error("Overview rebuild failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private void rebuildOne(String key) {
        Path src = null;
        Path cog = null;
        try {
            src = Files.createTempFile("cog_src_", ".tif");
            cog = Files.createTempFile("cog_out_", ".tif");
            // COG driver requires a non-existent output file
            Files.delete(cog);

            minioStorage.download(key, src);
            convertToCog(src, cog);
            minioStorage.upload(cog, key);

            done.incrementAndGet();
            int d = done.get(), t = total.get();
            if (d % 50 == 0 || d == t) {
                log.info("Converted to COG: {}/{}", d, t);
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            log.warn("Failed to convert COG for {}: {}", key, e.getMessage());
        } finally {
            tryDelete(src);
            tryDelete(cog);
        }
    }

    private void convertToCog(Path src, Path dst) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "gdal_translate",
                "-of", "COG",
                "-co", "BLOCKSIZE=256",
                "-co", "COMPRESS=DEFLATE",
                "-co", "OVERVIEW_RESAMPLING=AVERAGE",
                src.toAbsolutePath().toString(),
                dst.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("gdal_translate failed (exit " + exitCode + "): " + output.trim());
        }
    }

    private void tryDelete(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }
}
