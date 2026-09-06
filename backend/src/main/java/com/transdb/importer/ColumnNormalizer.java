package com.transdb.importer;

import java.util.Locale;

public final class ColumnNormalizer {

    private ColumnNormalizer() {
    }

    /** 表头/JSON 键 → 规范列名：trim、小写、空格/连字符转下划线、camelCase 转 snake_case。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace(' ', '_').replace('-', '_');
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return s.toLowerCase(Locale.ROOT);
    }
}
