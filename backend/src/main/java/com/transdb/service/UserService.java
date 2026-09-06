package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.common.PageResponse;
import com.transdb.domain.SysUser;
import com.transdb.domain.UserStatus;
import com.transdb.dto.CreateUserDTO;
import com.transdb.dto.UpdateUserDTO;
import com.transdb.dto.UserVO;
import com.transdb.repository.SysUserRepository;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<UserVO> list(int page, int size) {
        var result = userRepository.findAll(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.of(result.map(UserVO::from));
    }

    @Transactional
    public UserVO create(CreateUserDTO dto) {
        if (userRepository.existsByUsername(dto.username())) {
            throw BusinessException.of(ErrorCode.USERNAME_EXISTS);
        }
        SysUser u = new SysUser();
        u.setUsername(dto.username());
        u.setPassword(passwordEncoder.encode(dto.password()));
        u.setDisplayName(dto.displayName());
        u.setRole(dto.role());
        u.setStatus(UserStatus.ACTIVE);
        return UserVO.from(userRepository.save(u));
    }

    @Transactional
    public UserVO update(long id, UpdateUserDTO dto, LoginUser operator) {
        SysUser u = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        boolean demotingSelf = operator.id() == id
                && (dto.role() != null && dto.role() != u.getRole()
                    || dto.status() != null && dto.status() != UserStatus.ACTIVE);
        if (demotingSelf) {
            throw BusinessException.of(ErrorCode.SELF_MODIFY_FORBIDDEN);
        }
        if (dto.displayName() != null) {
            u.setDisplayName(dto.displayName());
        }
        if (dto.role() != null) {
            u.setRole(dto.role());
        }
        if (dto.status() != null) {
            u.setStatus(dto.status());
        }
        return UserVO.from(userRepository.save(u));
    }
}
