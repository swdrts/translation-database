package com.transdb.importer;

import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.domain.Tag;
import com.transdb.dto.ImportResultVO;
import com.transdb.dto.LineError;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.TagRepository;
import com.transdb.search.EsSyncService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportExecutor {

    private static final int BATCH_SIZE = 1000;

    private final ImportPreviewStore previewStore;
    private final SegmentRepository segmentRepository;
    private final TagRepository tagRepository;
    private final TransactionTemplate transactionTemplate;
    private final EsSyncService esSyncService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ImportResultVO execute(ImportPreviewStore.ImportPreviewSession session, LoginUser operator) {
        // 预览阶段的行校验错误（未成 plan 的无效行）也计入最终 failed 报告
        List<LineError> failed = new ArrayList<>(session.errors());
        List<Long> writtenIds = new ArrayList<>();
        int imported = 0;
        int overwritten = 0;

        List<ImportRowPlan> importRows = session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.IMPORT).toList();
        for (int i = 0; i < importRows.size(); i += BATCH_SIZE) {
            List<ImportRowPlan> batch = importRows.subList(i, Math.min(i + BATCH_SIZE, importRows.size()));
            try {
                List<Long> ids = transactionTemplate.execute(tx ->
                        insertBatch(batch, operator, failed));
                imported += ids == null ? 0 : ids.size();
                if (ids != null) {
                    writtenIds.addAll(ids);
                }
            } catch (Exception e) {
                log.warn("导入批次失败（{} 行）: {}", batch.size(), e.getMessage());
                batch.forEach(plan -> failed.add(new LineError(plan.row().lineNumber(),
                        "写入失败: " + rootMessage(e))));
            }
        }

        List<ImportRowPlan> overwriteRows = session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.OVERWRITE).toList();
        for (int i = 0; i < overwriteRows.size(); i += 100) {
            List<ImportRowPlan> batch = overwriteRows.subList(i, Math.min(i + 100, overwriteRows.size()));
            try {
                List<Long> ids = transactionTemplate.execute(tx ->
                        overwriteBatch(batch, operator, failed));
                overwritten += ids == null ? 0 : ids.size();
                if (ids != null) {
                    writtenIds.addAll(ids);
                }
            } catch (Exception e) {
                log.warn("覆盖批次失败（{} 行）: {}", batch.size(), e.getMessage());
                batch.forEach(plan -> failed.add(new LineError(plan.row().lineNumber(),
                        "覆盖失败: " + rootMessage(e))));
            }
        }

        int skipped = (int) session.rows().stream()
                .filter(p -> p.type() == ImportRowPlan.PlanType.SKIP).count();
        // JDBC 写入不产生领域事件，导入路径须显式批量同步；失败仅告警（对账兜底）
        esSyncService.bulkUpsert(writtenIds);
        return new ImportResultVO(imported, overwritten, skipped, failed);
    }

    /** 批插入 + 标签关联；在同一短事务内完成。返回生成的 segment id 列表。 */
    private List<Long> insertBatch(List<ImportRowPlan> batch, LoginUser operator, List<LineError> failed) {
        Map<String, Tag> tagCache = new HashMap<>();
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("segment")
                .usingColumns("source_text", "translated_text", "work_title", "chapter", "author",
                        "dynasty", "translator", "notes", "status", "version", "content_hash",
                        "created_by", "created_at", "updated_at")
                .usingGeneratedKeyColumns("id");

        List<Long> ids = new ArrayList<>();
        List<long[]> segmentTagPairs = new ArrayList<>();
        for (ImportRowPlan plan : batch) {
            var row = plan.row();
            Timestamp now = Timestamp.from(Instant.now());
            // 用 HashMap 承载参数：work_title 等经 blankToNull 后可为 null，
            // Map.ofEntries(Map.entry(...)) 拒绝 null 值会 NPE
            Map<String, Object> params = new HashMap<>();
            params.put("source_text", row.get("source_text"));
            params.put("translated_text", row.get("translated_text"));
            params.put("work_title", blankToNull(row.get("work_title")));
            params.put("chapter", blankToNull(row.get("chapter")));
            params.put("author", blankToNull(row.get("author")));
            params.put("dynasty", blankToNull(row.get("dynasty")));
            params.put("translator", blankToNull(row.get("translator")));
            params.put("notes", blankToNull(row.get("notes")));
            params.put("status", SegmentStatus.PUBLISHED.name());
            params.put("version", 0);
            params.put("content_hash", plan.contentHash());
            params.put("created_by", operator.id());
            params.put("created_at", now);
            params.put("updated_at", now);
            Number id = insert.executeAndReturnKey(params);
            long segmentId = id.longValue();
            ids.add(segmentId);
            for (String tagName : splitTags(row.get("tags"))) {
                Tag tag = resolveOrCreateTag(tagName, tagCache);
                segmentTagPairs.add(new long[]{segmentId, tag.getId()});
            }
        }
        if (!segmentTagPairs.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO segment_tag(segment_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    segmentTagPairs, Math.max(1, segmentTagPairs.size()),
                    (ps, pair) -> {
                        ps.setLong(1, pair[0]);
                        ps.setLong(2, pair[1]);
                    });
        }
        return ids;
    }

    private List<Long> overwriteBatch(List<ImportRowPlan> batch, LoginUser operator, List<LineError> failed) {
        List<Long> ids = new ArrayList<>();
        Map<String, Tag> tagCache = new HashMap<>();
        for (ImportRowPlan plan : batch) {
            var row = plan.row();
            Segment s = segmentRepository.findById(plan.existingSegmentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "库内条目已不存在 id=" + plan.existingSegmentId()));
            s.setSourceText(row.get("source_text"));
            s.setTranslatedText(row.get("translated_text"));
            s.setWorkTitle(blankToNull(row.get("work_title")));
            s.setChapter(blankToNull(row.get("chapter")));
            s.setAuthor(blankToNull(row.get("author")));
            s.setDynasty(blankToNull(row.get("dynasty")));
            s.setTranslator(blankToNull(row.get("translator")));
            s.setNotes(blankToNull(row.get("notes")));
            s.setContentHash(plan.contentHash());
            s.getTags().clear();
            for (String tagName : splitTags(row.get("tags"))) {
                s.getTags().add(resolveOrCreateTag(tagName, tagCache));
            }
            segmentRepository.save(s);
            ids.add(s.getId());
        }
        return ids;
    }

    private Tag resolveOrCreateTag(String name, Map<String, Tag> cache) {
        Tag cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Tag tag = tagRepository.findByName(name).orElseGet(() -> {
            Tag t = new Tag();
            t.setName(name);
            return tagRepository.save(t);
        });
        cache.put(name, tag);
        return tag;
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String t : tags.split("\\|")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
