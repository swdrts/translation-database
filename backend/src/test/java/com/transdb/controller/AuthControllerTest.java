package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> json(Object token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("Authorization", (String) token);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void loginAsBuiltInAdminReturnsToken() {
        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"admin\",\"password\":\"admin123\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"code\":0");
        assertThat(res.getBody()).contains("\"token\":");
        assertThat(res.getBody()).contains("\"role\":\"ADMIN\"");
    }

    @Test
    void wrongPasswordReturns1001() {
        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"admin\",\"password\":\"nope\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1001");
    }

    @Test
    void disabledUserLoginReturns1004() {
        var u = createUser(Role.EDITOR);
        u.setStatus(com.transdb.domain.UserStatus.DISABLED);
        userRepository.save(u);

        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"" + u.getUsername() + "\",\"password\":\"password123\"}"),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1004");
    }

    @Test
    void meReturnsCurrentUser() {
        var u = createUser(Role.VIEWER);
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/me",
                org.springframework.http.HttpMethod.GET, json(bearer(u), null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(u.getUsername());
        assertThat(res.getBody()).contains("\"role\":\"VIEWER\"");
    }

    @Test
    void meWithoutTokenReturns401() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/auth/me", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1002");
    }

    @Test
    void invalidTokenOnMeReturns401Code1002() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-valid-token");
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/me",
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1002");
    }
}
