package com.test.engine.dto.combat;

import com.test.engine.model.EffectSpec;
import lombok.Data;

import java.util.List;

/**
 * Frontend facing skill description.
 */
@Data
public class SkillView {

    private String id;
    private String name;
    private int energyCost;
    private int cooldown;
    private String targetType;
    private String description;
    private boolean upgraded;
    private List<EffectSpec> effects;
}
