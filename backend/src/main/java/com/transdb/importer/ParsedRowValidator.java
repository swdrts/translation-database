package com.transdb.importer;

import java.util.ArrayList;
import java.util.List;

public final class ParsedRowValidator {

    private ParsedRowValidator() {
    }

    public static List<String> validate(ParsedRow row) {
        return validate(row, true);
    }

    /** requireTranslation=false 用于整本书导入：只有原文、译文留空待补。 */
    public static List<String> validate(ParsedRow row, boolean requireTranslation) {
        List<String> errors = new ArrayList<>();
        requireNonBlank(row, "source_text", errors);
        if (requireTranslation) {
            requireNonBlank(row, "translated_text", errors);
        }
        requireMaxLen(row, "work_title", 255, errors);
        requireMaxLen(row, "chapter", 255, errors);
        requireMaxLen(row, "author", 255, errors);
        requireMaxLen(row, "translator", 255, errors);
        requireMaxLen(row, "dynasty", 64, errors);
        String tags = row.get("tags");
        if (tags != null && !tags.isBlank()) {
            for (String tag : tags.split("\\|")) {
                String t = tag.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.length() > 64) {
                    errors.add("tags 标签超长（≤64）: " + t);
                }
            }
        }
        return errors;
    }

    private static void requireNonBlank(ParsedRow row, String column, List<String> errors) {
        String v = row.get(column);
        if (v == null || v.isBlank()) {
            errors.add(column + " 不能为空");
        }
    }

    private static void requireMaxLen(ParsedRow row, String column, int max, List<String> errors) {
        String v = row.get(column);
        if (v != null && v.length() > max) {
            errors.add(column + " 超长（≤" + max + "）");
        }
    }
}
