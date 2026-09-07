package com.transdb.importer;

import com.transdb.dto.LineError;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImportPreviewStore {

    public record ImportPreviewSession(String id, long operatorId, DuplicateStrategy strategy,
                                       List<ImportRowPlan> rows, int totalRows,
                                       List<LineError> errors, Instant createdAt) {
    }

    private final Map<String, ImportPreviewSession> sessions = new ConcurrentHashMap<>();
    private final ImportProperties properties;

    public ImportPreviewStore(ImportProperties properties) {
        this.properties = properties;
    }

    public String create(long operatorId, DuplicateStrategy strategy, List<ImportRowPlan> rows,
                         int totalRows, List<LineError> errors) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new ImportPreviewSession(id, operatorId, strategy, rows, totalRows,
                errors, Instant.now()));
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
}
