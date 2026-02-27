package com.example.cellcover.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

import java.time.OffsetDateTime;

@Entity
@Table(name = "raster_coverages")
public class RasterCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cell_id", nullable = false)
    private String cellId;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "bbox", nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Geometry bbox;

    @Column(name = "crs", nullable = false, length = 64)
    private String crs = "EPSG:4326";

    @Column(name = "is_svr", nullable = false)
    private boolean svr;

    @Column(name = "is_visible", nullable = false)
    private boolean visible = true;

    @Column(name = "ovlp_group", nullable = false)
    private int ovlpGroup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCellId() {
        return cellId;
    }

    public void setCellId(String cellId) {
        this.cellId = cellId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Geometry getBbox() {
        return bbox;
    }

    public void setBbox(Geometry bbox) {
        this.bbox = bbox;
    }

    public String getCrs() {
        return crs;
    }

    public void setCrs(String crs) {
        this.crs = crs;
    }

    public boolean isSvr() {
        return svr;
    }

    public void setSvr(boolean svr) {
        this.svr = svr;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int getOvlpGroup() {
        return ovlpGroup;
    }

    public void setOvlpGroup(int ovlpGroup) {
        this.ovlpGroup = ovlpGroup;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
