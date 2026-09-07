package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.importer.DuplicateStrategy;
import com.transdb.importer.ImportPreviewService;
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
}
