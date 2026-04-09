package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.example.cellcover.dto.ProcessingJobRequest;
import com.example.cellcover.entity.Cell;
import com.example.cellcover.entity.ProcessingJob;
import com.example.cellcover.entity.ProcessingJobError;
import com.example.cellcover.entity.SourceRasterFile;
import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.repository.ProcessingJobErrorRepository;
import com.example.cellcover.repository.ProcessingJobRepository;
import com.example.cellcover.repository.SourceRasterFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CN-04.03: Orchestrates per-cell processing pipeline:
 *   1. COG_CONVERT  — download BIL from MinIO, run BilToCogConverter
 *   2. MINIO_UPLOAD — upload resulting COG to cog-raster bucket
 *   3. H3_INDEX_BUILD — run H3IndexPipelineService.buildForCell()
 *
 * On completion invalidates Redis tile cache for all processed cells.
 */
@Service
public class ProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobService.class);

    private final ProcessingJobRepository jobRepo;
    private final ProcessingJobErrorRepository errorRepo;
    private final SourceRasterFileRepository sourceFileRepo;
    private final CellRepository cellRepo;
    private final BilToCogConverter bilToCogConverter;
    private final H3IndexPipelineService h3Pipeline;
    private final CellCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final S3Client s3;
    private final String sourceBucket;
    private final String cogBucket;

    @Value("${cellcover.cog-dir:/tmp/cog}")
    private String cogDirStr;

    public ProcessingJobService(
            ProcessingJobRepository jobRepo,
            ProcessingJobErrorRepository errorRepo,
            SourceRasterFileRepository sourceFileRepo,
            CellRepository cellRepo,
            BilToCogConverter bilToCogConverter,
            H3IndexPipelineService h3Pipeline,
            CellCacheService cacheService,
            ObjectMapper objectMapper,
            CellImportProperties props,
            @Value("${minio.source-raster-bucket:source-raster}") String sourceBucket,
            @Value("${minio.cog-raster-bucket:cog-raster}") String cogBucket) {
        this.jobRepo           = jobRepo;
        this.errorRepo         = errorRepo;
        this.sourceFileRepo    = sourceFileRepo;
        this.cellRepo          = cellRepo;
        this.bilToCogConverter = bilToCogConverter;
        this.h3Pipeline        = h3Pipeline;
        this.cacheService      = cacheService;
        this.objectMapper      = objectMapper;
        this.sourceBucket      = sourceBucket;
        this.cogBucket         = cogBucket;
        CellImportProperties.Minio minio = props.getMinio();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(minio.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getAccessKey(), minio.getSecretKey())))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /** Check if any processing job is currently running. */
    public boolean isRunning() {
        return !jobRepo.findByStatus("running").isEmpty();
    }

    /**
     * Creates a ProcessingJob and triggers async execution.
     * Returns the jobId.
     */
    public String triggerProcessing(ProcessingJobRequest req, String triggeredBy) throws Exception {
        List<String> cellIds = req.getCellIds();
        String cellIdsJson = objectMapper.writeValueAsString(cellIds);

        ProcessingJob job = new ProcessingJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setTriggerType("manual");
        job.setNetworkType(req.getNetworkType());
        job.setCellIds(cellIdsJson);
        job.setTotalCells(cellIds.size());
        job.setTriggeredBy(triggeredBy);
        job.setStatus("queued");
        job = jobRepo.save(job);

        runProcessingJob(job.getJobId());
        return job.getJobId();
    }

    @Async("jobExecutor")
    public void runProcessingJob(String jobId) {
        ProcessingJob job = jobRepo.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Processing job not found: " + jobId));

        try {
            job.setStatus("running");
            job.setStartedAt(LocalDateTime.now());
            jobRepo.save(job);

            List<String> cellIds = objectMapper.readValue(job.getCellIds(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

            int processed = 0, succeeded = 0, failed = 0;
            for (String cellId : cellIds) {
                processed++;
                try {
                    processSingleCell(job.getJobId(), cellId);
                    succeeded++;
                    log.info("[proc-job:{}] Cell {} processed OK ({}/{})",
                            jobId, cellId, processed, cellIds.size());
                } catch (Exception e) {
                    failed++;
                    log.warn("[proc-job:{}] Cell {} failed: {}", jobId, cellId, e.getMessage());
                }

                job.setProcessed(processed);
                job.setSucceeded(succeeded);
                job.setFailedCount(failed);
                jobRepo.save(job);
            }

            job.setStatus(failed == 0 ? "completed" : "partial");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);

            // Invalidate tile cache for all processed cells
            cacheService.invalidateReal();
            cacheService.buildNetworkCache();

            log.info("[proc-job:{}] Done: total={} ok={} fail={}", jobId,
                    cellIds.size(), succeeded, failed);

        } catch (Exception e) {
            log.error("[proc-job:{}] Fatal error: {}", jobId, e.getMessage(), e);
            job.setStatus("failed");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
        }
    }

    private void processSingleCell(String jobId, String cellId) throws Exception {
        Path workDir = Files.createTempDirectory("proc-" + cellId + "-");
        try {
            // Stage 1: COG_CONVERT — download BIL from MinIO source bucket, convert to COG
            Optional<SourceRasterFile> srcFile = sourceFileRepo.findByCellIdAndIsActive(cellId, true);
            if (srcFile.isEmpty()) {
                throw new IllegalStateException("No active source raster file for cell: " + cellId);
            }

            Path bilPath = workDir.resolve(cellId + ".bil");
            Path hdrPath = workDir.resolve(cellId + ".hdr");

            try (var is = s3.getObject(GetObjectRequest.builder()
                    .bucket(sourceBucket).key(srcFile.get().getBilObjectKey()).build())) {
                Files.write(bilPath, is.readAllBytes());
            }
            if (srcFile.get().getHdrObjectKey() != null) {
                try (var is = s3.getObject(GetObjectRequest.builder()
                        .bucket(sourceBucket).key(srcFile.get().getHdrObjectKey()).build())) {
                    Files.write(hdrPath, is.readAllBytes());
                }
            }

            // Stage 2: Parse HDR and convert BIL → COG, upload to cog-raster bucket
            // BilToCogConverter.convert() handles both binary and continuous COG generation
            // and uploads them to MinIO.
            Map<String, Double> hdrMeta = parseHdr(hdrPath);
            String epsgCode = detectEpsg(hdrMeta);
            Path cogDir = Path.of(cogDirStr);
            Files.createDirectories(cogDir.resolve("binary"));
            Files.createDirectories(cogDir.resolve("continuous"));

            bilToCogConverter.convert(cellId, workDir, hdrMeta, epsgCode, cogDir);

            // Stage 3: H3_INDEX_BUILD — build H3 index from COG
            h3Pipeline.buildForCell(cellId);

        } finally {
            // Cleanup temp directory
            deleteDir(workDir);
        }
    }

    /** Parse a simple ESRI .hdr file into key→value map. */
    private Map<String, Double> parseHdr(Path hdrPath) throws Exception {
        Map<String, Double> meta = new java.util.HashMap<>();
        if (!Files.exists(hdrPath)) return meta;
        for (String line : Files.readAllLines(hdrPath)) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2) {
                try {
                    meta.put(parts[0].toLowerCase(), Double.parseDouble(parts[1].trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return meta;
    }

    /** Detect EPSG code from HDR metadata based on UTM zone. */
    private String detectEpsg(Map<String, Double> hdr) {
        // Default to UTM Zone 48N (EPSG:32648) for Vietnam
        // TODO: derive from ulxmap/ulymap coordinates if needed
        return "EPSG:32648";
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception ignored) {}
    }
}
