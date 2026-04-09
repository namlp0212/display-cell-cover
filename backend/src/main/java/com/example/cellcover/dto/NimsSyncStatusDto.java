package com.example.cellcover.dto;

import java.time.LocalDateTime;

public class NimsSyncStatusDto {

    private String syncId;
    private String status;
    private LocalDateTime fromTime;
    private LocalDateTime toTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int deptInserted;
    private int deptUpdated;
    private int locationInserted;
    private int locationUpdated;
    private int stationInserted;
    private int stationUpdated;
    private int rasterInserted;
    private int rasterUpdated;
    private int cellInserted;
    private int cellUpdated;
    private int errorCount;
    private String errorMessage;

    public String getSyncId() { return syncId; }
    public void setSyncId(String syncId) { this.syncId = syncId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getFromTime() { return fromTime; }
    public void setFromTime(LocalDateTime fromTime) { this.fromTime = fromTime; }

    public LocalDateTime getToTime() { return toTime; }
    public void setToTime(LocalDateTime toTime) { this.toTime = toTime; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public int getDeptInserted() { return deptInserted; }
    public void setDeptInserted(int deptInserted) { this.deptInserted = deptInserted; }

    public int getDeptUpdated() { return deptUpdated; }
    public void setDeptUpdated(int deptUpdated) { this.deptUpdated = deptUpdated; }

    public int getLocationInserted() { return locationInserted; }
    public void setLocationInserted(int locationInserted) { this.locationInserted = locationInserted; }

    public int getLocationUpdated() { return locationUpdated; }
    public void setLocationUpdated(int locationUpdated) { this.locationUpdated = locationUpdated; }

    public int getStationInserted() { return stationInserted; }
    public void setStationInserted(int stationInserted) { this.stationInserted = stationInserted; }

    public int getStationUpdated() { return stationUpdated; }
    public void setStationUpdated(int stationUpdated) { this.stationUpdated = stationUpdated; }

    public int getRasterInserted() { return rasterInserted; }
    public void setRasterInserted(int rasterInserted) { this.rasterInserted = rasterInserted; }

    public int getRasterUpdated() { return rasterUpdated; }
    public void setRasterUpdated(int rasterUpdated) { this.rasterUpdated = rasterUpdated; }

    public int getCellInserted() { return cellInserted; }
    public void setCellInserted(int cellInserted) { this.cellInserted = cellInserted; }

    public int getCellUpdated() { return cellUpdated; }
    public void setCellUpdated(int cellUpdated) { this.cellUpdated = cellUpdated; }

    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
