package com.test.engine.combat;

import com.test.engine.enums.DamageType;
import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import org.springframework.stereotype.Component;

/**
 * Damage adjudication per the design doc:
 *
 * <ul>
 *   <li>Shield absorbs 1.2x its value of direct damage before HP.</li>
 *   <li>Physical/magic damage is scaled by the target's resistance
 *       (resistance 1.2 means 1.2x damage taken).</li>
 *   <li>BREAK: 2x damage against armor/shield, 0.5x against unshielded HP,
 *       otherwise treated as physical.</li>
 *   <li>PIERCE: deals full damage to shield as usual, plus the target loses
 *       0.4x of the total damage directly as HP, shield unaffected.</li>
 * </ul>
 */
@Component
public class DamageResolver {

    private static final double SHIELD_MULTIPLIER = 1.2;
    private static final double PIERCE_HP_FRACTION = 0.4;
    private static final double BREAK_SHIELD_MULTIPLIER = 2.0;
    private static final double BREAK_HP_FRACTION = 0.5;

    private final DiceRoller dice;

    public DamageResolver(DiceRoller dice) {
        this.dice = dice;
    }

    /**
     * Applies damage to a target and returns the outcome record.
     */
    public DamageOutcome dealDamage(Combatant target, int rawDamage, DamageType type, CombatState state) {
        return dealDamage(target, rawDamage, type, state, null, null);
    }

    public DamageOutcome dealDamage(Combatant target, int rawDamage, DamageType type, CombatState state,
                                    Combatant attacker, String action) {
        DamageOutcome outcome = new DamageOutcome();
        outcome.setRaw(rawDamage);
        outcome.setType(type);
        if (rawDamage <= 0 || target.isDead()) {
            return outcome;
        }

        if (type == DamageType.PIERCE) {
            int pierceHp = (int) Math.round(rawDamage * PIERCE_HP_FRACTION);
            applyHpDamage(target, pierceHp, outcome);
            outcome.setPierceHp(pierceHp);
        }

        if (type == DamageType.BREAK && target.getShield() > 0) {
            int shieldDamage = (int) Math.round(rawDamage * BREAK_SHIELD_MULTIPLIER);
            absorbWithShield(target, shieldDamage, outcome);
            outcome.setShieldAbsorbed(Math.min(shieldDamage, target.getShield() + outcome.getShieldAbsorbed()));
            // any intent that overflows the shield hits HP at the unshielded
            // BREAK rate (0.5x) scaled by resistance - exactly like a
            // shield-less BREAK hit - instead of at full value
            int overflow = shieldDamage - outcome.getShieldAbsorbed();
            if (overflow > 0) {
                double resistance = (type == DamageType.MAGIC)
                        ? target.effectiveMagicResistance()
                        : target.effectivePhysicalResistance();
                outcome.setHpDamage((int) Math.round(overflow * BREAK_HP_FRACTION * resistance));
            }
        } else {
            double resistance = (type == DamageType.MAGIC)
                    ? target.effectiveMagicResistance()
                    : target.effectivePhysicalResistance();
            int scaled = (int) Math.round(rawDamage * resistance);
            if (type == DamageType.BREAK && target.getShield() <= 0) {
                scaled = (int) Math.round(rawDamage * BREAK_HP_FRACTION * resistance);
            }
            // defending combatants deduct their rolled block value
            // (design doc: block value directly offsets damage after resistance)
            if (target.isDefending() && target.getBlockValue() > 0) {
                int blocked = Math.min(scaled, target.getBlockValue());
                scaled -= blocked;
                outcome.setBlocked(blocked);
            }
            absorbWithShield(target, scaled, outcome);
        }

        applyHpDamage(target, outcome.getHpDamage(), outcome);
        logEvent(target, type, outcome, state, attacker, action);
        return outcome;
    }

    private void absorbWithShield(Combatant target, int damage, DamageOutcome outcome) {
        if (damage <= 0) {
            return;
        }
        if (target.getShield() > 0) {
            double effectiveShield = target.getShield() * SHIELD_MULTIPLIER;
            if (damage <= effectiveShield) {
                int consumed = (int) Math.ceil(damage / SHIELD_MULTIPLIER);
                target.setShield(Math.max(0, target.getShield() - consumed));
                outcome.setShieldAbsorbed(outcome.getShieldAbsorbed() + damage);
                outcome.setHpDamage(0);
                return;
            }
            int overflow = damage - (int) effectiveShield;
            outcome.setShieldAbsorbed(outcome.getShieldAbsorbed() + (int) effectiveShield);
            target.setShield(0);
            outcome.setHpDamage(overflow);
            return;
        }
        outcome.setHpDamage(damage);
    }

    private void applyHpDamage(Combatant target, int hpDamage, DamageOutcome outcome) {
        if (hpDamage <= 0) {
            return;
        }
        int actual = Math.min(hpDamage, target.getHp());
        target.setHp(target.getHp() - actual);
        outcome.setHpDamage(actual);
        outcome.setHpLost(actual);
    }

    private void logEvent(Combatant target, DamageType type, DamageOutcome outcome, CombatState state,
                          Combatant attacker, String action) {
        CombatEvent event = CombatEvent.of(state.getRound(), "damage",
                        target.getName() + " 受到 " + outcome.getRaw() + " 点" + type.label()
                                + "，格挡减免 " + outcome.getBlocked()
                                + "，护盾吸收 " + outcome.getShieldAbsorbed()
                                + "，生命损失 " + outcome.getHpDamage() + "。")
                .with("target", target.getId())
                .with("raw", outcome.getRaw())
                .with("blocked", outcome.getBlocked())
                .with("shieldAbsorbed", outcome.getShieldAbsorbed())
                .with("hpDamage", outcome.getHpDamage());
        if (attacker != null) {
            event.with("actorId", attacker.getId());
        }
        if (action != null) {
            event.with("action", action);
        }
        state.log(event);
    }

    /** Roll base damage for an attack. */
    public DiceResult rollDamage(String diceExpression) {
        return dice.roll(diceExpression);
    }

    /**
     * Outcome of a damage application.
     */
    public static class DamageOutcome {
        private int raw;
        private int blocked;
        private int shieldAbsorbed;
        private int hpDamage;
        private int hpLost;
        private int pierceHp;
        private DamageType type;

        public int getRaw() {
            return raw;
        }

        public int getBlocked() {
            return blocked;
        }

        public void setBlocked(int blocked) {
            this.blocked = blocked;
        }

        public void setRaw(int raw) {
            this.raw = raw;
        }

        public int getShieldAbsorbed() {
            return shieldAbsorbed;
        }

        public void setShieldAbsorbed(int shieldAbsorbed) {
            this.shieldAbsorbed = shieldAbsorbed;
        }

        public int getHpDamage() {
            return hpDamage;
        }

        public void setHpDamage(int hpDamage) {
            this.hpDamage = hpDamage;
        }

        public int getHpLost() {
            return hpLost;
        }

        public void setHpLost(int hpLost) {
            this.hpLost = hpLost;
        }

        public int getPierceHp() {
            return pierceHp;
        }

        public void setPierceHp(int pierceHp) {
            this.pierceHp = pierceHp;
        }

        public DamageType getType() {
            return type;
        }

        public void setType(DamageType type) {
            this.type = type;
        }
    }
}
