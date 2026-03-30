package com.example.cellcover.dto;

import jakarta.validation.constraints.NotNull;

public record BboxRequest(
        @NotNull Double minx,
        @NotNull Double miny,
        @NotNull Double maxx,
        @NotNull Double maxy
) {
}
