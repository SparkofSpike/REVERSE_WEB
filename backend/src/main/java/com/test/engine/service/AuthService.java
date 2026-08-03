package com.test.engine.service;

import com.test.engine.dto.AuthResponse;
import com.test.engine.entity.User;
import com.test.engine.exception.BusinessException;
import com.test.engine.repository.UserRepository;
import com.test.engine.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and token issuance.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        return new AuthResponse(jwtService.generateToken(name), name);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String username, String password) {
        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        return new AuthResponse(jwtService.generateToken(user.getUsername()), user.getUsername());
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }
}
