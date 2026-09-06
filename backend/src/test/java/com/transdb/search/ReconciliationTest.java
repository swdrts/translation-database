package com.transdb.search;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconciliationTest extends AbstractIntegrationTest {

    @Autowired RestClient esClient;
    @Autowired EsSyncService esSyncService;

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    @Test
    void reconcileRestoresMissingEsDocument() throws Exception {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "对账恢复测试_" + System.nanoTime();

        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, "{\"sourceText\":\"" + marker + "\",\"translatedText\":\"reconcile test\"}"),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(esClient.performRequest(new Request("HEAD", "/segments/_doc/" + id))
                        .getStatusLine().getStatusCode()).isEqualTo(200));

        // 直接删除 ES 文档，模拟漏写
        esClient.performRequest(new Request("DELETE", "/segments/_doc/" + id));

        esSyncService.reconcile(24);

        AtomicReference<String> doc = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            doc.set(EntityUtils.toString(esClient.performRequest(
                    new Request("GET", "/segments/_doc/" + id)).getEntity(), StandardCharsets.UTF_8));
            assertThat(doc.get()).contains(marker);
        });
    }
}
