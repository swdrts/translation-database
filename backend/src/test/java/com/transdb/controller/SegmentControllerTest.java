package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private String uniq(String base) {
        // 唯一后缀：所有测试类共享同一 PG 容器（tag.name 唯一约束；绝对总数断言必须限定过滤范围）
        return base + "_" + System.nanoTime();
    }

    private long createTag(String token, String name) {
        rest.exchange("/api/v1/tags", HttpMethod.POST, req(token, "{\"name\":\"" + name + "\"}"), String.class);
        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET, req(token, null), String.class);
        // JsonPath 的 [?(...)] 过滤属于 indefinite path，read 返回 JSONArray 而非标量，须在 Java 侧取首个元素
        java.util.List<?> matched = com.jayway.jsonpath.JsonPath.read(list.getBody(),
                "$.data[?(@.name=='" + name + "')]");
        return ((Number) ((java.util.Map<?, ?>) matched.get(0)).get("id")).longValue();
    }

    private ResponseEntity<String> createSegment(String token, String source, String translated,
                                                 String status, Long tagId, String dynasty) {
        String tagPart = tagId == null ? "" : ",\"tagIds\":[" + tagId + "]";
        String body = "{\"sourceText\":\"" + source + "\",\"translatedText\":\"" + translated
                + "\",\"workTitle\":\"论语测试\",\"dynasty\":\"" + dynasty + "\",\"status\":"
                + (status == null ? "null" : "\"" + status + "\"") + tagPart + "}";
        return rest.exchange("/api/v1/segments", HttpMethod.POST, req(token, body), String.class);
    }

    @Test
    void editorCreatesSegmentAndReadsItBack() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        long tagId = createTag(token, uniq("儒家"));

        ResponseEntity<String> created = createSegment(token, "学而时习之，不亦说乎？",
                "Is it not pleasant to learn and practice what one has learned?", null, tagId, uniq("先秦"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");
        assertThat(id.longValue()).isPositive();

        ResponseEntity<String> detail = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.GET, req(token, null), String.class);
        assertThat(detail.getBody()).contains("学而时习之");
        assertThat(detail.getBody()).contains("儒家_");
        // read 返回无界泛型 T，直接内联进 assertThat 会与 IntPredicate/Predicate 重载产生二义性，须先赋给具体类型
        String status = com.jayway.jsonpath.JsonPath.read(detail.getBody(), "$.data.status");
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    void blankSourceTextRejected() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = createSegment(bearer(editor), "", "x", null, null, uniq("先秦"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":9001");
    }

    @Test
    void updateWithStaleVersionReturns409() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        ResponseEntity<String> created = createSegment(token, "有朋自远方来", "Friends from afar", null, null, uniq("先秦"));
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");
        Number version = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.version");

        String body = "{\"sourceText\":\"有朋自远方来，不亦乐乎？\",\"translatedText\":\"Friends from afar\",\"version\":"
                + (version.intValue() + 5) + "}";
        ResponseEntity<String> conflict = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, body), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("\"code\":2002");

        String okBody = "{\"sourceText\":\"有朋自远方来，不亦乐乎？\",\"translatedText\":\"And is it not delightful to have friends coming from distant quarters?\",\"version\":"
                + version.intValue() + "}";
        ResponseEntity<String> ok = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, okBody), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).contains("delightful");
    }

    @Test
    void viewerSeesOnlyPublished() {
        var editor = createUser(Role.EDITOR);
        var viewer = createUser(Role.VIEWER);
        String token = bearer(editor);
        String dynasty = uniq("先秦");
        createSegment(token, "人不知而不愠", "not be displeased", "DRAFT", null, dynasty);
        createSegment(token, "吾日三省吾身", "I daily examine myself", "PUBLISHED", null, dynasty);

        // 断言限定在本测试的 dynasty 范围内（库中还有其他测试产生的数据）
        ResponseEntity<String> list = rest.exchange("/api/v1/segments?dynasty=" + dynasty, HttpMethod.GET,
                req(bearer(viewer), null), String.class);
        Number total = com.jayway.jsonpath.JsonPath.read(list.getBody(), "$.data.total");
        assertThat(total.longValue()).isEqualTo(1L);
        assertThat(list.getBody()).contains("吾日三省吾身");

        Number draftId = com.jayway.jsonpath.JsonPath.read(
                rest.exchange("/api/v1/segments?status=DRAFT&dynasty=" + dynasty, HttpMethod.GET,
                        req(token, null), String.class).getBody(),
                "$.data.content[0].id");
        ResponseEntity<String> forbidden = rest.exchange("/api/v1/segments/" + draftId.longValue(),
                HttpMethod.GET, req(bearer(viewer), null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":2003");
    }

    @Test
    void deleteRestrictedToAdmin() {
        var editor = createUser(Role.EDITOR);
        var admin = createUser(Role.ADMIN);
        String token = bearer(editor);
        ResponseEntity<String> created = createSegment(token, "温故而知新", "keep what has been taught", null, null, uniq("先秦"));
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");

        ResponseEntity<String> forbidden = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.DELETE, req(token, null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":9004");

        ResponseEntity<String> ok = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.DELETE, req(bearer(admin), null), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/segments/" + id.longValue(), HttpMethod.GET,
                req(bearer(admin), null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listFilterByDynastyAndTag() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String dynasty = uniq("先秦");
        long tagId = createTag(token, uniq("道家"));
        createSegment(token, "道可道，非常道", "The Tao that can be trodden", null, tagId, dynasty);
        createSegment(token, "学而时习之", "learn and practice", null, null, dynasty);

        ResponseEntity<String> byDynasty = rest.exchange("/api/v1/segments?dynasty=" + dynasty, HttpMethod.GET,
                req(token, null), String.class);
        Number byDynastyTotal = com.jayway.jsonpath.JsonPath.read(byDynasty.getBody(), "$.data.total");
        assertThat(byDynastyTotal.longValue()).isEqualTo(2L);

        ResponseEntity<String> byTag = rest.exchange("/api/v1/segments?tagId=" + tagId, HttpMethod.GET,
                req(token, null), String.class);
        Number byTagTotal = com.jayway.jsonpath.JsonPath.read(byTag.getBody(), "$.data.total");
        assertThat(byTagTotal.longValue()).isEqualTo(1L);
        assertThat(byTag.getBody()).contains("道可道");
    }

    @Test
    void updateWithoutStatusKeepsDraftState() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String dynasty = uniq("先秦");
        ResponseEntity<String> created = createSegment(token, "知之为知之", "know what you know", "DRAFT", null, dynasty);
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");
        Number version = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.version");

        String body = "{\"sourceText\":\"知之为知之，不知为不知，是知也\",\"translatedText\":\"know what you know and know what you do not\",\"version\":"
                + version.intValue() + "}";
        ResponseEntity<String> ok = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, body), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        // read 返回无界泛型 T，直接内联进 assertThat 会与 IntPredicate/Predicate 重载产生二义性，须先赋给具体类型（同上）
        String keptStatus = com.jayway.jsonpath.JsonPath.read(ok.getBody(), "$.data.status");
        assertThat(keptStatus).isEqualTo("DRAFT");

        String publishBody = "{\"sourceText\":\"知之为知之，不知为不知，是知也\",\"translatedText\":\"know what you know and know what you do not\",\"version\":"
                + (version.intValue() + 1) + ",\"status\":\"PUBLISHED\"}";
        ResponseEntity<String> published = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, publishBody), String.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        String publishedStatus = com.jayway.jsonpath.JsonPath.read(published.getBody(), "$.data.status");
        assertThat(publishedStatus).isEqualTo("PUBLISHED");
    }
}
