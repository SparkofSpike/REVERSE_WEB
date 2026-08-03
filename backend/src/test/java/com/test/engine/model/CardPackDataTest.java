package com.test.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.enums.ActionType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the TEST.1 card pack JSON parses into a complete model matching
 * the design document numbers.
 */
class CardPackDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsTest1Pack() throws Exception {
        CardPack pack;
        try (InputStream in = getClass().getResourceAsStream("/cards/test-1.json")) {
            pack = mapper.readValue(in, CardPack.class);
        }

        assertThat(pack.getId()).isEqualTo("test-1");
        assertThat(pack.getCore().getName()).isEqualTo("本心尚存");

        assertThat(pack.getInitialPerks()).hasSize(3);
        assertThat(pack.getSpecialPerks()).hasSize(6);
        assertThat(pack.getGenericSkills()).hasSize(12);
        assertThat(pack.getCharacters()).hasSize(4);
    }

    @Test
    void warriorMatchesDesignDoc() throws Exception {
        CharacterTemplate warrior = character("warrior");

        assertThat(warrior.getName()).isEqualTo("战斗者");
        assertThat(warrior.getMaxHp()).isEqualTo(80);
        assertThat(warrior.getMaxEnergy()).isEqualTo(100);
        assertThat(warrior.getSpeedDice()).isEqualTo("1d7");
        assertThat(warrior.getPhysicalResistance()).isEqualTo(1.0);
        assertThat(warrior.getMagicResistance()).isEqualTo(1.0);
        assertThat(warrior.getBaseDamageDice()).isEqualTo("1d7");
        assertThat(warrior.getBlockDice()).isEqualTo("1d7");
        assertThat(warrior.getBaseActions()).contains(ActionType.ATTACK, ActionType.CHASE);
        assertThat(warrior.getCorePassive().getType()).isEqualTo("undying");
        assertThat(warrior.getPerformance().getTriggerType()).isEqualTo("hp_below");
        assertThat(warrior.getPerformance().getThreshold()).isEqualTo(40);
        assertThat(warrior.getSkills()).hasSize(3);
        // every skill has an upgraded variant
        for (SkillTemplate s : warrior.getSkills()) {
            assertThat(s.getUpgraded()).as(s.getId() + " must have an upgraded form").isNotNull();
        }
    }

    @Test
    void mageAndPriestAndCrabMatchDesignDoc() throws Exception {
        CharacterTemplate mage = character("mage");
        assertThat(mage.getMaxHp()).isEqualTo(60);
        assertThat(mage.getMaxEnergy()).isEqualTo(120);
        assertThat(mage.getBaseDamageType()).hasToString("MAGIC");
        assertThat(mage.getCorePassive().getType()).isEqualTo("energy_discount");
        assertThat(mage.getPerformance().getTriggerType()).isEqualTo("energy_below");
        assertThat(mage.getBaseActions()).contains(ActionType.PRAY);
        assertThat(mage.getSkills()).hasSize(3);

        CharacterTemplate priest = character("priest");
        assertThat(priest.getMagicResistance()).isEqualTo(1.4);
        assertThat(priest.getCorePassive().getType()).isEqualTo("compassion_heal");
        assertThat(priest.getCorePassive().getRatio()).isEqualTo(0.5);
        assertThat(priest.getPerformance().getTriggerType()).isEqualTo("heal_total");
        assertThat(priest.getSkills()).hasSize(3);

        CharacterTemplate crab = character("crab-dwarf");
        assertThat(crab.getMaxHp()).isEqualTo(100);
        assertThat(crab.getCorePassive().getType()).isEqualTo("stone_shield");
        assertThat(crab.getPerformance().getTriggerType()).isEqualTo("guard_success");
        assertThat(crab.getSkills()).hasSize(3);
    }

    @Test
    void genericSkillsHaveEffects() throws Exception {
        CardPack pack = load("/cards/test-1.json");
        for (GenericSkillTemplate s : pack.getGenericSkills()) {
            assertThat(s.getEffects()).as(s.getId() + " must declare effects").isNotEmpty();
            assertThat(s.getName()).isNotBlank();
        }
        assertThat(pack.getGenericSkills().stream().filter(s -> s.isConsumed()))
                .extracting(GenericSkillTemplate::getId)
                .containsExactly("g-reverse-draw");
    }

    @Test
    void specialPerksCarryRoundRequirements() throws Exception {
        CardPack pack = load("/cards/test-1.json");
        assertThat(pack.getSpecialPerks().stream().filter(p -> p.getRoundRequirement() == 2))
                .extracting(Perk::getId)
                .containsExactly("perk-recharge-2");
        assertThat(pack.getSpecialPerks().stream().filter(p -> p.getRoundRequirement() == -1))
                .extracting(Perk::getId)
                .containsExactly("perk-cold-realization");
    }

    @Test
    void loadsPuppet() throws Exception {
        PuppetTemplate puppet = mapper.readValue(
                getClass().getResourceAsStream("/cards/puppet.json"), PuppetTemplate.class);
        assertThat(puppet.getId()).isEqualTo("training-dummy");
        assertThat(puppet.getMaxHp()).isEqualTo(800);
        assertThat(puppet.getBaseActions()).contains(ActionType.ATTACK, ActionType.DEFEND);
    }

    private CharacterTemplate character(String id) throws Exception {
        CardPack pack = load("/cards/test-1.json");
        return pack.getCharacters().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("character not found: " + id));
    }

    private CardPack load(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readValue(in, CardPack.class);
        }
    }
}
