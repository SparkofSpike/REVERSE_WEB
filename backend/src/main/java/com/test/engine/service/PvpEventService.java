package com.test.engine.service;

import com.test.engine.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight SSE signal channel for PVP battles. Subscribers only receive a
 * "refresh" ping (no battle data - the client pulls the real state through the
 * authenticated REST API afterwards), so the endpoint can stay unauthenticated.
 * The public channel is bounded to prevent arbitrary connection exhaustion.
 */
@Service
public class PvpEventService {

    private static final long EMITTER_TIMEOUT_MS = 60_000L;
    private static final int MAX_CONNECTIONS_PER_BATTLE = 8;
    private static final int MAX_CONNECTIONS_PER_CLIENT = 8;
    private static final int MAX_CONNECTIONS_TOTAL = 256;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<SseEmitter, String> emitterClients = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectionsByClient = new HashMap<>();
    private int totalConnections;

    /**
     * Registers a signal-only subscription. {@code clientKey} is the remote
     * address supplied by the controller; it is used only for rate limiting.
     */
    public SseEmitter subscribe(String battleId, String clientKey) {
        String client = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Set<SseEmitter> set;
        synchronized (this) {
            set = subscribers.computeIfAbsent(battleId, k -> ConcurrentHashMap.newKeySet());
            int clientConnections = connectionsByClient.getOrDefault(client, 0);
            if (set.size() >= MAX_CONNECTIONS_PER_BATTLE) {
                discardEmptyBattleSet(battleId, set);
                throw new BusinessException("该战斗的实时连接已达上限");
            }
            if (clientConnections >= MAX_CONNECTIONS_PER_CLIENT) {
                discardEmptyBattleSet(battleId, set);
                throw new BusinessException("实时连接过多，请关闭重复页面");
            }
            if (totalConnections >= MAX_CONNECTIONS_TOTAL) {
                discardEmptyBattleSet(battleId, set);
                throw new BusinessException("实时服务当前连接已满");
            }
            set.add(emitter);
            emitterClients.put(emitter, client);
            connectionsByClient.put(client, clientConnections + 1);
            totalConnections++;
        }
        emitter.onCompletion(() -> remove(battleId, set, emitter));
        emitter.onTimeout(() -> remove(battleId, set, emitter));
        emitter.onError(e -> remove(battleId, set, emitter));
        // Initial ping: the client may have missed the change that started the stream.
        if (!send(emitter)) {
            remove(battleId, set, emitter);
        }
        return emitter;
    }

    /** Backward-compatible convenience for non-HTTP callers. */
    public SseEmitter subscribe(String battleId) {
        return subscribe(battleId, "unknown");
    }

    private void discardEmptyBattleSet(String battleId, Set<SseEmitter> set) {
        if (set.isEmpty()) {
            subscribers.remove(battleId, set);
        }
    }

    private synchronized void remove(String battleId, Set<SseEmitter> set, SseEmitter emitter) {
        String client = emitterClients.remove(emitter);
        if (client == null) {
            return;
        }
        set.remove(emitter);
        totalConnections = Math.max(0, totalConnections - 1);
        int remaining = connectionsByClient.getOrDefault(client, 1) - 1;
        if (remaining <= 0) {
            connectionsByClient.remove(client);
        } else {
            connectionsByClient.put(client, remaining);
        }
        // Drop the map key when nobody subscribes anymore (map hygiene).
        if (set.isEmpty()) {
            subscribers.remove(battleId, set);
        }
    }

    /** Pushes a refresh ping to every subscriber of the battle. */
    public void publish(String battleId) {
        Set<SseEmitter> set = subscribers.get(battleId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            if (!send(emitter)) {
                remove(battleId, set, emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("refresh")
                    .data(Map.of("type", "refresh", "t", System.currentTimeMillis())));
            return true;
        } catch (IOException | IllegalStateException e) {
            // Client gone or emitter already completed; drop it.
            return false;
        }
    }
}
