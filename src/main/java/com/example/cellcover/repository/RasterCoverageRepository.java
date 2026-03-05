package com.example.cellcover.repository;

import com.example.cellcover.entity.RasterCoverage;
import org.locationtech.jts.geom.Geometry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RasterCoverageRepository extends JpaRepository<RasterCoverage, Long> {

    @Query("SELECT r FROM RasterCoverage r WHERE r.svr = true AND ST_Intersects(r.bbox, :viewport) = true")
    List<RasterCoverage> findInViewport(@Param("viewport") Geometry viewport);

    @Query("SELECT DISTINCT r.cellId FROM RasterCoverage r")
    List<String> findDistinctCellIds();

    @Query("SELECT COUNT(DISTINCT r.cellId) FROM RasterCoverage r")
    long countDistinctCellIds();

    @Query("SELECT DISTINCT r.cellId FROM RasterCoverage r WHERE r.visible = false")
    List<String> findHiddenCellIds();

    @Modifying
    @Query("DELETE FROM RasterCoverage r WHERE r.cellId IN :cellIds")
    int deleteByCellIds(@Param("cellIds") List<String> cellIds);

    @Modifying
    @Query("UPDATE RasterCoverage r SET r.visible = :visible WHERE r.cellId = :cellId")
    int toggleVisibility(@Param("cellId") String cellId, @Param("visible") boolean visible);
}
