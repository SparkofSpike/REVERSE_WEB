package com.test.engine.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A persisted dummy battle outcome with aggregated stats and the full log.
 */
@Entity
@Table(name = "battle_records")
@Getter
@Setter
public class BattleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String battleId;

    @Column(nullable = false, length = 32)
    private String packId;

    /** PLAYER or ENEMY (engine-side semantics: the side that won). */
    @Column(nullable = false, length = 8)
    private String winner;

    /** PLAYER or ENEMY: which side this record's owner controlled. */
    @Column(length = 8)
    private String mySide;

    /** Opposing human username; null for solo dummy battles. */
    @Column(length = 32)
    private String opponentUsername;

    @Column(nullable = false)
    private int rounds;

    @ElementCollection
    @CollectionTable(name = "record_characters", joinColumns = @JoinColumn(name = "record_id"))
    @Column(name = "character_id", length = 32)
    private List<String> playerCharacterIds = new ArrayList<>();

    /** Total damage dealt by the player team to the dummy. */
    @Column(nullable = false)
    private int totalDamageDealt;

    /** Largest single hit dealt by the player team. */
    @Column(nullable = false)
    private int maxSingleHit;

    @Column(nullable = false)
    private double avgDamagePerRound;

    @Lob
    @Column
    private String logJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
