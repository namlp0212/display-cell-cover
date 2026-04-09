package com.example.cellcover.dto;

import java.util.List;

public class SessionSimulationRequest {

    private String name;
    private List<String> cellsOff;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getCellsOff() { return cellsOff; }
    public void setCellsOff(List<String> cellsOff) { this.cellsOff = cellsOff; }
}
