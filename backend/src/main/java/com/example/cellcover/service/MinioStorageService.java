package com.example.cellcover.service;

import com.example.cellcover.config.CellImportProperties;
import it.geosolutions.imageio.plugins.cog.CogImageReadParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper around AWS SDK v2 S3Client configured to talk to a local MinIO instance.
 * Provides upload, download, delete and existence checks for COG files.
 *
 * Object key conventions:
 *   binary/   {cellId}_svr.tif   — binary signal COGs
 *   continuous/{cellId}.tif      — continuous signal COGs
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    /** Files larger than this threshold are uploaded using multipart (10 MB). */
    private static final long MULTIPART_THRESHOLD = 10 * 1024 * 1024L;

    /** Size of each individual part in a multipart upload (10 MB). */
    private static final long PART_SIZE = 10 * 1024 * 1024L;

    private final S3Client s3;
    private final String   bucket;
    private final String   minioEndpoint;

    public MinioStorageService(CellImportProperties props) {
        CellImportProperties.Minio minio = props.getMinio();
        this.bucket        = minio.getBucket();
        this.minioEndpoint = minio.getEndpoint();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(minio.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getAccessKey(), minio.getSecretKey())))
                .region(Region.US_EAST_1)   // MinIO ignores region but SDK requires one
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // required for MinIO (no virtual-host)
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Uploads a local file to MinIO.
     * Files <= 10 MB use a single PUT request.
     * Files >  10 MB use multipart upload: the file is split into 10 MB chunks that
     * are uploaded sequentially. Only the failed chunk needs to be retried on error,
     * and there is no 5 GB single-request limit.
     */
    public void upload(Path localFile, String objectKey) throws IOException {
        long fileSize = Files.size(localFile);
        log.info("Uploading {} ({} MB) → MinIO s3://{}/{}",
                localFile.getFileName(), fileSize / (1024 * 1024), bucket, objectKey);

        if (fileSize <= MULTIPART_THRESHOLD) {
            uploadSingle(localFile, objectKey);
        } else {
            uploadMultipart(localFile, objectKey, fileSize);
        }

        log.info("Upload complete: {} ({} MB)", objectKey, fileSize / (1024 * 1024));
    }

    private void uploadSingle(Path localFile, String objectKey) {
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                localFile);
    }

    private void uploadMultipart(Path localFile, String objectKey, long fileSize) throws IOException {
        String uploadId = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(objectKey).build()
        ).uploadId();

        List<CompletedPart> completedParts = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(localFile.toFile(), "r")) {
            int partNumber = 1;
            long remaining = fileSize;

            while (remaining > 0) {
                long chunkSize = Math.min(PART_SIZE, remaining);
                long offset    = fileSize - remaining;
                byte[] buffer  = new byte[(int) chunkSize];
                raf.seek(offset);
                raf.readFully(buffer, 0, (int) chunkSize);

                String etag = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket).key(objectKey)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength(chunkSize)
                                .build(),
                        RequestBody.fromBytes(buffer)
                ).eTag();

                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber).eTag(etag).build());

                log.debug("Uploaded part {}/{} for {}",
                        partNumber, (int) Math.ceil((double) fileSize / PART_SIZE), objectKey);
                partNumber++;
                remaining -= chunkSize;
            }
        } catch (Exception e) {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(objectKey).uploadId(uploadId).build());
            throw new IOException("Multipart upload failed for " + objectKey + ": " + e.getMessage(), e);
        }

        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(completedParts).build())
                .build());
    }

    public void download(String objectKey, Path dest) {
        log.info("Downloading MinIO s3://{}/{} → {}", bucket, objectKey, dest.getFileName());
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Download failed for " + objectKey + ": " + e.getMessage(), e);
        }
    }

    /** Downloads an object from MinIO and returns its contents as a byte array. */
    public byte[] downloadBytes(String objectKey) {
        log.info("Downloading MinIO s3://{}/{} → memory", bucket, objectKey);
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Download failed for " + objectKey + ": " + e.getMessage(), e);
        }
    }

    /** Opens a streaming connection to a MinIO object. Caller must close the returned stream. */
    public java.io.InputStream openStream(String objectKey) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    public void delete(String objectKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
            log.info("Deleted from MinIO: {}", objectKey);
        } catch (SdkException e) {
            log.warn("Failed to delete MinIO object {}: {}", objectKey, e.getMessage());
        }
    }

    /**
     * Lists all object keys under the given prefix (e.g. "continuous/" or "binary/").
     * Uses paginated ListObjectsV2 so it works with any number of objects.
     */
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
        pages.contents().forEach(obj -> keys.add(obj.key()));
        return keys;
    }

    public boolean exists(String objectKey) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the HTTP URL GeoServer uses to read the COG via HTTP range reads.
     * Example: http://localhost:9000/cellcover/binary/EDN000021_svr.tif
     */
    public String vsiPath(String objectKey) {
        return minioEndpoint + "/" + bucket + "/" + objectKey;
    }

    /**
     * Returns the plain HTTP URL for direct Java HTTP access (PixelMaxSignalService).
     * Example: http://localhost:9000/cellcover/continuous/EDN000021.tif
     */
    public String httpUrl(String objectKey) {
        return minioEndpoint + "/" + bucket + "/" + objectKey;
    }

    /**
     * Creates a {@link MinioRangeReader} for the given object key, ready to be used
     * with {@link it.geosolutions.imageioimpl.plugins.cog.DefaultCogImageInputStream}
     * for COG range reads.
     */
    public MinioRangeReader createRangeReader(String objectKey) {
        java.net.URI uri = java.net.URI.create(httpUrl(objectKey));
        return new MinioRangeReader(s3, bucket, objectKey, uri,
                CogImageReadParam.DEFAULT_HEADER_LENGTH);
    }

    public static String binaryKey(String cellId) {
        return "binary/" + cellId + "_svr.tif";
    }

    public static String continuousKey(String cellId) {
        return "continuous/" + cellId + ".tif";
    }
}
