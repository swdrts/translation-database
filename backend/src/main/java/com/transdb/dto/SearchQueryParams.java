package com.transdb.dto;

import java.util.List;

public record SearchQueryParams(
        String q, String field, List<String> tags, String dynasty, String work, int page, int size) {
}
