package com.test.engine.controller;

import com.test.engine.dto.PvpRoomView;
import com.test.engine.service.PvpEventService;
import com.test.engine.service.PvpRoomService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * PVP room lobby endpoints plus the unauthenticated SSE refresh channel.
 */
@RestController
@RequestMapping("/api/pvp")
public class PvpRoomController {

    private final PvpRoomService roomService;
    private final PvpEventService eventService;

    public PvpRoomController(PvpRoomService roomService, PvpEventService eventService) {
        this.roomService = roomService;
        this.eventService = eventService;
    }

    @GetMapping("/rooms")
    public List<PvpRoomView> listRooms() {
        return roomService.list();
    }

    @PostMapping("/rooms")
    public PvpRoomView createRoom(Authentication authentication, @RequestBody CreateRoomRequest request) {
        return roomService.create(authentication.getName(), request.packId, request.password, request.hostCharacterIds);
    }

    @GetMapping("/rooms/{roomId}")
    public PvpRoomView getRoom(@PathVariable String roomId) {
        return roomService.get(roomId);
    }

    @PostMapping("/rooms/{roomId}/join")
    public PvpRoomView joinRoom(Authentication authentication, @PathVariable String roomId,
                                @RequestBody JoinRoomRequest request) {
        return roomService.join(authentication.getName(), roomId, request.password, request.guestCharacterIds);
    }

    @PostMapping("/rooms/{roomId}/start")
    public Map<String, String> startRoom(Authentication authentication, @PathVariable String roomId) {
        return Map.of("battleId", roomService.start(authentication.getName(), roomId));
    }

    @PostMapping("/rooms/{roomId}/leave")
    public PvpRoomView leaveRoom(Authentication authentication, @PathVariable String roomId) {
        return roomService.leave(authentication.getName(), roomId);
    }

    @DeleteMapping("/rooms/{roomId}")
    public void deleteRoom(Authentication authentication, @PathVariable String roomId) {
        roomService.delete(authentication.getName(), roomId);
    }

    /**
     * SSE refresh signal for a battle: subscribers only get a ping and then
     * pull the real state through the authenticated combat API. Deliberately
     * unauthenticated (see SecurityConfig) - the channel carries no data.
     */
    @GetMapping("/events/{battleId}")
    public SseEmitter subscribe(@PathVariable String battleId) {
        return eventService.subscribe(battleId);
    }

    public record CreateRoomRequest(
            @NotEmpty(message = "请选择卡包") String packId,
            String password,
            @NotEmpty(message = "至少部署一个角色")
            @Size(min = 1, max = 4, message = "角色数量需在 1-4 之间")
            List<String> hostCharacterIds) {
    }

    public record JoinRoomRequest(
            String password,
            @NotEmpty(message = "至少部署一个角色")
            @Size(min = 1, max = 4, message = "角色数量需在 1-4 之间")
            List<String> guestCharacterIds) {
    }
}
