package com.transdb.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonImporterTest {

    private final JsonImporter importer = new JsonImporter(new ObjectMapper());

    @Test
    void supportsJsonExtensionOnly() {
        assertThat(importer.supports("corpus.json")).isTrue();
        assertThat(importer.supports("CORpus.JSON")).isTrue();
        assertThat(importer.supports("corpus.csv")).isFalse();
        assertThat(importer.supports("no-extension")).isFalse();
    }

    @Test
    void parsesArrayWithSnakeAndCamelKeys() {
        String json = """
                [
                  {"source_text":"学而时习之","translatedText":"To learn","tags":["儒家","教育"]},
                  {"source_text":"有朋自远方来","translated_text":"Friends from afar","work_title":"论语"}
                ]
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).lineNumber()).isEqualTo(1);
        assertThat(rows.get(0).get("source_text")).isEqualTo("学而时习之");
        assertThat(rows.get(0).get("translated_text")).isEqualTo("To learn");
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|教育");
        assertThat(rows.get(1).get("work_title")).isEqualTo("论语");
        assertThat(rows.get(1).get("chapter")).isNull();
    }

    @Test
    void jsonTagsListBecomesPipeJoined() {
        String json = """
                [{"source_text":"a","translated_text":"b","tags":["儒家","道家"]}]
                """;
        List<ParsedRow> rows = importer.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).get("tags")).isEqualTo("儒家|道家");
    }

    @Test
    void nonArrayOrNonObjectRejectedAsUnreadable() {
        assertThatThrownBy(() -> importer.parse(new ByteArrayInputStream(
                "{\"not\":\"an array\"}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(3001);
        assertThatThrownBy(() -> importer.parse(new ByteArrayInputStream(
                "[\"just\",\"strings\"]".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class);
    }
}
