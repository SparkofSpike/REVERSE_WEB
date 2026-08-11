package com.test.engine.model;

import lombok.Data;

import java.util.List;

/**
 * Universal skill (Universal Skill) played directly from hand as a card.
 */
@Data
public class GenericSkillTemplate {

    private String id;
    private String name;
    /** True when the card is removed from the deck after use. */
    private boolean consumed;
    private List<EffectSpec> effects;
    private String description;
}
