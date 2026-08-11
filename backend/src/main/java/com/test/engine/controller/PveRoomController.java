package com.test.engine.controller;

import com.test.engine.dto.EnemyView;
import com.test.engine.dto.PveRoomView;
import com.test.engine.model.PuppetTemplate;
import com.test.engine.model.PuppetTemplateProvider;
import com.test.engine.service.PveRoomService;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PVE room lobby endpoints: enemy templates for room creation plus the room
 * lifecycle (create/list/join/ready/unready/leave/delete). Enemies come from
 * a pluggable provider so a future account system can add admin-DIY content
 * without touching this controller.
 */
@RestController
@RequestMapping("/api/pve")
public class PveRoomController {

    private final PveRoomService roomService;
    private final PuppetTemplateProvider puppetProvider;

    public PveRoomController(PveRoomService roomService, PuppetTemplateProvider puppetProvider) {
        this.roomService = roomService;
        this.puppetProvider = puppetProvider;
    }

    /** All enemy templates the host may pick for a PVE room. */
    @GetMapping("/enemies")
    public List<EnemyView> listEnemies() {
        return puppetProvider.list().stream().map(this::toEnemyView).toList();
    }

    @GetMapping("/rooms")
    public List<PveRoomView> listRooms() {
        return roomService.list();
    }

    @PostMapping("/rooms")
    public PveRoomView createRoom(Authentication authentication, @RequestBody CreateRoomRequest request) {
        return roomService.create(authentication.getName(), request.packId, request.password, request.enemyIds);
    }

    @GetMapping("/rooms/{roomId}")
    public PveRoomView getRoom(@PathVariable String roomId) {
        return roomService.get(roomId);
    }

    @PostMapping("/rooms/{roomId}/join")
    public PveRoomView joinRoom(Authentication authentication, @PathVariable String roomId,
                                @RequestBody JoinRoomRequest request) {
        return roomService.join(authentication.getName(), roomId, request.password);
    }

    /** Picks the player's characters and marks them ready (auto-starts at full ready). */
    @PostMapping("/rooms/{roomId}/ready")
    public PveRoomView readyRoom(Authentication authentication, @PathVariable String roomId,
                                 @RequestBody ReadyRoomRequest request) {
        return roomService.ready(authentication.getName(), roomId, request.characterIds);
    }

    @PostMapping("/rooms/{roomId}/unready")
    public PveRoomView unreadyRoom(Authentication authentication, @PathVariable String roomId) {
        return roomService.unready(authentication.getName(), roomId);
    }

    @PostMapping("/rooms/{roomId}/leave")
    public PveRoomView leaveRoom(Authentication authentication, @PathVariable String roomId) {
        return roomService.leave(authentication.getName(), roomId);
    }

    @DeleteMapping("/rooms/{roomId}")
    public void deleteRoom(Authentication authentication, @PathVariable String roomId) {
        roomService.delete(authentication.getName(), roomId);
    }

    private EnemyView toEnemyView(PuppetTemplate t) {
        EnemyView v = new EnemyView();
        v.setId(t.getId());
        v.setName(t.getName());
        v.setMaxHp(t.getMaxHp());
        v.setMaxEnergy(t.getMaxEnergy());
        v.setSpeedDice(t.getSpeedDice());
        v.setBaseDamageDice(t.getBaseDamageDice());
        v.setBaseDamageType(t.getBaseDamageType());
        v.setPhysicalResistance(t.getPhysicalResistance());
        v.setMagicResistance(t.getMagicResistance());
        v.setBlockDice(t.getBlockDice());
        v.setDodgePenalty(t.getDodgePenalty());
        return v;
    }

    public record CreateRoomRequest(
            @NotEmpty(message = "请选择卡包") String packId,
            String password,
            @NotEmpty(message = "至少选择一个敌人")
            List<String> enemyIds) {
    }

    public record JoinRoomRequest(
            String password) {
    }

    public record ReadyRoomRequest(
            @NotEmpty(message = "至少部署一个角色")
            List<String> characterIds) {
    }
}
