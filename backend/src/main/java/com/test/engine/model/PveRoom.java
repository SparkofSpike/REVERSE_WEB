package com.test.engine.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory PVE room: a waiting lobby where the host picks the card pack and
 * the enemies, players join, each picks their characters and marks ready, and
 * the battle auto-starts once EVERY member is ready. Rooms are never
 * persisted; finished or stale rooms are reaped by the room service.
 */
@Getter
@Setter
public class PveRoom {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_FINISHED = "FINISHED";

    /** One occupied seat; the host sits at creation time. */
    @Getter
    @Setter
    public static class Seat {
        private String username;
        private List<String> characterIds = new ArrayList<>();
        private boolean ready;
        private boolean host;
        private Instant joinedAt = Instant.now();
    }

    private String id;
    private String hostUsername;
    /** SHA-256 of the join password; null for public rooms. */
    private String passwordHash;
    private String packId;
    /** Enemy template ids chosen by the host. */
    private List<String> enemyIds = new ArrayList<>();
    private List<Seat> seats = new ArrayList<>();
    private String status = STATUS_WAITING;
    /** Set when the battle starts. */
    private String battleId;
    private Instant createdAt = Instant.now();

    public Seat seatOf(String username) {
        return seats.stream().filter(s -> s.getUsername().equals(username)).findFirst().orElse(null);
    }
}
