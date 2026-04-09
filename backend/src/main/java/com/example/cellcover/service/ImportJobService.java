package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.example.cellcover.entity.ImportJob;
import com.example.cellcover.entity.ImportJobError;
import com.example.cellcover.entity.SourceFileMapping;
import com.example.cellcover.entity.SourceRasterFile;
import com.example.cellcover.repository.ImportJobErrorRepository;
import com.example.cellcover.repository.ImportJobRepository;
import com.example.cellcover.repository.SourceFileMappingRepository;
import com.example.cellcover.repository.SourceRasterFileRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CN-04.01 async: Processes import jobs — downloads archive, extracts BIL files,
 * resolves cell_id via mappings, uploads to source-raster bucket, upserts source_raster_files.
 */
@Service
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    private final ImportJobRepository jobRepo;
    private final ImportJobErrorRepository errorRepo;
    private final SourceRasterFileRepository sourceFileRepo;
    private final SourceFileMappingRepository mappingRepo;
    private final S3Client s3;
    private final String sourceBucket;

    public ImportJobService(
            ImportJobRepository jobRepo,
            ImportJobErrorRepository errorRepo,
            SourceRasterFileRepository sourceFileRepo,
            SourceFileMappingRepository mappingRepo,
            CellImportProperties props,
            @Value("${minio.source-raster-bucket:source-raster}") String sourceBucket) {
        this.jobRepo        = jobRepo;
        this.errorRepo      = errorRepo;
        this.sourceFileRepo = sourceFileRepo;
        this.mappingRepo    = mappingRepo;
        this.sourceBucket   = sourceBucket;
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

    @Async("jobExecutor")
    public void processImportJob(String jobId) {
        ImportJob job = jobRepo.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found: " + jobId));

        try {
            job.setStatus("running");
            job.setStartedAt(LocalDateTime.now());
            jobRepo.save(job);

            log.info("[import-job:{}] Starting: archive={}", jobId, job.getArchiveObjectKey());

            // Download archive from MinIO into memory
            byte[] archiveBytes;
            try (InputStream is = s3.getObject(GetObjectRequest.builder()
                    .bucket(sourceBucket)
                    .key(job.getArchiveObjectKey())
                    .build())) {
                archiveBytes = is.readAllBytes();
            }

            // Extract ZIP entries
            int totalFiles = 0, processed = 0, succeeded = 0, failed = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
                ZipEntry entry;
                byte[] bilBytes = null;
                String bilFilename = null;
                byte[] hdrBytes = null;
                String hdrFilename = null;

                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.endsWith(".bil") || name.endsWith(".BIL")) {
                        bilFilename = name;
                        bilBytes = zis.readAllBytes();
                        totalFiles++;
                    } else if (name.endsWith(".hdr") || name.endsWith(".HDR")) {
                        hdrFilename = name;
                        hdrBytes = zis.readAllBytes();
                    }

                    // Process BIL+HDR pair when both are available
                    if (bilBytes != null && hdrBytes != null) {
                        processed++;
                        try {
                            processBilHdrPair(job, bilFilename, bilBytes, hdrFilename, hdrBytes);
                            succeeded++;
                        } catch (Exception e) {
                            failed++;
                            log.warn("[import-job:{}] Failed to process {}: {}", jobId, bilFilename, e.getMessage());
                            ImportJobError err = new ImportJobError();
                            err.setJobId(jobId);
                            err.setFilename(bilFilename);
                            err.setErrorType("PROCESSING_ERROR");
                            err.setErrorMessage(e.getMessage());
                            errorRepo.save(err);
                        }
                        bilBytes = null; hdrBytes = null;
                        bilFilename = null; hdrFilename = null;
                    }
                    zis.closeEntry();
                }
            }

            job.setTotalFiles(totalFiles);
            job.setProcessed(processed);
            job.setSucceeded(succeeded);
            job.setFailedCount(failed);
            job.setStatus(failed == 0 ? "completed" : "completed_with_errors");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);

            log.info("[import-job:{}] Done: total={} ok={} fail={}", jobId, totalFiles, succeeded, failed);

        } catch (Exception e) {
            log.error("[import-job:{}] Fatal error: {}", jobId, e.getMessage(), e);
            job.setStatus("failed");
            job.setFinishedAt(LocalDateTime.now());
            jobRepo.save(job);
        }
    }

    private void processBilHdrPair(ImportJob job, String bilFilename, byte[] bilBytes,
                                    String hdrFilename, byte[] hdrBytes) {
        // Derive stem: "CN-01_234_svr.bil" → stem = "CN-01_234_svr"
        String stem = bilFilename.contains("/")
                ? bilFilename.substring(bilFilename.lastIndexOf('/') + 1)
                : bilFilename;
        stem = stem.replaceAll("\\.[bB][iI][lL]$", "");

        // Resolve cell_id from mapping table, or use stem directly
        String cellId = mappingRepo.findByFilenameStem(stem)
                .map(SourceFileMapping::getCellId)
                .orElse(stem);

        // Upload to MinIO source-raster/source/{cellId}/
        String bilKey = "source/" + cellId + "/" + bilFilename;
        String hdrKey = "source/" + cellId + "/" + hdrFilename;

        s3.putObject(PutObjectRequest.builder()
                .bucket(sourceBucket).key(bilKey).build(),
                RequestBody.fromBytes(bilBytes));
        s3.putObject(PutObjectRequest.builder()
                .bucket(sourceBucket).key(hdrKey).build(),
                RequestBody.fromBytes(hdrBytes));

        // Upsert source_raster_files
        Optional<SourceRasterFile> prev = sourceFileRepo.findByCellIdAndIsActive(cellId, true);
        prev.ifPresent(f -> { f.setActive(false); sourceFileRepo.save(f); });

        SourceRasterFile rec = new SourceRasterFile();
        rec.setCellId(cellId);
        rec.setBilObjectKey(bilKey);
        rec.setHdrObjectKey(hdrKey);
        rec.setFileSizeBytes((long) bilBytes.length);
        rec.setActive(true);
        rec.setUploadedAt(LocalDateTime.now());
        sourceFileRepo.save(rec);
    }
}
