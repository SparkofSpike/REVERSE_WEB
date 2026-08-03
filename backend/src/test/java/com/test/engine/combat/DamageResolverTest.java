package com.test.engine.combat;

import com.test.engine.enums.DamageType;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamageResolverTest {

    private final DamageResolver resolver = new DamageResolver(new DiceRoller(7L));

    private Combatant target(int hp, double physRes, double magicRes) {
        Combatant c = new Combatant();
        c.setId("t");
        c.setName("t");
        c.setMaxHp(hp);
        c.setHp(hp);
        c.setPhysicalResistance(physRes);
        c.setMagicResistance(magicRes);
        return c;
    }

    @Test
    void physicalDamageScaledByResistance() {
        Combatant t = target(100, 1.2, 1.0);
        CombatState state = new CombatState();
        DamageResolver.DamageOutcome o = resolver.dealDamage(t, 10, DamageType.PHYSICAL, state);
        // 10 * 1.2 = 12
        assertThat(t.getHp()).isEqualTo(88);
        assertThat(o.getHpDamage()).isEqualTo(12);
    }

    @Test
    void shieldAbsorbsWithOnePointTwoMultiplier() {
        Combatant t = target(100, 1.0, 1.0);
        t.setShield(10);
        CombatState state = new CombatState();
        // 10 shield * 1.2 = 12 effective; a 10 damage hit fully absorbed
        resolver.dealDamage(t, 10, DamageType.PHYSICAL, state);
        assertThat(t.getHp()).isEqualTo(100);
        // consumed 10/1.2 = 8.33 -> ceil 9 shield left 1
        assertThat(t.getShield()).isEqualTo(1);
    }

    @Test
    void shieldOverflowDamagesHp() {
        Combatant t = target(100, 1.0, 1.0);
        t.setShield(5);
        CombatState state = new CombatState();
        // effective shield 6; 15 damage -> 9 to hp
        resolver.dealDamage(t, 15, DamageType.PHYSICAL, state);
        assertThat(t.getHp()).isEqualTo(91);
        assertThat(t.getShield()).isZero();
    }

    @Test
    void breakDamageDoublesAgainstShield() {
        Combatant t = target(100, 1.0, 1.0);
        t.setShield(10);
        CombatState state = new CombatState();
        // 6 raw * 2 = 12 shield damage, shield absorbs up to 12
        resolver.dealDamage(t, 6, DamageType.BREAK, state);
        assertThat(t.getHp()).isEqualTo(100);
        assertThat(t.getShield()).isLessThan(10);
    }

    @Test
    void breakDamageHalvesWithoutShield() {
        Combatant t = target(100, 1.0, 1.0);
        CombatState state = new CombatState();
        resolver.dealDamage(t, 10, DamageType.BREAK, state);
        // 10 * 0.5 = 5
        assertThat(t.getHp()).isEqualTo(95);
    }

    @Test
    void pierceDamageHitsHpDirectly() {
        Combatant t = target(100, 1.0, 1.0);
        t.setShield(10);
        CombatState state = new CombatState();
        resolver.dealDamage(t, 10, DamageType.PIERCE, state);
        // 10*0.4 = 4 direct hp + shield absorbs rest
        assertThat(t.getHp()).isEqualTo(96);
        assertThat(t.getShield()).isLessThan(10);
    }

    @Test
    void magicDamageUsesMagicResistance() {
        Combatant t = target(100, 1.0, 0.8);
        CombatState state = new CombatState();
        resolver.dealDamage(t, 10, DamageType.MAGIC, state);
        // 10 * 0.8 = 8
        assertThat(t.getHp()).isEqualTo(92);
    }
}
