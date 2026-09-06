package com.transdb;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ScaffoldSmokeTest extends AbstractIntegrationTest {

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }
}
