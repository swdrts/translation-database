package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.ReindexStatusVO;
import com.transdb.search.ReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reindex")
@RequiredArgsConstructor
public class AdminReindexController {

    private final ReindexService reindexService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ReindexStatusVO> start() {
        return ApiResponse.ok(reindexService.start());
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ReindexStatusVO> status() {
        return ApiResponse.ok(reindexService.status());
    }
}
