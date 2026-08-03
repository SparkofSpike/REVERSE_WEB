package com.test.engine.model;

import lombok.Data;

import java.util.List;

/**
 * A card pack: one core, initial/special perks, universal skills and
 * character templates. Loaded from JSON under resources/cards.
 */
@Data
public class CardPack {

    private String id;
    private String name;
    private Core core;
    private List<Perk> initialPerks;
    private List<Perk> specialPerks;
    private List<GenericSkillTemplate> genericSkills;
    private List<CharacterTemplate> characters;
}
