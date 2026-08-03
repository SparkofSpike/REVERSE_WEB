package com.test.engine.model;

import lombok.Data;

/**
 * Core passive of a character (被动词条库 entry).
 */
@Data
public class PassiveSpec {

    /** Passive type key: undying, energy_discount, compassion_heal, stone_shield. */
    private String type;
    /** Threshold or flat magnitude. */
    private int amount;
    private double ratio;
    private String dice;
    private int duration;
    private String description;
}
