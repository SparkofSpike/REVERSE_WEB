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

    private static boolean allDistinct(List<Combatant> ordered, CombatState state) {
        return state.getRoundSpeed().values().stream().distinct().count()
                == state.getRoundSpeed().size();
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
    void modifiedDiceExpressionsResolveWithoutCrashing() {
        // regression: "2d6+2" failed the naive parseInt after the 'd',
        // collapsing to a 1-sided die and forcing every duel into last dash
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        alive.add(combatant("a", "2d6+2"));
        alive.add(combatant("b", "2d6+2"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(2);
        assertThat(state.getRoundSpeed()).containsKeys("a", "b");
        // range is 8 (6 sides + 2 modifier): no forced last dash, speeds distinct
        assertThat(state.getRoundSpeed().get("a")).isNotEqualTo(state.getRoundSpeed().get("b"));
        assertThat(allDistinct(ordered, state)).isTrue();
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
        assertThat(state.getLogs()).anyMatch(e -> "last_dash".equals(e.getType()));
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

    @Test
    void twoPersonTieOnMultiFieldTriggersLastDash() {
        // a is fixed at 3 (1d1 + 2), b and c are fixed at 1: exactly a
        // two-person tie on a three-person field -> immediate last dash.
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        Combatant a = combatant("a", "1d1");
        a.setPermanentSpeedBonus(2);
        alive.add(a);
        alive.add(combatant("b", "1d1"));
        alive.add(combatant("c", "1d1"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(3);
        assertThat(state.getLogs()).anyMatch(e -> "last_dash".equals(e.getType()));
        assertThat(allDistinct(ordered, state)).isTrue();
        // the dash winner becomes the fastest on the field, the loser the slowest
        int winnerSpeed = state.getRoundSpeed().get(ordered.get(0).getId());
        int loserSpeed = state.getRoundSpeed().get(ordered.get(2).getId());
        assertThat(winnerSpeed).isGreaterThan(state.getRoundSpeed().get("a"));
        assertThat(loserSpeed).isLessThan(state.getRoundSpeed().get("a"));
        assertThat(winnerSpeed).isGreaterThan(loserSpeed);
    }

    @Test
    void fixedDiceTiesResolveViaLastDash() {
        // three 1d1 combatants always tie and cannot be re-rolled apart:
        // they must go straight to the last dash without looping.
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        alive.add(combatant("x", "1d1"));
        alive.add(combatant("y", "1d1"));
        alive.add(combatant("z", "1d1"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(3);
        assertThat(state.getLogs()).anyMatch(e -> "last_dash".equals(e.getType()));
        assertThat(allDistinct(ordered, state)).isTrue();
    }

    @Test
    void wholeFieldTiedLastDashAllDistinct() {
        // 4 fixed-speed combatants: the whole field last-dashes; the final
        // speed map must still be strictly distinct.
        CombatState state = new CombatState();
        List<Combatant> alive = new ArrayList<>();
        alive.add(combatant("p", "1d1"));
        alive.add(combatant("q", "1d1"));
        alive.add(combatant("r", "1d1"));
        alive.add(combatant("s", "1d1"));

        List<Combatant> ordered = adjudicator.resolve(alive, state);

        assertThat(ordered).hasSize(4);
        assertThat(state.getLogs()).anyMatch(e -> "last_dash".equals(e.getType()));
        assertThat(allDistinct(ordered, state)).isTrue();
        // strictly descending order
        for (int i = 0; i < ordered.size() - 1; i++) {
            int s1 = state.getRoundSpeed().get(ordered.get(i).getId());
            int s2 = state.getRoundSpeed().get(ordered.get(i + 1).getId());
            assertThat(s1).isGreaterThan(s2);
        }
    }

    @Test
    void noResidualTiesAcrossManySeeds() {
        // 1d7 four-combatant fields frequently tie; whatever path is taken
        // (re-roll, 2-person dash, guaranteed-collision dash), the resolved
        // speed map must always be strictly distinct.
        for (long seed = 1; seed <= 40; seed++) {
            SpeedAdjudicator ad = new SpeedAdjudicator(new DiceRoller(seed));
            CombatState state = new CombatState();
            List<Combatant> alive = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                alive.add(combatant("c" + i, "1d7"));
            }

            List<Combatant> ordered = ad.resolve(alive, state);

            assertThat(state.getRoundSpeed().values().stream().distinct().count())
                    .as("seed %d must produce distinct speeds", seed)
                    .isEqualTo(4);
            for (int i = 0; i < ordered.size() - 1; i++) {
                int s1 = state.getRoundSpeed().get(ordered.get(i).getId());
                int s2 = state.getRoundSpeed().get(ordered.get(i + 1).getId());
                assertThat(s1).isGreaterThan(s2);
            }
        }
    }
}
