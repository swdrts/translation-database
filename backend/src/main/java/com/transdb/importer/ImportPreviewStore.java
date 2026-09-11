package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.LineError;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ImportPreviewStore {

    /**
     * sourceType 区分两种导入：TABLE（对照表，落库即 PUBLISHED）与
     * DOCUMENT（整本书仅原文，默认 DRAFT 待翻译，确认时可覆盖）。
     * rows 恒为可变 ArrayList：分段编辑服务对同一列表做 add/remove/set，
     * session.rows() 永远读到最新内容；rowCounter 是会话内 rowId 分配器
     * （可变对象由不可变字段持有），续期替换会话时原样转移。
     */
    public record ImportPreviewSession(String id, long operatorId, DuplicateStrategy strategy,
                                       List<ImportRowPlan> rows, int totalRows,
                                       List<LineError> errors, ImportSourceType sourceType,
                                       SegmentStatus status, Instant createdAt,
                                       AtomicLong rowCounter) {
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
        // 包成新 ArrayList：调用方传入的列表可能不可变（List.of）或被复用，会话内编辑要求可独占修改
        List<ImportRowPlan> mutableRows = new ArrayList<>(rows);
        sessions.put(id, new ImportPreviewSession(id, operatorId, strategy, mutableRows, totalRows,
                errors, sourceType, status, Instant.now(), new AtomicLong(mutableRows.size())));
        return id;
    }

    /** 编辑/查询入口：校验存在、属主与 sourceType 后返回会话，并滑动续期。 */
    public ImportPreviewSession requireOwned(String id, long operatorId,
                                             ImportSourceType requiredSourceType) {
        ImportPreviewSession session = get(id);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        if (session.operatorId() != operatorId) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_FORBIDDEN);
        }
        if (session.sourceType() != requiredSourceType) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "该预览不支持分段编辑");
        }
        return renew(id, session);
    }

    /** 滑动续期：以新 createdAt 替换 map 槽位（rows 引用不变，编辑仍指向同一列表）。 */
    private ImportPreviewSession renew(String id, ImportPreviewSession s) {
        ImportPreviewSession renewed = new ImportPreviewSession(s.id(), s.operatorId(), s.strategy(),
                s.rows(), s.totalRows(), s.errors(), s.sourceType(), s.status(),
                Instant.now(), s.rowCounter());
        sessions.put(id, renewed);
        return renewed;
    }

    /** 分配下一个 rowId（会话内单调递增）。 */
    public long nextRowId(String id) {
        ImportPreviewSession session = sessions.get(id);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        return session.rowCounter().incrementAndGet();
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
