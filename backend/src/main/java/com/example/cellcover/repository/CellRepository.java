package com.example.cellcover.repository;

import com.example.cellcover.entity.Cell;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CellRepository extends JpaRepository<Cell, String> {

    List<Cell> findByRealStatus(boolean realStatus);

    @Query("SELECT c.cellId FROM Cell c WHERE c.realStatus = true AND c.rowStatus = 1")
    List<String> findAllOnCellIds();

    @Query("SELECT c.cellId FROM Cell c WHERE c.realStatus = false AND c.rowStatus = 1")
    List<String> findAllOffCellIds();

    /** ON cells whose coverage polygon intersects the tile bbox (EPSG:4326).
     *  NOTE: cells.geom is stored as (lat lon) order — bbox polygon must match.
     *  Uses MBRIntersects for MariaDB compatibility. */
    @Query(value = """
           SELECT cell_id FROM cells
           WHERE real_status = true
             AND row_status = 1
             AND MBRIntersects(geom,
                   ST_GeomFromText(CONCAT('POLYGON((',
                     :miny,' ',:minx,',',:miny,' ',:maxx,',',:maxy,' ',:maxx,',',
                     :maxy,' ',:minx,',',:miny,' ',:minx,'))'), 4326))
           """, nativeQuery = true)
    List<String> findOnCellIdsInBbox(
            @Param("minx") double minx,
            @Param("miny") double miny,
            @Param("maxx") double maxx,
            @Param("maxy") double maxy);

    /** OFF cells whose coverage polygon intersects the tile bbox (EPSG:4326). */
    @Query(value = """
           SELECT cell_id FROM cells
           WHERE real_status = false
             AND row_status = 1
             AND MBRIntersects(geom,
                   ST_GeomFromText(CONCAT('POLYGON((',
                     :miny,' ',:minx,',',:miny,' ',:maxx,',',:maxy,' ',:maxx,',',
                     :maxy,' ',:minx,',',:miny,' ',:minx,'))'), 4326))
           """, nativeQuery = true)
    List<String> findOffCellIdsInBbox(
            @Param("minx") double minx,
            @Param("miny") double miny,
            @Param("maxx") double maxx,
            @Param("maxy") double maxy);

    /** Returns (cell_id, network_type) pairs for active cells with active rasters. */
    @Query(value = """
            SELECT c.cell_id, r.network_type
            FROM cells c
            JOIN infra_rasters r ON c.raster_id = r.id
            WHERE c.row_status = 1
              AND r.row_status = 1
            """, nativeQuery = true)
    List<Object[]> findAllCellNetworkTypes();

    /** Full-text search for CN-01.05 with JOIN to infra/catalog tables. */
    @Query(value = """
            SELECT c.cell_id, c.cell_name, c.real_status,
                   r.network_type, s.STATION_CODE, s.STATION_ID,
                   d.DEPT_ID, d.DEPT_NAME, l.LOCATION_ID, l.LOCATION_NAME
            FROM cells c
            LEFT JOIN infra_rasters r ON c.raster_id = r.id
            LEFT JOIN infra_stations s ON r.station_id = s.STATION_ID
            LEFT JOIN cat_department d ON s.DEPT_ID = d.DEPT_ID
            LEFT JOIN cat_location l ON s.LOCATION_ID = l.LOCATION_ID
            WHERE c.row_status = 1
              AND (:q IS NULL OR c.cell_id LIKE :q OR c.cell_name LIKE :q)
              AND (:networkType IS NULL OR r.network_type = :networkType)
              AND (:status IS NULL OR (:status = 'on' AND c.real_status = 1) OR (:status = 'off' AND c.real_status = 0))
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> searchCells(
            @Param("q") String q,
            @Param("networkType") String networkType,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*)
            FROM cells c
            LEFT JOIN infra_rasters r ON c.raster_id = r.id
            WHERE c.row_status = 1
              AND (:q IS NULL OR c.cell_id LIKE :q OR c.cell_name LIKE :q)
              AND (:networkType IS NULL OR r.network_type = :networkType)
            """, nativeQuery = true)
    long countSearchCells(
            @Param("q") String q,
            @Param("networkType") String networkType);

    /** All active cells with their real_status and network_type — used to populate ClickHouse Dictionary. */
    @Query(value = """
            SELECT c.cell_id, c.real_status, COALESCE(r.network_type, 'UNKNOWN')
            FROM cells c
            LEFT JOIN infra_rasters r ON c.raster_id = r.id
            WHERE c.row_status = 1
            """, nativeQuery = true)
    List<Object[]> findAllCellsForDict();

    /** All active cells with raster_id, cell_id, real_status — for tree endpoint. */
    @Query(value = """
            SELECT raster_id, cell_id, real_status
            FROM cells
            WHERE row_status = 1 AND raster_id IS NOT NULL
            ORDER BY raster_id, cell_id
            """, nativeQuery = true)
    List<Object[]> findAllCellsByRaster();

    /** Autocomplete: prefix search by cell_id or cell_name. */
    @Query(value = """
            SELECT cell_id, cell_name, real_status
            FROM cells
            WHERE row_status = 1
              AND (cell_id LIKE :prefix OR cell_name LIKE :prefix)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> autocompleteCells(
            @Param("prefix") String prefix,
            @Param("limit") int limit);
}
