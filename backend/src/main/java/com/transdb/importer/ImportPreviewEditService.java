package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.dto.ImportChapterStatVO;
import com.transdb.dto.ImportEditStatsVO;
import com.transdb.dto.ImportRowEditRequest;
import com.transdb.dto.ImportRowsPageVO;
import com.transdb.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 导入预览会话的分段编辑：每次操作即时重评受影响行的去重结论。
 * 会话的 rows 是可变列表，编辑直接作用于其上；textRole 依据导入侧文字是否为空推断。
 */
@Service
@RequiredArgsConstructor
public class ImportPreviewEditService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 500;

    private final ImportPreviewStore previewStore;
    private final SegmentRepository segmentRepository;

    /** 分页拉取分段全文：chapter 非空串时精确过滤（null 视作未分章）；suspicious 时按长/短阈值过滤。 */
    public ImportRowsPageVO rows(ImportPreviewStore.ImportPreviewSession s, String chapter, boolean suspicious,
                                 int longAbove, int shortBelow, int page, int size) {
        String side = sideField(textRoleOf(s));
        List<ImportRowsPageVO.RowVO> matched = new ArrayList<>();
        for (ImportRowPlan plan : s.rows()) {
            String text = nullToEmpty(plan.row().get(side));
            boolean chapterOk = chapter == null || chapter.equals(displayChapter(plan));
            boolean suspiciousOk = !suspicious || text.length() > longAbove || text.length() < shortBelow;
            if (chapterOk && suspiciousOk) {
                long prev = matched.isEmpty() ? -1 : matched.get(matched.size() - 1).rowId();
                matched.add(toVO(plan, matched.size() + 1, prev));
            }
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int totalPages = Math.max(1, (matched.size() + pageSize - 1) / pageSize);
        int pageNo = Math.min(Math.max(page, 0), totalPages - 1);
        int from = pageNo * pageSize;
        List<ImportRowsPageVO.RowVO> pageRows =
                from >= matched.size() ? List.of() : matched.subList(from, Math.min(from + pageSize, matched.size()));
        return new ImportRowsPageVO(statsOf(s), pageNo, totalPages, pageRows);
    }

    /** 章节及分段数，按首现顺序；未分章以空串表示。 */
    public List<ImportChapterStatVO> chapters(ImportPreviewStore.ImportPreviewSession s) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ImportRowPlan plan : s.rows()) {
            counts.merge(displayChapter(plan), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new ImportChapterStatVO(e.getKey(), e.getValue()))
                .toList();
    }

    /** 编辑单段：text/chapter 至少一项；text 变化时重跑去重判定。 */
    public ImportRowPlan editRow(ImportPreviewStore.ImportPreviewSession s, long rowId, ImportRowEditRequest req) {
        String text = req.text();
        String chapter = req.chapter();
        if (text == null && chapter == null) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "text 与 chapter 至少修改一项");
        }
        int idx = indexOfRowId(s, rowId);
        ImportRowPlan plan = s.rows().get(idx);
        String oldHash = plan.contentHash();
        Map<String, String> fields = new LinkedHashMap<>(plan.row().fields());
        if (text != null) {
            String stripped = text.strip();
            if (stripped.isEmpty()) {
                throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "分段内容不能为空");
            }
            fields.put(sideField(textRoleOf(s)), stripped);
        }
        if (chapter != null) {
            fields.put("chapter", chapter.strip());
        }
        ImportRowPlan updated = plan.withRow(new ParsedRow(plan.row().lineNumber(), fields));
        s.rows().set(idx, updated);
        if (text != null) {
            reevaluate(s, idx);
            revive(s, oldHash);
        }
        return s.rows().get(idx);
    }

    /** 合并连续多段：拼接按 joinText 规则，章节取第一段，rowId 新分配。 */
    public ImportRowPlan mergeRows(ImportPreviewStore.ImportPreviewSession s, List<Long> rowIds) {
        Set<Long> ids = new LinkedHashSet<>(rowIds == null ? List.of() : rowIds);
        if (ids.size() < 2) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "至少选择两段");
        }
        List<Integer> indexes = new ArrayList<>();
        for (Long id : ids) {
            indexes.add(indexOfRowId(s, id));
        }
        indexes.sort(Integer::compareTo);
        for (int i = 1; i < indexes.size(); i++) {
            if (indexes.get(i) != indexes.get(i - 1) + 1) {
                throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                        "分段不连续（从第 " + (indexes.get(i - 1) + 2) + " 段开始断开）");
            }
        }
        int first = indexes.get(0);
        ImportRowPlan firstPlan = s.rows().get(first);
        String oldFirstHash = firstPlan.contentHash();
        String side = sideField(textRoleOf(s));
        String joined = nullToEmpty(firstPlan.row().get(side));
        List<ImportRowPlan> mergedPlans = new ArrayList<>();
        for (int i = 1; i < indexes.size(); i++) {
            ImportRowPlan p = s.rows().get(indexes.get(i));
            mergedPlans.add(p);
            joined = joinText(joined, nullToEmpty(p.row().get(side)));
        }
        // 从后往前删，保持前排索引稳定
        for (int i = indexes.size() - 1; i >= 1; i--) {
            s.rows().remove(indexes.get(i).intValue());
        }
        Map<String, String> fields = new LinkedHashMap<>(firstPlan.row().fields());
        fields.put(side, joined);
        ImportRowPlan merged = new ImportRowPlan(previewStore.nextRowId(s.id()), firstPlan.type(),
                new ParsedRow(firstPlan.row().lineNumber(), fields),
                firstPlan.existingSegmentId(), firstPlan.contentHash(), true);
        s.rows().set(first, merged);
        reevaluate(s, first);
        revive(s, oldFirstHash);
        return s.rows().get(first);
    }

    /** 在 atChar 光标偏移处拆成两段：上段沿用原 rowId，下段新分配。 */
    public List<ImportRowPlan> splitRow(ImportPreviewStore.ImportPreviewSession s, long rowId, int atChar) {
        int idx = indexOfRowId(s, rowId);
        ImportRowPlan plan = s.rows().get(idx);
        String oldHash = plan.contentHash();
        String side = sideField(textRoleOf(s));
        String text = nullToEmpty(plan.row().get(side));
        if (atChar <= 0 || atChar >= text.length()) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "拆分位置必须在段落文字中间");
        }
        String upperText = text.substring(0, atChar).stripTrailing();
        String lowerText = text.substring(atChar).stripLeading();
        if (upperText.isEmpty() || lowerText.isEmpty()) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "拆分后不能有空段");
        }
        ImportRowPlan upper = plan.withRow(withField(plan.row(), side, upperText));
        ImportRowPlan lower = new ImportRowPlan(previewStore.nextRowId(s.id()), plan.type(),
                withField(plan.row(), side, lowerText),
                plan.existingSegmentId(), plan.contentHash(), true);
        s.rows().set(idx, upper);
        s.rows().add(idx + 1, lower);
        reevaluate(s, idx);
        reevaluate(s, idx + 1);
        revive(s, oldHash);
        return List.of(s.rows().get(idx), s.rows().get(idx + 1));
    }

    /** 删除一段；若它是同文段的保留者，其余同文段自动复活重评。 */
    public ImportEditStatsVO deleteRow(ImportPreviewStore.ImportPreviewSession s, long rowId) {
        int idx = indexOfRowId(s, rowId);
        String oldHash = s.rows().get(idx).contentHash();
        s.rows().remove(idx);
        revive(s, oldHash);
        return statsOf(s);
    }

    /** 章节改名：作用于该章全部段；to 传空白即归入未分章。 */
    public ImportEditStatsVO renameChapter(ImportPreviewStore.ImportPreviewSession s, String from, String to) {
        if (to == null) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "新章节名不能为空");
        }
        String origin = from == null ? "" : from.strip();
        String target = to.strip();
        boolean any = false;
        for (int i = 0; i < s.rows().size(); i++) {
            ImportRowPlan plan = s.rows().get(i);
            if (!origin.equals(displayChapter(plan))) {
                continue;
            }
            s.rows().set(i, plan.withRow(withField(plan.row(), "chapter", target)));
            any = true;
        }
        if (!any) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "章节不存在");
        }
        return statsOf(s);
    }

    /** 会话当前统计：总行数与按 planType 的计数。 */
    public ImportEditStatsVO statsOf(ImportPreviewStore.ImportPreviewSession s) {
        int will = 0;
        int over = 0;
        int skip = 0;
        for (ImportRowPlan plan : s.rows()) {
            switch (plan.type()) {
                case IMPORT -> will++;
                case OVERWRITE -> over++;
                case SKIP -> skip++;
            }
        }
        return new ImportEditStatsVO(s.rows().size(), will, over, skip);
    }

    /** 行视图：导入侧全文；chapter null 归未分章；prevRowId 无上一段时为 -1。 */
    public ImportRowsPageVO.RowVO toVO(ImportRowPlan plan, int seq, long prevRowId) {
        String source = plan.row().get("source_text");
        String side = source != null && !source.isBlank() ? "source_text" : "translated_text";
        return new ImportRowsPageVO.RowVO(plan.rowId(), seq, prevRowId, displayChapter(plan),
                nullToEmpty(plan.row().get(side)), plan.type().name(), plan.edited());
    }

    // ---------- 内部 ----------

    /** 导入侧推断：首行原文为空即译文侧导入。 */
    private ImportTextRole textRoleOf(ImportPreviewStore.ImportPreviewSession s) {
        if (s.rows().isEmpty()) {
            return ImportTextRole.SOURCE;
        }
        String source = s.rows().get(0).row().get("source_text");
        return source == null || source.isBlank() ? ImportTextRole.TRANSLATION : ImportTextRole.SOURCE;
    }

    private static String sideField(ImportTextRole role) {
        return role == ImportTextRole.TRANSLATION ? "translated_text" : "source_text";
    }

    private static String displayChapter(ImportRowPlan plan) {
        String ch = plan.row().get("chapter");
        return ch == null ? "" : ch;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static ParsedRow withField(ParsedRow row, String key, String value) {
        Map<String, String> fields = new LinkedHashMap<>(row.fields());
        fields.put(key, value);
        return new ParsedRow(row.lineNumber(), fields);
    }

    private static int indexOfRowId(ImportPreviewStore.ImportPreviewSession s, long rowId) {
        for (int i = 0; i < s.rows().size(); i++) {
            if (s.rows().get(i).rowId() == rowId) {
                return i;
            }
        }
        throw BusinessException.of(ErrorCode.IMPORT_ROW_NOT_FOUND);
    }

    /** 拼接两段文字：与 TextSegmenter.joinSeparator 同语义——CJK 直接拼接，拉丁字母/数字间补一个空格。 */
    private static String joinText(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return a + b;
        }
        char last = a.charAt(a.length() - 1);
        char first = b.charAt(0);
        boolean lastLatin = last < 0x80 && (Character.isLetterOrDigit(last) || last == ',');
        boolean firstLatin = first < 0x80 && Character.isLetterOrDigit(first);
        return lastLatin && firstLatin ? a + " " + b : a + b;
    }

    /**
     * 重评一行：文件内同文非首个 → 跳过；否则库内查重（hash + 导入侧整字段保护），按会话策略分派。
     */
    private void reevaluate(ImportPreviewStore.ImportPreviewSession s, int idx) {
        ImportRowPlan plan = s.rows().get(idx);
        String newHash = ContentHash.sha256(nullToEmpty(plan.row().get("source_text")),
                nullToEmpty(plan.row().get("translated_text")));
        // 文件内同文：会话顺序首个为保留者（目标行本身视同已持有新 hash，因其旧 hash 尚未落盘）
        for (ImportRowPlan other : s.rows()) {
            if (other.rowId() != plan.rowId() && Objects.equals(other.contentHash(), newHash)) {
                int firstIdx = -1;
                for (int i = 0; i < s.rows().size(); i++) {
                    if (i == idx || Objects.equals(s.rows().get(i).contentHash(), newHash)) {
                        firstIdx = i;
                        break;
                    }
                }
                if (firstIdx != idx) {
                    s.rows().set(idx, plan.withType(ImportRowPlan.PlanType.SKIP, null, newHash));
                    return;
                }
                break;
            }
        }
        // 目标行是首个同文行：后续同文行降为文件内重复（KEEP 策略照常保留，与预览语义一致）
        if (s.strategy() != DuplicateStrategy.KEEP) {
            for (int i = 0; i < s.rows().size(); i++) {
                ImportRowPlan p = s.rows().get(i);
                if (p.rowId() != plan.rowId() && Objects.equals(p.contentHash(), newHash)
                        && p.type() != ImportRowPlan.PlanType.SKIP) {
                    s.rows().set(i, p.withType(ImportRowPlan.PlanType.SKIP, null, newHash));
                }
            }
        }
        // 库内查重：内容 hash + 导入侧整字段（保护已配对成果）
        List<Segment> byHash = segmentRepository.findByContentHashIn(List.of(newHash));
        ImportTextRole role = textRoleOf(s);
        String sideText = nullToEmpty(plan.row().get(sideField(role)));
        List<Segment> bySide = role == ImportTextRole.TRANSLATION
                ? segmentRepository.findByTranslatedTextIn(List.of(sideText))
                : segmentRepository.findBySourceTextIn(List.of(sideText));
        Segment sameSide = bySide.isEmpty() ? null : bySide.get(0);
        if (sameSide != null && hasOtherSide(sameSide, role)) {
            s.rows().set(idx, plan.withType(ImportRowPlan.PlanType.SKIP, sameSide.getId(), newHash));
            return;
        }
        Long existingId = byHash.isEmpty() ? null : byHash.get(0).getId();
        if (existingId == null && sameSide != null) {
            existingId = sameSide.getId();
        }
        if (existingId == null) {
            s.rows().set(idx, plan.withType(ImportRowPlan.PlanType.IMPORT, null, newHash));
        } else {
            ImportRowPlan.PlanType type = switch (s.strategy()) {
                case SKIP -> ImportRowPlan.PlanType.SKIP;
                case OVERWRITE -> ImportRowPlan.PlanType.OVERWRITE;
                case KEEP -> ImportRowPlan.PlanType.IMPORT;
            };
            s.rows().set(idx, plan.withType(type, existingId, newHash));
        }
    }

    /** 旧 hash 的保留者被改/删/合并后，其余同文行逐个重评（复活）。 */
    private void revive(ImportPreviewStore.ImportPreviewSession s, String oldHash) {
        for (int i = 0; i < s.rows().size(); i++) {
            if (Objects.equals(s.rows().get(i).contentHash(), oldHash)) {
                reevaluate(s, i);
            }
        }
    }

    /** 库内该条目的另一侧是否已有内容（导入侧成果保护）。 */
    private static boolean hasOtherSide(Segment segment, ImportTextRole role) {
        String other = role == ImportTextRole.TRANSLATION
                ? segment.getSourceText() : segment.getTranslatedText();
        return other != null && !other.isBlank();
    }
}
