package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "upload_sessions")
public class UploadSession {

    @Id
    @Column(name = "upload_id", length = 36)
    private String uploadId;

    @Column(name = "filename", length = 500)
    private String filename;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "total_chunks")
    private int totalChunks;

    @Column(name = "chunk_size_bytes")
    private int chunkSizeBytes;

    @Column(name = "received_chunks", columnDefinition = "JSON")
    private String receivedChunks;

    @Column(name = "archive_type", length = 10)
    private String archiveType;

    @Column(name = "network_type", length = 10)
    private String networkType;

    @Column(name = "status", length = 20)
    private String status = "uploading";

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(int chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }

    public String getReceivedChunks() { return receivedChunks; }
    public void setReceivedChunks(String receivedChunks) { this.receivedChunks = receivedChunks; }

    public String getArchiveType() { return archiveType; }
    public void setArchiveType(String archiveType) { this.archiveType = archiveType; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
