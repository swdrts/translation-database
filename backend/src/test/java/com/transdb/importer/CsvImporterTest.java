package com.transdb.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImporterTest {

    private final CsvImporter importer = new CsvImporter();

    @Test
    void supportsCsvExtensionOnly() {
        assertThat(importer.supports("a.csv")).isTrue();
        assertThat(importer.supports("a.CSV")).isTrue();
        assertThat(importer.supports("a.json")).isFalse();
    }

    @Test
    void parsesHeaderAndQuotedFields() {
        String csv = """
                source_text,translated_text,work_title,tags
                "学而时习之，不亦说乎？","Is it not pleasant to learn, and practice?",论语,儒家|教育
                "有朋自远方来","He said: ""welcome\""",论语,儒家
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("translated_text")).isEqualTo("Is it not pleasant to learn, and practice?");
        assertThat(rows.get(1).get("translated_text")).isEqualTo("He said: \"welcome\"");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
        assertThat(rows.get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    void normalizesCamelHeaderAndMissingCells() {
        String csv = """
                sourceText,Translated Text,chapter
                a,b,1
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).get("source_text")).isEqualTo("a");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("b");
        assertThat(rows.get(0).get("chapter")).isEqualTo("1");
        assertThat(rows.get(0).get("tags")).isNull();
    }

    @Test
    void headerOnlyYieldsEmptyList() {
        String csv = "source_text,translated_text\n";
        assertThat(importer.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))).isEmpty();
    }

    @Test
    void duplicateHeaderNamesRejectedAsUnreadable() {
        String csv = """
                source_text,source_text
                a,b
                """;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer.parse(
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(com.transdb.common.BusinessException.class)
                .extracting(e -> ((com.transdb.common.BusinessException) e).getErrorCode().getCode())
                .isEqualTo(3001);
    }
}
