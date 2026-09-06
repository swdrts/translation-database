package com.transdb.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ReindexTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(headers);
    }

    @Test
    void nonAdminCannotTriggerReindex() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/admin/reindex", HttpMethod.POST,
                req(bearer(editor)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }

    @Test
    void reindexSwitchesAliasAndDataRemainsSearchable() {
        var admin = createUser(Role.ADMIN);
        String token = bearer(admin);
        String marker = "重建测试_" + System.nanoTime();
        createSegment(token, marker, "reindex test");

        AtomicReference<String> status = new AtomicReference<>();
        rest.exchange("/api/v1/admin/reindex", HttpMethod.POST, req(token), String.class);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<String> res = rest.exchange("/api/v1/admin/reindex/status",
                    HttpMethod.GET, req(token), String.class);
            status.set(res.getBody());
            assertThat((String) JsonPath.read(status.get(), "$.data.state")).isEqualTo("DONE");
        });
        assertThat(((Number) JsonPath.read(status.get(), "$.data.indexed")).longValue()).isGreaterThanOrEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = rest.exchange("/api/v1/search?q=" + marker,
                    HttpMethod.GET, req(token), String.class);
            assertThat(res.getBody()).contains(marker).doesNotContain("\"degraded\":true");
        });
    }

    @Test
    void concurrentReindexReturns409Code4001() {
        var admin = createUser(Role.ADMIN);
        ReflectionTestUtils.setField(reindexService, "state",
                new ReindexService.ReindexState("RUNNING", 0, 0, null, null, null));
        try {
            ResponseEntity<String> res = rest.exchange("/api/v1/admin/reindex", HttpMethod.POST,
                    req(bearer(admin)), String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody()).contains("\"code\":4001");
        } finally {
            ReflectionTestUtils.setField(reindexService, "state",
                    new ReindexService.ReindexState("IDLE", 0, 0, null, null, null));
        }
    }

    /**
     * bulk 写入前的处理阶段失败（此处用 writeValueAsString 即抛的 ObjectMapper 确定性触发）时，
     * 重建必须进入 FAILED，且别名尚未切换——已创建的孤儿 segments_reindex_* 索引应被尽力清理。
     * 索引创建/别名/删除仍走真实 ES。
     */
    @Test
    void reindexMarksFailedAndCleansUpOrphanIndexOnProcessingFailure() throws Exception {
        var admin = createUser(Role.ADMIN);
        String token = bearer(admin);

        RestClient real = (RestClient) ReflectionTestUtils.getField(reindexService, "restClient");
        ObjectMapper realMapper = (ObjectMapper) ReflectionTestUtils.getField(reindexService, "objectMapper");
        ObjectMapper poisoned = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new IllegalStateException("模拟序列化失败");
            }
        };
        // 库中须至少有一条数据，处理阶段才会执行到被投毒的序列化（空库会在空批处直接完成）
        createSegment(token, "孤儿索引测试_" + System.nanoTime(), "orphan cleanup");
        ReflectionTestUtils.setField(reindexService, "objectMapper", poisoned);
        try {
            Set<String> before = reindexIndices(real);
            rest.exchange("/api/v1/admin/reindex", HttpMethod.POST, req(token), String.class);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ResponseEntity<String> res = rest.exchange("/api/v1/admin/reindex/status",
                        HttpMethod.GET, req(token), String.class);
                assertThat((String) JsonPath.read(res.getBody(), "$.data.state")).isEqualTo("FAILED");
                assertThat((String) JsonPath.read(res.getBody(), "$.data.error")).contains("模拟序列化失败");
            });
            // 失败发生在别名切换前：本次创建的孤儿 segments_reindex_* 索引应被清理
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(reindexIndices(real)).isEqualTo(before));
        } finally {
            ReflectionTestUtils.setField(reindexService, "objectMapper", realMapper);
            ReflectionTestUtils.setField(reindexService, "state",
                    new ReindexService.ReindexState("IDLE", 0, 0, null, null, null));
        }
    }

    /** 别名缺失（启动时 ES 不可用的场景）不得让 reindex 直接 FAILED：应视为 0 个旧索引，照常建索引并绑定别名自愈。 */
    @Test
    void reindexRecoversWhenAliasMissing() {
        var admin = createUser(Role.ADMIN);
        String token = bearer(admin);
        String marker = "别名缺失自愈_" + System.nanoTime();
        createSegment(token, marker, "alias recovery test");

        // 删除别名的全部具体索引（别名随之消失，模拟"启动时 ES 不可用"的场景）；
        // 数据丢失是预期内的（模拟场景），reindex 会从 PG 全量重建
        List<String> indices = esIndexAdminService.currentAliasIndices();
        assertThat(indices).isNotEmpty();
        for (String idx : indices) {
            esIndexAdminService.deleteIndex(idx);
        }

        rest.exchange("/api/v1/admin/reindex", HttpMethod.POST, req(token), String.class);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<String> res = rest.exchange("/api/v1/admin/reindex/status",
                    HttpMethod.GET, req(token), String.class);
            assertThat((String) JsonPath.read(res.getBody(), "$.data.state")).isEqualTo("DONE");
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> res = rest.exchange("/api/v1/search?q=" + marker,
                    HttpMethod.GET, req(token), String.class);
            assertThat(res.getBody()).contains(marker).doesNotContain("\"degraded\":true");
        });
    }

    /** /_bulk 返回 HTTP 200 但 errors=true 时逐项失败必须被识别并计数，errors=false 不得误报。 */
    @Test
    void bulkItemErrorsAreDetectedAndReported() throws Exception {
        var mapper = new ObjectMapper();
        String errorsJson = "{\"errors\":true,\"items\":["
                + "{\"index\":{\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\","
                + "\"reason\":\"模拟映射错误\"}}},"
                + "{\"index\":{\"_id\":\"2\",\"status\":201}}]}";
        assertThatThrownBy(() -> ReindexService.requireNoBulkErrors(mapper.readTree(errorsJson), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bulk 部分失败")
                .hasMessageContaining("failed=1")
                .hasMessageContaining("mapper_parsing_exception");
        String okJson = "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"1\",\"status\":201}}]}";
        assertThatCode(() -> ReindexService.requireNoBulkErrors(mapper.readTree(okJson), 1))
                .doesNotThrowAnyException();
    }

    @Autowired ReindexService reindexService;
    @Autowired EsIndexAdminService esIndexAdminService;

    private Set<String> reindexIndices(RestClient client) throws Exception {
        Set<String> names = new HashSet<>();
        try {
            Response res = client.performRequest(
                    new Request("GET", "/_cat/indices/segments_reindex_*?format=json&h=index"));
            String body = EntityUtils.toString(res.getEntity(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                for (Object o : (List<?>) JsonPath.read(body, "$[*].index")) {
                    names.add((String) o);
                }
            }
        } catch (ResponseException e) {
            // 通配符无匹配时视为空集合
        }
        return names;
    }

    private void createSegment(String token, String source, String translated) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token.substring(7));
        rest.exchange("/api/v1/segments", HttpMethod.POST,
                new HttpEntity<>("{\"sourceText\":\"" + source + "\",\"translatedText\":\"" + translated + "\"}",
                        headers), String.class);
    }
}
