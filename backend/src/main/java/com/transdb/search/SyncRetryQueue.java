package com.transdb.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRetryQueue {

    private record Item(long segmentId, EsSyncService.Op op, int attempts, long nextAttemptAtMs) {
    }

    private static final int MAX_ATTEMPTS = 10;

    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<>();
    private final EsSyncService esSyncService;

    public boolean offer(long segmentId, EsSyncService.Op op) {
        return offer(new Item(segmentId, op, 0, System.currentTimeMillis() + 1000));
    }

    private boolean offer(Item item) {
        if (item.attempts() >= MAX_ATTEMPTS) {
            log.error("ES 同步重试放弃 segmentId={} op={} attempts={}", item.segmentId(), item.op(), item.attempts());
            return false;
        }
        return queue.add(item);
    }

    @Scheduled(fixedDelayString = "${transdb.es.retry-drain-ms:5000}")
    public void drain() {
        Iterator<Item> it = queue.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.nextAttemptAtMs() > System.currentTimeMillis()) {
                continue;
            }
            if (queue.remove(item)) {
                try {
                    esSyncService.applyNow(item.segmentId(), item.op());
                } catch (Exception e) {
                    int attempts = item.attempts() + 1;
                    long backoffMs = Math.min(60_000, 1000L * (1L << attempts));
                    offer(new Item(item.segmentId(), item.op(), attempts, System.currentTimeMillis() + backoffMs));
                    log.warn("ES 同步重试失败 segmentId={} attempts={}: {}", item.segmentId(), attempts, e.getMessage());
                }
            }
        }
    }
}
