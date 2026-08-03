package com.test.engine.utils;

import java.util.Arrays;

/**
 * Outcome of a dice roll: the expression, per-die values and the total.
 */
public record DiceResult(String expression, int count, int sides, int[] rolls, int total) {

    @Override
    public String toString() {
        return expression + " = " + total + " (" + Arrays.toString(rolls) + ")";
    }
}
