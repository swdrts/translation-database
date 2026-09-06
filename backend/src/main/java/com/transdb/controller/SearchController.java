package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.FacetsVO;
import com.transdb.dto.SearchQueryParams;
import com.transdb.dto.SearchResponseVO;
import com.transdb.dto.SuggestVO;
import com.transdb.search.EsSearchService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SearchController {

    private final EsSearchService esSearchService;

    @GetMapping("/search")
    public ApiResponse<SearchResponseVO> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "all") String field,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String dynasty,
            @RequestParam(required = false) String work,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal LoginUser operator) {
        List<String> tagList = tags == null || tags.isBlank()
                ? List.of() : Arrays.stream(tags.split(",")).map(String::trim).toList();
        return ApiResponse.ok(esSearchService.search(
                new SearchQueryParams(q, field, tagList, dynasty, work, page, size), operator));
    }

    @GetMapping("/suggest")
    public ApiResponse<SuggestVO> suggest(@RequestParam(required = false) String q) {
        return ApiResponse.ok(esSearchService.suggest(q, null));
    }

    @GetMapping("/facets")
    public ApiResponse<FacetsVO> facets(@AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(esSearchService.facets(operator));
    }
}
