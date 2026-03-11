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

    /**
     * Computes the geometry of areas that are:
     *   - covered by at least one OFF cell (is_visible = false), AND
     *   - NOT covered by any ON cell (is_visible = true)
     * in the given viewport rectangle.
     *
     * Logic:
     *   off_union  = ST_Union of all off-cell bboxes intersecting the viewport
     *   on_union   = ST_Union of all on-cell  bboxes intersecting the viewport
     *   result     = ST_Difference(off_union, on_union)
     *
     * CASE WHEN inside ST_Union acts as a conditional aggregate — it passes only
     * the matching rows' geometries, NULL for non-matching rows (which ST_Union
     * ignores).  COALESCE wraps the on_union so that if there are no on-cells
     * we subtract an empty geometry rather than NULL (ST_Difference(x, NULL) = NULL).
     *
     * Returns the result as a GeoJSON geometry string, or NULL if there are no
     * off-cells in the viewport (caller should treat NULL as "nothing to draw").
     */
    @Query(value = """
        SELECT ST_AsGeoJSON(
            ST_Difference(
                ST_Union(CASE WHEN r.is_visible = false THEN r.bbox END),
                COALESCE(
                    ST_Union(CASE WHEN r.is_visible = true  THEN r.bbox END),
                    ST_GeomFromText('GEOMETRYCOLLECTION EMPTY', 4326)
                )
            )
        )
        FROM raster_coverages r
        WHERE ST_Intersects(r.bbox, ST_MakeEnvelope(:minx, :miny, :maxx, :maxy, 4326))
        """, nativeQuery = true)
    String findOffOnlyGeometry(
            @Param("minx") double minx,
            @Param("miny") double miny,
            @Param("maxx") double maxx,
            @Param("maxy") double maxy);
}
