package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.PageResponse;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.SegmentFilter;
import com.transdb.dto.SegmentUpsertDTO;
import com.transdb.dto.SegmentVO;
import com.transdb.security.LoginUser;
import com.transdb.service.SegmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/segments")
@RequiredArgsConstructor
public class SegmentController {

    private final SegmentService segmentService;

    @GetMapping
    public ApiResponse<PageResponse<SegmentVO>> list(
            @RequestParam(required = false) String work,
            @RequestParam(required = false) String dynasty,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) SegmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(segmentService.list(
                new SegmentFilter(work, dynasty, tagId, status), page, size, operator));
    }

    @GetMapping("/{id}")
    public ApiResponse<SegmentVO> get(@PathVariable long id, @AuthenticationPrincipal LoginUser operator) {
        SegmentVO vo = segmentService.get(id);
        if (operator.role() == com.transdb.domain.Role.VIEWER
                && vo.status() == SegmentStatus.DRAFT) {
            throw com.transdb.common.BusinessException.of(com.transdb.common.ErrorCode.SEGMENT_FORBIDDEN);
        }
        return ApiResponse.ok(vo);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<SegmentVO> create(@RequestBody @Valid SegmentUpsertDTO dto,
                                         @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(segmentService.create(dto, operator));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<SegmentVO> update(@PathVariable long id, @RequestBody @Valid SegmentUpsertDTO dto) {
        return ApiResponse.ok(segmentService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        segmentService.delete(id);
        return ApiResponse.ok();
    }
}
