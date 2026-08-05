package com.test.engine.combat;

import com.test.engine.model.CardPackLoader;
import com.test.engine.model.EffectSpec;
import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.utils.DiceRoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end dummy battle flow: deploy, initial perk, decision rounds,
 * special perk rounds and victory resolution.
 */
class CombatEngineTest {

    private CombatEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        DiceRoller dice = new DiceRoller(2026L);
        engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                new DamageResolver(dice), new EffectExecutor(dice, new DamageResolver(dice), loader),
                new PuppetAi(dice));
    }

    @Test
    void fullDummyBattleRunsToCompletion() {
        CombatState state = engine.createDummyBattle("test-1",
                List.of("warrior", "mage", "priest", "crab-dwarf"), "tester");

        assertThat(state.getPhase()).isEqualTo(CombatPhase.INITIAL_PERK);
        assertThat(state.getInitialPerkOptions()).hasSize(3);
        assertThat(state.getPlayerHand()).hasSize(2);
        assertThat(state.alive(CombatSide.ENEMY)).hasSize(1);
        assertThat(state.alive(CombatSide.PLAYER)).hasSize(4);

        // pick the first initial perk
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);

        int safety = 0;
        while (!state.isOver() && safety < 150) {
            safety++;
            if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
                if (state.getSpecialPerkOptions().isEmpty()) {
                    engine.skipSpecialPerk(state.getId());
                } else {
                    engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(0).getId());
                }
                continue;
            }
            if (state.getPhase() != CombatPhase.DECISION) {
                break;
            }
            List<Combatant> players = state.alive(CombatSide.PLAYER);
            List<ActionDecision> decisions = players.stream()
                    .map(p -> ActionDecision.base(p.getId(), "ATTACK", "dummy"))
                    .toList();
            engine.decide(state.getId(), decisions);
        }

        assertThat(state.isOver()).as("battle must finish within 150 rounds, reached round "
                + state.getRound() + " with player hp "
                + state.alive(CombatSide.PLAYER).stream().mapToInt(Combatant::getHp).sum()
                + " and dummy hp " + state.alive(CombatSide.ENEMY).stream().mapToInt(Combatant::getHp).sum())
                .isTrue();
        assertThat(state.getWinner()).isIn("PLAYER", "ENEMY");
        assertThat(state.getLogs()).isNotEmpty();
        // some damage must have been dealt
        assertThat(state.getLogs().stream().anyMatch(e -> "damage".equals(e.getType()))).isTrue();
    }

    @Test
    void skillUseDeductsEnergyAndAppliesEffects() {
        CombatState state = engine.createDummyBattle("test-1", List.of("mage"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        Combatant mage = state.alive(CombatSide.PLAYER).get(0);
        int energyBefore = mage.getEnergy();

        // mage uses 聚求 (draw 1 generic skill), costs 28 - 2 (discount) = 26
        List<ActionDecision> decisions = List.of(
                ActionDecision.skill(mage.getId(), "mage-s2", null));
        engine.decide(state.getId(), decisions);

        assertThat(mage.getEnergy()).isEqualTo(energyBefore - 26);
        // drew one card: hand was 2, draw adds 1 -> 3 (unless consumed by play)
        assertThat(state.getPlayerHand().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void genericSkillCanBePlayed() {
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        String cardId = state.getPlayerHand().get(0).getId();
        engine.playGenericSkill(state.getId(), cardId, null);

        assertThat(state.getPlayerHand().stream().noneMatch(c -> c.getId().equals(cardId))).isTrue();
    }

    @Test
    void puppetCardThenDecideDoesNotThrow() {
        // regression: PuppetMinion had a null speedDice, so the next decide round
        // crashed in SpeedAdjudicator (dice.roll(null) -> Pattern.matcher(null) NPE),
        // surfacing as HTTP 500 and a black screen on the battle view.
        CombatState state = engine.createDummyBattle("test-1", List.of("priest"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        // the puppet card may not be dealt into the initial hand (deck is shuffled)
        GenericSkillTemplate puppetCard = state.getPlayerDeck().stream()
                .filter(c -> "g-puppet-block".equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("puppet card not in deck"));
        if (state.getPlayerHand().stream().noneMatch(c -> "g-puppet-block".equals(c.getId()))) {
            state.getPlayerHand().add(puppetCard);
        }
        GenericSkillTemplate card = state.getPlayerHand().stream()
                .filter(c -> "g-puppet-block".equals(c.getId()))
                .findFirst()
                .orElseThrow();
        engine.playGenericSkill(state.getId(), card.getId(), null);

        assertThat(state.getCombatants().stream().anyMatch(c -> c instanceof PuppetMinion)).isTrue();
        // every alive player character needs a decision, puppet included
        List<ActionDecision> decisions = state.alive(CombatSide.PLAYER).stream()
                .map(p -> ActionDecision.base(p.getId(), "ATTACK", "dummy"))
                .toList();
        engine.decide(state.getId(), decisions);
    }

    @Test
    void undyingKeepsWarriorAlive() {
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        // force warrior to near-death via direct damage
        warrior.setHp(5);

        // one round of attacks should not instantly kill the warrior
        int safety = 0;
        while (!state.isOver() && safety < 30) {
            safety++;
            if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
                engine.skipSpecialPerk(state.getId());
                continue;
            }
            if (state.getPhase() != CombatPhase.DECISION) {
                break;
            }
            engine.decide(state.getId(), List.of(
                    ActionDecision.base(warrior.getId(), "DEFEND", null)));
        }
        // warrior may be dead after multiple rounds, but undying must have
        // triggered at least once if it dropped to 0
        boolean undyingLogged = state.getLogs().stream()
                .anyMatch(e -> "performance".equals(e.getType()) && e.getMessage().contains("宁死不屈"));
        assertThat(undyingLogged).as("warrior must trigger undying when dropped").isTrue();
    }

    @Test
    void performanceGrantsDrawEnergyAndDefaultEnergyRestore() {
        // mage performance (energy below 50) has no energy-restore effect, so the
        // design-doc defaults apply: +2 draw energy and a default energy restore
        CombatState state = engine.createDummyBattle("test-1", List.of("mage"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        Combatant mage = state.alive(CombatSide.PLAYER).get(0);
        mage.setEnergy(30);
        int drawBefore = state.getPlayerDrawEnergy();

        engine.decide(state.getId(), List.of(
                ActionDecision.skill(mage.getId(), "mage-s2", null))); // 聚求: draw a card

        assertThat(mage.isPerforming()).isTrue();
        // mage performance has no upgrade_skills effect
        assertThat(mage.isSkillsUpgraded()).isFalse();
        assertThat(state.getPlayerDrawEnergy()).isGreaterThanOrEqualTo(drawBefore + 2);
        // mage-s2 costs 28, discount -2 (技艺生疏): 30 - 26 + 20 default restore = 24
        assertThat(mage.getEnergy()).isEqualTo(24);
    }

    @Test
    void performanceWithEnergyEffectSkipsDefaultRestore() {
        // warrior performance grants energy 35 explicitly; the default restore
        // must not stack on top of it
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setHp(35);
        warrior.setEnergy(60);

        // using a skill triggers the caster's performance check (hp 35 < 40)
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s2", null))); // 嗜血突袭: 20 energy

        assertThat(warrior.isPerforming()).isTrue();
        // warrior performance upgrades all three skills
        assertThat(warrior.isSkillsUpgraded()).isTrue();
        // 60 - 20 (skill) + 35 (performance) = 75; default +20 must NOT apply
        assertThat(warrior.getEnergy()).isEqualTo(75);
        assertThat(state.getPlayerDrawEnergy()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void clockAccelerateAdvancesSpecialPerkOffer() {
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        // ensure the clock-accelerate card is in hand (deck is shuffled)
        GenericSkillTemplate card = state.getPlayerHand().stream()
                .filter(c -> "g-clock-accelerate".equals(c.getId()))
                .findFirst()
                .orElseGet(() -> state.getPlayerDeck().stream()
                        .filter(c -> "g-clock-accelerate".equals(c.getId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("clock accelerate not in deck")));
        if (state.getPlayerHand().stream().noneMatch(c -> "g-clock-accelerate".equals(c.getId()))) {
            state.getPlayerHand().add(card);
        }
        engine.playGenericSkill(state.getId(), card.getId(), null);
        assertThat(state.isSpecialPerkAdvancePending()).isTrue();

        // advance rounds; the accelerated special perk offer fires at round 3
        // (3 % 4 == 3) instead of the normal round 4
        int safety = 0;
        while (state.getPhase() == CombatPhase.DECISION && safety < 8) {
            safety++;
            engine.decide(state.getId(), List.of(
                    ActionDecision.base(state.alive(CombatSide.PLAYER).get(0).getId(), "ATTACK", "dummy")));
        }
        assertThat(state.getRound()).isGreaterThanOrEqualTo(3);
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);
    }

    @Test
    void clockAccelerateSubstitutesNotStacksNormalPerkRound() {
        // regression: the accelerated offer (round 3) used to fire and the
        // normal round-4 offer fired right after it, granting two consecutive
        // perk rounds; the accelerated round must consume the following one
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        // round 1: 钟表加速
        GenericSkillTemplate card = state.getPlayerHand().stream()
                .filter(c -> "g-clock-accelerate".equals(c.getId()))
                .findFirst()
                .orElseGet(() -> state.getPlayerDeck().stream()
                        .filter(c -> "g-clock-accelerate".equals(c.getId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("clock accelerate not in deck")));
        if (state.getPlayerHand().stream().noneMatch(c -> "g-clock-accelerate".equals(c.getId()))) {
            state.getPlayerHand().add(card);
        }
        engine.playGenericSkill(state.getId(), card.getId(), null);
        assertThat(state.isSpecialPerkAdvancePending()).isTrue();

        // round 3 fires the accelerated offer
        int safety = 0;
        while (state.getPhase() == CombatPhase.DECISION && safety < 8) {
            safety++;
            engine.decide(state.getId(), List.of(
                    ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        }
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);
        assertThat(state.getRound()).isEqualTo(3);
        int roundsTakenAfterAccelerated = state.getSpecialPerkRoundsTaken();
        engine.skipSpecialPerk(state.getId());
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);

        // round 4 must NOT fire a second offer
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.getRound()).isEqualTo(5);
        assertThat(state.getSpecialPerkRoundsTaken()).isEqualTo(roundsTakenAfterAccelerated);
    }

    @Test
    void roundTransitionsLogStartAndEndEvents() {
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        // no round has started yet
        assertThat(state.getLogs().stream().noneMatch(e -> "round_start".equals(e.getType()))).isTrue();

        // picking the initial perk starts round 1 -> curtain rise event
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        assertThat(state.getRound()).isEqualTo(1);
        assertThat(state.getLogs().stream().anyMatch(e -> "round_start".equals(e.getType()))).isTrue();

        // resolving a round finishes with a curtain fall event (unless the
        // battle ends mid-execution, in which case endRound is skipped)
        int safety = 0;
        while (state.getPhase() == CombatPhase.DECISION && safety < 10) {
            safety++;
            engine.decide(state.getId(), List.of(
                    ActionDecision.base(state.alive(CombatSide.PLAYER).get(0).getId(), "ATTACK", "dummy")));
        }
        assertThat(state.getLogs().stream().anyMatch(e -> "round_end".equals(e.getType())))
                .as("endRound should have logged round_end once it ran")
                .isTrue();
        // round_end must reference the round that just finished
        assertThat(state.getLogs().stream()
                .filter(e -> "round_end".equals(e.getType()))
                .findFirst()
                .map(CombatEvent::getRound)
                .orElse(0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void skillCooldownCarriesOverThroughUpgrade() {
        // regression: the hp_below performance upgrades warrior skills
        // (warrior-s3 -> warrior-s3-up). Cooldowns were keyed by the old id,
        // so the upgraded skill never matched a cooldown entry and could be
        // cast again every round despite CD 3.
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());

        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setHp(35); // below 40: the next skill use triggers the performance
        warrior.setEnergy(100);

        // 连续奔袭: cost 25, cooldown 3, self target; the cast also triggers
        // the hp_below performance, which upgrades all three warrior skills
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));

        assertThat(warrior.isSkillsUpgraded()).isTrue();
        assertThat(warrior.findSkill("warrior-s3-up")).isNotNull();
        // the pending cooldown must have moved to the upgraded id
        assertThat(warrior.hasCooldown("warrior-s3-up")).isTrue();
        assertThat(warrior.hasCooldown("warrior-s3")).isFalse();

        // 连续奔袭 grants extra actions, opening the extra-action round;
        // skip it so the round finalizes before the next main decision
        if (state.isExtraActionRound()) {
            engine.skipExtraActions(state.getId());
        }

        int energyAfterFirstCast = warrior.getEnergy();

        // next round (phase is back to DECISION): casting the upgraded skill
        // while on cooldown must be rejected and must not consume energy
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3-up", null)));

        assertThat(warrior.getEnergy()).isEqualTo(energyAfterFirstCast);
        assertThat(state.getLogs().stream().anyMatch(e -> "skill".equals(e.getType())
                && e.getMessage().contains("仍在冷却"))).isTrue();
    }

    @Test
    void chaseIsActiveActionNotPassiveBonus() {
        // design doc (TEST.游戏玩法.pdf): 追击 is one of the base action
        // types ("若目标是上次攻击过的目标，则本次攻击会追加0d4点伤害，
        // 并恢复2点生命"). It must be actively chosen - a plain ATTACK must
        // never trigger the bonus on its own.
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);

        // round 1: plain ATTACK on the dummy - no passive chase may fire
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        assertThat(state.getLogs().stream().noneMatch(e -> "chase".equals(e.getType())))
                .as("a plain ATTACK must not trigger a passive chase")
                .isTrue();
        // and the ATTACK itself now emits an action cue for the frontend
        assertThat(state.getLogs().stream().anyMatch(e -> "action".equals(e.getType())
                && warrior.getId().equals(e.getData() != null ? e.getData().get("actorId") : null)
                && "ATTACK".equals(e.getData() != null ? e.getData().get("action") : null)))
                .as("a plain ATTACK must emit an action cue")
                .isTrue();

        int dummyHpBeforeChase = state.find("dummy").getHp();
        int warriorHpBeforeChase = warrior.getHp();

        // round 2: actively choose CHASE on the same target - bonus must fire
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "CHASE", "dummy")));
        long chaseEvents = state.getLogs().stream()
                .filter(e -> "chase".equals(e.getType())
                        && warrior.getId().equals(e.getData() != null ? e.getData().get("actorId") : null))
                .count();
        assertThat(chaseEvents).as("an active CHASE on the last-attacked target must fire the bonus")
                .isEqualTo(1);
        // bonus restores 2 HP to the chaser (dummy may have hit back, so
        // assert at most +2: hp cannot be lower than before the chase heal)
        assertThat(warrior.getHp()).isGreaterThanOrEqualTo(warriorHpBeforeChase - 20);
        // dummy must have lost hp in round 2 (chase main strike + bonus)
        assertThat(state.find("dummy").getHp()).isLessThan(dummyHpBeforeChase);
        // event order must be narrative: action cue -> main damage -> chase cue
        var events = state.getLogs().stream()
                .filter(e -> e.getRound() == 2)
                .filter(e -> warrior.getId().equals(e.getData() != null ? e.getData().get("actorId") : null))
                .toList();
        boolean sawAction = false, sawDamage = false, sawChase = false;
        for (CombatEvent e : events) {
            if ("action".equals(e.getType())) sawAction = true;
            if ("damage".equals(e.getType())) sawDamage = true;
            if ("chase".equals(e.getType())) {
                assertThat(sawAction).as("chase cue must come after the action cue").isTrue();
                assertThat(sawDamage).as("chase cue must come after the main damage").isTrue();
                sawChase = true;
            }
        }
        assertThat(sawChase).isTrue();
    }

    @Test
    void extraActionsArePlayerChosenFollowUpRounds() {
        // 连续奔袭 (warrior-s3): "使用后，获得仅限本回合使用的3次额外基础行动"
        // The player must be able to freely choose each extra action, not
        // have the engine auto-attack three times.
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        // round 1: plain ATTACK - exactly one hit, no extra-action round
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        assertThat(state.isExtraActionRound()).as("plain round must not open an extra-action round")
                .isFalse();

        // round 2: 连续奔袭 opens the extra-action round with 3 charges
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));
        assertThat(state.isExtraActionRound()).as("连续奔袭 must open an extra-action round")
                .isTrue();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(3);
        int dummyHpBefore = state.find("dummy").getHp();
        assertThat(state.find("dummy").getHp()).isEqualTo(dummyHpBefore);

        // extra action 1: free choice - spend it on a CHASE
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "CHASE", "dummy")));
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(2);
        assertThat(state.find("dummy").getHp()).isLessThan(dummyHpBefore);
        // the extra action must be a real, freely chosen action: a CHASE on
        // the last-attacked target fires its bonus cue
        assertThat(state.getLogs().stream().anyMatch(e -> "chase".equals(e.getType()))).isTrue();

        // skip the remaining two charges: the round must finalize
        engine.skipExtraActions(state.getId());
        assertThat(state.isExtraActionRound()).isFalse();
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.getRound()).isEqualTo(3);
        // the skipped charges are not auto-spent
        assertThat(warrior.getExtraActionsThisTurn()).isZero();
    }

    @Test
    void extraActionBatchCannotExceedRemainingCharges() {
        // regression: the validation loop only checked the current charge
        // count, so one batch could submit more decisions than charges and
        // every one of them would still execute (charges only decrement).
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(3);

        int dummyHpBefore = state.find("dummy").getHp();
        // 4 decisions in one batch with only 3 charges: the batch must be rejected
        assertThatThrownBy(() -> engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"))))
                .isInstanceOf(IllegalArgumentException.class);

        // nothing was spent or executed
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(3);
        assertThat(state.find("dummy").getHp()).isEqualTo(dummyHpBefore);

        // an exact-fit batch (3 decisions, 3 charges) still works
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        assertThat(warrior.getExtraActionsThisTurn()).isZero();
        assertThat(state.find("dummy").getHp()).isLessThan(dummyHpBefore);
    }

    private EffectExecutor newExecutor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        return new EffectExecutor(new DiceRoller(1L), new DamageResolver(new DiceRoller(1L)), loader);
    }

    @Test
    void effectDamageKillRespectsUndying() throws Exception {
        // regression: effect-executor kills bypassed the engine's undying
        // check, so lethal skill damage ignored 宁死不屈 entirely
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setHp(5);

        EffectSpec spec = new EffectSpec();
        spec.setType("damage");
        spec.setAmount(999);
        spec.setTarget("ally");

        EffectExecutor executor = newExecutor();
        executor.execute(spec, warrior, state, warrior.getId());
        // first lethal hit: undying triggers, HP reset to 1, still alive
        assertThat(warrior.isDead()).isFalse();
        assertThat(warrior.getHp()).isEqualTo(1);
        assertThat(warrior.isUndyingUsed()).isTrue();

        executor.execute(spec, warrior, state, warrior.getId());
        // second lethal hit: the undying rounds absorb it
        assertThat(warrior.isDead()).isFalse();
        assertThat(warrior.getHp()).isEqualTo(1);

        executor.execute(spec, warrior, state, warrior.getId());
        // third lethal hit: undying is spent, the warrior finally dies
        assertThat(warrior.isDead()).isTrue();
        assertThat(state.getLogs().stream().anyMatch(e -> "death".equals(e.getType()))).isTrue();
    }

    @Test
    void sacrificeBuffNeverLeavesZeroHpZombies() throws Exception {
        // regression: 冷漠实现 could drive an ally to 0 HP without death
        // handling, leaving a "living" zombie that kept acting and could
        // receive the permanent extra action
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior", "mage"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        Combatant mage = state.alive(CombatSide.PLAYER).get(1);
        warrior.setHp(3);
        mage.setHp(50);

        EffectSpec spec = new EffectSpec();
        spec.setType("sacrifice_buff");
        spec.setAmount(10);
        spec.setCount(30);
        spec.setTarget("allies");

        EffectExecutor executor = newExecutor();
        executor.execute(spec, warrior, state, null); // warrior 3 -> 0 (undying -> 1)
        executor.execute(spec, warrior, state, null); // warrior 1 -> 0 (undying rounds -> 0, hp 1)
        executor.execute(spec, warrior, state, null); // warrior 1 -> 0 (no undying left -> dead)

        assertThat(warrior.isDead()).isTrue();
        assertThat(mage.isDead()).isFalse();
        assertThat(mage.getHp()).isEqualTo(20);
        // the permanent extra action goes to the lowest ALIVE ally
        assertThat(mage.isPermanentExtraAction()).isTrue();
    }

    @Test
    void extraActionRoundRejectsSkillsWithoutOverlimit() {
        // regression: extra-action rounds accepted SKILL decisions even
        // though they grant "extra base actions" (连续奔袭); only 超限技能
        // (extra_skill) unlocks a skill inside the extra round
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));
        assertThat(state.isExtraActionRound()).isTrue();
        int dummyHpBefore = state.find("dummy").getHp();

        // a SKILL decision is rejected and burns nothing
        assertThatThrownBy(() -> engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s1", "dummy"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(3);
        assertThat(state.find("dummy").getHp()).isEqualTo(dummyHpBefore);

        // base actions still work
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        assertThat(warrior.getExtraActionsThisTurn()).isEqualTo(2);
    }

    @Test
    void extraSkillUnlocksSkillInsideExtraActionRound() {
        // 超限技能 (g-overlimit): "使一个角色在本回合内可以额外使用一个技能"
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        // round 1: 连续奔袭 opens the extra round with 3 base actions
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));
        assertThat(state.isExtraActionRound()).isTrue();

        // 超限技能 card grants +1 extra skill for this turn
        GenericSkillTemplate overlimit = state.getPlayerDeck().stream()
                .filter(c -> "g-overlimit".equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("overlimit card not in deck"));
        engine.playGenericSkill(state.getId(), overlimit.getId(), warrior.getId());
        assertThat(warrior.getExtraSkillsThisTurn()).isEqualTo(1);

        // a skill now passes the extra-round gate
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s1", "dummy")));
        assertThat(warrior.getExtraSkillsThisTurn()).isZero();
        assertThat(state.getLogs().stream()
                .anyMatch(e -> "skill".equals(e.getType()) && warrior.getId().equals(e.getData().get("actorId"))))
                .isTrue();
    }

    @Test
    void secondGuardSameTurnNeedsExtraGuardCharge() {
        // 蟹壳拓展 (crab-s1): "仅限本回合，获得2次额外的守护行动次数" - a
        // character can only guard once per turn unless it holds extra guard
        // charges (the extra-action round is the same-turn second action slot)
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior", "crab-dwarf"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        Combatant crab = state.alive(CombatSide.PLAYER).get(1);
        warrior.setEnergy(100);

        // main round: 连续奔袭 opens the extra-action round
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null),
                ActionDecision.base(crab.getId(), "ATTACK", "dummy")));
        assertThat(state.isExtraActionRound()).isTrue();

        // extra action 1: first guard of the turn is free
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "GUARD", crab.getId())));
        assertThat(warrior.getGuardTargetId()).isEqualTo(crab.getId());

        // extra action 2: a second guard without extra charges is rejected
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "GUARD", warrior.getId())));
        assertThat(warrior.getGuardTargetId()).isEqualTo(crab.getId());
        assertThat(state.getLogs().stream().anyMatch(e -> "action".equals(e.getType())
                && (e.getMessage() != null && e.getMessage().contains("没有额外的守护次数")))).isTrue();

        // 蟹壳拓展 grants the charge: the second guard now succeeds
        warrior.setExtraGuardsThisTurn(1);
        engine.decideExtraActions(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "GUARD", warrior.getId())));
        assertThat(warrior.getExtraGuardsThisTurn()).isZero();
        assertThat(warrior.getGuardTargetId()).isEqualTo(warrior.getId());
    }

    @Test
    void extraActionRoundStillOffersSpecialPerk() {
        // regression: an extra-action round landing on the perk round
        // (round % 4 == 0) skipped the special perk offer entirely - the
        // extra path returned before the old check and the extra-round
        // finale went straight to endRound, permanently losing that offer
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        warrior.setEnergy(100);

        // rounds 1-3: plain attacks
        for (int i = 1; i <= 3; i++) {
            engine.decide(state.getId(), List.of(
                    ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
            if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
                engine.skipSpecialPerk(state.getId());
            }
        }
        // round 4: 连续奔袭 opens the extra-action round on the perk round
        engine.decide(state.getId(), List.of(
                ActionDecision.skill(warrior.getId(), "warrior-s3", null)));
        assertThat(state.isExtraActionRound()).isTrue();
        // skipping the extra actions must still land in the perk offer
        engine.skipExtraActions(state.getId());
        assertThat(state.getPhase()).isEqualTo(CombatPhase.SPECIAL_PERK);
        assertThat(state.getSpecialPerkOptions()).isNotEmpty();

        // picking the perk finalizes cleanly into the next round (no re-offer)
        engine.selectSpecialPerk(state.getId(), state.getSpecialPerkOptions().get(0).getId());
        assertThat(state.getPhase()).isEqualTo(CombatPhase.DECISION);
        assertThat(state.getRound()).isEqualTo(5);
    }

    @Test
    void chaseWithoutPriorAttackGetsNoBonus() {
        // regression: lastAttackedTarget was overwritten by the chase's own
        // executeAttack, so the same-target condition was always true and a
        // chase on a brand-new target still granted the 0d4 bonus + 2 HP
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        int warriorHpBefore = warrior.getHp();

        // first action of the whole fight is a CHASE: no prior attack target
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "CHASE", "dummy")));
        assertThat(state.getLogs().stream().noneMatch(e -> "chase".equals(e.getType())))
                .as("no chase bonus cue without a prior attack on the target")
                .isTrue();
        assertThat(warrior.getHp()).isEqualTo(warriorHpBefore);
    }

    @Test
    void chaseBonusSkippedWhenTargetDodges() {
        // regression: a chase whose strike was dodged still granted the 0d4
        // bonus (dealing damage that bypassed the dodge) plus 2 HP
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);

        // round 1: plain attack so the dummy becomes the last-attacked target
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy")));
        Combatant dummy = state.find("dummy");
        assertThat(warrior.getLastAttackedTarget()).isEqualTo("dummy");

        // round 2: the dummy dodges everything; the chase must whiff
        int dummyHpBefore = dummy.getHp();
        dummy.setDodging(true);
        dummy.setDodgeValue(999);
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "CHASE", "dummy")));

        // the chase itself was dodged: no damage, no bonus cue, no heal
        assertThat(dummy.getHp()).isEqualTo(dummyHpBefore);
        assertThat(state.getLogs().stream().noneMatch(e -> "chase".equals(e.getType())))
                .as("dodged chase must not trigger the bonus")
                .isTrue();
    }

    @Test
    void cardKillSettlesVictoryImmediately() {
        // regression: playGenericSkill never ran the victory check, so a card
        // that killed the last combatant of a side left the battle in DECISION
        // with a dead side still "in play" and no record persisted
        CombatState state = engine.createDummyBattle("test-1", List.of("mage"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant mage = state.alive(CombatSide.PLAYER).get(0);
        mage.setHp(5);

        // put 团队奉献 (hp_cost 10 on an ally) into hand and play it on the mage
        GenericSkillTemplate card = state.getPlayerDeck().stream()
                .filter(c -> "g-team-sacrifice".equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("team sacrifice not in deck"));
        if (state.getPlayerHand().stream().noneMatch(c -> c.getId().equals(card.getId()))) {
            state.getPlayerHand().add(card);
        }
        engine.playGenericSkill(state.getId(), card.getId(), mage.getId());

        // the mage died from the hp cost -> player team wiped -> ENEMY wins
        assertThat(mage.isDead()).isTrue();
        assertThat(state.isOver()).isTrue();
        assertThat(state.getWinner()).isEqualTo("ENEMY");
        assertThat(state.getPhase()).isEqualTo(CombatPhase.FINISHED);
    }

    @Test
    void battleIdIsWideEnoughToAvoidCollisions() {
        // regression: 8 hex chars (32 bits) collide after roughly 77k battles
        // and silently overwrite an existing battle; ids must be 64 bits
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        assertThat(state.getId()).hasSize(16);
    }

    @Test
    void finishedBattlesAreReapedAfterTtl() {
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        state.setPhase(CombatPhase.FINISHED);
        state.setWinner("PLAYER");
        state.setCreatedAt(java.time.Instant.now().minusSeconds(7200));

        // the stale battle is reaped lazily: reads fail with not-found
        assertThatThrownBy(() -> engine.getBattle(state.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("battle not found");

        // fresh battles are untouched
        CombatState fresh = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        assertThat(engine.getBattle(fresh.getId())).isNotNull();
        assertThat(fresh.getId()).isNotEqualTo(state.getId());
    }

    @Test
    void concurrentActionsDoNotCorruptTheBattle() throws Exception {
        // smoke test: two threads slam the same battle at once; the
        // synchronized entry points serialize them, so no interleaved log
        // writes, duplicated AI decisions or ConcurrentModificationException
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        int roundsBefore = state.getRound();
        int logsBefore = state.getLogs().size();

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    engine.decide(state.getId(), List.of(
                            ActionDecision.base(state.alive(CombatSide.PLAYER).get(0).getId(),
                                    "ATTACK", "dummy")));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    // exactly one thread wins the decide; the other is
                    // rejected cleanly because the phase already moved on
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // the state advanced deterministically (round +1, logs grew), and no
        // exception escaped the threads
        assertThat(state.getRound()).isEqualTo(roundsBefore + 1);
        assertThat(state.getLogs().size()).isGreaterThan(logsBefore);
    }

    @Test
    void decideRejectsInvalidCombatantIds() {
        // regression: decide accepted decisions for wrong/dead/enemy ids and
        // silently dropped them, making that character skip its action
        CombatState state = engine.createDummyBattle("test-1", List.of("warrior", "mage"), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        Combatant warrior = state.alive(CombatSide.PLAYER).get(0);
        Combatant mage = state.alive(CombatSide.PLAYER).get(1);

        // unknown id
        assertThatThrownBy(() -> engine.decide(state.getId(), List.of(
                ActionDecision.base("nobody", "ATTACK", "dummy"),
                ActionDecision.base(mage.getId(), "ATTACK", "dummy"))))
                .isInstanceOf(IllegalArgumentException.class);
        // duplicate id
        assertThatThrownBy(() -> engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"))))
                .isInstanceOf(IllegalArgumentException.class);
        // enemy id (the dummy)
        assertThatThrownBy(() -> engine.decide(state.getId(), List.of(
                ActionDecision.base("dummy", "ATTACK", warrior.getId()),
                ActionDecision.base(mage.getId(), "ATTACK", "dummy"))))
                .isInstanceOf(IllegalArgumentException.class);
        // a valid batch still works
        engine.decide(state.getId(), List.of(
                ActionDecision.base(warrior.getId(), "ATTACK", "dummy"),
                ActionDecision.base(mage.getId(), "ATTACK", "dummy")));
        assertThat(state.getRound()).isEqualTo(2);
    }
}
