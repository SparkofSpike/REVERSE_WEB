package com.test.engine.combat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.model.CardPackLoader;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PVE battle flow at the engine level: N players share the PLAYER side with
 * per-player hands and perk gates, decisions resolve only when EVERY player
 * submitted, drafts feed the 30s timeout (no AI fills in for a human), the
 * shared extra-action window, per-player surrender and idle surrender.
 */
class PveCombatEngineTest {

    private CombatEngine engine;
    private CombatState state;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper, "./target/test-cards-engine");
        DiceRoller dice = new DiceRoller(2026L);
        engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice), null);
        LinkedHashMap<String, List<String>> players = new LinkedHashMap<>();
        players.put("host", List.of("warrior", "mage"));
        players.put("guest", List.of("priest"));
        state = engine.createPveBattle("test-1", List.of("training-dummy", "training-dummy"), players);
    }

    private void pickAllInitialPerks() {
        String perk = state.getInitialPerkOptions().get(0).getId();
        engine.selectInitialPerkForUser(state.getId(), "host", perk);
        engine.selectInitialPerkForUser(state.getId(), "guest", perk);
    }

    private List<ActionDecision> attackAllOf(String username) {
        String targetId = state.alive(CombatSide.ENEMY).get(0).getId();
        return state.aliveOf(username).stream()
                .map(c -> ActionDecision.base(c.getId(), "ATTACK", targetId))
                .toList();
    }

    @Test
    void createPveBattleAssemblesPlayersAndEnemies() {
        assertThat(state.isPve()).isTrue();
        assertThat(state.isPvp()).isFalse();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
        assertThat(state.playerUsers()).containsExactly("host", "guest");
        // host's two characters are marked with their owner; guest has one
        assertThat(state.alive(CombatSide.PLAYER)).hasSize(3);
        assertThat(state.alive(CombatSide.PLAYER).stream()
                .filter(c -> c.getOwnerUsername().equals("host"))).hasSize(2);
        assertThat(state.alive(CombatSide.PLAYER).stream()
                .filter(c -> c.getOwnerUsername().equals("guest"))).hasSize(1);
        // enemies get unique ids so several can fight side by side
        assertThat(state.alive(CombatSide.ENEMY)).hasSize(2);
        assertThat(state.alive(CombatSide.ENEMY).get(0).getId()).isEqualTo("enemy-1");
        assertThat(state.alive(CombatSide.ENEMY).get(1).getId()).isEqualTo("enemy-2");
        // every player drew their own hand from their own deck
        assertThat(state.handOf("host")).hasSize(2);
        assertThat(state.handOf("guest")).hasSize(2);
        assertThat(state.deckOf("host")).isNotEmpty();
        assertThat(state.deckOf("guest")).isNotEmpty();
    }

    @Test
    void initialPerkWaitsForEveryPlayer() {
        engine.selectInitialPerkForUser(state.getId(), "host", state.getInitialPerkOptions().get(0).getId());
        // still waiting for the guest
        assertThat(state.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
        assertThat(state.initialPerkPickedBy("host")).isTrue();
        assertThat(state.initialPerkPickedBy("guest")).isFalse();

        engine.selectInitialPerkForUser(state.getId(), "guest", state.getInitialPerkOptions().get(1).getId());
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
    }

    @Test
    void decideResolvesOnlyWhenEveryPlayerSubmitted() {
        pickAllInitialPerks();

        engine.decideForUser(state.getId(), "host", attackAllOf("host"));
        // fog of war: the guest has not acted, nothing is resolved
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.submittedBy("host")).isTrue();
        assertThat(state.submittedBy("guest")).isFalse();
        assertThat(state.getLogs().stream().noneMatch(e -> "speed".equals(e.getType()))).isTrue();

        engine.decideForUser(state.getId(), "guest", attackAllOf("guest"));
        assertThat(state.getRound()).isGreaterThan(1);
        assertThat(state.getLogs().stream().anyMatch(e -> "speed".equals(e.getType()))).isTrue();
    }

    @Test
    void decisionsAreScopedToThePlayersOwnCharacters() {
        pickAllInitialPerks();
        // steal the guest's character id
        String guestUnit = state.aliveOf("guest").get(0).getId();
        assertThatThrownBy(() -> engine.decideForUser(state.getId(), "host",
                List.of(ActionDecision.base(guestUnit, "ATTACK", state.alive(CombatSide.ENEMY).get(0).getId()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.submittedBy("host")).isFalse();
    }

    @Test
    void playerCannotSubmitTwiceInOneRound() {
        pickAllInitialPerks();
        engine.decideForUser(state.getId(), "host", attackAllOf("host"));
        assertThatThrownBy(() -> engine.decideForUser(state.getId(), "host", attackAllOf("host")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutSubmitsTheSavedDraftNotAiMoves() {
        pickAllInitialPerks();
        List<ActionDecision> draft = attackAllOf("host");
        engine.saveDraft(state.getId(), "host", draft);
        // guest walks away without a draft; the window expires
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1000);
        engine.tickDeadlines();

        // the timeout resolved the round: the host's draft was applied, the
        // guest submitted nothing (their characters skip) and the round
        // advanced (submission gates reset for round 2)
        assertThat(state.getRound()).isGreaterThan(1);
        // the merged decisions were applied to the round (pendingByUser is
        // cleared by the next round's gate reset, so inspect pendingDecisions)
        List<ActionDecision> merged = state.getPendingDecisions();
        assertThat(merged.stream().anyMatch(d -> d.getCombatantId().startsWith("warrior-")
                || d.getCombatantId().startsWith("mage-"))).isTrue();
        assertThat(merged.stream().noneMatch(d -> d.getCombatantId().startsWith("priest-"))).isTrue();
        // enemy side still got AI decisions
        List<ActionDecision> enemy = merged.stream()
                .filter(d -> {
                    Combatant c = state.find(d.getCombatantId());
                    return c != null && c.getSide() == CombatSide.ENEMY;
                })
                .toList();
        assertThat(enemy).hasSize(2);
    }

    @Test
    void draftRejectsOtherPlayersCharacters() {
        pickAllInitialPerks();
        String guestUnit = state.aliveOf("guest").get(0).getId();
        assertThatThrownBy(() -> engine.saveDraft(state.getId(), "host",
                List.of(ActionDecision.base(guestUnit, "ATTACK", state.alive(CombatSide.ENEMY).get(0).getId()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extraActionWindowIsSharedUntilEveryoneDone() {
        pickAllInitialPerks();
        // grant the host's warrior an extra action and both submit their mains
        Combatant hostWarrior = state.aliveOf("host").stream()
                .filter(c -> c.getTemplateId().equals("warrior"))
                .findFirst().orElseThrow();
        hostWarrior.setExtraActionsThisTurn(1);
        engine.decideForUser(state.getId(), "host", attackAllOf("host"));
        engine.decideForUser(state.getId(), "guest", attackAllOf("guest"));

        // the shared window opened (host has a charge, guest does not)
        assertThat(state.isExtraActionRound()).isTrue();
        assertThat(state.getExtraRoundSide()).isNull();
        assertThat(state.extraDoneBy("guest")).isTrue();
        assertThat(state.extraDoneBy("host")).isFalse();

        // host spends the charge
        String enemyTarget = state.alive(CombatSide.ENEMY).get(0).getId();
        engine.decideExtraActionsForUser(state.getId(), "host",
                List.of(ActionDecision.base(hostWarrior.getId(), "ATTACK", enemyTarget)));
        assertThat(state.isExtraActionRound()).isFalse();
        assertThat(state.getRound()).isEqualTo(2);
    }

    @Test
    void extraWindowCanBeSkippedByEachPlayer() {
        pickAllInitialPerks();
        Combatant hostWarrior = state.aliveOf("host").stream()
                .filter(c -> c.getTemplateId().equals("warrior"))
                .findFirst().orElseThrow();
        hostWarrior.setExtraActionsThisTurn(1);
        engine.decideForUser(state.getId(), "host", attackAllOf("host"));
        engine.decideForUser(state.getId(), "guest", attackAllOf("guest"));

        engine.skipExtraActionsForUser(state.getId(), "host");
        assertThat(state.isExtraActionRound()).isFalse();
        assertThat(state.getRound()).isEqualTo(2);
    }

    @Test
    void surrenderWithdrawsOnlyTheSurrenderingPlayer() {
        pickAllInitialPerks();
        engine.surrenderForUser(state.getId(), "guest");
        // guest's priest is gone but the host's team keeps fighting
        assertThat(state.aliveOf("guest")).isEmpty();
        assertThat(state.alive(CombatSide.PLAYER)).hasSize(2);
        assertThat(state.isOver()).isFalse();
    }

    @Test
    void surrenderOfTheWholeTeamLosesTheBattle() {
        pickAllInitialPerks();
        engine.surrenderForUser(state.getId(), "host");
        engine.surrenderForUser(state.getId(), "guest");
        assertThat(state.isOver()).isTrue();
        assertThat(state.getWinner()).isEqualTo("ENEMY");
    }

    @Test
    void idlePlayerLosesTheirCharactersAfterThreeRounds() {
        pickAllInitialPerks();
        // the guest never submits nor saves drafts; the host keeps playing
        for (int round = 0; round < 3; round++) {
            state.setDecisionDeadlineAt(System.currentTimeMillis() - 1000);
            engine.tickDeadlines();
            if (state.isOver()) {
                break;
            }
        }
        assertThat(state.aliveOf("guest")).isEmpty();
        assertThat(state.idleRoundsOf("guest")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void battleFinishesWhenEnemiesAreDefeated() {
        pickAllInitialPerks();
        // 1d9999 damage one-shot every enemy through decisions (dice are not
        // mocked, so use many rounds of heavy attacks via a custom loop)
        for (int guard = 0; guard < 200 && !state.isOver(); guard++) {
            if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
                engine.skipSpecialPerkForUser(state.getId(), "host");
                engine.skipSpecialPerkForUser(state.getId(), "guest");
                continue;
            }
            if (state.isExtraActionRound()) {
                engine.skipExtraActionsForUser(state.getId(), "host");
                continue;
            }
            if (state.submittedBy("host") && state.submittedBy("guest")) {
                continue;
            }
            engine.decideForUser(state.getId(), "host", attackAllOf("host"));
            if (!state.submittedBy("guest")) {
                engine.decideForUser(state.getId(), "guest", attackAllOf("guest"));
            }
        }
        assertThat(state.isOver()).as("pve battle must finish, round " + state.getRound()).isTrue();
    }

    @Test
    void playersGetIndependentDrawEnergy() {
        pickAllInitialPerks();
        // every player gains the same energy per round end (team-wide first strike)
        state.addDrawEnergy("host", 3);
        assertThat(state.drawEnergyOf("host")).isEqualTo(3);
        assertThat(state.drawEnergyOf("guest")).isZero();
    }
}
