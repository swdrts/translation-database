package com.transdb.search;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedSearchTest extends AbstractIntegrationTest {

    @Autowired EsSearchService esSearchService;
    private RestClient originalClient;
    private RestClient deadClient;

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    @BeforeEach
    void swapToDeadClient() {
        originalClient = (RestClient) ReflectionTestUtils.getField(esSearchService, "restClient");
        deadClient = RestClient.builder(HttpHost.create("http://127.0.0.1:1"))
                .setRequestConfigCallback(r -> r.setConnectTimeout(500).setSocketTimeout(500))
                .build();
        ReflectionTestUtils.setField(esSearchService, "restClient", deadClient);
    }

    @AfterEach
    void restoreClient() throws IOException {
        ReflectionTestUtils.setField(esSearchService, "restClient", originalClient);
        deadClient.close();
    }

    @Test
    void searchFallsBackToPgWhenEsDown() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> res = rest.exchange("/api/v1/search?q=" + "仁者", HttpMethod.GET,
                req(bearer(viewer), null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"degraded\":true");
    }

    @Test
    void facetsFallBackToPgDistinctValues() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        // 本类单独运行时 PG 为空库，先自备一条标签与一条句段（带作品名），保证 distinct 断言有意义
        String tag = "降级标签_" + System.nanoTime();
        rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"" + tag + "\"}"), String.class);
        String work = "降级作品_" + System.nanoTime();
        rest.exchange("/api/v1/segments", HttpMethod.POST, req(token,
                "{\"sourceText\":\"降级测试原文\",\"translatedText\":\"degraded facet test\","
                        + "\"workTitle\":\"" + work + "\",\"dynasty\":\"先秦\"}"), String.class);

        ResponseEntity<String> res = rest.exchange("/api/v1/facets", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) JsonPath.read(res.getBody(), "$.data.tags")).isNotEmpty();
        assertThat((java.util.List<?>) JsonPath.read(res.getBody(), "$.data.works")).isNotEmpty();
    }
}
