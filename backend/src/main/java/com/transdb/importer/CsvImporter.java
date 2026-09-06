package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV 解析器（commons-csv，RFC4180：引号/转义/内嵌换行）。
 * 首行为表头，表头经 {@link ColumnNormalizer#normalize} 规范化；数据列按位置映射。
 * 空文件/仅表头 → 空列表（由上层预览服务统一判 IMPORT_NO_ROWS）。
 */
@Component
public class CsvImporter implements FileParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        try {
            // 一次 parse：先把流读入 byte[]，避免消费流后无法二次读取
            byte[] bytes = in.readAllBytes();
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .build();
            List<ParsedRow> rows = new ArrayList<>();
            try (CSVParser parser = CSVParser.parse(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, format)) {
                List<String> header = parser.getHeaderNames().stream()
                        .map(ColumnNormalizer::normalize)
                        .toList();
                int lineNumber = 0;
                for (CSVRecord record : parser) {
                    lineNumber++;
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (int i = 0; i < header.size() && i < record.size(); i++) {
                        fields.put(header.get(i), record.get(i));
                    }
                    rows.add(new ParsedRow(lineNumber, fields));
                }
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "CSV 解析失败: " + e.getMessage());
        }
    }
}
