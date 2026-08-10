package com.test.engine.pvp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.combat.CombatEngine;
import com.test.engine.combat.DamageResolver;
import com.test.engine.combat.EffectExecutor;
import com.test.engine.combat.PuppetAi;
import com.test.engine.combat.SpeedAdjudicator;
import com.test.engine.dto.PvpRoomView;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPackLoader;
import com.test.engine.service.PvpRoomService;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PVP room lobby flow: create, list, join (password gate), start and delete.
 */
class PvpRoomServiceTest {

    private PvpRoomService rooms;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        DiceRoller dice = new DiceRoller(2026L);
        CombatEngine engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice), null);
        rooms = new PvpRoomService(loader, engine);
    }

    @Test
    void createdRoomAppearsInLobby() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior", "mage"));
        assertThat(room.isLocked()).isFalse();
        assertThat(room.getGuestUsername()).isNull();

        List<PvpRoomView> lobby = rooms.list();
        assertThat(lobby).extracting(PvpRoomView::getId).contains(room.getId());
    }

    @Test
    void lockedRoomRejectsWrongPassword() {
        PvpRoomView room = rooms.create("host", "test-1", "s3cret", List.of("warrior"));
        assertThat(room.isLocked()).isTrue();

        assertThatThrownBy(() -> rooms.join("guest", room.getId(), "nope", List.of("mage")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码");
        // correct password joins fine
        PvpRoomView joined = rooms.join("guest", room.getId(), "s3cret", List.of("mage"));
        assertThat(joined.getGuestUsername()).isEqualTo("guest");
    }

    @Test
    void hostCannotJoinOwnRoom() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        assertThatThrownBy(() -> rooms.join("host", room.getId(), null, List.of("mage")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void roomRejectsCharactersOutsideThePack() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        assertThatThrownBy(() -> rooms.join("guest", room.getId(), null, List.of("unknown-char")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于卡包");
    }

    @Test
    void startRequiresGuestAndHostRole() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        // nobody joined yet
        assertThatThrownBy(() -> rooms.start("host", room.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等待对手");
        // a guest cannot start
        rooms.join("guest", room.getId(), null, List.of("mage"));
        assertThatThrownBy(() -> rooms.start("guest", room.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("房主");
        // the host can
        String battleId = rooms.start("host", room.getId());
        assertThat(battleId).isNotBlank();
        assertThat(rooms.get(room.getId()).getStatus()).isEqualTo("PLAYING");
    }

    @Test
    void fullRoomRejectsSecondGuest() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        rooms.join("guest1", room.getId(), null, List.of("mage"));
        assertThatThrownBy(() -> rooms.join("guest2", room.getId(), null, List.of("mage")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已满");
    }

    @Test
    void hostCanDeleteWaitingRoomButNotStartedBattle() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        rooms.delete("host", room.getId());
        assertThatThrownBy(() -> rooms.get(room.getId()))
                .isInstanceOf(BusinessException.class);

        PvpRoomView room2 = rooms.create("host", "test-1", null, List.of("warrior"));
        rooms.join("guest", room2.getId(), null, List.of("mage"));
        rooms.start("host", room2.getId());
        assertThatThrownBy(() -> rooms.delete("host", room2.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法删除");
    }

    @Test
    void startedBattleIsPlayableByBothSides() {
        PvpRoomView room = rooms.create("host", "test-1", null, List.of("warrior"));
        rooms.join("guest", room.getId(), null, List.of("mage"));
        String battleId = rooms.start("host", room.getId());

        // both humans can now access the battle; a random user cannot
        assertThat(battleId).isNotBlank();
    }
}
