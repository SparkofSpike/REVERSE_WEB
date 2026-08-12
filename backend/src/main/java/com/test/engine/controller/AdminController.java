package com.test.engine.controller;

import com.test.engine.dto.AdminUserView;
import com.test.engine.dto.EnabledRequest;
import com.test.engine.dto.RoleRequest;
import com.test.engine.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Super-admin (OP) account management. The whole mapping is guarded by
 * hasRole("OP") in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<AdminUserView> list() {
        return adminService.listUsers();
    }

    @PatchMapping("/{id}/role")
    public AdminUserView setRole(Authentication authentication, @PathVariable Long id,
                                 @Valid @RequestBody RoleRequest request) {
        return adminService.setRole(requireOperatorId(authentication), id, request.getRole());
    }

    @PatchMapping("/{id}/enabled")
    public AdminUserView setEnabled(Authentication authentication, @PathVariable Long id,
                                    @Valid @RequestBody EnabledRequest request) {
        return adminService.setEnabled(requireOperatorId(authentication), id, request.isEnabled());
    }

    /** User id placed into the auth details by JwtAuthFilter. */
    private Long requireOperatorId(Authentication authentication) {
        return (Long) authentication.getDetails();
    }
}
