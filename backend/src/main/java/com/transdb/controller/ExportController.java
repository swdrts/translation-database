package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ExportPreviewVO;
import com.transdb.dto.ExportRequest;
import com.transdb.dto.ExportWorksItemVO;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import com.transdb.exporter.ExportService;
import com.transdb.exporter.ExportedFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/works")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<List<ExportWorksItemVO>> works() {
        return ApiResponse.ok(exportService.listWorks());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ExportPreviewVO> preview(@RequestBody ExportRequest req) {
        return ApiResponse.ok(exportService.preview(req.workTitle()));
    }

    /** 项目首个文件流端点：书稿下载（blob），文件名 RFC 5987 编码支持中文。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest req) {
        ExportMode mode = parseMode(req.mode());
        ExportFormat format = parseFormat(req.format());
        ExportedFile file = exportService.generate(req.workTitle(), mode, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20"))
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .body(file.content());
    }

    private ExportMode parseMode(String raw) {
        try {
            return ExportMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "mode 只支持 TRANSLATION_ONLY（仅译文）/ BILINGUAL（对照）/ SOURCE_ONLY（仅原文）");
        }
    }

    private ExportFormat parseFormat(String raw) {
        try {
            return ExportFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "format 只支持 TXT / MARKDOWN / DOCX");
        }
    }
}
