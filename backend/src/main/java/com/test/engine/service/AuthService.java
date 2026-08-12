package com.test.engine.service;

import com.test.engine.config.OpConfig;
import com.test.engine.dto.AuthResponse;
import com.test.engine.entity.User;
import com.test.engine.enums.UserRole;
import com.test.engine.exception.BusinessException;
import com.test.engine.repository.UserRepository;
import com.test.engine.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, token issuance and self-service profile updates.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OpConfig opConfig;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, OpConfig opConfig) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.opConfig = opConfig;
    }

    @Transactional
    public AuthResponse register(String username, String password) {
        String name = username.trim();
        if (userRepository.existsByUsername(name)) {
            throw new BusinessException("用户名已被占用");
        }
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String username, String password) {
        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (!user.isEnabled()) {
            throw new BusinessException("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    /** Updates the display name and returns the effective (trimmed) value. */
    @Transactional
    public String updateProfile(String username, String nickname) {
        User user = findByUsername(username);
        String trimmed = nickname == null ? null : nickname.trim();
        user.setNickname(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        return user.getNickname();
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        User user = findByUsername(username);
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException("原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    /** Role exposed to clients: OP wins over the stored ADMIN/USER value. */
    public String effectiveRole(User user) {
        if (opConfig.isOp(user.getId())) {
            return "OP";
        }
        return user.getRole() != null ? user.getRole().name() : UserRole.USER.name();
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(jwtService.generateToken(user.getUsername()), user.getUsername(),
                effectiveRole(user), user.getNickname());
    }
}
