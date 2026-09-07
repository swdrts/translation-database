package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;

public final class BulkResponseGuard {

    private BulkResponseGuard() {
    }

    /** ES /_bulk 对单项失败返回 HTTP 200 + errors:true——必须显式校验，否则静默丢文档。 */
    public static void requireNoErrors(JsonNode bulkResult, int expectedDocs) {
        if (bulkResult.path("errors").asBoolean(false)) {
            int failed = 0;
            String firstError = "";
            for (JsonNode item : bulkResult.path("items")) {
                JsonNode err = item.path("index").path("error");
                if (err.isObject()) {
                    failed++;
                    if (firstError.isEmpty()) {
                        firstError = err.path("type").asText();
                    }
                }
            }
            throw new IllegalStateException("bulk 部分失败：items=" + expectedDocs
                    + " failed=" + failed + " firstError=" + firstError);
        }
    }
}
