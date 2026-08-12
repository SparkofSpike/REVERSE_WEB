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
        CardPackLoader loader = new CardPackLoader(mapper, "./target/test-cards-engine");
        DiceRoller dice = new DiceRoller(2026L);
        engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice), null);
        // enemy side leads with a warrior so the extra-action tests can grant
        // it Relentless Charge (warrior-s3) on both sides
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
        // both sides use Relentless Charge (warrior-s3) so both earn extra charges;
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
    void guestActionsEarnDrawEnergyInPvp() {
        pickInitialPerks();
        int before = state.getEnemyDrawEnergy();
        engine.decideSide(state.getId(), CombatSide.PLAYER, attackAll(CombatSide.PLAYER));
        engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        // the guest's own actions must feed its own draw energy, not the host's
        assertThat(state.getEnemyDrawEnergy()).isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    void guestPermanentExtraActionFiresOnDeferredPath() {
        pickInitialPerks();
        grantEnergy(state, CombatSide.PLAYER);
        grantEnergy(state, CombatSide.ENEMY);
        // simulate the Cold Indifference special perk on the guest's leading unit
        Combatant guestUnit = state.alive(CombatSide.ENEMY).get(0);
        guestUnit.setPermanentExtraAction(true);
        // host uses Relentless Charge so the guest main actions run deferred
        engine.decideSide(state.getId(), CombatSide.PLAYER, decisionsWithSkill(CombatSide.PLAYER, "warrior-s3"));
        engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        assertThat(state.isExtraActionRound()).isTrue();
        engine.skipExtraActions(state.getId(), CombatSide.PLAYER);
        // the deferred guest phase must include the permanent extra strike:
        // main attack + auto-strike = at least two damage events by the unit
        long strikes = state.getLogs().stream()
                .filter(e -> "damage".equals(e.getType()))
                .filter(e -> guestUnit.getId().equals(e.getData().get("actorId")))
                .count();
        assertThat(strikes).as("guest unit must land its main attack and the permanent extra strike")
                .isGreaterThanOrEqualTo(2);
        assertThat(state.getRound()).isEqualTo(2);
    }

    @Test
    void surrenderEndsTheBattleWithOpponentWin() {
        pickInitialPerks();
        engine.surrender(state.getId(), CombatSide.PLAYER);
        assertThat(state.isOver()).isTrue();
        assertThat(state.getWinner()).isEqualTo("ENEMY");
        assertThat(state.getPhase()).isEqualTo(CombatPhase.FINISHED);
        assertThat(state.getLogs()).anyMatch(e -> "surrender".equals(e.getType()));
        // a finished battle cannot surrender again
        assertThatThrownBy(() -> engine.surrender(state.getId(), CombatSide.PLAYER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.surrender(state.getId(), CombatSide.ENEMY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void soloBattlesCannotSurrender() {
        CombatState solo = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        assertThatThrownBy(() -> engine.surrender(solo.getId(), CombatSide.PLAYER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialDecisionsLeaveUnconfiguredUnitsIdle() {
        pickInitialPerks();
        List<Combatant> hostUnits = state.alive(CombatSide.PLAYER);
        Combatant mage = hostUnits.get(1);
        String targetId = state.alive(CombatSide.ENEMY).get(0).getId();
        // only the warrior is configured; the mage has no decision at all
        engine.decideSide(state.getId(), CombatSide.PLAYER,
                List.of(ActionDecision.base(hostUnits.get(0).getId(), "ATTACK", targetId)));
        engine.decideSide(state.getId(), CombatSide.ENEMY, attackAll(CombatSide.ENEMY));
        assertThat(state.getRound()).isEqualTo(2);
        // hearthstone-style: the unconfigured mage skipped its action
        assertThat(state.getLogs()).noneMatch(e -> mage.getId().equals(e.getData().get("actorId")));
    }

    @Test
    void timeoutEndsTurnWithoutFillingDecisions() {
        pickInitialPerks();
        List<Combatant> hostUnits = state.alive(CombatSide.PLAYER);
        Combatant mage = hostUnits.get(1);
        String targetId = state.alive(CombatSide.ENEMY).get(0).getId();
        // host configured only the warrior, then the window expired (no AI fill)
        engine.decideSide(state.getId(), CombatSide.PLAYER,
                List.of(ActionDecision.base(hostUnits.get(0).getId(), "ATTACK", targetId)));
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        assertThat(state.getRound()).isGreaterThan(1);
        assertThat(state.getLogs()).anyMatch(e -> e.getMessage().contains("超时"));
        assertThat(state.getLogs()).noneMatch(e -> mage.getId().equals(e.getData().get("actorId")));
    }

    @Test
    void threeIdleRoundsSurrenderAsOfflineLoss() {
        pickInitialPerks();
        // the guest defends (never kills) while the host idles three rounds
        for (int i = 0; i < 3; i++) {
            engine.decideSide(state.getId(), CombatSide.ENEMY, defendAll(CombatSide.ENEMY));
            state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
            engine.tickDeadlines();
        }
        assertThat(state.isOver()).isTrue();
        assertThat(state.getWinner()).isEqualTo("ENEMY");
        assertThat(state.getLogs()).anyMatch(e -> e.getMessage().contains("离线判负"));
    }

    @Test
    void activeSubmissionResetsTheIdleStreak() {
        pickInitialPerks();
        // round 1: both sides submit (the host is active)
        engine.decideSide(state.getId(), CombatSide.PLAYER, defendAll(CombatSide.PLAYER));
        engine.decideSide(state.getId(), CombatSide.ENEMY, defendAll(CombatSide.ENEMY));
        // rounds 2-3: the host idles twice - not enough to surrender
        for (int i = 0; i < 2; i++) {
            engine.decideSide(state.getId(), CombatSide.ENEMY, defendAll(CombatSide.ENEMY));
            state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
            engine.tickDeadlines();
        }
        assertThat(state.isOver()).isFalse();
        // round 4: idle a third time; the round ends into the special perk
        // round (round 4 % 4 == 0), which delays the surrender check
        engine.decideSide(state.getId(), CombatSide.ENEMY, defendAll(CombatSide.ENEMY));
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);
        // resolve the perk round by timeout (the idle host picks nothing);
        // the idle surrender then fires on round 5's start
        state.setDecisionDeadlineAt(System.currentTimeMillis() - 1L);
        engine.tickDeadlines();
        assertThat(state.isOver())
                .as("round=%d idleP=%d idleE=%d phase=%s",
                        state.getRound(), state.idleRounds(CombatSide.PLAYER),
                        state.idleRounds(CombatSide.ENEMY), state.getPhase())
                .isTrue();
        assertThat(state.getLogs()).anyMatch(e -> e.getMessage().contains("离线判负"));
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

    /** Every alive unit defends (harmless: keeps idle-surrender tests stable). */
    private List<ActionDecision> defendAll(CombatSide side) {
        return state.alive(side).stream()
                .map(c -> ActionDecision.base(c.getId(), "DEFEND", null))
                .toList();
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
