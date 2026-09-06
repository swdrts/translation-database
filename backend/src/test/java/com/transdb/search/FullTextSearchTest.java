package com.transdb.search;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FullTextSearchTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private long createTag(String token, String name) {
        rest.exchange("/api/v1/tags", HttpMethod.POST, req(token, "{\"name\":\"" + name + "\"}"), String.class);
        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET, req(token, null), String.class);
        java.util.List<?> matched = com.jayway.jsonpath.JsonPath.read(list.getBody(),
                "$.data[?(@.name=='" + name + "')]");
        return ((Number) ((java.util.Map<?, ?>) matched.get(0)).get("id")).longValue();
    }

    private long createSegment(String token, String source, String translated, String work,
                               String dynasty, Long tagId, String status) {
        String tagPart = tagId == null ? "" : ",\"tagIds\":[" + tagId + "]";
        String body = "{\"sourceText\":\"" + source + "\",\"translatedText\":\"" + translated
                + "\",\"workTitle\":\"" + work + "\",\"dynasty\":\"" + dynasty + "\",\"status\":"
                + (status == null ? "null" : "\"" + status + "\"") + tagPart + "}";
        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, body), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();
    }

    private ResponseEntity<String> search(String token, String query) {
        return rest.exchange("/api/v1/search" + query, HttpMethod.GET, req(token, null), String.class);
    }

    @Test
    void chineseWordSegmentationFindsSource() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String unique = "温故而知新可以为师矣" + System.nanoTime();
        createSegment(token, unique, "review the old and know the new", "论语测试", "先秦", null, null);

        AtomicReference<Integer> total = new AtomicReference<>(0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=" + unique.substring(0, 4));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).doesNotContain("\"degraded\":true");
            total.set(JsonPath.read(res.getBody(), "$.data.total"));
            assertThat(total.get()).isGreaterThanOrEqualTo(1);
            assertThat(res.getBody()).contains(unique);
        });
    }

    @Test
    void englishTypoToleranceHitsTranslation() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "pleasant" + System.nanoTime();
        createSegment(token, "英文容错测试" + marker, "Is it not pleasant to learn", "论语测试", "先秦", null, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=pleasa");
            assertThat(res.getBody()).contains(marker);
        });
    }

    /**
     * 隔离验证 fuzziness（不被拼音子句掩盖）：field=translation 时 multi_match 仅含
     * translated_text、拼音 phrase_prefix 子句为空，"pleasannt"（与 pleasant 编辑距离 1）
     * 只能靠 translated_text 上的 fuzziness=AUTO 命中。
     */
    @Test
    void pureFuzzyMatchWithoutPinyinAssist() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        // 中文原文 + 纯英文译文：field=translation 时拼音子句不参与，只有 fuzziness 能命中
        createSegment(token, "纯容错通道" + System.nanoTime(), "The benevolent is pleasant and calm",
                "论语测试", "先秦", null, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?field=translation&q=pleasannt");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).doesNotContain("\"degraded\":true");
            assertThat(((Number) JsonPath.read(res.getBody(), "$.data.total")).intValue())
                    .isGreaterThanOrEqualTo(1);
            assertThat(res.getBody()).contains("The benevolent is pleasant and calm");
        });
    }

    @Test
    void pinyinAndFirstLetterHitWorkTitle() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String work = "中庸测试" + System.nanoTime();
        createSegment(token, "天命之谓性", "What Heaven confers is called nature", work, "先秦", null, null);

        // 全拼
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=zhongyong");
            assertThat(res.getBody()).contains(work);
        });
        // 首字母
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=zy");
            assertThat(res.getBody()).contains(work);
        });
    }

    @Test
    void englishQueryFindsChineseSource() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "双侧测试" + System.nanoTime();
        createSegment(token, marker, "Heaven does not speak", "论语测试", "先秦", null, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=speak");
            assertThat(res.getBody()).contains(marker);
        });
    }

    @Test
    void filtersAndHighlightWork() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String tag = "儒家_" + System.nanoTime();
        long tagId = createTag(token, tag);
        String source = "克己复礼为仁" + System.nanoTime();
        createSegment(token, source, "restrain yourself and return to ritual", "论语测试", "先秦", tagId, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?q=克己&tags=" + tag + "&dynasty=先秦&work=论语测试");
            assertThat(res.getBody()).contains(source);
            assertThat(res.getBody()).contains("<em>");
            assertThat((List<Integer>) JsonPath.read(res.getBody(), "$.data.facets.tags[*].count"))
                    .isNotEmpty();
        });
    }

    @Test
    void viewerSeesOnlyPublishedInSearch() {
        var editor = createUser(Role.EDITOR);
        var viewer = createUser(Role.VIEWER);
        String token = bearer(editor);
        String draftMarker = "搜索草稿隐藏" + System.nanoTime();
        String publishedMarker = "搜索已发布" + System.nanoTime();
        createSegment(token, draftMarker, "draft", "论语测试", "先秦", null, "DRAFT");
        createSegment(token, publishedMarker, "published", "论语测试", "先秦", null, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(bearer(viewer), "?q=搜索草稿隐藏 OR 搜索已发布 OR " + publishedMarker);
            assertThat(res.getBody()).doesNotContain(draftMarker);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(bearer(viewer), "?q=" + publishedMarker);
            assertThat(res.getBody()).contains(publishedMarker);
        });
    }

    @Test
    void emptyQueryBrowsesByUpdatedDesc() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        createSegment(token, "浏览测试一" + System.nanoTime(), "b1", "论语测试", "先秦", null, null);
        createSegment(token, "浏览测试二" + System.nanoTime(), "b2", "论语测试", "先秦", null, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = search(token, "?size=5");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<String>) JsonPath.read(res.getBody(), "$.data.content[*].sourceText"))
                    .isNotEmpty();
        });
    }
}
