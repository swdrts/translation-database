package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.PageResponse;
import com.transdb.dto.CreateUserDTO;
import com.transdb.dto.UpdateUserDTO;
import com.transdb.dto.UserVO;
import com.transdb.security.LoginUser;
import com.transdb.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<PageResponse<UserVO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.list(page, size));
    }

    @PostMapping
    public ApiResponse<UserVO> create(@RequestBody @Valid CreateUserDTO dto) {
        return ApiResponse.ok(userService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserVO> update(@PathVariable long id,
                                      @RequestBody @Valid UpdateUserDTO dto,
                                      @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(userService.update(id, dto, operator));
    }
}
