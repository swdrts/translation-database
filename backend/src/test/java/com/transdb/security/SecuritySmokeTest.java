package com.transdb.security;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecuritySmokeTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;

    /** 测试专用受保护端点：Task 5 时业务 Controller 尚不存在，用它验证"有效令牌可访问受保护资源"。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class ProbeConfig {
        @org.springframework.web.bind.annotation.RestController
        static class ProbeController {
            @org.springframework.web.bind.annotation.GetMapping("/api/v1/test-only/authenticated")
            public String probe() {
                return "ok";
            }

            /** 方法级安全探测：验证 @PreAuthorize 拒绝经由 RestAccessDeniedHandler 返回 403/9004。 */
            @org.springframework.web.bind.annotation.GetMapping("/api/v1/test-only/admin")
            @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
            public String adminProbe() {
                return "admin-ok";
            }
        }
    }

    @Test
    void protectedEndpointWithoutTokenReturns401Code1002() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/tags", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1002");
    }

    @Test
    void actuatorHealthIsPublic() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validTokenGrantsAccess() {
        var user = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/test-only/authenticated",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<Void>(authHeaders(user)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("ok");
    }

    @Test
    void adminInitializedOnStartup() {
        assertThat(users.findByUsername("admin")).isPresent();
    }

    @Test
    void methodSecurityDenialReturns403Code9004() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/test-only/admin",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<Void>(authHeaders(editor)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }

    private org.springframework.http.HttpHeaders authHeaders(com.transdb.domain.SysUser u) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", bearer(u));
        return headers;
    }
}
