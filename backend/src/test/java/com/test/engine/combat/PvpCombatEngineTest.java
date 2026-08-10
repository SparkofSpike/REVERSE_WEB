package com.test.engine.combat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.model.CardPackLoader;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PVP battle flow at the engine level: two-sided initial perk gates, buffered
 * decisions resolved only when BOTH sides submitted (fog of war), the 30s
 * deadline sweeper, the per-side extra-action windows and both-side special
 * perk rounds.
 */
class PvpCombatEngineTest {

    private CombatEngine engine;
    private CombatState state;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        DiceRoller dice = new DiceRoller(2026L);
        engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice), null);
        // enemy side leads with a warrior so the extra-action tests can grant
        // it 连续奔袭 (warrior-s3) on both sides
        state = engine.createPvpBattle("test-1",
                List.of("warrior", "mage"), List.of("warrior", "priest"),
                "host", "guest");
    }

    private List<ActionDecision> attackAll(CombatSide side) {
        CombatSide foe = CombatState.opposite(side);
        String targetId = state.alive(foe).get(0).getId();
        return state.alive(side).stream()
                .map(c -> ActionDecision.base(c.getId(), "ATTACK", targetId))
                .toList();
    }

    private void pickInitialPerks() {
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId(), CombatSide.PLAYER);
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId(), CombatSide.ENEMY);
    }

    @Test
    void pvpBattleStartsWithIndependentHandsAndPerkGates() {
        assertThat(state.isPvp()).isTrue();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
        assertThat(state.alive(CombatSide.PLAYER)).hasSize(2);
        assertThat(state.alive(CombatSide.ENEMY)).hasSize(2);
        // each side drew its own initial hand from its own deck
        assertThat(state.sideHand(CombatSide.PLAYER)).hasSize(2);
        assertThat(state.sideHand(CombatSide.ENEMY)).hasSize(2);
        assertThat(state.initialPerkPicked(CombatSide.PLAYER)).isFalse();
        assertThat(state.initialPerkPicked(CombatSide.ENEMY)).isFalse();
    }

    @Test
    void initialPerkWaitsForBothSides() {
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId(), CombatSide.PLAYER);
        // still waiting for the guest
        assertThat(state.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
        assertThat(state.initialPerkPicked(CombatSide.PLAYER)).isTrue();
        assertThat(state.initialPerkPicked(CombatSide.ENEMY)).isFalse();

        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(1).getId(), CombatSide.ENEMY);
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
    }

    @Test
    void decideWaitsForBothSidesBeforeResolution() {
        pickInitialPerks();

        engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
        // fog of war: only one side submitted, nothing is resolved yet
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.submitted(CombatSide.PLAYER)).isTrue();
        assertThat(state.submitted(CombatSide.ENEMY)).isFalse();
        assertThat(state.getRoundSpeed()).isEmpty();
        assertThat(state.getLogs().stream().noneMatch(e -> "speed".equals(e.getType()))).isTrue();

        // the second submission resolves the round immediately: speed events
        // appear and the round advances (round 2 starts in DECISION again)
        engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        assertThat(state.getRound()).isGreaterThan(1);
        assertThat(state.getLogs().stream().anyMatch(e -> "speed".equals(e.getType()))).isTrue();
    }

    @Test
    void sideCannotSubmitTwiceInOneRound() {
        pickInitialPerks();
        engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
        assertThatThrownBy(() ->
                engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deadlineSweeperAutoSubmitsTheLaggingSide() {
        pickInitialPerks();
        engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);

        // expire the window: the sweeper auto-submits the guest and the round
        // resolves (the fresh round then resets the submission gates)
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();

        assertThat(state.getRound()).isGreaterThan(1);
        assertThat(state.getLogs().stream().anyMatch(e -> e.getMessage().contains("自动提交")
                || e.getMessage().contains("超时"))).isTrue();
    }

    @Test
    void deadlineSweeperAutoPicksInitialPerks() {
        // neither side picked: expire and sweep
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();

        assertThat(state.initialPerkPicked(CombatSide.PLAYER)).isTrue();
        assertThat(state.initialPerkPicked(CombatSide.ENEMY)).isTrue();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
    }

    @Test
    void deadlineSweeperIgnoresSoloBattles() {
        CombatState solo = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        solo.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        // solo battles have no deadline semantics: the perk phase stays
        assertThat(solo.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
    }

    @Test
    void extraActionWindowsAlternateBetweenSides() {
        pickInitialPerks();
        // both sides use 连续奔袭 (warrior-s3) so both earn extra charges;
        // every alive unit needs a decision, the mage just attacks
        grantEnergy(state, CombatSide.PLAYER);
        grantEnergy(state, CombatSide.ENEMY);
        engine.decideSide(state.getId(), CombatSide.PLAYER, decisionsWithSkill(CombatSide.PLAYER, "warrior-s3"));
        engine.decideSide(state.getId(), CombatSide.ENEMY, decisionsWithSkill(CombatSide.ENEMY, "warrior-s3"));

        // host phase ran first; the host's extra window is open (the guest is
        // pre-marked finished because its main actions are still deferred)
        assertThat(state.isExtraActionRound()).isTrue();
        assertThat(state.getExtraRoundSide()).isEqualTo(CombatSide.PLAYER);
        assertThat(state.extraFinished(CombatSide.PLAYER)).isFalse();
        assertThat(state.extraFinished(CombatSide.ENEMY)).isTrue();

        // host spends one extra action then skips the rest
        String hostWarrior = state.alive(CombatSide.PLAYER).get(0).getId();
        String guestTarget = state.alive(CombatSide.ENEMY).get(0).getId();
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(hostWarrior, "ATTACK", guestTarget)), CombatSide.PLAYER);
        assertThat(state.isExtraActionRound()).isTrue();
        engine.skipExtraActions(state.getId(), CombatSide.PLAYER);

        // guest main actions run now, then the guest window opens
        assertThat(state.isExtraActionRound()).isTrue();
        assertThat(state.getExtraRoundSide()).isEqualTo(CombatSide.ENEMY);
        assertThat(state.extraFinished(CombatSide.PLAYER)).isTrue();

        // guest skips: the round finalizes and round 2 starts
        engine.skipExtraActions(state.getId(), CombatSide.ENEMY);
        assertThat(state.isExtraActionRound()).isFalse();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.getRound()).isEqualTo(2);
    }

    @Test
    void deadlineSweeperClosesAnAbandonedExtraWindow() {
        pickInitialPerks();
        grantEnergy(state, CombatSide.PLAYER);
        grantEnergy(state, CombatSide.ENEMY);
        engine.decideSide(state.getId(), CombatSide.PLAYER, decisionsWithSkill(CombatSide.PLAYER, "warrior-s3"));
        engine.decideSide(state.getId(), CombatSide.ENEMY, decisionsWithSkill(CombatSide.ENEMY, "warrior-s3"));
        assertThat(state.getExtraRoundSide()).isEqualTo(CombatSide.PLAYER);

        // the host abandons the extra window: the sweeper must close it and
        // eventually end the round (guest skips by its own deadline)
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        assertThat(state.extraFinished(CombatSide.PLAYER)).isTrue();
        assertThat(state.getExtraRoundSide()).isEqualTo(CombatSide.ENEMY);
        // the guest window gets its own fresh 30s deadline: expire it too
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        assertThat(state.isExtraActionRound()).isFalse();
    }

    @Test
    void specialPerkWaitsForBothSides() {
        pickInitialPerks();
        int safety = 0;
        while (state.getPhase() != CombatPhase.SPECIAL_PERK && safety < 40) {
            safety++;
            if (state.getPhase() != CombatPhase.DECISION) {
                break;
            }
            engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
            engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        }
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);

        engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(0).getId(), CombatSide.PLAYER);
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);
        assertThat(state.specialPerkPicked(CombatSide.ENEMY)).isFalse();

        engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(1).getId(), CombatSide.ENEMY);
        // both picked: the round ends and round 5 starts
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.getRound()).isEqualTo(5);
    }

    @Test
    void genericSkillPlaysArePerSide() {
        pickInitialPerks();
        List<Combatant> hostUnits = state.alive(CombatSide.PLAYER);
        List<Combatant> guestUnits = state.alive(CombatSide.ENEMY);

        // host plays a card from its own hand; the guest hand is untouched
        var hostCard = state.sideHand(CombatSide.PLAYER).get(0);
        int guestHandSize = state.sideHand(CombatSide.ENEMY).size();
        engine.playGenericSkill(state.getId(), hostCard.getId(), hostUnits.get(0).getId(), CombatSide.PLAYER);
        assertThat(state.sideHand(CombatSide.PLAYER)).doesNotContain(hostCard);
        assertThat(state.sideHand(CombatSide.ENEMY)).hasSize(guestHandSize);

        // a guest card is not playable by the host
        var guestCard = state.sideHand(CombatSide.ENEMY).get(0);
        assertThatThrownBy(() -> engine.playGenericSkill(state.getId(), guestCard.getId(),
                guestUnits.get(0).getId(), CombatSide.PLAYER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullPvpBattleRunsToCompletion() {
        pickInitialPerks();
        int safety = 0;
        while (!state.isOver() && safety < 150) {
            safety++;
            if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
                engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(0).getId(), CombatSide.PLAYER);
                engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(0).getId(), CombatSide.ENEMY);
                continue;
            }
            if (state.getPhase() != CombatPhase.DECISION) {
                break;
            }
            if (state.isExtraActionRound()) {
                engine.skipExtraActions(state.getId(), state.getExtraRoundSide());
                continue;
            }
            engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
            engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        }
        assertThat(state.isOver()).as("pvp battle must finish within 150 rounds, reached round " + state.getRound())
                .isTrue();
        assertThat(state.getWinner()).isIn("PLAYER", "ENEMY");
    }

    private void grantEnergy(CombatState s, CombatSide side) {
        for (Combatant c : s.alive(side)) {
            c.setEnergy(c.getMaxEnergy());
        }
    }

    /** Every alive unit decides: the first one uses the skill, the rest attack. */
    private List<ActionDecision> decisionsWithSkill(CombatSide side, String skillId) {
        List<Combatant> units = state.alive(side);
        String targetId = state.alive(CombatState.opposite(side)).get(0).getId();
        List<ActionDecision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (i == 0) {
                decisions.add(ActionDecision.skill(units.get(i).getId(), skillId, null));
            } else {
                decisions.add(ActionDecision.base(units.get(i).getId(), "ATTACK", targetId));
            }
        }
        return decisions;
    }
}
