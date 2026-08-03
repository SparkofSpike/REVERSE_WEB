package com.test.engine.dto.combat;

import com.test.engine.combat.CombatEvent;
import com.test.engine.combat.CombatPhase;
import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.model.Perk;
import lombok.Data;

import java.util.List;

/**
 * Frontend facing battle state snapshot.
 */
@Data
public class CombatView {

    private String id;
    private String ownerUsername;
    private CombatPhase phase;
    private int round;
    private String winner;

    private Integer firstStrikeSide;
    private int playerDrawEnergy;
    private List<GenericSkillTemplate> playerHand;
    private List<Perk> initialPerkOptions;
    private List<Perk> specialPerkOptions;
    private int specialPerkRoundsTaken;

    private List<CombatantView> combatants;
    private List<CombatEvent> logs;
}
