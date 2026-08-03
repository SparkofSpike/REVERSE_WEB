package com.test.engine.combat;

import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedAdjudicatorTest {

    private final SpeedAdjudicator adjudicator = new SpeedAdjudicator(new DiceRoller(99L));

    private Combatant combatant(String id, String speedDice) {
        Combatant c = new Combatant();
        c.setId(id);
        c.setName(id);
        c.setSpeedDice(speedDice);
        return c;
    }

    @Test
    void ordersBySpeedDescending() {
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        alive.add(combatant("a", "1d7"));
        alive.add(combatant("b", "1d7"));
        alive.add(combatant("c", "1d7"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(3);
        // speeds must be distinct after resolution
        long distinct = state.getRoundSpeed().values().stream().distinct().count();
        assertThat(distinct).isEqualTo(3);
        // order matches descending speeds
        for (int i = 0; i < ordered.size() - 1; i++) {
            int s1 = state.getRoundSpeed().get(ordered.get(i).getId());
            int s2 = state.getRoundSpeed().get(ordered.get(i + 1).getId());
            assertThat(s1).isGreaterThan(s2);
        }
    }

    @Test
    void twoCombatantsTiedTriggerLastDash() {
        // two identical 1d4 combatants: tie guaranteed, last dash decides
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        alive.add(combatant("a", "1d4"));
        alive.add(combatant("b", "1d4"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(2);
        int speedA = state.getRoundSpeed().get("a");
        int speedB = state.getRoundSpeed().get("b");
        assertThat(speedA).isNotEqualTo(speedB);
        // winner is first
        assertThat(ordered.get(0).getId()).isEqualTo(speedA > speedB ? "a" : "b");
    }

    @Test
    void groupLargerThanDiceRangeTriggersLastDash() {
        // 4 combatants with 1d3 (range 3 < group size 4): must last dash
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            alive.add(combatant("c" + i, "1d3"));
        }

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(4);
        long distinct = state.getRoundSpeed().values().stream().distinct().count();
        assertThat(distinct).isEqualTo(4);
    }

    @Test
    void speedBoostsAffectOrder() {
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        Combatant a = combatant("a", "1d7");
        Combatant b = combatant("b", "1d7");
        b.setPermanentSpeedBonus(10);
        alive.add(a);
        alive.add(b);

        List<Combatant> ordered = adjudicator.resolve(alive, state);
        // b's +10 permanent boost always wins against a's 1d7 (max 7)
        assertThat(ordered.get(0).getId()).isEqualTo("b");
    }
}
