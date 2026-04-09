package com.example.cellcover.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cat_location")
public class CatLocation {

    @Id
    @Column(name = "LOCATION_ID")
    private Long locationId;

    @Column(name = "LOCATION_CODE", length = 50)
    private String locationCode;

    @Column(name = "LOCATION_NAME", length = 100)
    private String locationName;

    @Column(name = "PARENT_ID")
    private Long parentId;

    @Column(name = "LOCATION_LEVEL")
    private int locationLevel;

    @Column(name = "ROW_STATUS")
    private int rowStatus;

    @Column(name = "UPDATE_TIME")
    private LocalDateTime updateTime;

    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }

    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String locationCode) { this.locationCode = locationCode; }

    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public int getLocationLevel() { return locationLevel; }
    public void setLocationLevel(int locationLevel) { this.locationLevel = locationLevel; }

    public int getRowStatus() { return rowStatus; }
    public void setRowStatus(int rowStatus) { this.rowStatus = rowStatus; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
