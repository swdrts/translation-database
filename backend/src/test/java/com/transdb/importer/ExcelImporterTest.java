package com.transdb.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelImporterTest {

    private final ExcelImporter importer = new ExcelImporter();

    @Test
    void supportsExcelExtensions() {
        assertThat(importer.supports("a.xlsx")).isTrue();
        assertThat(importer.supports("a.XLS")).isTrue();
        assertThat(importer.supports("a.csv")).isFalse();
    }

    @Test
    void parsesHeaderRowAndDataCells() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("语料");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("source_text");
            header.createCell(1).setCellValue("translatedText");
            header.createCell(2).setCellValue("dynasty");
            header.createCell(3).setCellValue("tags");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("学而时习之");
            data.createCell(1).setCellValue("To learn");
            data.createCell(2).setCellValue(476); // 数值单元格按显示值取
            data.createCell(3).setCellValue("儒家|教育");
            wb.write(out);
            bytes = out.toByteArray();
        }

        List<ParsedRow> rows = importer.parse(new ByteArrayInputStream(bytes));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("source_text")).isEqualTo("学而时习之");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("To learn");
        assertThat(rows.get(0).get("dynasty")).isEqualTo("476");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
    }

    @Test
    void emptySheetYieldsEmptyList() {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("空表");
            wb.write(out);
            bytes = out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(importer.parse(new ByteArrayInputStream(bytes))).isEmpty();
    }
}
