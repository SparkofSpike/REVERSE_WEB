package com.test.engine.combat;

import com.test.engine.enums.DamageType;
import com.test.engine.model.CardPackLoader;
import com.test.engine.utils.DiceRoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hod mechanics: bleed stacks halve on action, stun skips the next turn,
 * bloodletting grants an extra action + bleed on damage, collapse triggers
 * on ally death, dodge-training reduces teammate dodge penalties.
 */
class HodMechanicsTest {

    private CombatEngine engine;
    private DamageResolver damageResolver;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CardPackLoader loader = new CardPackLoader(mapper);
        DiceRoller dice = new DiceRoller(2026L);
        damageResolver = new DamageResolver(dice);
        engine = new CombatEngine(dice, loader, new SpeedAdjudicator(dice),
                damageResolver, new EffectExecutor(dice, damageResolver, loader),
                new PuppetAi(dice), null);
    }

    private CombatState start(String... chars) {
        CombatState state = engine.createDummyBattle("test-1", List.of(chars), "tester");
        engine.selectInitialPerk(state.getId(), state.getInitialPerkOptions().get(0).getId());
        return state;
    }

    /** Runs decision rounds; the first round uses the given decisions (the
     *  rest of the team defends), later rounds everyone defends. */
    private void decideAll(CombatState state, List<ActionDecision> firstRound) {
        int safety = 0;
        while (state.getPhase() == CombatPhase.DECISION && safety < 50) {
            safety++;
            List<Combatant> players = state.alive(CombatSide.PLAYER);
            List<ActionDecision> decisions;
            if (safety == 1) {
                decisions = new ArrayList<>(firstRound);
                for (Combatant p : players) {
                    if (decisions.stream().noneMatch(d -> d.getCombatantId().equals(p.getId()))) {
                        decisions.add(ActionDecision.base(p.getId(), "DEFEND", null));
                    }
                }
            } else {
                decisions = players.stream()
                        .map(p -> ActionDecision.base(p.getId(), "DEFEND", null)).toList();
            }
            engine.decide(state.getId(), decisions);
        }
    }

    @Test
    void bleedStacksHalveAndDamageOnNextAction() {
        CombatState state = start("hod");
        Combatant hod = state.alive(CombatSide.PLAYER).get(0);
        Combatant dummy = state.alive(CombatSide.ENEMY).get(0);
        int dummyHpBefore = dummy.getHp();

        // 横切斩开: 1d11 damage + 2 bleed stacks on the dummy
        decideAll(state, List.of(ActionDecision.skill(hod.getId(), "hod-s2", dummy.getId())));

        // whenever the dummy acts, it loses hp equal to the stacks and they halve
        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("因流血失去"))).isTrue();
        assertThat(dummy.getHp()).isLessThan(dummyHpBefore);
    }

    @Test
    void stunSkipsTheNextTurn() {
        CombatState state = start("hod");
        Combatant hod = state.alive(CombatSide.PLAYER).get(0);
        Combatant dummy = state.alive(CombatSide.ENEMY).get(0);

        // 重槌制服: 1d6 damage + stun on the dummy (next turn cannot act)
        decideAll(state, List.of(ActionDecision.skill(hod.getId(), "hod-s1", dummy.getId())));

        // the dummy either got stunned now (acted before hod this round) or
        // will be stunned next round - in both cases a stun state exists at
        // some point and a "晕眩中" skip log appears
        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("晕眩"))).isTrue();
    }

    @Test
    void bloodlettingGrantsExtraActionAndBleedOnDamage() {
        CombatState state = start("hod");
        Combatant hod = state.alive(CombatSide.PLAYER).get(0);
        Combatant dummy = state.alive(CombatSide.ENEMY).get(0);

        // 放血: 3 rounds of +1 extra action per round + bleed on any damage.
        // decideAll runs many rounds (extra-action windows), so the buff may
        // already expire - assert on the logs instead of the live state.
        decideAll(state, List.of(ActionDecision.skill(hod.getId(), "hod-s3", null)));

        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("进入放血状态"))).isTrue();
        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("放血效果"))).isTrue();
    }

    @Test
    void bloodlettingAddsBleedOnAnyDirectDamage() {
        CombatState state = start("hod");
        Combatant hod = state.alive(CombatSide.PLAYER).get(0);
        Combatant dummy = state.alive(CombatSide.ENEMY).get(0);
        hod.addStatus(StatusEffect.of("bloodletting", 3));

        damageResolver.dealDamage(dummy, 5, DamageType.PHYSICAL, state, hod, "ATTACK");

        assertThat(dummy.statusesOfType("bleed")).hasSize(1);
        assertThat(dummy.statusesOfType("bleed").get(0).getCount()).isEqualTo(2);
    }

    @Test
    void collapseTriggersOnAllyDeath() {
        CombatState state = start("hod", "mage");
        Combatant hod = state.alive(CombatSide.PLAYER).stream()
                .filter(c -> "hod".equals(c.getTemplateId())).findFirst().orElseThrow();
        Combatant mage = state.alive(CombatSide.PLAYER).stream()
                .filter(c -> "mage".equals(c.getTemplateId())).findFirst().orElseThrow();

        // mage dies (simulated through the shared death adjudication)
        mage.setHp(0);
        state.resolvePotentialDeath(mage);
        assertThat(mage.isDead()).as("mage should be dead").isTrue();

        decideAll(state, List.of(ActionDecision.base(hod.getId(), "DEFEND", null)));

        assertThat(hod.isPerforming()).as("hod's collapse performance should have triggered").isTrue();
        // from the next round start: hod loses 10 hp, alive teammates gain a 5-shield
        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("崩溃"))).isTrue();
        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("因崩溃失去"))).isTrue();
    }

    @Test
    void dodgeTrainingReducesTeammatePenalty() {
        CombatState state = start("hod", "warrior");
        Combatant warrior = state.alive(CombatSide.PLAYER).stream()
                .filter(c -> "warrior".equals(c.getTemplateId())).findFirst().orElseThrow();
        Combatant hod = state.alive(CombatSide.PLAYER).stream()
                .filter(c -> "hod".equals(c.getTemplateId())).findFirst().orElseThrow();

        decideAll(state, List.of(
                ActionDecision.base(warrior.getId(), "DODGE", null),
                ActionDecision.base(hod.getId(), "DEFEND", null)));

        assertThat(state.getLogs().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("教导有方"))).isTrue();
    }
}
