package com.test.engine.entity;

import com.test.engine.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Registered player account.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** Optional display name shown in the UI; falls back to username. */
    @Column(length = 32)
    private String nickname;

    /** Persistent role; OP is derived from configuration, not stored here. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'USER'")
    private UserRole role = UserRole.USER;

    /** Disabled accounts are rejected at login and lose token validity. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
