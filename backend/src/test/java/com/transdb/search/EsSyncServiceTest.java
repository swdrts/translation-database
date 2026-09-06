package com.transdb.search;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

class EsSyncServiceTest extends AbstractIntegrationTest {

    @Autowired RestClient esClient;

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private String esDoc(long id) throws Exception {
        Response resp = esClient.performRequest(new Request("GET", "/segments/_doc/" + id));
        return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
    }

    @Test
    void createUpdateDeletePropagateToElasticsearch() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "仁者爱人_" + System.nanoTime();

        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, "{\"sourceText\":\"" + marker + "\",\"translatedText\":\"The benevolent love others\",\"workTitle\":\"论语测试\",\"tags\":[]}"),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();

        AtomicReference<String> docBody = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            docBody.set(esDoc(id));
            assertThat(docBody.get()).contains(marker).contains("The benevolent love others").contains("论语测试");
        });

        ResponseEntity<String> updated = rest.exchange("/api/v1/segments/" + id, HttpMethod.PUT,
                req(token, "{\"sourceText\":\"" + marker + "\",\"translatedText\":\"The benevolent love all men\",\"version\":0}"),
                String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(esDoc(id)).contains("love all men"));

        ResponseEntity<String> deleted = rest.exchange("/api/v1/segments/" + id, HttpMethod.DELETE,
                req(bearer(createUser(Role.ADMIN)), null), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Response resp = esClient.performRequest(new Request("HEAD", "/segments/_doc/" + id));
            assertThat(resp.getStatusLine().getStatusCode()).isEqualTo(404);
        });
    }

    @Test
    void suggestedInputsContainPinyinVariants() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String work = "道德经测试_" + System.nanoTime();

        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, "{\"sourceText\":\"道可道\",\"translatedText\":\"The Tao\",\"workTitle\":\"" + work + "\",\"tags\":[]}"),
                String.class);
        long id = ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String doc = esDoc(id);
            assertThat(doc).contains("work");
            assertThat((java.util.List<?>) JsonPath.read(doc, "$._source.suggest[*].input"))
                    .anyMatch(v -> ((String) v).equals(work))
                    .anyMatch(v -> ((String) v).startsWith("daodejing"))
                    .anyMatch(v -> ((String) v).startsWith("ddj"));
        });
    }
}
