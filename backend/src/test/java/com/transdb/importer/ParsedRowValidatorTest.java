package com.transdb.importer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedRowValidatorTest {

    private ParsedRow row(Map<String, String> fields) {
        return new ParsedRow(1, fields);
    }

    @Test
    void validRowHasNoErrors() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "学而时习之");
        f.put("translated_text", "To learn");
        f.put("dynasty", "先秦");
        f.put("tags", "儒家|教育|");
        assertThat(ParsedRowValidator.validate(row(f))).isEmpty();
    }

    @Test
    void blankRequiredFieldsReported() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "  ");
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(2); // source_text 与 translated_text 都缺
        assertThat(errors.get(0)).contains("source_text");
    }

    @Test
    void overlongFieldsReported() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "s");
        f.put("translated_text", "t");
        f.put("dynasty", "朝".repeat(65));
        f.put("work_title", "书".repeat(256));
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(2);
        assertThat(errors.toString()).contains("dynasty").contains("work_title");
    }

    @Test
    void overlongTagReportedButValidTagsSplit() {
        Map<String, String> f = new HashMap<>();
        f.put("source_text", "s");
        f.put("translated_text", "t");
        f.put("tags", "ok|" + "长".repeat(65));
        List<String> errors = ParsedRowValidator.validate(row(f));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("tags");
    }
}
