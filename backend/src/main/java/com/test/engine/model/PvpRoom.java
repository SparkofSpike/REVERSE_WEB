package com.test.engine.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory PVP room: a waiting lobby where the host picks the card pack and
 * both sides pick their characters before the battle starts. Rooms are never
 * persisted; finished or stale rooms are reaped by the room service.
 */
@Getter
@Setter
public class PvpRoom {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_FINISHED = "FINISHED";

    private String id;
    private String hostUsername;
    /** Null until a guest joins. */
    private String guestUsername;
    /** SHA-256 of the join password; null for public rooms. */
    private String passwordHash;
    private String packId;
    private List<String> hostCharacterIds = new ArrayList<>();
    private List<String> guestCharacterIds = new ArrayList<>();
    private String status = STATUS_WAITING;
    /** Set when the battle starts. */
    private String battleId;
    private Instant createdAt = Instant.now();
}
