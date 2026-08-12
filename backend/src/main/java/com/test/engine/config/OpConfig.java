package com.test.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Super-admin (OP) accounts configured by user id via app.admin-ids
 * (comma-separated). OP powers are configuration-driven and therefore cannot
 * be granted or revoked through the admin UI.
 */
@Component
public class OpConfig {

    private final Set<Long> adminIds;

    public OpConfig(@Value("${app.admin-ids:}") String adminIds) {
        Set<Long> ids = new HashSet<>();
        if (adminIds != null && !adminIds.isBlank()) {
            for (String part : adminIds.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(Long.parseLong(trimmed));
                }
            }
        }
        this.adminIds = Collections.unmodifiableSet(ids);
    }

    public boolean isOp(Long userId) {
        return userId != null && adminIds.contains(userId);
    }

    public boolean hasAny() {
        return !adminIds.isEmpty();
    }
}
