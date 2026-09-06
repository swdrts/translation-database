package com.transdb.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transdb.import")
public record ImportProperties(long maxRows, long previewTtlMinutes) {
}
