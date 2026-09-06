package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private String adminToken() {
        return bearer(users.findByUsername("admin").orElseThrow());
    }

    @Test
    void adminCreatesAndListsUsers() {
        String token = adminToken();
        String username = "alice_" + System.currentTimeMillis();

        ResponseEntity<String> created = rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"displayName\":\"爱丽丝\",\"role\":\"EDITOR\"}"),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 共享 PG 容器下全套件累计用户已超默认页大小 20（id 升序，新建用户在最后一页），
        // 用 size=100 保证新建用户落在本页内
        ResponseEntity<String> list = rest.exchange("/api/v1/users?page=0&size=100", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(list.getBody()).contains(username);
        assertThat(list.getBody()).contains("\"total\":");
    }

    @Test
    void duplicateUsernameReturns5001() {
        String token = adminToken();
        String username = "bob_" + System.currentTimeMillis();
        rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"EDITOR\"}"),
                String.class);
        ResponseEntity<String> dup = rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"VIEWER\"}"),
                String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("\"code\":5001");
    }

    @Test
    void disableUserBlocksLogin() {
        String token = adminToken();
        String username = "carol_" + System.currentTimeMillis();
        rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"EDITOR\"}"),
                String.class);
        long id = users.findByUsername(username).orElseThrow().getId();

        ResponseEntity<String> disabled = rest.exchange("/api/v1/users/" + id, HttpMethod.PUT,
                req(token, "{\"status\":\"DISABLED\"}"), String.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> login = rest.postForEntity("/api/v1/auth/login",
                req(null, "{\"username\":\"" + username + "\",\"password\":\"secret66\"}"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody()).contains("\"code\":1004");
    }

    @Test
    void adminCannotDisableSelf() {
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        ResponseEntity<String> res = rest.exchange("/api/v1/users/" + adminId, HttpMethod.PUT,
                req(adminToken(), "{\"status\":\"DISABLED\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":5005");
    }

    @Test
    void nonAdminAccessDenied() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/users", HttpMethod.GET,
                req(bearer(editor), null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }
}
