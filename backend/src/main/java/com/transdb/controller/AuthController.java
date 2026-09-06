package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.LoginRequest;
import com.transdb.dto.LoginVO;
import com.transdb.dto.UserVO;
import com.transdb.security.LoginUser;
import com.transdb.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me(@AuthenticationPrincipal LoginUser principal) {
        return ApiResponse.ok(new UserVO(principal.id(), principal.username(),
                principal.displayName(), principal.role()));
    }
}
