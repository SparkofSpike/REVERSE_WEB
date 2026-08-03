package com.test.engine.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved deck (构筑) owned by a user: card pack, deployed characters and
 * the chosen initial perk.
 */
@Entity
@Table(name = "builds")
@Getter
@Setter
public class Build {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 32)
    private String packId;

    @ElementCollection
    @CollectionTable(name = "build_characters", joinColumns = @JoinColumn(name = "build_id"))
    @Column(name = "character_id", length = 32)
    private List<String> characterIds = new ArrayList<>();

    @Column(length = 64)
    private String initialPerkId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
