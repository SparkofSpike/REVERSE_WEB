package com.test.engine.utils;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * TRPG style dice roller.
 *
 * <p>Supports the standard "XdY" notation (X dice of Y sides each, summed).
 * As a TEST-specific convention agreed with the design doc, a zero-count die
 * "0dY" rolls a uniform value in the inclusive range [0, Y].
 */
@Component
public class DiceRoller {

    private static final Pattern DICE_PATTERN =
            Pattern.compile("^\\s*(\\d+)\\s*[dD]\\s*(\\d+)\\s*([+-]\\s*\\d+)?\\s*$");

    private final Random random;

    public DiceRoller() {
        this(new Random());
    }

    public DiceRoller(long seed) {
        this(new Random(seed));
    }

    public DiceRoller(Random random) {
        this.random = random;
    }

    /**
     * Rolls an "XdY" expression.
     *
     * @throws IllegalArgumentException if the expression is malformed
     */
    public DiceResult roll(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("dice expression must not be null");
        }
        Matcher m = DICE_PATTERN.matcher(expression);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid dice expression: " + expression);
        }
        int count = Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = 0;
        if (m.group(3) != null) {
            modifier = Integer.parseInt(m.group(3).replaceAll("\\s+", ""));
        }
        if (modifier == 0) {
            return roll(count, sides);
        }
        // modified expression: base roll plus a flat +/- modifier
        DiceResult base = roll(count, sides);
        return new DiceResult(expression.trim(), count, sides, base.rolls(), base.total() + modifier);
    }

    /**
     * Rolls {@code count} dice of {@code sides} faces.
     *
     * <p>A count of 0 uses the TEST convention: one uniform roll in [0, sides].
     */
    public DiceResult roll(int count, int sides) {
        if (sides < 1) {
            throw new IllegalArgumentException("dice sides must be >= 1, got " + sides);
        }
        if (count < 0) {
            throw new IllegalArgumentException("dice count must be >= 0, got " + count);
        }
        if (count == 0) {
            int value = random.nextInt(sides + 1);
            return new DiceResult("0d" + sides, 0, sides, new int[]{value}, value);
        }
        int[] rolls = new int[count];
        int total = 0;
        for (int i = 0; i < count; i++) {
            int v = random.nextInt(sides) + 1;
            rolls[i] = v;
            total += v;
        }
        return new DiceResult(count + "d" + sides, count, sides, rolls, total);
    }

    /** Uniform integer in [min, max], inclusive. */
    public int between(int min, int max) {
        if (max < min) {
            throw new IllegalArgumentException("max must be >= min");
        }
        return min + random.nextInt(max - min + 1);
    }

    /** The backing random source, exposed for deterministic scenarios. */
    public Random random() {
        return random;
    }
}
