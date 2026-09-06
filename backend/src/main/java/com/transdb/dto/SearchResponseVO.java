package com.transdb.dto;

import java.util.List;

public record SearchResponseVO(
        List<SearchItemVO> content, long total, int page, int size, boolean degraded, FacetsVO facets) {
}
