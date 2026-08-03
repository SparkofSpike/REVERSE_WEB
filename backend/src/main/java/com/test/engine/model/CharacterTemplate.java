package com.test.engine.model;

import lombok.Data;

import java.util.List;

/**
 * Static character definition from a card pack.
 */
@Data
public class CharacterTemplate {

    private String id;
    private String name;
    private String description;

    private int maxHp;
    private int maxEnergy;
    /** Speed dice, e.g. "1d7". */
    private String speedDice;
    private double physicalResistance;
    private double magicResistance;
    /** Base damage dice, e.g. "1d7". */
    private String baseDamageDice;
    private com.test.engine.enums.DamageType baseDamageType;
    /** Block dice, e.g. "1d7". */
    private String blockDice;
    /** Dodge penalty dice, e.g. "0d3". */
    private String dodgePenalty;

    /** Available base actions, may include character-exclusive ones. */
    private List<com.test.engine.enums.ActionType> baseActions;

    private PassiveSpec corePassive;
    private PerformanceSpec performance;

    private List<SkillTemplate> skills;
}
