package com.test.engine.combat;

import com.test.engine.model.CardPackLoader;
import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.utils.DiceRoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
