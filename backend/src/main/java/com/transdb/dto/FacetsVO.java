package com.transdb.dto;

import java.util.List;

public record FacetsVO(List<FacetItem> tags, List<FacetItem> dynasties, List<FacetItem> works) {

    public static FacetsVO empty() {
        return new FacetsVO(List.of(), List.of(), List.of());
    }
}
