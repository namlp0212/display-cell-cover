package com.example.cellcover.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

import java.time.LocalDateTime;

@Entity
@Table(name = "cells")
public class Cell {

    @Id
    @Column(name = "cell_id", length = 50)
    private String cellId;

    @Column(name = "cell_name", length = 200)
    private String cellName;

    @Column(name = "operator", length = 100)
    private String operator;

    @Column(name = "band", length = 20)
    private String band;

    @Column(name = "geom", nullable = false, columnDefinition = "GEOMETRY")
    private Geometry geom;

    @Column(name = "real_status", nullable = false)
    private boolean realStatus = true;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // V18 fields
    @Column(name = "raster_id")
    private Long rasterId;

    @Column(name = "row_status")
    private int rowStatus = 1;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "nims_updated_at")
    private LocalDateTime nimsUpdatedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getCellId() { return cellId; }
    public void setCellId(String cellId) { this.cellId = cellId; }

    public String getCellName() { return cellName; }
    public void setCellName(String cellName) { this.cellName = cellName; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getBand() { return band; }
    public void setBand(String band) { this.band = band; }

    public Geometry getGeom() { return geom; }
    public void setGeom(Geometry geom) { this.geom = geom; }

    public boolean isRealStatus() { return realStatus; }
    public void setRealStatus(boolean realStatus) { this.realStatus = realStatus; }

    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getRasterId() { return rasterId; }
    public void setRasterId(Long rasterId) { this.rasterId = rasterId; }

    public int getRowStatus() { return rowStatus; }
    public void setRowStatus(int rowStatus) { this.rowStatus = rowStatus; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getNimsUpdatedAt() { return nimsUpdatedAt; }
    public void setNimsUpdatedAt(LocalDateTime nimsUpdatedAt) { this.nimsUpdatedAt = nimsUpdatedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
