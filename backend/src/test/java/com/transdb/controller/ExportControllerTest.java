package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.common.ContentHash;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.domain.SysUser;
import com.transdb.repository.SegmentRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportControllerTest extends AbstractIntegrationTest {

    @Autowired
    private SegmentRepository segmentRepository;

    private final String work = "导出测试书" + System.nanoTime();

    private void seg(SysUser owner, String src, String dst, String chapter) {
        Segment s = new Segment();
        s.setSourceText(src);
        s.setTranslatedText(dst == null ? "" : dst);
        s.setWorkTitle(work);
        s.setChapter(chapter);
        s.setStatus(SegmentStatus.DRAFT);
        s.setCreatedBy(owner);
        s.setContentHash(ContentHash.sha256(src, dst == null ? "" : dst));
        segmentRepository.saveAndFlush(s);
    }

    private HttpHeaders auth(SysUser u) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.AUTHORIZATION, bearer(u));
        return h;
    }

    private HttpEntity<Map<String, String>> json(SysUser user, Map<String, String> body) {
        HttpHeaders h = auth(user);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    @Test
    void anonymousAndViewerAreRejected() {
        ResponseEntity<String> anon = rest.getForEntity("/api/v1/export/works", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        SysUser viewer = createUser(Role.VIEWER);
        ResponseEntity<String> viewerRes = rest.exchange("/api/v1/export/works", HttpMethod.GET,
                new HttpEntity<>(auth(viewer)), String.class);
        assertThat(viewerRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewerRes.getBody()).contains("9004");
    }

    @Test
    void worksListAggregatesByWorkTitle() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "一", "t1", "第一章");
        seg(editor, "二", "t2", "第一章");
        seg(editor, "三", "", "第二章");

        ResponseEntity<String> res = rest.exchange("/api/v1/export/works", HttpMethod.GET,
                new HttpEntity<>(auth(editor)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(work);
        assertThat(res.getBody()).contains("\"totalSegments\":3");
        assertThat(res.getBody()).contains("\"translatedSegments\":2");
        assertThat(res.getBody()).contains("\"chapters\":2");
    }

    @Test
    void previewPairsSplitSideSegments() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "甲", null, "学而第一");
        seg(editor, "乙", null, "学而第一");
        seg(editor, "", "译甲", "学而第一");
        seg(editor, "", "译乙", "学而第一");

        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", work)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"units\":2");
        assertThat(res.getBody()).contains("\"pairedUnits\":2");
        assertThat(res.getBody()).contains("\"src\":2");
        assertThat(res.getBody()).contains("\"dst\":2");
        assertThat(res.getBody()).contains("\"paired\":2");
    }

    @Test
    void previewWarnsOnCountMismatch() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "甲", null, "学而第一");
        seg(editor, "甲2", null, "学而第一");
        seg(editor, "", "译甲", "学而第一");

        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", work)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("未配上");
    }

    @Test
    void downloadBilingualTxtHasBomAndBothTexts() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "学而时习之", "To learn and practise", "学而第一");

        ResponseEntity<byte[]> res = rest.postForEntity("/api/v1/export",
                json(editor, Map.of("workTitle", work, "mode", "BILINGUAL", "format", "TXT")), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        byte[] body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(body, 3, body.length - 3, StandardCharsets.UTF_8))
                .contains("学而时习之")
                .contains("To learn and practise");
    }

    @Test
    void downloadDocxIsZipAndTranslationOnlyDropsSource() throws Exception {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "学而时习之", "To learn and practise", "学而第一");

        ResponseEntity<byte[]> res = rest.postForEntity("/api/v1/export",
                json(editor, Map.of("workTitle", work, "mode", "TRANSLATION_ONLY", "format", "DOCX")), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".docx");
        byte[] body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).startsWith('P', 'K');
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(body))) {
            var texts = doc.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            assertThat(texts).contains("To learn and practise");
            assertThat(texts).doesNotContain("学而时习之");
        }
    }

    @Test
    void unknownWorkRejected6001() {
        SysUser editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", "不存在的书" + System.nanoTime())), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("6001");
    }
}
