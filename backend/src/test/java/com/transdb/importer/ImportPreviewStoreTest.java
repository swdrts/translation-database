package com.transdb.importer;

import com.transdb.dto.LineError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
