package com.example.cellcover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class SimulationRequest {

    @NotBlank
    private String name;

    private String description;
    private String createdBy;

    @NotNull
    private List<String> cellsOff;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public List<String> getCellsOff() { return cellsOff; }
    public void setCellsOff(List<String> cellsOff) { this.cellsOff = cellsOff; }
}
