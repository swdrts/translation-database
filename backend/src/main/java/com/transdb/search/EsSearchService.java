package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Role;
import com.transdb.dto.*;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsSearchService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final EsProperties esProperties;
    private final PgSearchFallbackService pgFallback;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMs = 0;
    private static final int BREAKER_THRESHOLD = 3;
    private static final long BREAKER_OPEN_MS = 30_000;

    public SearchResponseVO search(SearchQueryParams p, LoginUser operator) {
        if (!esProperties.enabled() || !esAvailable()) {
            return pgFallback.search(p, operator);
        }
        try {
            int size = Math.min(Math.max(p.size(), 1), 50);
            int page = Math.max(p.page(), 0);
            if ((long) page * size + size > 1000) {
                throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "分页过深：page*size 不得超过 1000");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("query", buildQuery(p, operator));
            body.put("from", page * size);
            body.put("size", size);
            if (p.q() == null || p.q().isBlank()) {
                body.put("sort", List.of(Map.of("updated_at", "desc")));
            }
            body.put("highlight", Map.of(
                    "pre_tags", List.of("<em>"), "post_tags", List.of("</em>"),
                    "fields", Map.of(
                            "source_text", Map.of("fragment_size", 100, "number_of_fragments", 2),
                            "translated_text", Map.of("fragment_size", 120, "number_of_fragments", 2))));
            body.put("aggs", Map.of(
                    "tags", Map.of("terms", Map.of("field", "tags", "size", 50)),
                    "dynasties", Map.of("terms", Map.of("field", "dynasty", "size", 50)),
                    "works", Map.of("terms", Map.of("field", "work_title.raw", "size", 100))));

            JsonNode root = call("GET", "/segments/_search", body);
            consecutiveFailures.set(0);

            List<SearchItemVO> items = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                items.add(parseItem(hit));
            }
            long total = root.path("hits").path("total").path("value").asLong();
            return new SearchResponseVO(items, total, page, size, false, parseFacets(root));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            recordFailure();
            log.warn("ES 搜索失败，降级 PG: {}", e.getMessage());
            return pgFallback.search(p, operator);
        }
    }

    public FacetsVO facets(LoginUser operator) {
        if (!esProperties.enabled() || !esAvailable()) {
            return pgFallback.facets();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("size", 0);
            body.put("query", buildQuery(new SearchQueryParams(null, null, null, null, null, 0, 1), operator));
            body.put("aggs", Map.of(
                    "tags", Map.of("terms", Map.of("field", "tags", "size", 50)),
                    "dynasties", Map.of("terms", Map.of("field", "dynasty", "size", 50)),
                    "works", Map.of("terms", Map.of("field", "work_title.raw", "size", 100))));
            JsonNode root = call("GET", "/segments/_search", body);
            consecutiveFailures.set(0);
            return parseFacets(root);
        } catch (Exception e) {
            recordFailure();
            log.warn("ES facets 失败，降级 PG: {}", e.getMessage());
            return pgFallback.facets();
        }
    }

    public SuggestVO suggest(String q, LoginUser operator) {
        if (q == null || q.isBlank()) {
            return new SuggestVO(List.of(), List.of(), List.of());
        }
        if (!esProperties.enabled() || !esAvailable()) {
            return new SuggestVO(List.of(), List.of(), List.of());
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("size", 0);
            Map<String, Object> completion = new LinkedHashMap<>();
            completion.put("field", "suggest");
            completion.put("size", 5);
            completion.put("skip_duplicates", true);
            Map<String, Object> suggesterBase = Map.of("prefix", q, "completion", completion);
            body.put("suggest", Map.of(
                    "works", withContext(suggesterBase, "work"),
                    "authors", withContext(suggesterBase, "author"),
                    "tags", withContext(suggesterBase, "tag")));
            JsonNode root = call("GET", "/segments/_search", body);
            consecutiveFailures.set(0);
            return new SuggestVO(
                    options(root, "works"), options(root, "authors"), options(root, "tags"));
        } catch (Exception e) {
            recordFailure();
            log.warn("ES suggest 失败: {}", e.getMessage());
            return new SuggestVO(List.of(), List.of(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withContext(Map<String, Object> base, String kind) {
        Map<String, Object> completion = new LinkedHashMap<>((Map<String, Object>) base.get("completion"));
        completion.put("contexts", Map.of("kind", List.of(kind)));
        return Map.of("prefix", base.get("prefix"), "completion", completion);
    }

    private List<String> options(JsonNode root, String name) {
        List<String> result = new ArrayList<>();
        for (JsonNode opt : root.path("suggest").path(name).path(0).path("options")) {
            result.add(opt.path("text").asText());
        }
        return result;
    }

    private Map<String, Object> buildQuery(SearchQueryParams p, LoginUser operator) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (operator.role() == Role.VIEWER) {
            filters.add(Map.of("term", Map.of("status", "PUBLISHED")));
        }
        if (p.tags() != null && !p.tags().isEmpty()) {
            filters.add(Map.of("terms", Map.of("tags", p.tags())));
        }
        if (p.dynasty() != null && !p.dynasty().isBlank()) {
            filters.add(Map.of("term", Map.of("dynasty", p.dynasty())));
        }
        if (p.work() != null && !p.work().isBlank()) {
            filters.add(Map.of("term", Map.of("work_title.raw", p.work())));
        }
        Map<String, Object> bool = new HashMap<>();
        if (p.q() != null && !p.q().isBlank()) {
            List<Object> should = new ArrayList<>();
            should.add(Map.of("multi_match", Map.of(
                    "query", p.q(), "fields", fieldsFor(p.field()), "fuzziness", "AUTO")));
            // 拼音前缀检索：term 级 multi_match 无法命中拼音子字段的 joined/首字母 token
            //（zhongyong → 索引 token "zhongyongceshi"、zy → "zycs…"，均为前缀而非等值），
            // 以 phrase_prefix 并行兜底，minimum_should_match=1 取两者之或
            List<String> pinyinFields = pinyinPrefixFieldsFor(p.field());
            if (!pinyinFields.isEmpty()) {
                should.add(Map.of("multi_match", Map.of(
                        "query", p.q(), "fields", pinyinFields, "type", "phrase_prefix")));
            }
            bool.put("should", should);
            bool.put("minimum_should_match", 1);
        }
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }
        return bool.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", bool);
    }

    private List<String> fieldsFor(String field) {
        String f = field == null ? "all" : field;
        return switch (f) {
            case "source" -> List.of("source_text^3", "source_text.pinyin");
            case "translation" -> List.of("translated_text^2");
            default -> List.of("source_text^3", "source_text.pinyin", "translated_text^2",
                    "work_title^2", "work_title.pinyin", "author", "translator", "notes");
        };
    }

    /** phrase_prefix 兜底用的拼音子字段（译文无拼音子字段，field=translation 时为空）。 */
    private List<String> pinyinPrefixFieldsFor(String field) {
        String f = field == null ? "all" : field;
        return switch (f) {
            case "source" -> List.of("source_text.pinyin");
            case "translation" -> List.of();
            default -> List.of("source_text.pinyin", "work_title.pinyin");
        };
    }

    private SearchItemVO parseItem(JsonNode hit) {
        JsonNode source = hit.path("_source");
        List<String> tags = new ArrayList<>();
        source.path("tags").forEach(t -> tags.add(t.asText()));
        Map<String, List<String>> highlight = new HashMap<>();
        hit.path("highlight").fields().forEachRemaining(e -> {
            List<String> fragments = new ArrayList<>();
            e.getValue().forEach(f -> fragments.add(f.asText()));
            highlight.put(e.getKey(), fragments);
        });
        return new SearchItemVO(
                Long.parseLong(source.path("segment_id").asText("0")),
                source.path("source_text").asText(""),
                source.path("translated_text").asText(""),
                source.path("work_title").asText(null),
                source.path("chapter").asText(null),
                source.path("author").asText(null),
                source.path("dynasty").asText(null),
                source.path("translator").asText(null),
                tags, highlight, hit.path("_score").asDouble(0));
    }

    private FacetsVO parseFacets(JsonNode root) {
        return new FacetsVO(
                buckets(root, "tags"), buckets(root, "dynasties"), buckets(root, "works"));
    }

    private List<FacetItem> buckets(JsonNode root, String name) {
        List<FacetItem> items = new ArrayList<>();
        for (JsonNode b : root.path("aggregations").path(name).path("buckets")) {
            items.add(new FacetItem(b.path("key").asText(), b.path("doc_count").asLong()));
        }
        return items;
    }

    private JsonNode call(String method, String path, Map<String, Object> body) {
        try {
            Request request = new Request(method, path);
            request.setJsonEntity(objectMapper.writeValueAsString(body));
            Response response = restClient.performRequest(request);
            return objectMapper.readTree(response.getEntity().getContent());
        } catch (Exception e) {
            throw new IllegalStateException("ES 调用失败: " + path, e);
        }
    }

    private boolean esAvailable() {
        return consecutiveFailures.get() < BREAKER_THRESHOLD
                || System.currentTimeMillis() >= openUntilMs;
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= BREAKER_THRESHOLD) {
            openUntilMs = System.currentTimeMillis() + BREAKER_OPEN_MS;
        }
    }
}
