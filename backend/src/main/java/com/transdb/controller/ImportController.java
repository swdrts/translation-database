package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.ImportResultVO;
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
    private final ImportExecutor importExecutor;
    private final ImportPreviewStore previewStore;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportPreviewVO> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "SKIP") String duplicateStrategy,
            @AuthenticationPrincipal LoginUser operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        DuplicateStrategy strategy;
        try {
            strategy = DuplicateStrategy.valueOf(duplicateStrategy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "duplicateStrategy 只支持 SKIP/OVERWRITE/KEEP");
        }
        ImportPreviewVO preview = importPreviewService.buildPreview(
                file.getOriginalFilename(), file.getInputStream(), strategy, operator);
        return ApiResponse.ok(preview);
    }

    @PostMapping("/{previewId}/confirm")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportResultVO> confirm(@PathVariable String previewId,
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
        return ApiResponse.ok(importExecutor.execute(owned, operator));
    }
}
