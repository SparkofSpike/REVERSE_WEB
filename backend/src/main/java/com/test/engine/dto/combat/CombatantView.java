package com.test.engine.dto.combat;

import com.test.engine.combat.StatusEffect;
import com.test.engine.enums.ActionType;
import com.test.engine.enums.DamageType;
import com.test.engine.model.PerformanceSpec;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Frontend facing combatant snapshot.
 */
@Data
public class CombatantView {

    private String id;
    private String templateId;
    private String name;
    private String side;

    private int hp;
    private int maxHp;
    private int energy;
    private int maxEnergy;
    private int shield;
    private int shieldRemainingRounds;
    private boolean dead;
    private boolean performing;
    private boolean skillsUpgraded;
    private boolean dodging;
    private int guardSuccessCount;
    private int totalHealGiven;
    private String guardTargetId;
    private boolean permanentExtraAction;
    private boolean undyingUsed;
    private int undyingRounds;

    private String speedDice;
    private int permanentSpeedBonus;
    private double physicalResistance;
    private double magicResistance;
    private String baseDamageDice;
    private DamageType baseDamageType;
    private String blockDice;
    private String dodgePenalty;
    private List<ActionType> baseActions;

    private List<SkillView> skills;
    private String corePassiveName;
    private String corePassiveDescription;
    private PerformanceSpec performance;
    private List<StatusEffect> statusEffects;
    private Map<String, Integer> cooldowns;
    private int bonusDamage;
    private int extraActionsThisTurn;
}
