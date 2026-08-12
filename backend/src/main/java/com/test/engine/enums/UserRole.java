package com.test.engine.enums;

/**
 * Persistent account roles. OP (super admin) is intentionally not part of
 * this enum: OP powers come from the app.admin-ids configuration so they can
 * never be revoked through the admin UI itself, and OP always implies ADMIN.
 */
public enum UserRole {
    USER,
    ADMIN
}
