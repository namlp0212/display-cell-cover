package com.example.cellcover.controller;

import com.example.cellcover.entity.CatDepartment;
import com.example.cellcover.entity.InfraRaster;
import com.example.cellcover.entity.InfraStation;
import com.example.cellcover.repository.CatDepartmentRepository;
import com.example.cellcover.repository.CellRepository;
import com.example.cellcover.repository.InfraRasterRepository;
import com.example.cellcover.repository.InfraStationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Group 1 (Map Display) — CN-01.05: Cell search and tree view.
 *
 * GET /api/cells/search?q=&networkType=&status=&page=0&size=50
 * GET /api/cells/autocomplete?q=&limit=10
 * GET /api/cells/tree?networkType=
 */
@RestController
@RequestMapping("/api/cells")
public class CellController {

    private final CellRepository cellRepo;
    private final InfraRasterRepository rasterRepo;
    private final InfraStationRepository stationRepo;
    private final CatDepartmentRepository deptRepo;

    public CellController(CellRepository cellRepo,
                          InfraRasterRepository rasterRepo,
                          InfraStationRepository stationRepo,
                          CatDepartmentRepository deptRepo) {
        this.cellRepo = cellRepo;
        this.rasterRepo = rasterRepo;
        this.stationRepo = stationRepo;
        this.deptRepo = deptRepo;
    }

    /**
     * CN-01.05: Search cells with optional filters.
     * Returns paginated list with infra/catalog metadata.
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String networkType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String qLike = (q != null && !q.isBlank()) ? "%" + q + "%" : null;
        String ntFilter = (networkType != null && !networkType.isBlank()) ? networkType : null;
        String statusFilter = (status != null && !status.isBlank()) ? status : null;

        int offset = page * size;
        List<Object[]> rows = cellRepo.searchCells(qLike, ntFilter, statusFilter, size, offset);
        long total = cellRepo.countSearchCells(qLike, ntFilter);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cellId",       row[0]);
            item.put("cellName",     row[1]);
            item.put("realStatus",   row[2]);
            item.put("networkType",  row[3]);
            item.put("stationCode",  row[4]);
            item.put("stationId",    row[5]);
            item.put("deptId",       row[6]);
            item.put("deptName",     row[7]);
            item.put("locationId",   row[8]);
            item.put("locationName", row[9]);
            results.add(item);
        }

        return ResponseEntity.ok(Map.of(
                "data",  results,
                "total", total,
                "page",  page,
                "size",  size
        ));
    }

    /**
     * Autocomplete: fast prefix-match on cell_id or cell_name.
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<?> autocomplete(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "10") int limit) {

        if (q.isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        String prefix = q + "%";
        List<Object[]> rows = cellRepo.autocompleteCells(prefix, limit);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            results.add(Map.of(
                    "cellId",     row[0],
                    "cellName",   row[1] != null ? row[1] : "",
                    "realStatus", row[2]
            ));
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Tree view: full Dept hierarchy (root→leaf) → Station → Raster → Cell.
     * Cells include real_status for ON/OFF display.
     * If networkType is given, only branches containing that network type are included.
     */
    @GetMapping("/tree")
    public ResponseEntity<?> tree(
            @RequestParam(required = false) String networkType) {

        // ── 1. Load all active rasters (optionally filtered) ─────────────────
        List<InfraRaster> rasters;
        if (networkType != null && !networkType.isBlank()) {
            rasters = rasterRepo.findByNetworkTypeAndRowStatus(networkType.toUpperCase(), 1);
        } else {
            rasters = rasterRepo.findAll().stream()
                    .filter(r -> r.getRowStatus() == 1)
                    .toList();
        }

        // ── 2. Load all stations as a lookup map ──────────────────────────────
        Map<Long, InfraStation> stationById = stationRepo.findAll().stream()
                .filter(s -> s.getRowStatus() == 1)
                .collect(Collectors.toMap(InfraStation::getStationId, s -> s));

        // ── 3. Load all cells grouped by rasterId ─────────────────────────────
        Map<Long, List<Map<String, Object>>> cellsByRaster = new LinkedHashMap<>();
        for (Object[] row : cellRepo.findAllCellsByRaster()) {
            Long rasterId  = ((Number) row[0]).longValue();
            String cellId  = (String) row[1];
            boolean on = row[2] instanceof Boolean b ? b : ((Number) row[2]).intValue() != 0;
            cellsByRaster.computeIfAbsent(rasterId, k -> new ArrayList<>())
                    .add(Map.of("cellId", cellId, "realStatus", on));
        }

        // ── 4. Build: leafDeptId → stationId → rasterList ────────────────────
        Map<Long, Map<Long, Map<String, Object>>> deptStationMap = new LinkedHashMap<>();
        for (InfraRaster raster : rasters) {
            if (raster.getStationId() == null) continue;
            InfraStation station = stationById.get(raster.getStationId());
            if (station == null || station.getDeptId() == null) continue;
            Long deptId = station.getDeptId();

            deptStationMap.computeIfAbsent(deptId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(station.getStationId(), k -> {
                        Map<String, Object> st = new LinkedHashMap<>();
                        st.put("stationId",   station.getStationId());
                        st.put("stationCode", station.getStationCode());
                        st.put("rasters",     new ArrayList<Map<String, Object>>());
                        return st;
                    });

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rasterList = (List<Map<String, Object>>)
                    deptStationMap.get(deptId).get(station.getStationId()).get("rasters");

            Map<String, Object> rasterNode = new LinkedHashMap<>();
            rasterNode.put("rasterId",    raster.getId());
            rasterNode.put("rasterCode",  raster.getRasterCode());
            rasterNode.put("rasterName",  raster.getRasterName());
            rasterNode.put("networkType", raster.getNetworkType());
            rasterNode.put("cells",       cellsByRaster.getOrDefault(raster.getId(), List.of()));
            rasterList.add(rasterNode);
        }

        // Flatten stationId→map to stationId→list for each leaf dept
        Map<Long, List<Map<String, Object>>> leafDeptStations = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<Long, Map<String, Object>>> e : deptStationMap.entrySet()) {
            leafDeptStations.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        }

        // ── 5. Load all depts and build parent→children map ───────────────────
        Map<Long, CatDepartment> deptById = deptRepo.findAll().stream()
                .filter(d -> d.getRowStatus() == 1)
                .collect(Collectors.toMap(CatDepartment::getDeptId, d -> d));
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        Long rootId = null;
        for (CatDepartment d : deptById.values()) {
            if (d.getParentId() == null) {
                rootId = d.getDeptId();
            } else {
                childrenOf.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d.getDeptId());
            }
        }

        // ── 6. Recursively build dept node ────────────────────────────────────
        if (rootId == null) {
            return ResponseEntity.ok(List.of());
        }
        List<Map<String, Object>> roots = buildDeptNodes(
                List.of(rootId), deptById, childrenOf, leafDeptStations);
        return ResponseEntity.ok(roots);
    }

    /** Recursively convert a list of dept IDs into tree nodes. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDeptNodes(
            List<Long> ids,
            Map<Long, CatDepartment> deptById,
            Map<Long, List<Long>> childrenOf,
            Map<Long, List<Map<String, Object>>> leafDeptStations) {

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) {
            CatDepartment dept = deptById.get(id);
            if (dept == null) continue;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("deptId",   dept.getDeptId());
            node.put("deptCode", dept.getDeptCode());
            node.put("deptName", dept.getDeptName());
            node.put("deptLevel", dept.getDepartmentLevel());

            List<Long> childIds = childrenOf.getOrDefault(id, List.of());
            if (!childIds.isEmpty()) {
                // Intermediate dept: recurse into children
                node.put("children", buildDeptNodes(
                        childIds, deptById, childrenOf, leafDeptStations));
            } else {
                // Leaf dept: attach stations
                node.put("stations", leafDeptStations.getOrDefault(id, List.of()));
            }
            result.add(node);
        }
        return result;
    }
}
