package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "job_id", length = 36, unique = true)
    private String jobId;

    @Column(name = "job_type", length = 20)
    private String jobType;

    @Column(name = "network_type", length = 10)
    private String networkType;

    @Column(name = "triggered_by", length = 200)
    private String triggeredBy;

    @Column(name = "status", length = 20)
    private String status = "queued";

    @Column(name = "total_files")
    private int totalFiles;

    @Column(name = "processed")
    private int processed;

    @Column(name = "succeeded")
    private int succeeded;

    @Column(name = "failed_count")
    private int failedCount;

    @Column(name = "archive_filename", length = 500)
    private String archiveFilename;

    @Column(name = "archive_object_key", length = 1000)
    private String archiveObjectKey;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

    public int getProcessed() { return processed; }
    public void setProcessed(int processed) { this.processed = processed; }

    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public String getArchiveFilename() { return archiveFilename; }
    public void setArchiveFilename(String archiveFilename) { this.archiveFilename = archiveFilename; }

    public String getArchiveObjectKey() { return archiveObjectKey; }
    public void setArchiveObjectKey(String archiveObjectKey) { this.archiveObjectKey = archiveObjectKey; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
