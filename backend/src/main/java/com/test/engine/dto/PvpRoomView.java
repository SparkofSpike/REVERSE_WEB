package com.test.engine.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Frontend facing PVP room snapshot (the password is never exposed).
 */
@Data
public class PvpRoomView {

    private String id;
    private String hostUsername;
    private String guestUsername;
    private boolean locked;
    private String packId;
    private List<String> hostCharacterIds;
    private List<String> guestCharacterIds;
    private String status;
    private String battleId;
    private Instant createdAt;
}
