package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImportControllerTest extends AbstractIntegrationTest {

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                String content, String strategy) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token.substring(7));
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", file);
        if (strategy != null) {
            body.add("duplicateStrategy", strategy);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    void editorUploadsJsonPreview() {
        var editor = createUser(Role.EDITOR);
        String json = """
                [{"source_text":"导入预览测试%s","translated_text":"preview","tags":["儒家"]}]
                """.formatted(System.nanoTime());
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "corpus.json", json, null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"previewId\"");
        assertThat(res.getBody()).contains("\"willImportRows\":1");
        assertThat(res.getBody()).contains("\"strategy\":\"SKIP\"");
    }

    @Test
    void blankFileRejected3005() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "empty.json", "[]", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":3005");
    }

    @Test
    void unsupportedExtensionRejected3001() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(editor), "corpus.txt", "hello", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":3001");
    }

    @Test
    void viewerForbidden9004AndAnonymous401() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> viewerRes = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(bearer(viewer), "a.json", "[]", null), String.class);
        assertThat(viewerRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewerRes.getBody()).contains("\"code\":9004");

        ResponseEntity<String> anon = rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(null, "a.json", "[]", null), String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anon.getBody()).contains("\"code\":1002");
    }
}
