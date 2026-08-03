package com.test.engine.combat;

import lombok.Getter;
import lombok.Setter;

/**
 * A timed status effect attached to a combatant. Interpreted by the engine
 * at round start / end ticks.
 */
@Getter
@Setter
public class StatusEffect {

    /** Effect type: lifesteal, damage_bonus, heal_over_time, shield_over_time, energy_over_time, draw_over_time. */
    private String type;
    /** Remaining rounds, decremented at round end. */
    private int remainingRounds;
    /** Initial duration for display. */
    private int duration;
    private double ratio;
    private String dice;
    private int amount;
    /** Multi-target count for effects affecting N allies. */
    private int count;
    /** Cap for draw_over_time. */
    private int max;
    /** Owning combatant id (for heal attribution). */
    private String ownerId;

    public static StatusEffect of(String type, int duration) {
        StatusEffect e = new StatusEffect();
        e.setType(type);
        e.setDuration(duration);
        e.setRemainingRounds(duration);
        return e;
    }

    public boolean expired() {
        return remainingRounds <= 0;
    }
}
