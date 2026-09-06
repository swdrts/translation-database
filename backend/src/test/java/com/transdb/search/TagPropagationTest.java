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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TagPropagationTest extends AbstractIntegrationTest {

    @Autowired RestClient esClient;

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

    @Test
    void tagRenameUpdatesIndexedSegments() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String oldName = "旧标签_" + System.nanoTime();
        String newName = "新标签_" + System.nanoTime();
        long tagId = createTag(token, oldName);

        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, "{\"sourceText\":\"tag propagation test\",\"translatedText\":\"test\",\"tagIds\":[" + tagId + "]}"),
                String.class);
        long segmentId = ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();

        AtomicReference<String> doc = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            doc.set(EntityUtils.toString(esClient.performRequest(
                    new Request("GET", "/segments/_doc/" + segmentId)).getEntity(), StandardCharsets.UTF_8));
            assertThat((java.util.List<String>) JsonPath.read(doc.get(), "$._source.tags")).contains(oldName);
        });

        ResponseEntity<String> renamed = rest.exchange("/api/v1/tags/" + tagId, HttpMethod.PUT,
                req(token, "{\"name\":\"" + newName + "\"}"), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            doc.set(EntityUtils.toString(esClient.performRequest(
                    new Request("GET", "/segments/_doc/" + segmentId)).getEntity(), StandardCharsets.UTF_8));
            assertThat((java.util.List<String>) JsonPath.read(doc.get(), "$._source.tags")).contains(newName).doesNotContain(oldName);
        });
    }

    @Test
    void tagDeleteRemovesTagFromIndexedSegments() {
        var editor = createUser(Role.EDITOR);
        var admin = createUser(Role.ADMIN);
        String token = bearer(editor);
        String name = "将删标签_" + System.nanoTime();
        long tagId = createTag(token, name);

        ResponseEntity<String> created = rest.exchange("/api/v1/segments", HttpMethod.POST,
                req(token, "{\"sourceText\":\"tag delete test\",\"translatedText\":\"test\",\"tagIds\":[" + tagId + "]}"),
                String.class);
        long segmentId = ((Number) JsonPath.read(created.getBody(), "$.data.id")).longValue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String doc = EntityUtils.toString(esClient.performRequest(
                    new Request("GET", "/segments/_doc/" + segmentId)).getEntity(), StandardCharsets.UTF_8);
            assertThat((java.util.List<String>) JsonPath.read(doc, "$._source.tags")).contains(name);
        });

        ResponseEntity<String> deleted = rest.exchange("/api/v1/tags/" + tagId, HttpMethod.DELETE,
                req(bearer(admin), null), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String doc = EntityUtils.toString(esClient.performRequest(
                    new Request("GET", "/segments/_doc/" + segmentId)).getEntity(), StandardCharsets.UTF_8);
            assertThat((java.util.List<String>) JsonPath.read(doc, "$._source.tags")).doesNotContain(name);
        });
    }
}
