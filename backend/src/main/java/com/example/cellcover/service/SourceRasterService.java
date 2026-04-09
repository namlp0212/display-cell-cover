package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import com.example.cellcover.dto.UploadInitRequest;
import com.example.cellcover.entity.ImportJob;
import com.example.cellcover.entity.SourceFileMapping;
import com.example.cellcover.entity.SourceRasterFile;
import com.example.cellcover.entity.UploadSession;
import com.example.cellcover.repository.ImportJobRepository;
import com.example.cellcover.repository.SourceFileMappingRepository;
import com.example.cellcover.repository.SourceRasterFileRepository;
import com.example.cellcover.repository.UploadSessionRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * CN-04.01: Handles uploading source BIL/HDR raster files to MinIO.
 *
 * Supports:
 *  - Single file upload (BIL + HDR pair)
 *  - Chunked archive upload (init → uploadChunk → complete → triggers ImportJob)
 */
@Service
public class SourceRasterService {

    private static final Logger log = LoggerFactory.getLogger(SourceRasterService.class);

    private final SourceRasterFileRepository sourceFileRepo;
    private final UploadSessionRepository uploadSessionRepo;
    private final SourceFileMappingRepository mappingRepo;
    private final ImportJobRepository importJobRepo;
    private final ImportJobService importJobService;
    private final S3Client s3;
    private final String sourceBucket;

    public SourceRasterService(
            SourceRasterFileRepository sourceFileRepo,
            UploadSessionRepository uploadSessionRepo,
            SourceFileMappingRepository mappingRepo,
            ImportJobRepository importJobRepo,
            ImportJobService importJobService,
            CellImportProperties props,
            @Value("${minio.source-raster-bucket:source-raster}") String sourceBucket) {
        this.sourceFileRepo    = sourceFileRepo;
        this.uploadSessionRepo = uploadSessionRepo;
        this.mappingRepo       = mappingRepo;
        this.importJobRepo     = importJobRepo;
        this.importJobService  = importJobService;
        this.sourceBucket      = sourceBucket;
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

    // ── Single file upload ────────────────────────────────────────────────────

    @Transactional
    public SourceRasterFile uploadSingle(MultipartFile bil, MultipartFile hdr, String cellId,
                                         String uploadedBy) throws Exception {
        String bilKey = "source/" + cellId + "/" + bil.getOriginalFilename();
        String hdrKey = "source/" + cellId + "/" + hdr.getOriginalFilename();

        s3.putObject(PutObjectRequest.builder()
                        .bucket(sourceBucket).key(bilKey).build(),
                RequestBody.fromBytes(bil.getBytes()));
        s3.putObject(PutObjectRequest.builder()
                        .bucket(sourceBucket).key(hdrKey).build(),
                RequestBody.fromBytes(hdr.getBytes()));

        // Mark previous active file as inactive
        Optional<SourceRasterFile> prev = sourceFileRepo.findByCellIdAndIsActive(cellId, true);
        prev.ifPresent(f -> { f.setActive(false); sourceFileRepo.save(f); });

        SourceRasterFile rec = new SourceRasterFile();
        rec.setCellId(cellId);
        rec.setBilObjectKey(bilKey);
        rec.setHdrObjectKey(hdrKey);
        rec.setFileSizeBytes(bil.getSize());
        rec.setActive(true);
        rec.setUploadedBy(uploadedBy);
        rec.setUploadedAt(LocalDateTime.now());
        return sourceFileRepo.save(rec);
    }

    // ── Chunked upload ────────────────────────────────────────────────────────

    @Transactional
    public UploadSession initUpload(UploadInitRequest req, String createdBy) {
        UploadSession session = new UploadSession();
        session.setUploadId(UUID.randomUUID().toString());
        session.setFilename(req.getFilename());
        session.setFileSizeBytes(req.getFileSizeBytes());
        session.setTotalChunks(req.getTotalChunks());
        session.setChunkSizeBytes(req.getChunkSizeBytes());
        session.setArchiveType(req.getArchiveType());
        session.setNetworkType(req.getNetworkType());
        session.setStatus("uploading");
        session.setCreatedBy(createdBy);
        session.setCreatedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        session.setReceivedChunks("[]");
        return uploadSessionRepo.save(session);
    }

    @Transactional
    public UploadSession uploadChunk(String uploadId, int chunkIndex, byte[] data) {
        UploadSession session = uploadSessionRepo.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + uploadId));

        String chunkKey = "uploads/" + uploadId + "/chunks/" + chunkIndex;
        s3.putObject(PutObjectRequest.builder()
                        .bucket(sourceBucket).key(chunkKey).build(),
                RequestBody.fromBytes(data));

        // Track received chunks in JSON array (simple string manipulation)
        String received = session.getReceivedChunks();
        if (received == null || received.isBlank() || "[]".equals(received)) {
            session.setReceivedChunks("[" + chunkIndex + "]");
        } else {
            session.setReceivedChunks(received.substring(0, received.length() - 1) + "," + chunkIndex + "]");
        }

        return uploadSessionRepo.save(session);
    }

    public UploadSession getUploadStatus(String uploadId) {
        return uploadSessionRepo.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + uploadId));
    }

    @Transactional
    public ImportJob completeUpload(String uploadId) {
        UploadSession session = uploadSessionRepo.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + uploadId));

        session.setStatus("merging");
        uploadSessionRepo.save(session);

        // Create import job
        ImportJob job = new ImportJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setJobType("archive");
        job.setNetworkType(session.getNetworkType());
        job.setArchiveFilename(session.getFilename());
        job.setArchiveObjectKey("uploads/" + uploadId + "/merged.zip");
        job.setStatus("queued");
        job = importJobRepo.save(job);

        session.setStatus("completed");
        session.setCompletedAt(LocalDateTime.now());
        uploadSessionRepo.save(session);

        // Trigger async processing
        importJobService.processImportJob(job.getJobId());
        return job;
    }
}
