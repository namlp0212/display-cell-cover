package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "infra_rasters")
public class InfraRaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "raster_code", length = 100, unique = true)
    private String rasterCode;

    @Column(name = "raster_name", length = 300)
    private String rasterName;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "network_type", length = 10)
    private String networkType;

    @Column(name = "azimuth")
    private Double azimuth;

    @Column(name = "tilt")
    private Double tilt;

    @Column(name = "power_dbm")
    private Double powerDbm;

    @Column(name = "row_status")
    private int rowStatus = 1;

    @Column(name = "nims_updated_at")
    private LocalDateTime nimsUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRasterCode() { return rasterCode; }
    public void setRasterCode(String rasterCode) { this.rasterCode = rasterCode; }

    public String getRasterName() { return rasterName; }
    public void setRasterName(String rasterName) { this.rasterName = rasterName; }

    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public Double getAzimuth() { return azimuth; }
    public void setAzimuth(Double azimuth) { this.azimuth = azimuth; }

    public Double getTilt() { return tilt; }
    public void setTilt(Double tilt) { this.tilt = tilt; }

    public Double getPowerDbm() { return powerDbm; }
    public void setPowerDbm(Double powerDbm) { this.powerDbm = powerDbm; }

    public int getRowStatus() { return rowStatus; }
    public void setRowStatus(int rowStatus) { this.rowStatus = rowStatus; }

    public LocalDateTime getNimsUpdatedAt() { return nimsUpdatedAt; }
    public void setNimsUpdatedAt(LocalDateTime nimsUpdatedAt) { this.nimsUpdatedAt = nimsUpdatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
