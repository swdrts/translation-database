package com.transdb.importer;

import com.transdb.domain.SegmentStatus;
import com.transdb.dto.LineError;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImportPreviewStore {

    /**
     * sourceType 区分两种导入：TABLE（对照表，落库即 PUBLISHED）与
     * DOCUMENT（整本书仅原文，默认 DRAFT 待翻译，确认时可覆盖）。
     */
    public record ImportPreviewSession(String id, long operatorId, DuplicateStrategy strategy,
                                       List<ImportRowPlan> rows, int totalRows,
                                       List<LineError> errors, ImportSourceType sourceType,
                                       SegmentStatus status, Instant createdAt) {
    }

    private final Map<String, ImportPreviewSession> sessions = new ConcurrentHashMap<>();
    private final ImportProperties properties;

    public ImportPreviewStore(ImportProperties properties) {
        this.properties = properties;
    }

    public String create(long operatorId, DuplicateStrategy strategy, List<ImportRowPlan> rows,
                         int totalRows, List<LineError> errors) {
        return create(operatorId, strategy, rows, totalRows, errors,
                ImportSourceType.TABLE, SegmentStatus.PUBLISHED);
    }

    public String create(long operatorId, DuplicateStrategy strategy, List<ImportRowPlan> rows,
                         int totalRows, List<LineError> errors,
                         ImportSourceType sourceType, SegmentStatus status) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new ImportPreviewSession(id, operatorId, strategy, rows, totalRows,
                errors, sourceType, status, Instant.now()));
        return id;
    }

    /** 惰性过期：TTL 外视为不存在并移除。 */
    public ImportPreviewSession get(String id) {
        if (id == null) {
            return null;
        }
        ImportPreviewSession session = sessions.get(id);
        if (session == null) {
            return null;
        }
        long ttlMs = properties.previewTtlMinutes() * 60_000;
        if (Instant.now().toEpochMilli() - session.createdAt().toEpochMilli() > ttlMs) {
            sessions.remove(id);
            return null;
        }
        return session;
    }

    /** 取回会话并从 store 移除（confirm 消费后防重放）；不存在/已过期返回 null。 */
    public ImportPreviewSession remove(String id) {
        ImportPreviewSession session = get(id);
        if (session != null) {
            sessions.remove(id);
        }
        return session;
    }

    /** 定时清扫被遗弃的预览会话，防止未确认会话（可达 10 万行计划）无限占用内存。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void sweepExpired() {
        long ttlMs = properties.previewTtlMinutes() * 60_000;
        long cutoff = Instant.now().toEpochMilli() - ttlMs;
        sessions.values().removeIf(s -> s.createdAt().toEpochMilli() < cutoff);
    }
}
