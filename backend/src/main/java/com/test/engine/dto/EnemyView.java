package com.test.engine.dto;

import com.test.engine.enums.DamageType;
import lombok.Data;

/**
 * Frontend facing enemy (puppet) template snapshot for PVE room creation.
 */
@Data
public class EnemyView {

    private String id;
    private String name;
    private int maxHp;
    private int maxEnergy;
    private String speedDice;
    private String baseDamageDice;
    private DamageType baseDamageType;
    private double physicalResistance;
    private double magicResistance;
    private String blockDice;
    private String dodgePenalty;
}
