package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.TagUpsertDTO;
import com.transdb.dto.TagVO;
import com.transdb.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ApiResponse<List<TagVO>> list() {
        return ApiResponse.ok(tagService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<TagVO> create(@RequestBody @Valid TagUpsertDTO dto) {
        return ApiResponse.ok(tagService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<TagVO> update(@PathVariable long id, @RequestBody @Valid TagUpsertDTO dto) {
        return ApiResponse.ok(tagService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        tagService.delete(id);
        return ApiResponse.ok();
    }
}
