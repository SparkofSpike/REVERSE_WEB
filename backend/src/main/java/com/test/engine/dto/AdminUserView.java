package com.test.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Admin-facing user row; never exposes the password hash.
 */
@Data
@AllArgsConstructor
public class AdminUserView {

    private Long id;
    private String username;
    private String nickname;
    /** Effective role: USER, ADMIN or OP (derived from config). */
    private String role;
    private boolean enabled;
    private Instant createdAt;
}
