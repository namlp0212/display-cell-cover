package com.example.cellcover.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import java.time.LocalDateTime;

@Entity
@Table(name = "infra_stations")
public class InfraStation {

    @Id
    @Column(name = "STATION_ID")
    private Long stationId;

    @Column(name = "STATION_CODE", length = 50)
    private String stationCode;

    @Column(name = "GEOMETRY", columnDefinition = "GEOMETRY")
    private Point geometry;

    @Column(name = "DEPT_ID")
    private Long deptId;

    @Column(name = "LOCATION_ID")
    private Long locationId;

    @Column(name = "ROW_STATUS")
    private int rowStatus;

    @Column(name = "UPDATE_TIME")
    private LocalDateTime updateTime;

    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }

    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }

    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }

    public int getRowStatus() { return rowStatus; }
    public void setRowStatus(int rowStatus) { this.rowStatus = rowStatus; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
