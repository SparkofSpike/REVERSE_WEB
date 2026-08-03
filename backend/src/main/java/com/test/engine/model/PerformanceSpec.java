package com.test.engine.model;

import lombok.Data;

import java.util.List;

/**
 * Performance (演出) trigger and its bonus effects. Triggering upgrades the
 * character and grants the listed effects.
 */
@Data
public class PerformanceSpec {

    /** Trigger type: hp_below, energy_below, heal_total, guard_success. */
    private String triggerType;
    /** Threshold value for the trigger. */
    private int threshold;
    /** Human readable trigger condition. */
    private String description;
    /** Effects applied when the performance triggers. */
    private List<EffectSpec> effects;
}
