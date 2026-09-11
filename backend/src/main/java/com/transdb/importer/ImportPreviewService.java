package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.LineError;
import com.transdb.repository.SegmentRepository;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImportPreviewService {

    private static final int HASH_CHUNK_SIZE = 1000;
    private static final int SOURCE_CHUNK_SIZE = 500;

    private final List<FileParser> parsers;
    private final SegmentRepository segmentRepository;
    private final ImportPreviewStore previewStore;
    private final ImportProperties importProperties;

    /** 对照表导入（json/csv/excel）：每行须同时有原文与译文，落库即 PUBLISHED。 */
    public ImportPreviewVO buildPreview(String filename, InputStream in,
                                        DuplicateStrategy strategy, LoginUser operator) {
        FileParser parser = parsers.stream().filter(p -> p.supports(filename)).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                        "不支持的文件类型: " + filename));
        return buildPreview(parser.parse(in), strategy, operator, ImportSourceType.TABLE, null, null);
    }

    /** 整本书导入：仅一侧文字、另一侧留空待补，默认 DRAFT；书名/作者/章节用于预览展示与回填。 */
    public ImportPreviewVO buildDocumentPreview(List<ParsedRow> parsedRows,
                                                DuplicateStrategy strategy, LoginUser operator,
                                                String documentTitle, String documentAuthor,
                                                int chapterCount, ImportTextRole textRole) {
        return buildPreview(parsedRows, strategy, operator, ImportSourceType.DOCUMENT,
                new DocumentMeta(documentTitle, documentAuthor, chapterCount), textRole);
    }

    private record DocumentMeta(String title, String author, int chapterCount) {
    }

    private ImportPreviewVO buildPreview(List<ParsedRow> parsed, DuplicateStrategy strategy,
                                         LoginUser operator, ImportSourceType sourceType,
                                         DocumentMeta docMeta, ImportTextRole textRole) {
        if (parsed.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        if (parsed.size() > importProperties.maxRows()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_TOO_LARGE,
                    "行数 " + parsed.size() + " 超过单次导入上限 " + importProperties.maxRows());
        }
        // 原文侧：source 必填、translated 留空；译文侧：translated 必填、source 留空；表格：两者皆必填
        boolean requireSource = sourceType != ImportSourceType.DOCUMENT
                || textRole != ImportTextRole.TRANSLATION;
        boolean requireTranslation = sourceType != ImportSourceType.DOCUMENT
                || textRole != ImportTextRole.SOURCE;

        List<LineError> errors = new ArrayList<>();
        List<ParsedRow> validRows = new ArrayList<>();
        for (ParsedRow row : parsed) {
            List<String> rowErrors = ParsedRowValidator.validate(row, requireSource, requireTranslation);
            if (rowErrors.isEmpty()) {
                validRows.add(row);
            } else {
                rowErrors.forEach(reason -> errors.add(new LineError(row.lineNumber(), reason)));
            }
        }

        // 文件内去重：同 hash 的后出现行（SKIP/OVERWRITE 策略下）视为文件内重复
        Map<String, ParsedRow> seenInFile = new LinkedHashMap<>();
        List<ParsedRow> deduped = new ArrayList<>();
        List<LineError> duplicates = new ArrayList<>();
        Set<Integer> inFileDupLines = new HashSet<>();
        if (strategy != DuplicateStrategy.KEEP) {
            for (ParsedRow row : validRows) {
                String hash = ContentHash.sha256(row.get("source_text"), row.get("translated_text"));
                ParsedRow existing = seenInFile.get(hash);
                if (existing == null) {
                    seenInFile.put(hash, row);
                    deduped.add(row);
                } else if (strategy == DuplicateStrategy.OVERWRITE) {
                    // 后写胜：以较后一行的元数据为准，先前行报告为文件内重复
                    deduped.set(deduped.indexOf(existing), row);
                    seenInFile.put(hash, row);
                    inFileDupLines.add(row.lineNumber());
                    duplicates.add(new LineError(row.lineNumber(),
                            "文件内重复（与第 " + existing.lineNumber() + " 行相同，后写胜）"));
                } else {
                    inFileDupLines.add(row.lineNumber());
                    duplicates.add(new LineError(row.lineNumber(),
                            "文件内重复（与第 " + existing.lineNumber() + " 行相同）"));
                }
            }
        } else {
            deduped.addAll(validRows);
        }

        // 库内批查：TABLE 按内容 hash；DOCUMENT 额外按导入侧文字查
        // （另一侧已补齐的条目 hash 不相同，须按字段查才能保护已配对的成果）
        Map<String, Long> existingByHash = findExistingByHash(deduped);
        Map<String, Segment> existingCounterpart = sourceType == ImportSourceType.DOCUMENT
                ? findExistingByImportedSide(deduped, textRole) : Map.of();

        List<ImportRowPlan> plans = new ArrayList<>();
        int willImport = 0;
        int overwrite = 0;
        int skipped = inFileDupLines.size();
        // rowId 会话内稳定：按行序 1..N 分配，预览确认前编辑行内容不会改变定位
        long nextRowId = 0;
        for (ParsedRow row : deduped) {
            String source = row.get("source_text");
            String hash = ContentHash.sha256(source, row.get("translated_text"));
            String lookupKey = textRole == ImportTextRole.TRANSLATION
                    ? row.get("translated_text") : source;
            Segment sameSide = existingCounterpart.get(lookupKey);
            // 整本书导入专属保护：导入侧文字在库中已配好另一侧（原文侧=已有译文；译文侧=已配原文），
            // 无论何种策略都不覆盖已配对成果
            if (sameSide != null && hasOtherSide(sameSide, textRole)) {
                duplicates.add(new LineError(row.lineNumber(),
                        protectMessage(sameSide, textRole)));
                plans.add(new ImportRowPlan(++nextRowId, ImportRowPlan.PlanType.SKIP, row,
                        sameSide.getId(), hash, false));
                skipped++;
                continue;
            }
            Long existingId = existingByHash.get(hash);
            if (existingId == null && sameSide != null) {
                existingId = sameSide.getId();
            }
            if (existingId == null) {
                plans.add(new ImportRowPlan(++nextRowId, ImportRowPlan.PlanType.IMPORT, row,
                        null, hash, false));
                willImport++;
            } else {
                duplicates.add(new LineError(row.lineNumber(), "库内重复（existingId=" + existingId + "）"));
                switch (strategy) {
                    case SKIP -> {
                        plans.add(new ImportRowPlan(++nextRowId, ImportRowPlan.PlanType.SKIP, row,
                                existingId, hash, false));
                        skipped++;
                    }
                    case OVERWRITE -> {
                        plans.add(new ImportRowPlan(++nextRowId, ImportRowPlan.PlanType.OVERWRITE, row,
                                existingId, hash, false));
                        overwrite++;
                    }
                    case KEEP -> {
                        plans.add(new ImportRowPlan(++nextRowId, ImportRowPlan.PlanType.IMPORT, row,
                                existingId, hash, false));
                        willImport++;
                    }
                }
            }
        }

        SegmentStatus status = sourceType == ImportSourceType.DOCUMENT
                ? SegmentStatus.DRAFT : SegmentStatus.PUBLISHED;
        String previewId = previewStore.create(operator.id(), strategy, plans, parsed.size(),
                errors, sourceType, status);
        return new ImportPreviewVO(previewId, strategy.name(), parsed.size(), willImport,
                overwrite, skipped, errors, duplicates, sourceType.name(),
                docMeta == null ? null : docMeta.title(), docMeta == null ? null : docMeta.author(),
                docMeta == null ? 0 : docMeta.chapterCount(),
                sourceType == ImportSourceType.DOCUMENT && textRole != null ? textRole.name() : null);
    }

    private Map<String, Long> findExistingByHash(List<ParsedRow> rows) {
        Map<String, Long> result = new HashMap<>();
        List<String> hashes = rows.stream()
                .map(r -> ContentHash.sha256(r.get("source_text"), r.get("translated_text")))
                .toList();
        for (int i = 0; i < hashes.size(); i += HASH_CHUNK_SIZE) {
            List<String> chunk = hashes.subList(i, Math.min(i + HASH_CHUNK_SIZE, hashes.size()));
            for (Segment s : segmentRepository.findByContentHashIn(chunk)) {
                result.put(s.getContentHash(), s.getId());
            }
        }
        return result;
    }

    private Map<String, Segment> findExistingByImportedSide(List<ParsedRow> rows, ImportTextRole textRole) {
        Map<String, Segment> result = new HashMap<>();
        List<String> keys = rows.stream()
                .map(r -> textRole == ImportTextRole.TRANSLATION
                        ? r.get("translated_text") : r.get("source_text"))
                .distinct().toList();
        for (int i = 0; i < keys.size(); i += SOURCE_CHUNK_SIZE) {
            List<String> chunk = keys.subList(i, Math.min(i + SOURCE_CHUNK_SIZE, keys.size()));
            List<Segment> found = textRole == ImportTextRole.TRANSLATION
                    ? segmentRepository.findByTranslatedTextIn(chunk)
                    : segmentRepository.findBySourceTextIn(chunk);
            for (Segment s : found) {
                result.putIfAbsent(textRole == ImportTextRole.TRANSLATION
                        ? s.getTranslatedText() : s.getSourceText(), s);
            }
        }
        return result;
    }

    /** 库中该条目的另一侧（原文侧导入看译文；译文侧导入看原文）是否已有内容。 */
    private static boolean hasOtherSide(Segment s, ImportTextRole textRole) {
        String other = textRole == ImportTextRole.TRANSLATION ? s.getSourceText() : s.getTranslatedText();
        return other != null && !other.isBlank();
    }

    private static String protectMessage(Segment sameSide, ImportTextRole textRole) {
        return textRole == ImportTextRole.TRANSLATION
                ? "库中该译文已配有原文（id=" + sameSide.getId() + "），已自动跳过保护"
                : "原文已有译文（id=" + sameSide.getId() + "），已自动跳过保护";
    }
}
