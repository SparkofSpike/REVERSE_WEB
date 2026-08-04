package com.test.engine.combat;

import com.test.engine.enums.ActionType;
import com.test.engine.enums.DamageType;
import com.test.engine.model.CharacterTemplate;
import com.test.engine.model.SkillTemplate;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable in-battle state of a combatant, copied from its template.
 */
@Getter
@Setter
public class Combatant {

    private String id;
    private String templateId;
    private String name;
    private CombatSide side;

    // base stats (from template)
    private int maxHp;
    private int hp;
    private int maxEnergy;
    private int energy;
    private String speedDice;
    private int permanentSpeedBonus;
    private double physicalResistance;
    private double magicResistance;
    private String baseDamageDice;
    private DamageType baseDamageType;
    private String blockDice;
    private String dodgePenalty;
    private List<ActionType> baseActions = new ArrayList<>();

    // transient combat state
    private int shield;
    private int shieldRemainingRounds;
    /** Block value rolled while defending, deducted from incoming damage. */
    private int blockValue;
    private boolean dead;
    private boolean performing;
    /** True once the performance has upgraded this character's skills. */
    private boolean skillsUpgraded;
    private boolean undyingUsed;
    private int extraActionsThisTurn;
    private int extraGuardsThisTurn;
    private int extraSkillsThisTurn;
    private int bonusDamage;
    private int speedBoostThisRound;
    private boolean defending;
    private boolean countering;
    /** Guard target (the protected ally). */
    private String guardTargetId;
    private int guardSuccessCount;
    private int totalHealGiven;
    private boolean permanentExtraAction;
    /** Shield-mountain bind: this combatant absorbs damage for the target. */
    private String guardBindTargetId;
    private int guardBindRounds;
    private int guardBindShield;

    private Map<String, Integer> cooldowns = new LinkedHashMap<>();
    private List<StatusEffect> statusEffects = new ArrayList<>();

    /** Last target this combatant attacked (for CHASE bonus). */
    private String lastAttackedTarget;
    /** Stone-shield pending payout from last round's defense. */
    private boolean stoneShieldPending;
    /** Remaining undying protection rounds (宁死不屈). */
    private int undyingRounds;
    /** Dodging state and its resolved dodge value this round. */
    private boolean dodging;
    private int dodgeValue;

    /** Template reference (nullable for puppets). */
    private CharacterTemplate template;
    /** Skills resolved at runtime: upgrades replace entries after performance. */
    private List<SkillTemplate> skills = new ArrayList<>();

    public static Combatant fromTemplate(CharacterTemplate t, String id, CombatSide side) {
        Combatant c = new Combatant();
        c.setId(id);
        c.setTemplateId(t.getId());
        c.setName(t.getName());
        c.setSide(side);
        c.setMaxHp(t.getMaxHp());
        c.setHp(t.getMaxHp());
        c.setMaxEnergy(t.getMaxEnergy());
        c.setEnergy(t.getMaxEnergy());
        c.setSpeedDice(t.getSpeedDice());
        c.setPhysicalResistance(t.getPhysicalResistance());
        c.setMagicResistance(t.getMagicResistance());
        c.setBaseDamageDice(t.getBaseDamageDice());
        c.setBaseDamageType(t.getBaseDamageType());
        c.setBlockDice(t.getBlockDice());
        c.setDodgePenalty(t.getDodgePenalty());
        c.setBaseActions(new ArrayList<>(t.getBaseActions()));
        c.setTemplate(t);
        c.setSkills(new ArrayList<>(t.getSkills()));
        return c;
    }

    public boolean isPlayerSide() {
        return side == CombatSide.PLAYER;
    }

    /** Effective resistance including transient mods (defend -0.2, counter -0.1). */
    public double effectivePhysicalResistance() {
        double r = physicalResistance;
        if (defending) {
            r -= 0.2;
        }
        if (countering) {
            r -= 0.1;
        }
        return r;
    }

    public double effectiveMagicResistance() {
        double r = magicResistance;
        if (defending) {
            r -= 0.2;
        }
        if (countering) {
            r -= 0.1;
        }
        return r;
    }

    /** Effective speed for this round, including permanent and round boosts. */
    public int effectiveSpeed() {
        return permanentSpeedBonus + speedBoostThisRound;
    }

    public boolean hasCooldown(String skillId) {
        return cooldowns.getOrDefault(skillId, 0) > 0;
    }

    public void tickCooldowns() {
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
    }

    public void setCooldown(String skillId, int rounds) {
        cooldowns.put(skillId, rounds);
    }

    /** Finds a skill by id, falling back to upgraded entries. */
    public SkillTemplate findSkill(String skillId) {
        return skills.stream().filter(s -> s.getId().equals(skillId)).findFirst().orElse(null);
    }

    public void addStatus(StatusEffect effect) {
        statusEffects.add(effect);
    }

    public List<StatusEffect> statusesOfType(String type) {
        return statusEffects.stream().filter(s -> s.getType().equals(type)).toList();
    }
}
