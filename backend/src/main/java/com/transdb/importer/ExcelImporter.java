package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Excel 解析器（POI WorkbookFactory，兼容 .xlsx/.xls）。
 * 取首个 sheet，首行为表头（经 {@link ColumnNormalizer#normalize} 规范化），
 * DataFormatter 取单元格显示值；空白单元格不入 fields（缺键，get() 返回 null）。
 * 空表 → 空列表（由上层预览服务统一判 IMPORT_NO_ROWS）。
 */
@Component
public class ExcelImporter implements FileParser {

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) {
        // DataFormatter 内部缓存可变 java.text.Format（如 DecimalFormat，非线程安全），
        // 单例 @Component 上共享实例会有并发写坏数值显示值的风险，故每次调用新建
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<ParsedRow> rows = new ArrayList<>();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return rows;
            }
            int columns = headerRow.getLastCellNum();
            List<String> header = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                header.add(ColumnNormalizer.normalize(formatter.formatCellValue(headerRow.getCell(c))));
            }
            int lineNumber = 0;
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                lineNumber++;
                Map<String, String> fields = new LinkedHashMap<>();
                for (int c = 0; c < columns; c++) {
                    String value = formatter.formatCellValue(row.getCell(c));
                    if (value != null && !value.isBlank()) {
                        fields.put(header.get(c), value);
                    }
                }
                rows.add(new ParsedRow(lineNumber, fields));
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "Excel 解析失败: " + e.getMessage());
        }
    }
}
