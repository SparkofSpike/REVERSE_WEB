package com.test.engine.controller;

import com.test.engine.dto.AuthResponse;
import com.test.engine.dto.LoginRequest;
import com.test.engine.dto.RegisterRequest;
import com.test.engine.entity.User;
import com.test.engine.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.getUsername(), request.getPassword());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getUsername(), request.getPassword());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        User user = authService.findByUsername(authentication.getName());
        return Map.of("id", user.getId(), "username", user.getUsername(),
                "createdAt", user.getCreatedAt());
    }
}
