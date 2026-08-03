package com.test.engine.controller;

import com.test.engine.dto.BuildRequest;
import com.test.engine.dto.BuildResponse;
import com.test.engine.entity.User;
import com.test.engine.service.AuthService;
import com.test.engine.service.BuildService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/builds")
public class BuildController {

    private final BuildService buildService;
    private final AuthService authService;

    public BuildController(BuildService buildService, AuthService authService) {
        this.buildService = buildService;
        this.authService = authService;
    }

    @GetMapping
    public List<BuildResponse> list(Authentication authentication) {
        return buildService.list(userId(authentication));
    }

    @PostMapping
    public BuildResponse create(Authentication authentication,
                                @Valid @RequestBody BuildRequest request) {
        return buildService.create(userId(authentication), request);
    }

    @GetMapping("/{id}")
    public BuildResponse get(Authentication authentication, @PathVariable Long id) {
        return buildService.get(userId(authentication), id);
    }

    @PutMapping("/{id}")
    public BuildResponse update(Authentication authentication, @PathVariable Long id,
                                @Valid @RequestBody BuildRequest request) {
        return buildService.update(userId(authentication), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(Authentication authentication, @PathVariable Long id) {
        buildService.delete(userId(authentication), id);
    }

    private Long userId(Authentication authentication) {
        User user = authService.findByUsername(authentication.getName());
        return user.getId();
    }
}
