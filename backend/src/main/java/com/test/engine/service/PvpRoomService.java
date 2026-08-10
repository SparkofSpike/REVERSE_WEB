package com.test.engine.service;

import com.test.engine.combat.CombatEngine;
import com.test.engine.dto.PvpRoomView;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.CharacterTemplate;
import com.test.engine.model.PvpRoom;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PVP room lobby: create/list/join/start rooms in memory. Waiting rooms expire
 * after 10 minutes; started and finished rooms after one hour (lazy reaping
 * on every read/write, matching the battle TTL).
 */
@Service
public class PvpRoomService {

    private static final long WAITING_TTL_MS = 10 * 60 * 1000L;
    private static final long SETTLED_TTL_MS = 60 * 60 * 1000L;
    private static final int MAX_CHARACTERS = 4;

    private final Map<String, PvpRoom> rooms = new ConcurrentHashMap<>();
    private final CardPackLoader cardPackLoader;
    private final CombatEngine engine;

    public PvpRoomService(CardPackLoader cardPackLoader, CombatEngine engine) {
        this.cardPackLoader = cardPackLoader;
        this.engine = engine;
    }

    public PvpRoomView create(String username, String packId, String password, List<String> hostCharacterIds) {
        validatePackAndCharacters(packId, hostCharacterIds);
        PvpRoom room = new PvpRoom();
        room.setId(UUID.randomUUID().toString().substring(0, 8));
        room.setHostUsername(username);
        room.setPackId(packId);
        room.setHostCharacterIds(List.copyOf(hostCharacterIds));
        if (password != null && !password.isBlank()) {
            room.setPasswordHash(sha256(password));
        }
        reap();
        rooms.put(room.getId(), room);
        return toView(room);
    }

    public List<PvpRoomView> list() {
        reap();
        return rooms.values().stream()
                .filter(r -> PvpRoom.STATUS_WAITING.equals(r.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toView)
                .toList();
    }

    public PvpRoomView get(String roomId) {
        reap();
        PvpRoom room = find(roomId);
        syncStatus(room);
        return toView(room);
    }

    /**
     * Joining is synchronized so two guests racing for the last seat cannot
     * both pass the "already full" check and overwrite each other.
     */
    public synchronized PvpRoomView join(String username, String roomId, String password, List<String> guestCharacterIds) {
        reap();
        PvpRoom room = find(roomId);
        if (!PvpRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("房间已开始或已结束");
        }
        if (username.equals(room.getHostUsername())) {
            throw new BusinessException("不能加入自己创建的房间");
        }
        if (room.getGuestUsername() != null) {
            throw new BusinessException("房间已满");
        }
        if (room.getPasswordHash() != null && !MessageDigest.isEqual(
                room.getPasswordHash().getBytes(StandardCharsets.UTF_8),
                sha256(password == null ? "" : password).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("房间密码错误");
        }
        validatePackAndCharacters(room.getPackId(), guestCharacterIds);
        room.setGuestUsername(username);
        room.setGuestCharacterIds(List.copyOf(guestCharacterIds));
        return toView(room);
    }

    /** Host starts the battle once both sides deployed their characters. */
    public String start(String username, String roomId) {
        reap();
        PvpRoom room = find(roomId);
        if (!room.getHostUsername().equals(username)) {
            throw new BusinessException("只有房主可以开始战斗");
        }
        if (room.getGuestUsername() == null) {
            throw new BusinessException("等待对手加入");
        }
        if (!PvpRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("房间已开始或已结束");
        }
        String battleId = engine.createPvpBattle(room.getPackId(), room.getHostCharacterIds(),
                room.getGuestCharacterIds(), room.getHostUsername(), room.getGuestUsername()).getId();
        room.setBattleId(battleId);
        room.setStatus(PvpRoom.STATUS_PLAYING);
        return battleId;
    }

    /**
     * The guest may leave a waiting room: the seat frees up so another
     * challenger can join (a ghost guest would otherwise force the host to
     * play against a 30s-timeout AI all match).
     */
    public synchronized PvpRoomView leave(String username, String roomId) {
        PvpRoom room = find(roomId);
        if (!room.getGuestUsername().equals(username)) {
            throw new BusinessException("只有挑战者可以退出房间");
        }
        if (!PvpRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("战斗已开始，无法退出");
        }
        room.setGuestUsername(null);
        room.setGuestCharacterIds(List.of());
        return toView(room);
    }

    /** Host may cancel a waiting room; the battle itself is untouched once started. */
    public void delete(String username, String roomId) {
        PvpRoom room = find(roomId);
        if (!room.getHostUsername().equals(username)) {
            throw new BusinessException("只有房主可以删除房间");
        }
        if (!PvpRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("战斗已开始，无法删除房间");
        }
        rooms.remove(roomId);
    }

    // ===================== internals =====================

    private void validatePackAndCharacters(String packId, List<String> characterIds) {
        if (characterIds == null || characterIds.isEmpty() || characterIds.size() > MAX_CHARACTERS) {
            throw new BusinessException("需要部署 1-" + MAX_CHARACTERS + " 名角色");
        }
        CardPack pack = cardPackLoader.get(packId);
        for (String characterId : characterIds) {
            boolean known = pack.getCharacters().stream()
                    .map(CharacterTemplate::getId)
                    .anyMatch(characterId::equals);
            if (!known) {
                throw new BusinessException("角色 " + characterId + " 不属于卡包 " + packId);
            }
        }
    }

    private PvpRoom find(String roomId) {
        PvpRoom room = rooms.get(roomId);
        if (room == null) {
            throw new BusinessException("房间不存在或已过期");
        }
        return room;
    }

    /** Marks a room FINISHED when its battle ended (the frontend polls room state). */
    private void syncStatus(PvpRoom room) {
        if (!PvpRoom.STATUS_PLAYING.equals(room.getStatus()) || room.getBattleId() == null) {
            return;
        }
        try {
            if (engine.getBattle(room.getBattleId()).isOver()) {
                room.setStatus(PvpRoom.STATUS_FINISHED);
            }
        } catch (IllegalArgumentException ignored) {
            // battle reaped: treat the room as finished
            room.setStatus(PvpRoom.STATUS_FINISHED);
        }
    }

    private void reap() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(e -> {
            PvpRoom r = e.getValue();
            long age = now - r.getCreatedAt().toEpochMilli();
            if (PvpRoom.STATUS_WAITING.equals(r.getStatus())) {
                return age > WAITING_TTL_MS;
            }
            return age > SETTLED_TTL_MS;
        });
    }

    private PvpRoomView toView(PvpRoom room) {
        PvpRoomView view = new PvpRoomView();
        view.setId(room.getId());
        view.setHostUsername(room.getHostUsername());
        view.setGuestUsername(room.getGuestUsername());
        view.setLocked(room.getPasswordHash() != null);
        view.setPackId(room.getPackId());
        view.setHostCharacterIds(room.getHostCharacterIds());
        view.setGuestCharacterIds(room.getGuestCharacterIds());
        view.setStatus(room.getStatus());
        view.setBattleId(room.getBattleId());
        view.setCreatedAt(room.getCreatedAt());
        return view;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
