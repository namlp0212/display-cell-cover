package com.example.cellcover.controller;

import com.example.cellcover.dto.CellImportResult;
import com.example.cellcover.service.CellImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CellImportService cellImportService;

    public AdminController(CellImportService cellImportService) {
        this.cellImportService = cellImportService;
    }

    @PostMapping("/sync-cells")
    public CellImportResult syncCells() {
        return cellImportService.syncCells();
    }
}
