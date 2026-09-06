package com.transdb.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transdb.es")
public record EsProperties(
        boolean enabled,
        boolean reconciliationEnabled,
        long reconciliationFixedDelayMs,
        long reconciliationWindowHours) {
}
