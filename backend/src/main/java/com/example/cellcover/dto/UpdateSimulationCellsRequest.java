package com.example.cellcover.dto;

import java.util.List;

public class UpdateSimulationCellsRequest {

    private List<String> addCellsOff;
    private List<String> removeCellsOff;

    public List<String> getAddCellsOff() { return addCellsOff; }
    public void setAddCellsOff(List<String> addCellsOff) { this.addCellsOff = addCellsOff; }

    public List<String> getRemoveCellsOff() { return removeCellsOff; }
    public void setRemoveCellsOff(List<String> removeCellsOff) { this.removeCellsOff = removeCellsOff; }
}
