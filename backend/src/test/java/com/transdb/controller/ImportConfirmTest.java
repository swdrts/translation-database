package com.transdb.controller;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ImportConfirmTest extends AbstractIntegrationTest {

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                String content, String strategy) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token.substring(7));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (strategy != null) body.add("duplicateStrategy", strategy);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> upload(String token, String json, String strategy) {
        return rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(token, "corpus.json", json, strategy), String.class);
    }

    private ResponseEntity<String> confirm(String token, String previewId) {
        return rest.exchange("/api/v1/segments/import/" + previewId + "/confirm",
                HttpMethod.POST, new HttpEntity<Void>(auth(token)), String.class);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        return headers;
    }

    @Test
    void fullFlowImportThenSearchableInEs() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "导入端到端_" + System.nanoTime();
        String json = """
                [
                  {"source_text":"%s","translated_text":"imported one","work_title":"论语导入","tags":["儒家导入标签|教育"]},
                  {"source_text":"%s","translated_text":"imported two"},
                  {"source_text":"","translated_text":"bad row"}
                ]
                """.formatted(marker, marker + "_2");

        ResponseEntity<String> previewRes = upload(token, json, null);
        assertThat(previewRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String previewId = JsonPath.read(previewRes.getBody(), "$.data.previewId").toString();
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.willImportRows")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.errors.size()")).isEqualTo(1);

        ResponseEntity<String> confirmRes = confirm(token, previewId);
        assertThat(confirmRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.imported")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.failed.size()")).isEqualTo(1);
        assertThat(confirmRes.getBody()).contains("source_text 不能为空");

        // PG 落库
        ResponseEntity<String> list = rest.exchange("/api/v1/segments?q=&size=50", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(list.getBody()).contains(marker);

        // ES 可搜（导入路径显式批量同步）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<String> search = rest.exchange("/api/v1/search?q=" + marker,
                    HttpMethod.GET, new HttpEntity<Void>(auth(token)), String.class);
            assertThat(search.getBody()).contains(marker).doesNotContain("\"degraded\":true");
        });

        // 标签自动创建
        ResponseEntity<String> tags = rest.exchange("/api/v1/tags", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(tags.getBody()).contains("儒家导入标签");

        // 确认后预览被消费：重复确认 → 404/3003
        ResponseEntity<String> replay = confirm(token, previewId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(replay.getBody()).contains("\"code\":3003");
    }

    @Test
    void skipStrategySkipsDatabaseDuplicates() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String json = """
                [{"source_text":"重复跳过%s","translated_text":"dup test"}]
                """.formatted(System.nanoTime());

        String previewId = JsonPath.read(upload(token, json, null).getBody(), "$.data.previewId").toString();
        assertThat((Integer) JsonPath.read(confirm(token, previewId).getBody(), "$.data.imported")).isEqualTo(1);

        // 再导同一内容，SKIP → 全部跳过
        AtomicReference<String> secondPreview = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // 等 ES/PG 稳定后再传第二份
            secondPreview.set(JsonPath.read(upload(token, json, null).getBody(), "$.data.previewId").toString());
            assertThat(secondPreview.get()).isNotEmpty();
        });
        ResponseEntity<String> secondConfirm = confirm(token, secondPreview.get());
        assertThat((Integer) JsonPath.read(secondConfirm.getBody(), "$.data.skipped")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(secondConfirm.getBody(), "$.data.imported")).isZero();
    }

    @Test
    void overwriteStrategyUpdatesExistingSegments() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        // 两份文件共用同一 marker：source_text+translated_text 完全一致 → 同 content_hash
        String marker = "覆盖前" + System.nanoTime();
        String json1 = """
                [{"source_text":"%s","translated_text":"before overwrite","work_title":"旧书名"}]
                """.formatted(marker);
        String previewId1 = JsonPath.read(upload(token, json1, null).getBody(), "$.data.previewId").toString();
        confirm(token, previewId1);

        // source_text+translated_text 与第一份完全一致（同 content_hash → 命中库内重复 → OVERWRITE），
        // 仅改 work_title 验证覆盖生效；若连译文也改则 hash 变化、不再命中 OVERWRITE
        String json2 = """
                [{"source_text":"%s","translated_text":"before overwrite","work_title":"新书名"}]
                """.formatted(marker);
        String previewId2 = JsonPath.read(upload(token, json2, "OVERWRITE").getBody(), "$.data.previewId").toString();
        ResponseEntity<String> confirmRes = confirm(token, previewId2);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.overwritten")).isEqualTo(1);

        // 译文保持一致（hash 不变），覆盖生效体现为 work_title 更新 + version 自增
        ResponseEntity<String> list = rest.exchange("/api/v1/segments?work=新书名", HttpMethod.GET,
                new HttpEntity<Void>(auth(token)), String.class);
        assertThat(list.getBody()).contains("before overwrite").contains("新书名").contains("\"version\":1");
    }

    @Test
    void foreignUserCannotConfirmOthersPreview() {
        var editorA = createUser(Role.EDITOR);
        var editorB = createUser(Role.EDITOR);
        String json = """
                [{"source_text":"他人预览%s","translated_text":"x"}]
                """.formatted(System.nanoTime());
        String previewId = JsonPath.read(upload(bearer(editorA), json, null).getBody(), "$.data.previewId").toString();

        ResponseEntity<String> res = confirm(bearer(editorB), previewId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":3004");
    }

    @Test
    void removedPreviewConfirmedAgainReturns3003Not500() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String json = """
                [{"source_text":"并发确认防护%s","translated_text":"x"}]
                """.formatted(System.nanoTime());
        String previewId = com.jayway.jsonpath.JsonPath.read(upload(token, json, null).getBody(), "$.data.previewId").toString();
        assertThat(confirm(token, previewId).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> replay = confirm(token, previewId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(replay.getBody()).contains("\"code\":3003");
    }

    @Test
    void unknownPreviewReturns404Code3003() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = confirm(bearer(editor), "nonexistent-preview-id");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":3003");
    }
}
