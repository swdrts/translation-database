package com.transdb.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonImporter implements FileParser {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        JsonNode root;
        try {
            root = objectMapper.readTree(in);
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "JSON 解析失败: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "JSON 顶层必须是对象数组");
        }
        List<ParsedRow> rows = new ArrayList<>();
        int lineNumber = 0;
        for (JsonNode element : root) {
            lineNumber++;
            if (!element.isObject()) {
                throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "第 " + lineNumber + " 个元素不是对象");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            element.fields().forEachRemaining(e -> fields.put(ColumnNormalizer.normalize(e.getKey()),
                    e.getValue().isNull() ? null : e.getValue().asText()));
            if (element.has("tags") && element.get("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                element.get("tags").forEach(t -> tags.add(t.asText()));
                fields.put("tags", String.join("|", tags));
            }
            rows.add(new ParsedRow(lineNumber, fields));
        }
        return rows;
    }
}
