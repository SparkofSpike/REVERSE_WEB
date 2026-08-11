package com.test.engine.pve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.combat.CombatEngine;
import com.test.engine.combat.DamageResolver;
import com.test.engine.combat.EffectExecutor;
import com.test.engine.combat.PuppetAi;
import com.test.engine.combat.SpeedAdjudicator;
import com.test.engine.dto.PveRoomView;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPackLoader;
import com.test.engine.service.PveRoomService;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PVE room lobby flow: create with enemies, join (password gate), ready gating
 * the auto-start, unready, leave and delete.
 */
class PveRoomServiceTest {

    private PveRoomService rooms;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        DiceRoller dice = new DiceRoller(2026L);
        CombatEngine engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice), null);
        rooms = new PveRoomService(loader, loader, engine);
    }

    @Test
    void createdRoomSitsTheHostAndListsEnemies() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout", "guard"));
        assertThat(room.isLocked()).isFalse();
        assertThat(room.getEnemyIds()).containsExactly("scout", "guard");
        assertThat(room.getSeats()).extracting(PveRoomView.SeatView::getUsername).containsExactly("host");
        assertThat(room.getSeats().get(0).isReady()).isFalse();
        assertThat(room.getSeats().get(0).isHost()).isTrue();

        assertThat(rooms.list()).extracting(PveRoomView::getId).contains(room.getId());
    }

    @Test
    void createRejectsUnknownEnemies() {
        assertThatThrownBy(() -> rooms.create("host", "test-1", null, List.of("dragon")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void lockedRoomRejectsWrongPassword() {
        PveRoomView room = rooms.create("host", "test-1", "s3cret", List.of("scout"));
        assertThat(room.isLocked()).isTrue();

        assertThatThrownBy(() -> rooms.join("guest", room.getId(), "nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码");
        PveRoomView joined = rooms.join("guest", room.getId(), "s3cret");
        assertThat(joined.getSeats()).extracting(PveRoomView.SeatView::getUsername)
                .containsExactly("host", "guest");
    }

    @Test
    void readyRequiresCharactersFromThePack() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        assertThatThrownBy(() -> rooms.ready("host", room.getId(), List.of("unknown-char")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于卡包");
        assertThatThrownBy(() -> rooms.ready("host", room.getId(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少部署");
    }

    @Test
    void battleAutoStartsWhenEverySeatIsReady() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        rooms.join("guest", room.getId(), null);

        // host ready alone does not start
        PveRoomView afterHost = rooms.ready("host", room.getId(), List.of("warrior", "mage"));
        assertThat(afterHost.getStatus()).isEqualTo("WAITING");
        assertThat(afterHost.getBattleId()).isNull();
        assertThat(afterHost.getSeats().get(0).isReady()).isTrue();
        assertThat(afterHost.getSeats().get(1).isReady()).isFalse();

        // the last member readies -> the battle starts immediately
        PveRoomView started = rooms.ready("guest", room.getId(), List.of("priest"));
        assertThat(started.getStatus()).isEqualTo("PLAYING");
        assertThat(started.getBattleId()).isNotBlank();
    }

    @Test
    void unreadyUnlocksTheRoomAgain() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        rooms.join("guest", room.getId(), null);
        rooms.ready("host", room.getId(), List.of("warrior"));
        rooms.ready("guest", room.getId(), List.of("mage"));
        assertThat(rooms.get(room.getId()).getStatus()).isEqualTo("PLAYING");

        PveRoomView room2 = rooms.create("host2", "test-1", null, List.of("scout"));
        rooms.join("guest2", room2.getId(), null);
        rooms.ready("host2", room2.getId(), List.of("warrior"));
        rooms.unready("host2", room2.getId());
        rooms.ready("guest2", room2.getId(), List.of("mage"));
        // host unreadied: the room must NOT start
        assertThat(rooms.get(room2.getId()).getStatus()).isEqualTo("WAITING");
    }

    @Test
    void singleHostRoomStartsWhenTheHostReadies() {
        PveRoomView room = rooms.create("solo", "test-1", null, List.of("warlord"));
        PveRoomView started = rooms.ready("solo", room.getId(), List.of("warrior"));
        assertThat(started.getStatus()).isEqualTo("PLAYING");
        assertThat(started.getBattleId()).isNotBlank();
    }

    @Test
    void guestLeavesFreeTheSeat() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        rooms.join("guest", room.getId(), null);
        PveRoomView afterLeave = rooms.leave("guest", room.getId());
        assertThat(afterLeave.getSeats()).extracting(PveRoomView.SeatView::getUsername)
                .containsExactly("host");
    }

    @Test
    void hostLeavingCancelsTheRoom() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        PveRoomView result = rooms.leave("host", room.getId());
        assertThat(result).isNull();
        assertThatThrownBy(() -> rooms.get(room.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRequiresHostRoleAndWaitingStatus() {
        PveRoomView room = rooms.create("host", "test-1", null, List.of("scout"));
        rooms.join("guest", room.getId(), null);
        assertThatThrownBy(() -> rooms.delete("guest", room.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("房主");

        PveRoomView room2 = rooms.create("host", "test-1", null, List.of("scout"));
        rooms.ready("host", room2.getId(), List.of("warrior"));
        assertThatThrownBy(() -> rooms.delete("host", room2.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法删除");
    }
}
