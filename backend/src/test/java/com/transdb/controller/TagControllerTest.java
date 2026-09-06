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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TagControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private String uniq(String base) {
        // 唯一名：所有测试类共享同一 PG 容器，tag.name 有唯一约束
        return base + "_" + System.nanoTime();
    }

    @Test
    void editorCanCreateAndListTags() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String name = uniq("儒家");

        ResponseEntity<String> created = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"" + name + "\",\"description\":\"儒家经典\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).contains("\"code\":0");

        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(list.getBody()).contains(name);
    }

    @Test
    void duplicateTagNameReturns5002() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String name = uniq("道家");
        rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"" + name + "\"}"), String.class);

        ResponseEntity<String> dup = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"" + name + "\"}"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("\"code\":5002");
    }

    @Test
    void editorCanUpdateTag() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String name = uniq("佛家");
        rest.exchange("/api/v1/tags", HttpMethod.POST, req(token, "{\"name\":\"" + name + "\"}"), String.class);
        long id = listIdByName(token, name);

        ResponseEntity<String> updated = rest.exchange("/api/v1/tags/" + id, HttpMethod.PUT,
                req(token, "{\"name\":\"" + name + "\",\"description\":\"更新后的描述\"}"), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void editorCannotDeleteTagButAdminCan() {
        var editor = createUser(Role.EDITOR);
        var admin = createUser(Role.ADMIN);
        String name = uniq("兵家");
        rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(bearer(editor), "{\"name\":\"" + name + "\"}"), String.class);
        long id = listIdByName(bearer(editor), name);

        ResponseEntity<String> forbidden = rest.exchange("/api/v1/tags/" + id, HttpMethod.DELETE,
                req(bearer(editor), null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":9004");

        ResponseEntity<String> ok = rest.exchange("/api/v1/tags/" + id, HttpMethod.DELETE,
                req(bearer(admin), null), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void viewerCannotCreateTag() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> res = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(bearer(viewer), "{\"name\":\"" + uniq("墨家") + "\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }

    private long listIdByName(String token, String name) {
        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET,
                req(token, null), String.class);
        // JsonPath 的 [?(...)] 过滤属于 indefinite path，read 返回 JSONArray 而非标量，须在 Java 侧取首个元素
        List<?> matched = com.jayway.jsonpath.JsonPath.read(list.getBody(),
                "$.data[?(@.name=='" + name + "')]");
        return ((Number) ((java.util.Map<?, ?>) matched.get(0)).get("id")).longValue();
    }
}
