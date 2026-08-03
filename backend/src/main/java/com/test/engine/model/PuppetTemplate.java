package com.test.engine.model;

import com.test.engine.enums.ActionType;
import com.test.engine.enums.DamageType;
import lombok.Data;

import java.util.List;

/**
 * Training dummy (木桩) definition. A dummy has no skills, passives or
 * performance; it retaliates with simple base actions.
 */
@Data
public class PuppetTemplate {

    private String id;
    private String name;
    private int maxHp;
    private int maxEnergy;
    private String speedDice;
    private double physicalResistance;
    private double magicResistance;
    private String baseDamageDice;
    private DamageType baseDamageType;
    private String blockDice;
    private String dodgePenalty;
    private List<ActionType> baseActions;
}
