package com.example.cellcover.service;

import com.example.cellcover.dto.RasterCoverageDto;
import com.example.cellcover.entity.RasterCoverage;
import com.example.cellcover.repository.RasterCoverageRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RasterCoverageService {

    private static final int SRID = 4326;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), SRID);
    private final RasterCoverageRepository repository;

    public RasterCoverageService(RasterCoverageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RasterCoverageDto> findVisibleInBbox(double minx, double miny, double maxx, double maxy) {
        Polygon viewport = createBboxPolygon(minx, miny, maxx, maxy);
        return repository.findInViewport(viewport)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> findHiddenCellIds() {
        return repository.findHiddenCellIds();
    }

    @Transactional
    public int toggleVisibility(String cellId, boolean visible) {
        return repository.toggleVisibility(cellId, visible);
    }

    private Polygon createBboxPolygon(double minx, double miny, double maxx, double maxy) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(minx, miny),
                new Coordinate(maxx, miny),
                new Coordinate(maxx, maxy),
                new Coordinate(minx, maxy),
                new Coordinate(minx, miny)
        };
        Polygon polygon = geometryFactory.createPolygon(coords);
        polygon.setSRID(SRID);
        return polygon;
    }

    private RasterCoverageDto toDto(RasterCoverage entity) {
        return new RasterCoverageDto(
                entity.getId(),
                entity.getCellId(),
                entity.getFilePath(),
                entity.getBbox(),
                entity.getCrs(),
                entity.isSvr(),
                entity.isVisible(),
                entity.getCreatedAt()
        );
    }
}
