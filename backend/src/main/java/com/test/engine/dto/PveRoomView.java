package com.test.engine.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Frontend facing PVE room snapshot (the password is never exposed).
 */
@Data
public class PveRoomView {

    @Data
    public static class SeatView {
        private String username;
        private List<String> characterIds;
        private boolean ready;
        private boolean host;
        private Instant joinedAt;
    }

    private String id;
    private String hostUsername;
    private boolean locked;
    private String packId;
    private List<String> enemyIds;
    private String status;
    private String battleId;
    private Instant createdAt;
    private List<SeatView> seats;
}
