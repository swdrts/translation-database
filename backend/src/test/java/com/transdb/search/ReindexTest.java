package com.transdb.search;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired ReindexService reindexService;

    private void createSegment(String token, String source, String translated) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token.substring(7));
        rest.exchange("/api/v1/segments", HttpMethod.POST,
                new HttpEntity<>("{\"sourceText\":\"" + source + "\",\"translatedText\":\"" + translated + "\"}",
                        headers), String.class);
    }
}
