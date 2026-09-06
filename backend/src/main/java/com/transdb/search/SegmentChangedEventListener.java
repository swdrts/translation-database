package com.transdb.search;

import com.transdb.domain.SegmentChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentChangedEventListener {

    private final EsSyncService esSyncService;
    private final SyncRetryQueue retryQueue;
    private final EsProperties esProperties;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SegmentChangedEvent event) {
        if (!esProperties.enabled()) {
            return;
        }
        EsSyncService.Op op = event.type() == com.transdb.domain.ChangeType.DELETED
                ? EsSyncService.Op.DELETE : EsSyncService.Op.UPSERT;
        try {
            esSyncService.applyNow(event.segmentId(), op);
        } catch (Exception e) {
            log.warn("ES 同步进入重试队列 segmentId={} op={}", event.segmentId(), op, e);
            retryQueue.offer(event.segmentId(), op);
        }
    }
}
