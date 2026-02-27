package com.example.cellcover.dto;

import java.util.List;

public record CellImportResult(
        List<String> importedCells,
        List<String> skippedCells,
        int totalImported,
        String geoServerOutput
) {
}
