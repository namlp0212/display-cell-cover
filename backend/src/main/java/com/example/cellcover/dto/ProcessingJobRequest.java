package com.example.cellcover.dto;

import java.util.List;

public class ProcessingJobRequest {

    private String networkType;
    private List<String> cellIds;

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public List<String> getCellIds() { return cellIds; }
    public void setCellIds(List<String> cellIds) { this.cellIds = cellIds; }
}
