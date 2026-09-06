package com.transdb.dto;

import java.util.List;
import java.util.Map;

public record SearchItemVO(long id, String sourceText, String translatedText, String workTitle,
                           String chapter, String author, String dynasty, String translator,
                           List<String> tags, Map<String, List<String>> highlight, double score) {
}
