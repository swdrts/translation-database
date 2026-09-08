package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.ImportRowSampleVO;
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
    private static final int SAMPLE_ROWS = 8;
    private static final int SAMPLE_TEXT_MAX = 120;

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
        return buildPreview(parser.parse(in), strategy, operator, ImportSourceType.TABLE, null);
    }

    /** 整本书导入：仅原文、译文留空待补，默认 DRAFT；书名/作者/章节用于预览展示与回填。 */
    public ImportPreviewVO buildDocumentPreview(List<ParsedRow> parsedRows,
                                                DuplicateStrategy strategy, LoginUser operator,
                                                String documentTitle, String documentAuthor,
                                                int chapterCount) {
        return buildPreview(parsedRows, strategy, operator, ImportSourceType.DOCUMENT,
                new DocumentMeta(documentTitle, documentAuthor, chapterCount));
    }

    private record DocumentMeta(String title, String author, int chapterCount) {
    }

    private ImportPreviewVO buildPreview(List<ParsedRow> parsed, DuplicateStrategy strategy,
                                         LoginUser operator, ImportSourceType sourceType,
                                         DocumentMeta docMeta) {
        if (parsed.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        if (parsed.size() > importProperties.maxRows()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_TOO_LARGE,
                    "行数 " + parsed.size() + " 超过单次导入上限 " + importProperties.maxRows());
        }
        boolean requireTranslation = sourceType != ImportSourceType.DOCUMENT;

        List<LineError> errors = new ArrayList<>();
        List<ParsedRow> validRows = new ArrayList<>();
        for (ParsedRow row : parsed) {
            List<String> rowErrors = ParsedRowValidator.validate(row, requireTranslation);
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

        // 库内批查：TABLE 按内容 hash；DOCUMENT 额外按原文查（覆盖已有译文的情形 hash 不相同）
        Map<String, Long> existingByHash = findExistingByHash(deduped);
        Map<String, Segment> existingBySource = sourceType == ImportSourceType.DOCUMENT
                ? findExistingBySource(deduped) : Map.of();

        List<ImportRowPlan> plans = new ArrayList<>();
        int willImport = 0;
        int overwrite = 0;
        int skipped = inFileDupLines.size();
        for (ParsedRow row : deduped) {
            String source = row.get("source_text");
            String hash = ContentHash.sha256(source, row.get("translated_text"));
            Segment srcExisting = existingBySource.get(source);
            if (srcExisting != null && srcExisting.getTranslatedText() != null
                    && !srcExisting.getTranslatedText().isBlank()) {
                // 整本书导入专属保护：原文已有译文，无论何种策略都不覆盖译文成果
                duplicates.add(new LineError(row.lineNumber(),
                        "原文已有译文（id=" + srcExisting.getId() + "），已自动跳过保护"));
                plans.add(new ImportRowPlan(ImportRowPlan.PlanType.SKIP, row,
                        srcExisting.getId(), hash));
                skipped++;
                continue;
            }
            Long existingId = existingByHash.get(hash);
            if (existingId == null && srcExisting != null) {
                existingId = srcExisting.getId();
            }
            if (existingId == null) {
                plans.add(new ImportRowPlan(ImportRowPlan.PlanType.IMPORT, row, null, hash));
                willImport++;
            } else {
                duplicates.add(new LineError(row.lineNumber(), "库内重复（existingId=" + existingId + "）"));
                switch (strategy) {
                    case SKIP -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.SKIP, row, existingId, hash));
                        skipped++;
                    }
                    case OVERWRITE -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.OVERWRITE, row, existingId, hash));
                        overwrite++;
                    }
                    case KEEP -> {
                        plans.add(new ImportRowPlan(ImportRowPlan.PlanType.IMPORT, row, existingId, hash));
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
                docMeta == null ? List.of() : sampleRows(parsed));
    }

    private List<ImportRowSampleVO> sampleRows(List<ParsedRow> parsed) {
        return parsed.stream()
                .limit(SAMPLE_ROWS)
                .map(r -> new ImportRowSampleVO(r.lineNumber(), r.get("chapter"),
                        truncate(r.get("source_text"))))
                .toList();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SAMPLE_TEXT_MAX ? text : text.substring(0, SAMPLE_TEXT_MAX) + "…";
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

    private Map<String, Segment> findExistingBySource(List<ParsedRow> rows) {
        Map<String, Segment> result = new HashMap<>();
        List<String> sources = rows.stream().map(r -> r.get("source_text")).distinct().toList();
        for (int i = 0; i < sources.size(); i += SOURCE_CHUNK_SIZE) {
            List<String> chunk = sources.subList(i, Math.min(i + SOURCE_CHUNK_SIZE, sources.size()));
            for (Segment s : segmentRepository.findBySourceTextIn(chunk)) {
                result.putIfAbsent(s.getSourceText(), s);
            }
        }
        return result;
    }
}
