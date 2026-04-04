package com.example.cellcover.repository;

import com.example.cellcover.entity.Cell;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CellRepository extends JpaRepository<Cell, String> {

    List<Cell> findByRealStatus(boolean realStatus);

    @Query("SELECT c.cellId FROM Cell c WHERE c.realStatus = true")
    List<String> findAllOnCellIds();

    @Query("SELECT c.cellId FROM Cell c WHERE c.realStatus = false")
    List<String> findAllOffCellIds();

    /** ON cells whose coverage polygon intersects the tile bbox (EPSG:4326).
     *  NOTE: cells.geom is stored as (lat lon) order — bbox polygon must match.
     *  Uses MBRIntersects for MariaDB compatibility. */
    @Query(value = """
           SELECT cell_id FROM cells
           WHERE real_status = true
             AND MBRIntersects(geom,
                   ST_GeomFromText(CONCAT('POLYGON((',
                     :miny,' ',:minx,',',:miny,' ',:maxx,',',:maxy,' ',:maxx,',',
                     :maxy,' ',:minx,',',:miny,' ',:minx,'))'), 4326))
           """, nativeQuery = true)
    List<String> findOnCellIdsInBbox(
            @org.springframework.data.repository.query.Param("minx") double minx,
            @org.springframework.data.repository.query.Param("miny") double miny,
            @org.springframework.data.repository.query.Param("maxx") double maxx,
            @org.springframework.data.repository.query.Param("maxy") double maxy);

    /** OFF cells whose coverage polygon intersects the tile bbox (EPSG:4326). */
    @Query(value = """
           SELECT cell_id FROM cells
           WHERE real_status = false
             AND MBRIntersects(geom,
                   ST_GeomFromText(CONCAT('POLYGON((',
                     :miny,' ',:minx,',',:miny,' ',:maxx,',',:maxy,' ',:maxx,',',
                     :maxy,' ',:minx,',',:miny,' ',:minx,'))'), 4326))
           """, nativeQuery = true)
    List<String> findOffCellIdsInBbox(
            @org.springframework.data.repository.query.Param("minx") double minx,
            @org.springframework.data.repository.query.Param("miny") double miny,
            @org.springframework.data.repository.query.Param("maxx") double maxx,
            @org.springframework.data.repository.query.Param("maxy") double maxy);
}
