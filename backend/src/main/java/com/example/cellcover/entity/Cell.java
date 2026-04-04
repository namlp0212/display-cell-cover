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
}
