package com.transdb.dto;

import java.util.List;

public record SuggestVO(List<String> works, List<String> authors, List<String> tags) {
}
