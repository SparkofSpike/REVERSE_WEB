package com.test.engine.model;

import lombok.Data;

/**
 * A perk (外在词条) offered during battle setup or special rounds.
 *
 * <p>Initial perks have roundRequirement 0. Special perks carry a round
 * requirement: positive value = pickable on that round number, -1 = only on
 * the final special round.
 */
@Data
public class Perk {

    private String id;
    private String name;
    private String description;
    private EffectSpec effect;
    /** 0 = no restriction, positive = round number, -1 = final round. */
    private int roundRequirement;
}
