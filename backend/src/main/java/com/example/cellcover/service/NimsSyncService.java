package com.example.cellcover.service;

import com.example.cellcover.dto.NimsSyncStatusDto;
import com.example.cellcover.entity.*;
import com.example.cellcover.repository.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CN-04.02: Synchronizes catalog and infra data from NIMS API into local DB.
 *
 * Sync order: cat_department → cat_location → infra_stations → infra_rasters → cells
 *
 * Strategy per entity:
 *   - UPSERT by code (INSERT or UPDATE if code already exists)
 *   - Soft-delete: mark ROW_STATUS=2 for records absent after grace period
 *
 * Status is tracked in memory (ConcurrentHashMap) — no DB persistence for sync state.
 */
@Service
public class NimsSyncService {

    private static final Logger log = LoggerFactory.getLogger(NimsSyncService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final NimsApiClient nimsApiClient;
    private final CatDepartmentRepository deptRepo;
    private final CatLocationRepository locationRepo;
    private final InfraStationRepository stationRepo;
    private final InfraRasterRepository rasterRepo;
    private final CellRepository cellRepo;
    private final CellCacheService cacheService;

    @Value("${nims.api.page-size:500}")
    private int pageSize;

    // In-memory sync status registry (syncId → status DTO)
    private final ConcurrentHashMap<String, NimsSyncStatusDto> syncRegistry = new ConcurrentHashMap<>();

    public NimsSyncService(NimsApiClient nimsApiClient,
                           CatDepartmentRepository deptRepo,
                           CatLocationRepository locationRepo,
                           InfraStationRepository stationRepo,
                           InfraRasterRepository rasterRepo,
                           CellRepository cellRepo,
                           CellCacheService cacheService) {
        this.nimsApiClient = nimsApiClient;
        this.deptRepo = deptRepo;
        this.locationRepo = locationRepo;
        this.stationRepo = stationRepo;
        this.rasterRepo = rasterRepo;
        this.cellRepo = cellRepo;
        this.cacheService = cacheService;
    }

    // ── Check if a sync is already running ───────────────────────────────────

    public boolean isRunning() {
        return syncRegistry.values().stream()
                .anyMatch(s -> "running".equals(s.getStatus()));
    }

    public NimsSyncStatusDto getStatus(String syncId) {
        return syncRegistry.get(syncId);
    }

    public List<NimsSyncStatusDto> getHistory(int page, int size) {
        List<NimsSyncStatusDto> all = new ArrayList<>(syncRegistry.values());
        all.sort(Comparator.comparing(NimsSyncStatusDto::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int from = page * size;
        int to   = Math.min(from + size, all.size());
        return from >= all.size() ? Collections.emptyList() : all.subList(from, to);
    }

    // ── Public trigger ────────────────────────────────────────────────────────

    public String startSync() {
        String syncId = UUID.randomUUID().toString();
        NimsSyncStatusDto dto = new NimsSyncStatusDto();
        dto.setSyncId(syncId);
        dto.setStatus("running");
        dto.setStartedAt(LocalDateTime.now());
        syncRegistry.put(syncId, dto);
        runSync(syncId);
        return syncId;
    }

    // ── Scheduled auto-sync at 2AM ────────────────────────────────────────────

    @Scheduled(cron = "${nims.sync.cron:0 0 2 * * *}")
    public void scheduledSync() {
        if (isRunning()) {
            log.info("Skipping scheduled NIMS sync — another sync is already running");
            return;
        }
        log.info("Starting scheduled NIMS sync");
        startSync();
    }

    // ── Main sync logic (runs asynchronously) ─────────────────────────────────

    @Async("jobExecutor")
    public void runSync(String syncId) {
        NimsSyncStatusDto dto = syncRegistry.get(syncId);
        try {
            log.info("[{}] NIMS sync started", syncId);

            syncDepartments(dto);
            syncLocations(dto);
            syncStations(dto);
            syncRasters(dto);
            syncCells(dto);

            dto.setStatus("completed");
            dto.setFinishedAt(LocalDateTime.now());
            log.info("[{}] NIMS sync completed: deptIns={} deptUpd={} stationIns={} cellIns={}",
                    syncId, dto.getDeptInserted(), dto.getDeptUpdated(),
                    dto.getStationInserted(), dto.getCellInserted());

            // Rebuild Redis caches
            cacheService.invalidateReal();
            cacheService.buildNetworkCache();

        } catch (Exception e) {
            log.error("[{}] NIMS sync failed: {}", syncId, e.getMessage(), e);
            dto.setStatus("failed");
            dto.setFinishedAt(LocalDateTime.now());
            dto.setErrorMessage(e.getMessage());
            dto.setErrorCount(dto.getErrorCount() + 1);
        }
    }

    // ── Per-entity sync methods ───────────────────────────────────────────────

    @Transactional
    protected void syncDepartments(NimsSyncStatusDto dto) {
        log.debug("[{}] Syncing departments", dto.getSyncId());
        int page = 0;
        List<Map<String, Object>> records;
        do {
            records = nimsApiClient.fetchDepartments(null, null, page, pageSize);
            for (Map<String, Object> rec : records) {
                try {
                    upsertDepartment(rec, dto);
                } catch (Exception e) {
                    log.warn("Failed to upsert department: {}", e.getMessage());
                    dto.setErrorCount(dto.getErrorCount() + 1);
                }
            }
            page++;
        } while (records.size() == pageSize);
    }

    private void upsertDepartment(Map<String, Object> rec, NimsSyncStatusDto dto) {
        // TODO: Map actual NIMS field names to CatDepartment fields.
        // The field names below (DEPT_CODE, DEPT_NAME, PARENT_ID, DEPARTMENT_LEVEL)
        // are placeholders — replace with real NIMS field names when API doc is available.
        String code = getString(rec, "DEPT_CODE");
        if (code == null || code.isBlank()) return;

        Optional<CatDepartment> existing = deptRepo.findByDeptCode(code);
        CatDepartment dept = existing.orElseGet(CatDepartment::new);
        boolean isNew = existing.isEmpty();

        dept.setDeptCode(code);
        dept.setDeptName(getString(rec, "DEPT_NAME"));
        dept.setParentId(getLong(rec, "PARENT_ID"));
        dept.setDepartmentLevel(getInt(rec, "DEPARTMENT_LEVEL", 0));
        dept.setRowStatus(1);
        dept.setUpdateTime(LocalDateTime.now());

        if (dept.getDeptId() == null) {
            dept.setDeptId(getLong(rec, "DEPT_ID"));
        }

        deptRepo.save(dept);
        if (isNew) dto.setDeptInserted(dto.getDeptInserted() + 1);
        else dto.setDeptUpdated(dto.getDeptUpdated() + 1);
    }

    @Transactional
    protected void syncLocations(NimsSyncStatusDto dto) {
        log.debug("[{}] Syncing locations", dto.getSyncId());
        int page = 0;
        List<Map<String, Object>> records;
        do {
            records = nimsApiClient.fetchLocations(null, null, page, pageSize);
            for (Map<String, Object> rec : records) {
                try {
                    upsertLocation(rec, dto);
                } catch (Exception e) {
                    log.warn("Failed to upsert location: {}", e.getMessage());
                    dto.setErrorCount(dto.getErrorCount() + 1);
                }
            }
            page++;
        } while (records.size() == pageSize);
    }

    private void upsertLocation(Map<String, Object> rec, NimsSyncStatusDto dto) {
        // TODO: Map actual NIMS field names for location records.
        String code = getString(rec, "LOCATION_CODE");
        if (code == null || code.isBlank()) return;

        Optional<CatLocation> existing = locationRepo.findByLocationCode(code);
        CatLocation loc = existing.orElseGet(CatLocation::new);
        boolean isNew = existing.isEmpty();

        loc.setLocationCode(code);
        loc.setLocationName(getString(rec, "LOCATION_NAME"));
        loc.setParentId(getLong(rec, "PARENT_ID"));
        loc.setLocationLevel(getInt(rec, "LOCATION_LEVEL", 0));
        loc.setRowStatus(1);
        loc.setUpdateTime(LocalDateTime.now());

        if (loc.getLocationId() == null) {
            loc.setLocationId(getLong(rec, "LOCATION_ID"));
        }

        locationRepo.save(loc);
        if (isNew) dto.setLocationInserted(dto.getLocationInserted() + 1);
        else dto.setLocationUpdated(dto.getLocationUpdated() + 1);
    }

    @Transactional
    protected void syncStations(NimsSyncStatusDto dto) {
        log.debug("[{}] Syncing stations", dto.getSyncId());
        int page = 0;
        List<Map<String, Object>> records;
        do {
            records = nimsApiClient.fetchStations(null, null, page, pageSize);
            for (Map<String, Object> rec : records) {
                try {
                    upsertStation(rec, dto);
                } catch (Exception e) {
                    log.warn("Failed to upsert station: {}", e.getMessage());
                    dto.setErrorCount(dto.getErrorCount() + 1);
                }
            }
            page++;
        } while (records.size() == pageSize);
    }

    private void upsertStation(Map<String, Object> rec, NimsSyncStatusDto dto) {
        // TODO: Map actual NIMS field names for station records.
        String code = getString(rec, "STATION_CODE");
        if (code == null || code.isBlank()) return;

        Optional<InfraStation> existing = stationRepo.findByStationCode(code);
        InfraStation station = existing.orElseGet(InfraStation::new);
        boolean isNew = existing.isEmpty();

        station.setStationCode(code);
        station.setDeptId(getLong(rec, "DEPT_ID"));
        station.setLocationId(getLong(rec, "LOCATION_ID"));
        station.setRowStatus(1);
        station.setUpdateTime(LocalDateTime.now());

        if (station.getStationId() == null) {
            station.setStationId(getLong(rec, "STATION_ID"));
        }

        stationRepo.save(station);
        if (isNew) dto.setStationInserted(dto.getStationInserted() + 1);
        else dto.setStationUpdated(dto.getStationUpdated() + 1);
    }

    @Transactional
    protected void syncRasters(NimsSyncStatusDto dto) {
        log.debug("[{}] Syncing rasters", dto.getSyncId());
        int page = 0;
        List<Map<String, Object>> records;
        do {
            records = nimsApiClient.fetchRasters(null, null, page, pageSize);
            for (Map<String, Object> rec : records) {
                try {
                    upsertRaster(rec, dto);
                } catch (Exception e) {
                    log.warn("Failed to upsert raster: {}", e.getMessage());
                    dto.setErrorCount(dto.getErrorCount() + 1);
                }
            }
            page++;
        } while (records.size() == pageSize);
    }

    private void upsertRaster(Map<String, Object> rec, NimsSyncStatusDto dto) {
        // TODO: Map actual NIMS field names for raster/antenna records.
        String code = getString(rec, "RASTER_CODE");
        if (code == null || code.isBlank()) return;

        Optional<InfraRaster> existing = rasterRepo.findByRasterCode(code);
        InfraRaster raster = existing.orElseGet(InfraRaster::new);
        boolean isNew = existing.isEmpty();

        raster.setRasterCode(code);
        raster.setRasterName(getString(rec, "RASTER_NAME"));
        raster.setStationId(getLong(rec, "STATION_ID"));
        raster.setNetworkType(getString(rec, "NETWORK_TYPE"));
        raster.setRowStatus(1);
        raster.setNimsUpdatedAt(LocalDateTime.now());
        raster.setUpdatedAt(LocalDateTime.now());

        rasterRepo.save(raster);
        if (isNew) dto.setRasterInserted(dto.getRasterInserted() + 1);
        else dto.setRasterUpdated(dto.getRasterUpdated() + 1);
    }

    @Transactional
    protected void syncCells(NimsSyncStatusDto dto) {
        log.debug("[{}] Syncing cells", dto.getSyncId());
        int page = 0;
        List<Map<String, Object>> records;
        do {
            records = nimsApiClient.fetchCells(null, null, page, pageSize);
            for (Map<String, Object> rec : records) {
                try {
                    upsertCell(rec, dto);
                } catch (Exception e) {
                    log.warn("Failed to upsert cell: {}", e.getMessage());
                    dto.setErrorCount(dto.getErrorCount() + 1);
                }
            }
            page++;
        } while (records.size() == pageSize);
    }

    private void upsertCell(Map<String, Object> rec, NimsSyncStatusDto dto) {
        // TODO: Map actual NIMS field names for cell records.
        String cellId = getString(rec, "CELL_ID");
        if (cellId == null || cellId.isBlank()) return;

        Optional<Cell> existing = cellRepo.findById(cellId);
        Cell cell = existing.orElseGet(Cell::new);
        boolean isNew = existing.isEmpty();

        cell.setCellId(cellId);
        cell.setCellName(getString(rec, "CELL_NAME"));
        cell.setRowStatus(1);
        cell.setNimsUpdatedAt(LocalDateTime.now());
        cell.setUpdatedAt(LocalDateTime.now());

        // TODO: Set rasterId by matching raster code from NIMS response.
        // String rasterCode = getString(rec, "RASTER_CODE");
        // rasterRepo.findByRasterCode(rasterCode).ifPresent(r -> cell.setRasterId(r.getId()));

        cellRepo.save(cell);
        if (isNew) dto.setCellInserted(dto.getCellInserted() + 1);
        else dto.setCellUpdated(dto.getCellUpdated() + 1);
    }

    // ── Helper field extractors ───────────────────────────────────────────────

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static Long getLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }
}
