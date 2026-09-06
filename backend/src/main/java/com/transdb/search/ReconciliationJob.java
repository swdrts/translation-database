package com.transdb.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final EsSyncService esSyncService;
    private final EsProperties esProperties;

    @Scheduled(fixedDelayString = "${transdb.es.reconciliation-fixed-delay-ms:3600000}")
    public void reconcile() {
        if (!esProperties.enabled() || !esProperties.reconciliationEnabled()) {
            return;
        }
        try {
            esSyncService.reconcile(esProperties.reconciliationWindowHours());
        } catch (Exception e) {
            log.warn("ES 对账任务失败: {}", e.getMessage());
        }
    }
}
