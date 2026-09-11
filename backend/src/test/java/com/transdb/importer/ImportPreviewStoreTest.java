package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.LineError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯单元测试：直接构造 store，不启动 Spring 上下文。 */
class ImportPreviewStoreTest {

    @Test
    void sweepRemovesSessionsOlderThanTtl() throws Exception {
        // TTL 0 分钟：get 的惰性过期基于毫秒差，刚创建的会话仍在窗口内可取回
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 0));
        String id = store.create(1L, DuplicateStrategy.SKIP, List.of(), 0, List.<LineError>of());
        assertThat(store.get(id)).isNotNull();
        Thread.sleep(10);
        store.sweepExpired();
        assertThat(store.get(id)).isNull();
    }

    @Test
    void sweepKeepsFreshSessions() throws Exception {
        // TTL 60 分钟：新会话不应被清扫
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 60));
        String id = store.create(1L, DuplicateStrategy.SKIP, List.of(), 0, List.<LineError>of());
        Thread.sleep(5);
        store.sweepExpired();
        assertThat(store.get(id)).isNotNull();
    }

    @Test
    void nextRowIdIncrementsFromSessionRows() {
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 60));
        ImportRowPlan p1 = new ImportRowPlan(1, ImportRowPlan.PlanType.IMPORT,
                new ParsedRow(1, java.util.Map.of("source_text", "甲")), null, "h1", false);
        ImportRowPlan p2 = new ImportRowPlan(2, ImportRowPlan.PlanType.IMPORT,
                new ParsedRow(2, java.util.Map.of("source_text", "乙")), null, "h2", false);
        String id = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(List.of(p1, p2)), 2, List.<LineError>of(),
                ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        assertThat(store.nextRowId(id)).isEqualTo(3);
        assertThat(store.nextRowId(id)).isEqualTo(4);
    }

    @Test
    void requireOwnedChecksOwnerSourceTypeAndRenewsTtl() {
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 60));
        String id = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(), 0, List.<LineError>of(),
                ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        // 属主 + 类型正确 → 通过
        store.requireOwned(id, 1L, ImportSourceType.DOCUMENT);
        // 他人会话 → FORBIDDEN
        assertThatThrownBy(() -> store.requireOwned(id, 2L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
        // TABLE 会话 → VALIDATION_FAILED
        String tableId = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(), 0, List.<LineError>of());
        assertThatThrownBy(() -> store.requireOwned(tableId, 1L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
        // 不存在 → IMPORT_PREVIEW_NOT_FOUND
        assertThatThrownBy(() -> store.requireOwned("nope", 1L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
    }
}
