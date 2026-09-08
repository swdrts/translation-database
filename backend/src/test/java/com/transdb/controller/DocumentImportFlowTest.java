package com.transdb.controller;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.common.ContentHash;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.repository.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 整本书/文档导入端到端：/segments/import/document → 预览 → confirm（含元数据覆盖）。 */
class DocumentImportFlowTest extends AbstractIntegrationTest {

    @Autowired SegmentRepository segmentRepository;

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                byte[] content, String strategy) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (strategy != null) {
            body.add("duplicateStrategy", strategy);
        }
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> uploadDoc(String token, String filename, byte[] content) {
        return rest.exchange("/api/v1/segments/import/document", HttpMethod.POST,
                multipart(token, filename, content, null), String.class);
    }

    /** 带确认覆盖请求体的 confirm。 */
    private ResponseEntity<String> confirm(String token, String previewId, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange("/api/v1/segments/import/" + previewId + "/confirm",
                HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
    }

    @Test
    void documentImportFullFlowWithMetadataOverrides() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "文档导入" + System.nanoTime();

        // 页眉重复 6 次 + 页码行应被过滤；两句正文各成一段
        String header = "《论语》·中华书局版";
        String txt = (header + "\n" + marker + "原文一，不亦说乎？\n123\n\n"
                + (header + "\n").repeat(5) + marker + "原文二，不亦乐乎？\n");
        ResponseEntity<String> previewRes = uploadDoc(token, "lunyu.txt",
                txt.getBytes(StandardCharsets.UTF_8));
        assertThat(previewRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(previewRes.getBody(), "$.data.sourceType").toString())
                .isEqualTo("DOCUMENT");
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.totalRows")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.willImportRows")).isEqualTo(2);
        // 抽样段落
        assertThat((Integer) JsonPath.read(previewRes.getBody(), "$.data.sampleRows.size()")).isEqualTo(2);
        assertThat(previewRes.getBody()).contains(marker + "原文一");

        String previewId = JsonPath.read(previewRes.getBody(), "$.data.previewId").toString();

        // 确认时补全书目信息：书名/作者/朝代/标签
        String confirmBody = """
                {"workTitle":"论语","author":"孔子弟子","dynasty":"先秦","tags":"儒家|语录"}
                """;
        ResponseEntity<String> confirmRes = confirm(token, previewId, confirmBody);
        assertThat(confirmRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.imported")).isEqualTo(2);

        // 落库校验：仅原文、状态 DRAFT、元数据已覆盖、译文为空（join fetch 取回标签）
        List<Long> ids = segmentRepository.findAll().stream()
                .filter(s -> s.getSourceText().contains(marker))
                .map(Segment::getId).toList();
        assertThat(ids).hasSize(2);
        var segments = segmentRepository.findByIdForSyncIn(ids);
        for (Segment s : segments) {
            assertThat(s.getTranslatedText()).isEmpty();
            assertThat(s.getStatus()).isEqualTo(SegmentStatus.DRAFT);
            assertThat(s.getWorkTitle()).isEqualTo("论语");
            assertThat(s.getAuthor()).isEqualTo("孔子弟子");
            assertThat(s.getDynasty()).isEqualTo("先秦");
        }
        for (Segment s : segments) {
            assertThat(s.getTags().stream().map(t -> t.getName()))
                    .containsExactlyInAnyOrder("儒家", "语录");
        }
    }

    @Test
    void existingTranslationIsProtectedFromDocumentImport() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "译文保护" + System.nanoTime();

        // 库内已有该原文且带译文
        Segment existing = new Segment();
        existing.setSourceText(marker + "已有译文的句子。");
        existing.setTranslatedText("Already translated sentence.");
        existing.setStatus(SegmentStatus.PUBLISHED);
        existing.setContentHash(ContentHash.sha256(marker + "已有译文的句子。",
                "Already translated sentence."));
        existing.setCreatedBy(editor);
        segmentRepository.save(existing);
        long existingId = existing.getId();

        String txt = marker + "已有译文的句子。\n\n" + marker + "全新句子。\n";
        String previewId = JsonPath.read(uploadDoc(token, "book.txt",
                txt.getBytes(StandardCharsets.UTF_8)).getBody(), "$.data.previewId").toString();

        ResponseEntity<String> confirmRes = confirm(token, previewId, null);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.imported")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(confirmRes.getBody(), "$.data.skipped")).isEqualTo(1);

        // 已有译文未被覆盖
        Segment after = segmentRepository.findById(existingId).orElseThrow();
        assertThat(after.getTranslatedText()).isEqualTo("Already translated sentence.");
    }

    @Test
    void reimportUntranslatedSourceSkipsAll() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "重复导入" + System.nanoTime();

        String txt = marker + "句子甲。\n";
        String firstId = JsonPath.read(uploadDoc(token, "a.txt",
                txt.getBytes(StandardCharsets.UTF_8)).getBody(), "$.data.previewId").toString();
        assertThat((Integer) JsonPath.read(confirm(token, firstId, null).getBody(),
                "$.data.imported")).isEqualTo(1);

        String secondId = JsonPath.read(uploadDoc(token, "a.txt",
                txt.getBytes(StandardCharsets.UTF_8)).getBody(), "$.data.previewId").toString();
        ResponseEntity<String> second = confirm(token, secondId, null);
        assertThat((Integer) JsonPath.read(second.getBody(), "$.data.imported")).isZero();
        assertThat((Integer) JsonPath.read(second.getBody(), "$.data.skipped")).isEqualTo(1);
    }

    @Test
    void confirmCanPublishImmediately() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "直接发布" + System.nanoTime();

        String txt = marker + "公开句子。\n";
        String previewId = JsonPath.read(uploadDoc(token, "pub.txt",
                txt.getBytes(StandardCharsets.UTF_8)).getBody(), "$.data.previewId").toString();
        confirm(token, previewId, "{\"status\":\"PUBLISHED\"}");

        Segment saved = segmentRepository.findAll().stream()
                .filter(s -> s.getSourceText().contains(marker)).findFirst().orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SegmentStatus.PUBLISHED);
    }

    @Test
    void unsupportedOrEmptyDocumentFails() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);

        ResponseEntity<String> bad = uploadDoc(token, "book.zip", "zip".getBytes());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("\"code\":3001");

        ResponseEntity<String> empty = uploadDoc(token, "empty.txt", "   \n\n  \n".getBytes());
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody()).contains("\"code\":3005");

        // 扫描版 PDF 提示：内容全是数字（无文字）→ 3005
        ResponseEntity<String> numbers = uploadDoc(token, "scan.txt",
                "1\n2\n3\n".getBytes(StandardCharsets.UTF_8));
        assertThat(numbers.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void viewerCannotImportDocument() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> res = uploadDoc(bearer(viewer), "v.txt", "内容".getBytes());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
