package com.example.cellcover.service;

import it.geosolutions.imageioimpl.plugins.cog.RangeReader;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * COG {@link RangeReader} backed by the AWS S3 SDK.
 *
 * Used with {@link it.geosolutions.imageioimpl.plugins.cog.DefaultCogImageInputStream} so that
 * {@link it.geosolutions.imageioimpl.plugins.cog.CogImageReader} fetches only the COG header
 * (once, then cached) and the exact tile byte ranges overlapping the requested viewport —
 * instead of downloading the full COG file.
 *
 * <p>Thread-safe: multiple concurrent tile requests may share the same RANGE_POOL and
 * HEADER_CACHE.  Each request gets its own reader instance but headers are deduplicated.
 */
public class MinioRangeReader implements RangeReader {

    private static final Logger log = Logger.getLogger(MinioRangeReader.class.getName());

    /**
     * Parallel range GETs shared across all MinioRangeReader instances.
     * Sized large enough to keep many concurrent cells reading simultaneously.
     * Under 100-user load: Coverage IO_POOL (64) + Signal IO_POOL (64) both submit
     * tasks that call into this pool.  Use unbounded virtual threads to avoid
     * blocking under bursts; fall back to a large fixed pool on older JVMs.
     */
    private static final ExecutorService RANGE_POOL =
            Executors.newFixedThreadPool(Math.min(256, Runtime.getRuntime().availableProcessors() * 32));

    /**
     * COG IFD headers are stable for the lifetime of a file (~16 KB each, ~1113 cells ≈ 18 MB).
     * Caching eliminates the header range read on every tile request after the first.
     */
    private static final ConcurrentHashMap<String, byte[]> HEADER_CACHE = new ConcurrentHashMap<>();

    private final S3Client s3;
    private final String   bucket;
    private final String   key;
    private final URI      uri;
    private       int      headerLength;

    MinioRangeReader(S3Client s3, String bucket, String key, URI uri, int headerLength) {
        this.s3           = s3;
        this.bucket       = bucket;
        this.key          = key;
        this.uri          = uri;
        this.headerLength = headerLength;
    }

    // ── RangeReader contract ──────────────────────────────────────────────────

    @Override
    public URL getURL() throws MalformedURLException {
        return uri.toURL();
    }

    @Override
    public void setHeaderLength(int headerLength) {
        this.headerLength = headerLength;
    }

    @Override
    public int getHeaderLength() {
        return headerLength;
    }

    /**
     * Returns the first {@code headerLength} bytes of the COG, using a cache to avoid
     * re-reading the same header on subsequent tile requests for the same cell.
     */
    @Override
    public byte[] readHeader() {
        byte[] cached = HEADER_CACHE.get(key);
        if (cached != null && cached.length >= headerLength) return cached;
        byte[] data = rangeGet(0, headerLength - 1);
        if (data != null) HEADER_CACHE.put(key, data);
        return data;
    }

    /**
     * Called by {@link it.geosolutions.imageioimpl.plugins.cog.DefaultCogImageInputStream}
     * when the initial header is too small to hold all tile offsets.  Doubles the fetch size.
     */
    @Override
    public byte[] fetchHeader() {
        headerLength = headerLength * 2;
        byte[] data = rangeGet(0, headerLength - 1);
        if (data != null) HEADER_CACHE.put(key, data);
        return data;
    }

    /** Reads the specified byte ranges in parallel and returns a map of startOffset → bytes. */
    @Override
    public Map<Long, byte[]> read(long[]... ranges) {
        return read(Arrays.asList(ranges));
    }

    @Override
    public Map<Long, byte[]> read(Collection<long[]> ranges) {
        if (ranges.isEmpty()) return Collections.emptyMap();

        Map<Long, byte[]> result  = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(ranges.size());

        for (long[] range : ranges) {
            final long start = range[0], end = range[1];
            futures.add(CompletableFuture.runAsync(() -> {
                byte[] data = rangeGet(start, end);
                if (data != null) result.put(start, data);
            }, RANGE_POOL));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] rangeGet(long start, long end) {
        try (var in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key)
                .range("bytes=" + start + "-" + end)
                .build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.log(Level.WARNING, "Range GET [{0}-{1}] failed for {2}: {3}",
                    new Object[]{start, end, key, e.getMessage()});
            return null;
        }
    }

    /** Evicts the cached header for a cell after its COG file is replaced. */
    public static void invalidateHeader(String objectKey) {
        HEADER_CACHE.remove(objectKey);
    }
}
