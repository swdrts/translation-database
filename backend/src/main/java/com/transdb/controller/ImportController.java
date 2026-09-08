package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportConfirmRequest;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.ImportResultVO;
import com.transdb.importer.DocumentImportService;
import com.transdb.importer.DuplicateStrategy;
import com.transdb.importer.ImportExecutor;
import com.transdb.importer.ImportPreviewService;
import com.transdb.importer.ImportPreviewStore;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/segments/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportPreviewService importPreviewService;
    private final DocumentImportService documentImportService;
    private final ImportExecutor importExecutor;
    private final ImportPreviewStore previewStore;

    /** 对照表导入（json/csv/xlsx/xls）：每行须有原文+译文。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportPreviewVO> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "SKIP") String duplicateStrategy,
            @AuthenticationPrincipal LoginUser operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        ImportPreviewVO preview = importPreviewService.buildPreview(
                file.getOriginalFilename(), file.getInputStream(),
                parseStrategy(duplicateStrategy), operator);
        return ApiResponse.ok(preview);
    }

    /** 整本书/文档导入（epub/pdf/docx/doc/txt/md/html）：自动拆段，仅原文，译文留空待补。 */
    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportPreviewVO> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "SKIP") String duplicateStrategy,
            @AuthenticationPrincipal LoginUser operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        ImportPreviewVO preview = documentImportService.buildPreview(
                file.getOriginalFilename(), file.getInputStream(),
                parseStrategy(duplicateStrategy), operator);
        return ApiResponse.ok(preview);
    }

    @PostMapping("/{previewId}/confirm")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportResultVO> confirm(@PathVariable String previewId,
                                               @RequestBody(required = false) ImportConfirmRequest request,
                                               @AuthenticationPrincipal LoginUser operator) {
        ImportPreviewStore.ImportPreviewSession session = previewStore.get(previewId);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        if (session.operatorId() != operator.id()) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_FORBIDDEN);
        }
        // 确认后立即移除，防重放；失败需重新上传预览
        ImportPreviewStore.ImportPreviewSession owned = previewStore.remove(previewId);
        if (owned == null) {
            // 并发双击下另一请求已消费预览：此处与 get 判空同路径，返回 404/3003 而非 500
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        return ApiResponse.ok(importExecutor.execute(owned, operator, request));
    }

    private DuplicateStrategy parseStrategy(String duplicateStrategy) {
        try {
            return DuplicateStrategy.valueOf(duplicateStrategy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "duplicateStrategy 只支持 SKIP/OVERWRITE/KEEP");
        }
    }
}
