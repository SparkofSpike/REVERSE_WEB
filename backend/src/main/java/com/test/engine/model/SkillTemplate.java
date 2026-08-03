package com.test.engine.model;

import lombok.Data;

/**
 * Character skill template (card form). Upgraded variant applies after the
 * character's performance triggers.
 */
@Data
public class SkillTemplate {

    private String id;
    private String name;
    private int energyCost;
    /** Rounds between uses, 0 = no cooldown. */
    private int cooldown;
    /** Target selector: enemy, enemies, ally, allies, self, random_ally. */
    private String targetType;
    private java.util.List<EffectSpec> effects;
    private String description;
    /** Evolved (升变) version, null until performance upgrades it. */
    private SkillTemplate upgraded;
}
