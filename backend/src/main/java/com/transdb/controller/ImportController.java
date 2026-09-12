package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportChapterRenameRequest;
import com.transdb.dto.ImportChapterStatVO;
import com.transdb.dto.ImportConfirmRequest;
import com.transdb.dto.ImportEditStatsVO;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.dto.ImportResultVO;
import com.transdb.dto.ImportRowEditRequest;
import com.transdb.dto.ImportRowMergeRequest;
import com.transdb.dto.ImportRowsPageVO;
import com.transdb.dto.ImportRowSplitRequest;
import com.transdb.importer.DocumentImportService;
import com.transdb.importer.DuplicateStrategy;
import com.transdb.importer.ImportExecutor;
import com.transdb.importer.ImportPreviewEditService;
import com.transdb.importer.ImportRowPlan;
import com.transdb.importer.ImportSourceType;
import com.transdb.importer.ImportTextRole;
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
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/segments/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportPreviewService importPreviewService;
    private final DocumentImportService documentImportService;
    private final ImportPreviewEditService importPreviewEditService;
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

    /**
     * 整本书/文档导入（epub/pdf/docx/doc/txt/md/html）：自动拆段，仅一侧文字。
     * textRole=SOURCE（默认）导入的是原文、译文留空待补；TRANSLATION 导入的是译文、原文留空待补。
     */
    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportPreviewVO> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "SKIP") String duplicateStrategy,
            @RequestParam(required = false, defaultValue = "SOURCE") String textRole,
            @AuthenticationPrincipal LoginUser operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS);
        }
        ImportTextRole role;
        try {
            role = ImportTextRole.valueOf(textRole.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "textRole 只支持 SOURCE（原文）/ TRANSLATION（译文）");
        }
        ImportPreviewVO preview = documentImportService.buildPreview(
                file.getOriginalFilename(), file.getInputStream(),
                parseStrategy(duplicateStrategy), role, operator);
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

    // ---------- 分段编辑（仅整本书/文档导入会话；每次调用滑动续期） ----------

    /** 分页拉取全部分段（全文不截断）：chapter 空串=未分章；suspicious 按长/短阈值过滤；默认每页 10 段。 */
    @GetMapping("/{previewId}/rows")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportRowsPageVO> rows(@PathVariable String previewId,
            @RequestParam(required = false) String chapter,
            @RequestParam(required = false, defaultValue = "false") boolean suspicious,
            @RequestParam(required = false, defaultValue = "300") int longAbove,
            @RequestParam(required = false, defaultValue = "10") int shortBelow,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        return ApiResponse.ok(importPreviewEditService.rows(
                session, chapter, suspicious, longAbove, shortBelow, page, size));
    }

    @GetMapping("/{previewId}/chapters")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<List<ImportChapterStatVO>> chapters(@PathVariable String previewId,
            @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        return ApiResponse.ok(importPreviewEditService.chapters(session));
    }

    /** 编辑单段：text/chapter 至少一项；text 变化即时重跑去重判定。 */
    @PatchMapping("/{previewId}/rows/{rowId}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportRowsPageVO.ImportRowOpResultVO> editRow(@PathVariable String previewId,
            @PathVariable long rowId, @RequestBody ImportRowEditRequest request,
            @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        ImportRowPlan updated = importPreviewEditService.editRow(session, rowId, request);
        return ApiResponse.ok(new ImportRowsPageVO.ImportRowOpResultVO(
                rowAt(session, updated), importPreviewEditService.statsOf(session)));
    }

    /** 合并连续多段（rowIds ≥2 且顺序连续）；章节取第一段。 */
    @PostMapping("/{previewId}/rows/merge")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportRowsPageVO.ImportRowOpResultVO> mergeRows(@PathVariable String previewId,
            @RequestBody ImportRowMergeRequest request, @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        ImportRowPlan merged = importPreviewEditService.mergeRows(session, request.rowIds());
        return ApiResponse.ok(new ImportRowsPageVO.ImportRowOpResultVO(
                rowAt(session, merged), importPreviewEditService.statsOf(session)));
    }

    /** 在 atChar 光标偏移处拆成两段。 */
    @PostMapping("/{previewId}/rows/{rowId}/split")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportRowsPageVO.ImportSplitResultVO> splitRow(@PathVariable String previewId,
            @PathVariable long rowId, @RequestBody ImportRowSplitRequest request,
            @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        List<ImportRowPlan> parts = importPreviewEditService.splitRow(session, rowId, request.atChar());
        return ApiResponse.ok(new ImportRowsPageVO.ImportSplitResultVO(parts.stream()
                .map(p -> rowAt(session, p)).toList(), importPreviewEditService.statsOf(session)));
    }

    @DeleteMapping("/{previewId}/rows/{rowId}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportEditStatsVO> deleteRow(@PathVariable String previewId,
            @PathVariable long rowId, @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        return ApiResponse.ok(importPreviewEditService.deleteRow(session, rowId));
    }

    /** 章节改名：to 与已有章节同名即两章合并；to 空白即归入未分章。 */
    @PostMapping("/{previewId}/chapters/rename")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportEditStatsVO> renameChapter(@PathVariable String previewId,
            @RequestBody ImportChapterRenameRequest request, @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        return ApiResponse.ok(importPreviewEditService.renameChapter(
                session, request.from(), request.to()));
    }

    /** 以行在会话中的当前位置构造 RowVO（seq/prevRowId 即时计算）。 */
    private ImportRowsPageVO.RowVO rowAt(ImportPreviewStore.ImportPreviewSession session,
                                         ImportRowPlan plan) {
        int idx = session.rows().indexOf(plan);
        long prev = idx > 0 ? session.rows().get(idx - 1).rowId() : -1;
        return importPreviewEditService.toVO(plan, idx + 1, prev);
    }
}
