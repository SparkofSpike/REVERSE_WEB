package com.test.engine.controller;

import com.test.engine.dto.AuthResponse;
import com.test.engine.dto.ChangePasswordRequest;
import com.test.engine.dto.LoginRequest;
import com.test.engine.dto.RegisterRequest;
import com.test.engine.dto.UserProfileRequest;
import com.test.engine.entity.User;
import com.test.engine.service.AuthService;
import com.test.engine.service.AvatarService;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AvatarService avatarService;

    public AuthController(AuthService authService, AvatarService avatarService) {
        this.authService = authService;
        this.avatarService = avatarService;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("nickname", user.getNickname());
        body.put("role", authService.effectiveRole(user));
        body.put("enabled", user.isEnabled());
        body.put("avatarUrl", authService.avatarUrl(user));
        body.put("createdAt", user.getCreatedAt());
        return body;
    }

    @PutMapping("/avatar")
    public Map<String, Object> uploadAvatar(Authentication authentication,
                                            @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getDetails();
        String ext = avatarService.save(userId, file);
        // persist via a managed entity: the controller's own lookups are
        // detached and would silently drop the update
        authService.setAvatar(authentication.getName(), ext);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("avatarUrl", "/api/avatars/" + userId + "." + ext);
        return body;
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(Authentication authentication,
                                             @Valid @RequestBody UserProfileRequest request) {
        User user = authService.findByUsername(authentication.getName());
        Map<String, Object> body = new LinkedHashMap<>();
        // LinkedHashMap allows a null nickname (cleared display name)
        body.put("nickname", authService.updateProfile(user.getUsername(), request.getNickname()));
        return body;
    }

    @PutMapping("/password")
    public Map<String, Object> changePassword(Authentication authentication,
                                              @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(authentication.getName(),
                request.getOldPassword(), request.getNewPassword());
        return Map.of("ok", true);
    }
}
