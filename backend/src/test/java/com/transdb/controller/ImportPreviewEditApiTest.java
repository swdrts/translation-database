package com.transdb.controller;

import com.jayway.jsonpath.JsonPath;
import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
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

/** 分段编辑端点：权限/属主/过期/分页筛选/操作校验 + 编辑到落库端到端。 */
class ImportPreviewEditApiTest extends AbstractIntegrationTest {

    @Autowired SegmentRepository segmentRepository;

    // ---------- helpers ----------

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, String filename,
                                                                byte[] content) {
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
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> uploadDoc(String token, byte[] content) {
        return rest.exchange("/api/v1/segments/import/document", HttpMethod.POST,
                multipart(token, "book.txt", content), String.class);
    }

    private ResponseEntity<String> uploadTable(String token, byte[] content) {
        return rest.exchange("/api/v1/segments/import", HttpMethod.POST,
                multipart(token, "table.json", content), String.class);
    }

    private HttpEntity<String> jsonEntity(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> bearerOnly(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<String> get(String token, String path) {
        return rest.exchange(path, HttpMethod.GET, bearerOnly(token), String.class);
    }

    private String uploadAndGetPreviewId(String token, String txt) {
        ResponseEntity<String> res = uploadDoc(token, txt.getBytes(StandardCharsets.UTF_8));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JsonPath.read(res.getBody(), "$.data.previewId").toString();
    }

    // ---------- 查询 ----------

    @Test
    void editorCanListRowsAndChapters() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "列表" + System.nanoTime();
        String previewId = uploadAndGetPreviewId(token,
                marker + "学而时习之，不亦说乎？\n\n" + marker + "有朋自远方来，不亦乐乎？\n");

        ResponseEntity<String> rows = get(token, "/api/v1/segments/import/" + previewId + "/rows");
        assertThat(rows.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.stats.totalRows")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.totalPages")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows.size()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows[0].rowId")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows[0].seq")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows[0].prevRowId")).isEqualTo(-1);
        assertThat(rows.getBody()).contains(marker + "学而时习之，不亦说乎？");
        assertThat(JsonPath.read(rows.getBody(), "$.data.rows[0].planType").toString())
                .isEqualTo("IMPORT");

        ResponseEntity<String> chapters = get(token, "/api/v1/segments/import/" + previewId + "/chapters");
        assertThat((Integer) JsonPath.read(chapters.getBody(), "$.data[0].rowCount")).isEqualTo(2);
        assertThat(JsonPath.read(chapters.getBody(), "$.data[0].title").toString()).isEmpty();
    }

    @Test
    void rowsDefaultPageSizeIsTen() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "分页" + System.nanoTime();
        StringBuilder txt = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            txt.append(marker).append("第").append(i).append("句。\n\n");
        }
        String previewId = uploadAndGetPreviewId(token, txt.toString());

        // 不带 size 参数：默认每页 10 段，12 段分 2 页
        ResponseEntity<String> rows = get(token, "/api/v1/segments/import/" + previewId + "/rows");
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows.size()")).isEqualTo(10);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.totalPages")).isEqualTo(2);

        // 越过末页的跳页（如跳页框输了大数）：返回末页数据
        ResponseEntity<String> last = get(token, "/api/v1/segments/import/" + previewId + "/rows?page=99");
        assertThat((Integer) JsonPath.read(last.getBody(), "$.data.rows.size()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(last.getBody(), "$.data.page")).isEqualTo(1);
    }

    @Test
    void rowsSuspiciousFilterWorksOverHttp() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "可疑" + System.nanoTime();
        String previewId = uploadAndGetPreviewId(token,
                marker + "长".repeat(400) + "。\n\n" + marker + "短句。\n");

        ResponseEntity<String> rows = get(token, "/api/v1/segments/import/" + previewId
                + "/rows?suspicious=true&longAbove=300&shortBelow=10");
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows.size()")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(rows.getBody(), "$.data.rows[0].rowId")).isEqualTo(1);
    }

    // ---------- 端到端：拆分→合并→改字→确认落库 ----------

    @Test
    void editSplitMergeChangeDbOutcome() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "端到端" + System.nanoTime();
        String previewId = uploadAndGetPreviewId(token, marker + "甲乙丙丁。\n");

        // 拆分：切在 marker+甲乙 之后 → 上段 marker+甲乙、下段 丙丁。
        int at = marker.length() + "甲乙".length();
        ResponseEntity<String> split = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/rows/1/split", HttpMethod.POST,
                jsonEntity(token, "{\"atChar\":" + at + "}"), String.class);
        assertThat(split.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(split.getBody(), "$.data.rows.size()")).isEqualTo(2);
        assertThat(split.getBody()).contains(marker + "甲乙");
        assertThat(split.getBody()).contains("丙丁。");

        // 合并回去
        ResponseEntity<String> merged = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/rows/merge", HttpMethod.POST,
                jsonEntity(token, "{\"rowIds\":[1,2]}"), String.class);
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(merged.getBody(), "$.data.stats.totalRows")).isEqualTo(1);
        assertThat(merged.getBody()).contains(marker + "甲乙丙丁。");

        // 改字（合并后的行是新行，rowId=3）
        ResponseEntity<String> edited = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/rows/3", HttpMethod.PATCH,
                jsonEntity(token, "{\"text\":\"" + marker + "甲乙丙丁，改过了。\"}"), String.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(edited.getBody(), "$.data.row.edited").toString()).isEqualTo("true");

        // 确认 → 落库为改后文本、1 段
        ResponseEntity<String> confirm = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/confirm", HttpMethod.POST,
                jsonEntity(token, "{\"workTitle\":\"论语\"}"), String.class);
        assertThat((Integer) JsonPath.read(confirm.getBody(), "$.data.imported")).isEqualTo(1);

        List<Segment> saved = segmentRepository.findAll().stream()
                .filter(s -> s.getSourceText().contains(marker)).toList();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getSourceText()).isEqualTo(marker + "甲乙丙丁，改过了。");
        assertThat(saved.get(0).getWorkTitle()).isEqualTo("论语");
    }

    @Test
    void renameChapterEndToEnd() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String marker = "改名" + System.nanoTime();
        String previewId = uploadAndGetPreviewId(token,
                marker + "章前句子。\n\n第一章\n" + marker + "章内句子。\n");

        // 未分章 → 并入已有“第一章”（to 与已有章节同名即两章合并）
        ResponseEntity<String> renamed = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/chapters/rename", HttpMethod.POST,
                jsonEntity(token, "{\"from\":\"\",\"to\":\"第一章\"}"), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> chapters = get(token, "/api/v1/segments/import/" + previewId + "/chapters");
        assertThat((Integer) JsonPath.read(chapters.getBody(), "$.data.size()")).isEqualTo(1);
        assertThat(JsonPath.read(chapters.getBody(), "$.data[0].title").toString()).isEqualTo("第一章");
        assertThat((Integer) JsonPath.read(chapters.getBody(), "$.data[0].rowCount")).isEqualTo(2);

        ResponseEntity<String> confirm = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/confirm", HttpMethod.POST,
                jsonEntity(token, null), String.class);
        assertThat((Integer) JsonPath.read(confirm.getBody(), "$.data.imported")).isEqualTo(2);
        List<Segment> saved = segmentRepository.findAll().stream()
                .filter(s -> s.getSourceText().contains(marker)).toList();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(s -> assertThat(s.getChapter()).isEqualTo("第一章"));
    }

    // ---------- 权限与错误 ----------

    @Test
    void tableSessionCannotEdit() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String json = "[{\"source_text\":\"甲\",\"translated_text\":\"A\"}]";
        ResponseEntity<String> preview = uploadTable(token, json.getBytes(StandardCharsets.UTF_8));
        String previewId = JsonPath.read(preview.getBody(), "$.data.previewId").toString();

        ResponseEntity<String> res = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/rows/1", HttpMethod.PATCH,
                jsonEntity(token, "{\"text\":\"改\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("不支持分段编辑");
    }

    @Test
    void othersPreviewForbidden() {
        var owner = createUser(Role.EDITOR);
        var other = createUser(Role.EDITOR);
        String previewId = uploadAndGetPreviewId(bearer(owner), "别人的句子。\n");

        ResponseEntity<String> res = get(bearer(other),
                "/api/v1/segments/import/" + previewId + "/rows");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":3004");
    }

    @Test
    void missingPreviewRejected() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = get(bearer(editor),
                "/api/v1/segments/import/no-such-preview/rows");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":3003");
    }

    @Test
    void rowIdNotFoundRejected() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        String previewId = uploadAndGetPreviewId(token, "单句。\n");

        ResponseEntity<String> res = rest.exchange(
                "/api/v1/segments/import/" + previewId + "/rows/99999", HttpMethod.PATCH,
                jsonEntity(token, "{\"text\":\"改\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"code\":3006");
    }

    @Test
    void viewerCannotEdit() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> res = get(bearer(viewer),
                "/api/v1/segments/import/whatever/rows");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
