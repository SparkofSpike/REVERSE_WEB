package com.test.engine.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight SSE signal channel for PVP battles. Subscribers only receive a
 * "refresh" ping (no battle data - the client pulls the real state through the
 * authenticated REST API afterwards), so the endpoint can stay unauthenticated.
 * Battle ids are random 16-hex strings and expose nothing sensitive.
 */
@Service
public class PvpEventService {

    private static final long EMITTER_TIMEOUT_MS = 60_000L;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String battleId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Set<SseEmitter> set = subscribers.computeIfAbsent(battleId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        emitter.onCompletion(() -> remove(battleId, set, emitter));
        emitter.onTimeout(() -> remove(battleId, set, emitter));
        emitter.onError(e -> remove(battleId, set, emitter));
        // initial ping: the client may have missed the change that started the stream
        send(emitter, battleId);
        return emitter;
    }

    private void remove(String battleId, Set<SseEmitter> set, SseEmitter emitter) {
        set.remove(emitter);
        // drop the map key when nobody subscribes anymore (map hygiene)
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
            if (!send(emitter, battleId)) {
                set.remove(emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter, String battleId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("refresh")
                    .data(Map.of("type", "refresh", "t", System.currentTimeMillis())));
            return true;
        } catch (IOException | IllegalStateException e) {
            // client gone or emitter already completed; drop it
            return false;
        }
    }
}
