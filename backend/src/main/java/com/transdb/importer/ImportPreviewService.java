package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
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

    private final List<FileParser> parsers;
    private final SegmentRepository segmentRepository;
    private final ImportPreviewStore previewStore;
    private final ImportProperties importProperties;

    public ImportPreviewVO buildPreview(String filename, InputStream in,
                                        DuplicateStrategy strategy, LoginUser operator) {
        FileParser parser = parsers.stream().filter(p -> p.supports(filename)).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                        "不支持的文件类型: " + filename));
        List<ParsedRow> parsed = parser.parse(in);
        if (parsed.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        if (parsed.size() > importProperties.maxRows()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_TOO_LARGE,
                    "行数 " + parsed.size() + " 超过单次导入上限 " + importProperties.maxRows());
        }

        List<LineError> errors = new ArrayList<>();
        List<ParsedRow> validRows = new ArrayList<>();
        for (ParsedRow row : parsed) {
            List<String> rowErrors = ParsedRowValidator.validate(row);
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

        // 库内批查
        Map<String, Long> existingByHash = findExistingByHash(deduped);

        List<ImportRowPlan> plans = new ArrayList<>();
        int willImport = 0;
        int overwrite = 0;
        int skipped = inFileDupLines.size();
        for (ParsedRow row : deduped) {
            String hash = ContentHash.sha256(row.get("source_text"), row.get("translated_text"));
            Long existingId = existingByHash.get(hash);
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

        String previewId = previewStore.create(operator.id(), strategy, plans, parsed.size(), errors);
        return new ImportPreviewVO(previewId, strategy.name(), parsed.size(), willImport,
                overwrite, skipped, errors, duplicates);
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
}
