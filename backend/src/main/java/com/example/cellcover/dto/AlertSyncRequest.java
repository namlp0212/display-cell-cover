package com.example.cellcover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AlertSyncRequest {

    @NotBlank
    private String cellId;

    @NotNull
    private Boolean status;   // true = ON, false = OFF

    private String timestamp;

    public String getCellId() { return cellId; }
    public void setCellId(String cellId) { this.cellId = cellId; }

    public Boolean getStatus() { return status; }
    public void setStatus(Boolean status) { this.status = status; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
