package com.test.engine.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiceRollerTest {

    private final DiceRoller roller = new DiceRoller(42L);

    @Test
    void parsesStandardExpression() {
        DiceResult r = roller.roll("5d7");
        assertThat(r.count()).isEqualTo(5);
        assertThat(r.sides()).isEqualTo(7);
        assertThat(r.rolls()).hasSize(5);
        assertThat(r.total()).isEqualTo(sum(r.rolls()));
    }

    @Test
    void standardDiceStayInRange() {
        DiceRoller loop = new DiceRoller();
        for (int i = 0; i < 2000; i++) {
            DiceResult r = loop.roll("2d6");
            assertThat(r.total()).isBetween(2, 12);
            for (int v : r.rolls()) {
                assertThat(v).isBetween(1, 6);
            }
        }
    }

    @Test
    void zeroDieUsesZeroToSidesConvention() {
        DiceRoller loop = new DiceRoller();
        boolean sawZero = false;
        boolean sawMax = false;
        for (int i = 0; i < 5000; i++) {
            DiceResult r = loop.roll("0d3");
            assertThat(r.count()).isZero();
            assertThat(r.total()).isBetween(0, 3);
            if (r.total() == 0) sawZero = true;
            if (r.total() == 3) sawMax = true;
        }
        assertThat(sawZero).as("0 must be reachable for 0dN").isTrue();
        assertThat(sawMax).as("N must be reachable for 0dN").isTrue();
    }

    @Test
    void seededRollerIsDeterministic() {
        DiceRoller a = new DiceRoller(7L);
        DiceRoller b = new DiceRoller(7L);
        for (int i = 0; i < 50; i++) {
            assertThat(a.roll("3d9").total()).isEqualTo(b.roll("3d9").total());
        }
    }

    @Test
    void rejectsMalformedExpression() {
        assertThatThrownBy(() -> roller.roll("abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> roller.roll("1d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> roller.roll("d6"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullExpression() {
        // regression: SpeedAdjudicator once passed a null dice string (puppet
        // minion without speedDice), which NPE'd inside Pattern.matcher
        // instead of failing with a clear business error.
        assertThatThrownBy(() -> roller.roll(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidSidesAndCount() {
        assertThatThrownBy(() -> roller.roll(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> roller.roll(-1, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void betweenIsInclusive() {
        DiceRoller loop = new DiceRoller();
        for (int i = 0; i < 2000; i++) {
            assertThat(loop.between(2, 5)).isBetween(2, 5);
        }
    }

    private static int sum(int[] values) {
        int s = 0;
        for (int v : values) {
            s += v;
        }
        return s;
    }
}
