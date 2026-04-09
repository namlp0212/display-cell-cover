package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "source_raster_files")
public class SourceRasterFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cell_id", length = 50)
    private String cellId;

    @Column(name = "bil_object_key", length = 1000)
    private String bilObjectKey;

    @Column(name = "hdr_object_key", length = 1000)
    private String hdrObjectKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_md5", length = 64)
    private String checksumMd5;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "uploaded_by", length = 200)
    private String uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCellId() { return cellId; }
    public void setCellId(String cellId) { this.cellId = cellId; }

    public String getBilObjectKey() { return bilObjectKey; }
    public void setBilObjectKey(String bilObjectKey) { this.bilObjectKey = bilObjectKey; }

    public String getHdrObjectKey() { return hdrObjectKey; }
    public void setHdrObjectKey(String hdrObjectKey) { this.hdrObjectKey = hdrObjectKey; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getChecksumMd5() { return checksumMd5; }
    public void setChecksumMd5(String checksumMd5) { this.checksumMd5 = checksumMd5; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
