package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.UserStatus;
import com.transdb.dto.LoginRequest;
import com.transdb.dto.LoginVO;
import com.transdb.dto.UserVO;
import com.transdb.repository.SysUserRepository;
import com.transdb.security.JwtService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public LoginVO login(LoginRequest request) {
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> BusinessException.of(ErrorCode.WRONG_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw BusinessException.of(ErrorCode.WRONG_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw BusinessException.of(ErrorCode.USER_DISABLED);
        }
        LoginUser principal = new LoginUser(user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRole());
        return new LoginVO(jwtService.generate(principal), UserVO.from(user));
    }
}
