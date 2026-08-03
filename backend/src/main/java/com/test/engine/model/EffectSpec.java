package com.test.engine.model;

import com.test.engine.enums.DamageType;
import lombok.Data;

/**
 * Declarative effect description loaded from card JSON.
 *
 * <p>Fields are optional and interpreted per effect type by the combat
 * engine. Keeping the spec data-driven allows new card packs without code
 * changes; brand-new effect types still require engine support.
 */
@Data
public class EffectSpec {

    /** Effect type key, e.g. damage, heal, shield, draw, lifesteal. */
    private String type;

    /** Dice expression for random magnitudes, e.g. "1d12". */
    private String dice;

    /** Fixed magnitude used when dice is absent. */
    private int amount;

    /** Ratio parameter, e.g. lifesteal 0.2. */
    private double ratio;

    /** Duration in rounds, 0 = instant or permanent. */
    private int duration;

    /** Count parameter: number of targets, cards, or extra actions. */
    private int count;

    /** Upper cap for quantities like drawn cards per round. */
    private int max;

    /** Damage category for damage effects. */
    private DamageType damageType;

    /** Target selector: self, ally, allies, enemy, enemies, random_ally. */
    private String target;

    /** Interval in rounds for periodic effects. */
    private int interval;
}
