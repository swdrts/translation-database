package com.transdb.domain;

/** 事务内发布；消费者须用 @TransactionalEventListener(AFTER_COMMIT)（Plan 2 的 ES 同步）。 */
public record SegmentChangedEvent(Long segmentId, ChangeType type) {
}
