package com.test.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private String username;
    /** Effective role: USER, ADMIN or OP (OP is derived from config). */
    private String role;
    /** Display name; null when unset. */
    private String nickname;
    /** Avatar URL; null when unset. */
    private String avatarUrl;
}
