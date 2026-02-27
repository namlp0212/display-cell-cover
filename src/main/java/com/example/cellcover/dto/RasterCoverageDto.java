package com.example.cellcover.dto;

import org.locationtech.jts.geom.Geometry;

import java.time.OffsetDateTime;

public record RasterCoverageDto(
        Long id,
        String cellId,
        String filePath,
        Geometry bbox,
        String crs,
        boolean svr,
        boolean visible,
        OffsetDateTime createdAt
) {
}
