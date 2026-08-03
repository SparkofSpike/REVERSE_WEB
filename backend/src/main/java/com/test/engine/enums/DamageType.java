package com.test.engine.enums;

/**
 * Damage categories in TEST.
 *
 * <p>PHYSICAL and MAGIC are plain direct damage scaled by the corresponding
 * resistance. BREAK deals double damage to armor/shield and 0.5 normally,
 * otherwise treated as physical. PIERCE bypasses shield partially: the target
 * loses 0.4x of the total damage as health while the shield absorbs normally.
 */
public enum DamageType {
    PHYSICAL("物理伤害"),
    MAGIC("法术伤害"),
    BREAK("破击伤害"),
    PIERCE("穿击伤害");

    private final String label;

    DamageType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
