package com.test.engine.service;

import com.test.engine.combat.CombatEngine;
import com.test.engine.dto.PveRoomView;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.CharacterTemplate;
import com.test.engine.model.PuppetTemplateProvider;
import com.test.engine.model.PveRoom;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PVE room lobby: create/list/join/ready rooms in memory. The host picks the
 * card pack and the enemies; players join, pick their characters and mark
 * ready; the battle auto-starts once EVERY member is ready. Waiting rooms
 * expire after 10 minutes; started and finished rooms after one hour (lazy
 * reaping on every read/write, matching the battle TTL).
 */
@Service
public class PveRoomService {

    private static final long WAITING_TTL_MS = 10 * 60 * 1000L;
    private static final long SETTLED_TTL_MS = 60 * 60 * 1000L;

    private final Map<String, PveRoom> rooms = new ConcurrentHashMap<>();
    private final CardPackLoader cardPackLoader;
    private final PuppetTemplateProvider puppetProvider;
    private final CombatEngine engine;

    public PveRoomService(CardPackLoader cardPackLoader, PuppetTemplateProvider puppetProvider,
                          CombatEngine engine) {
        this.cardPackLoader = cardPackLoader;
        this.puppetProvider = puppetProvider;
        this.engine = engine;
    }

    public PveRoomView create(String username, String packId, String password, List<String> enemyIds) {
        validateEnemies(enemyIds);
        PveRoom room = new PveRoom();
        room.setId(UUID.randomUUID().toString().substring(0, 8));
        room.setHostUsername(username);
        room.setPackId(packId);
        room.setEnemyIds(List.copyOf(enemyIds));
        if (password != null && !password.isBlank()) {
            room.setPasswordHash(sha256(password));
        }
        PveRoom.Seat hostSeat = new PveRoom.Seat();
        hostSeat.setUsername(username);
        hostSeat.setHost(true);
        room.getSeats().add(hostSeat);
        reap();
        rooms.put(room.getId(), room);
        return toView(room);
    }

    public List<PveRoomView> list() {
        reap();
        return rooms.values().stream()
                .filter(r -> PveRoom.STATUS_WAITING.equals(r.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toView)
                .toList();
    }

    public PveRoomView get(String roomId) {
        reap();
        PveRoom room = find(roomId);
        syncStatus(room);
        return toView(room);
    }

    /** Joining is synchronized so two players racing for a seat cannot both pass. */
    public synchronized PveRoomView join(String username, String roomId, String password) {
        reap();
        PveRoom room = find(roomId);
        if (!PveRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("房间已开始或已结束");
        }
        if (room.seatOf(username) != null) {
            throw new BusinessException("你已在房间中");
        }
        if (room.getPasswordHash() != null
                && !MessageDigest.isEqual(room.getPasswordHash().getBytes(StandardCharsets.UTF_8),
                sha256(password).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("房间密码错误");
        }
        PveRoom.Seat seat = new PveRoom.Seat();
        seat.setUsername(username);
        room.getSeats().add(seat);
        return toView(room);
    }

    /**
     * Picks the player's characters and marks them ready. Once EVERY member
     * is ready the battle auto-starts (host included - the host also sits a
     * seat and must ready up).
     */
    public synchronized PveRoomView ready(String username, String roomId, List<String> characterIds) {
        reap();
        PveRoom room = find(roomId);
        if (!PveRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("房间已开始或已结束");
        }
        PveRoom.Seat seat = room.seatOf(username);
        if (seat == null) {
            throw new BusinessException("请先加入房间");
        }
        validateCharacters(room.getPackId(), characterIds);
        seat.setCharacterIds(List.copyOf(characterIds));
        seat.setReady(true);
        tryAutoStart(room);
        return toView(room);
    }

    /** Un-readies the player so they can change characters or leave. */
    public synchronized PveRoomView unready(String username, String roomId) {
        PveRoom room = find(roomId);
        if (!PveRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("房间已开始或已结束");
        }
        PveRoom.Seat seat = room.seatOf(username);
        if (seat == null) {
            throw new BusinessException("请先加入房间");
        }
        seat.setReady(false);
        return toView(room);
    }

    /**
     * A player leaves a waiting room: the seat frees up. The host deleting
     * the room is handled by delete(); leaving as host cancels the room.
     */
    public synchronized PveRoomView leave(String username, String roomId) {
        PveRoom room = find(roomId);
        if (!PveRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("战斗已开始，无法退出");
        }
        if (room.getHostUsername().equals(username)) {
            rooms.remove(roomId);
            return null;
        }
        PveRoom.Seat seat = room.seatOf(username);
        if (seat == null) {
            throw new BusinessException("你不在房间中");
        }
        room.getSeats().remove(seat);
        return toView(room);
    }

    /** Host may cancel a waiting room; the battle itself is untouched once started. */
    public void delete(String username, String roomId) {
        PveRoom room = find(roomId);
        if (!room.getHostUsername().equals(username)) {
            throw new BusinessException("只有房主可以删除房间");
        }
        if (!PveRoom.STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException("战斗已开始，无法删除房间");
        }
        rooms.remove(roomId);
    }

    // ===================== internals =====================

    /** Starts the battle when every seat is ready; returns the battle id. */
    private String tryAutoStart(PveRoom room) {
        if (room.getSeats().isEmpty() || room.getSeats().stream().anyMatch(s -> !s.isReady())) {
            return null;
        }
        LinkedHashMap<String, List<String>> charactersByUser = new LinkedHashMap<>();
        for (PveRoom.Seat seat : room.getSeats()) {
            charactersByUser.put(seat.getUsername(), new ArrayList<>(seat.getCharacterIds()));
        }
        String battleId = engine.createPveBattle(room.getPackId(), room.getEnemyIds(), charactersByUser).getId();
        room.setBattleId(battleId);
        room.setStatus(PveRoom.STATUS_PLAYING);
        return battleId;
    }

    private void validateEnemies(List<String> enemyIds) {
        if (enemyIds == null || enemyIds.isEmpty()) {
            throw new BusinessException("至少选择一个敌人");
        }
        for (String enemyId : enemyIds) {
            try {
                puppetProvider.getPuppet(enemyId);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("未知敌人: " + enemyId);
            }
        }
    }

    private void validateCharacters(String packId, List<String> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            throw new BusinessException("至少部署一个角色");
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

    private PveRoom find(String roomId) {
        PveRoom room = rooms.get(roomId);
        if (room == null) {
            throw new BusinessException("房间不存在或已过期");
        }
        return room;
    }

    /** Marks a room FINISHED when its battle ended (the frontend polls room state). */
    private void syncStatus(PveRoom room) {
        if (!PveRoom.STATUS_PLAYING.equals(room.getStatus()) || room.getBattleId() == null) {
            return;
        }
        try {
            if (engine.getBattle(room.getBattleId()).isOver()) {
                room.setStatus(PveRoom.STATUS_FINISHED);
            }
        } catch (IllegalArgumentException ignored) {
            // battle reaped: treat the room as finished
            room.setStatus(PveRoom.STATUS_FINISHED);
        }
    }

    private void reap() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(e -> {
            PveRoom r = e.getValue();
            long age = now - r.getCreatedAt().toEpochMilli();
            if (PveRoom.STATUS_WAITING.equals(r.getStatus())) {
                return age > WAITING_TTL_MS;
            }
            return age > SETTLED_TTL_MS;
        });
    }

    private PveRoomView toView(PveRoom room) {
        PveRoomView view = new PveRoomView();
        view.setId(room.getId());
        view.setHostUsername(room.getHostUsername());
        view.setLocked(room.getPasswordHash() != null);
        view.setPackId(room.getPackId());
        view.setEnemyIds(room.getEnemyIds());
        view.setStatus(room.getStatus());
        view.setBattleId(room.getBattleId());
        view.setCreatedAt(room.getCreatedAt());
        List<PveRoomView.SeatView> seats = new ArrayList<>();
        for (PveRoom.Seat seat : room.getSeats()) {
            PveRoomView.SeatView seatView = new PveRoomView.SeatView();
            seatView.setUsername(seat.getUsername());
            seatView.setCharacterIds(seat.getCharacterIds());
            seatView.setReady(seat.isReady());
            seatView.setHost(seat.isHost());
            seatView.setJoinedAt(seat.getJoinedAt());
            seats.add(seatView);
        }
        view.setSeats(seats);
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
