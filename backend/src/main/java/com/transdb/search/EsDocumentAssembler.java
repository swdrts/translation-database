package com.transdb.search;

import com.transdb.domain.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EsDocumentAssembler {

    public Map<String, Object> toDoc(Segment s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("segment_id", String.valueOf(s.getId()));
        doc.put("source_text", s.getSourceText());
        doc.put("translated_text", s.getTranslatedText());
        putIfNotBlank(doc, "work_title", s.getWorkTitle());
        putIfNotBlank(doc, "chapter", s.getChapter());
        putIfNotBlank(doc, "author", s.getAuthor());
        putIfNotBlank(doc, "dynasty", s.getDynasty());
        putIfNotBlank(doc, "translator", s.getTranslator());
        putIfNotBlank(doc, "notes", s.getNotes());
        doc.put("tags", s.getTags().stream().map(t -> t.getName()).sorted().toList());
        doc.put("status", s.getStatus().name());
        doc.put("version", s.getVersion());
        doc.put("created_at", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        doc.put("updated_at", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
        doc.put("suggest", buildSuggest(s));
        doc.values().removeIf(v -> v == null);
        return doc;
    }

    private List<Map<String, Object>> buildSuggest(Segment s) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        addSuggestInputs(inputs, "work", s.getWorkTitle());
        addSuggestInputs(inputs, "author", s.getAuthor());
        for (var tag : s.getTags()) {
            addSuggestInputs(inputs, "tag", tag.getName());
        }
        return inputs;
    }

    private void addSuggestInputs(List<Map<String, Object>> inputs, String kind, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        inputs.add(Map.of("input", text, "contexts", Map.of("kind", kind)));
        String full = PinyinUtils.toFullPinyin(text);
        if (!full.isBlank() && !full.equals(text)) {
            inputs.add(Map.of("input", full, "contexts", Map.of("kind", kind)));
        }
        String letters = PinyinUtils.toFirstLetters(text);
        if (!letters.isBlank() && !letters.equals(full)) {
            inputs.add(Map.of("input", letters, "contexts", Map.of("kind", kind)));
        }
    }

    private void putIfNotBlank(Map<String, Object> doc, String key, String value) {
        if (value != null && !value.isBlank()) {
            doc.put(key, value);
        }
    }
}
