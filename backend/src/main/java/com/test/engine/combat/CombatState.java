package com.test.engine.combat;

import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.model.Perk;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full mutable battle state driving the state machine.
 */
@Getter
@Setter
public class CombatState {

    private String id;
    private String ownerUsername;
    private Instant createdAt = Instant.now();

    private List<Combatant> combatants = new ArrayList<>();
    private CombatPhase phase = CombatPhase.SETUP;
    private int round;

    // player-side draw energy and generic skill deck
    private int playerDrawEnergy;
    private List<GenericSkillTemplate> playerHand = new ArrayList<>();
    private List<GenericSkillTemplate> playerDeck = new ArrayList<>();
    private boolean drawBoostPending;

    // perk offers
    private List<Perk> initialPerkOptions = new ArrayList<>();
    private List<Perk> specialPerkOptions = new ArrayList<>();
    private int specialPerkRoundsTaken;
    private boolean specialPerkAdvancePending;
    private boolean perkSkipped;

    // round flow
    private Integer firstStrikeSide;
    private List<ActionDecision> pendingDecisions = new ArrayList<>();
    private Map<String, Integer> roundSpeed = new LinkedHashMap<>();
    /** True while the player may spend extra base actions (连续奔袭 etc.). */
    private boolean extraActionRound;

    private String winner;
    private List<CombatEvent> logs = new ArrayList<>();

    // ----- helpers -----

    public void log(CombatEvent event) {
        logs.add(event);
    }

    public List<Combatant> side(CombatSide side) {
        return combatants.stream().filter(c -> c.getSide() == side).toList();
    }

    public List<Combatant> alive(CombatSide side) {
        return side(side).stream().filter(c -> !c.isDead()).toList();
    }

    public Combatant find(String id) {
        return combatants.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    public List<Combatant> allAlive() {
        return combatants.stream().filter(c -> !c.isDead()).toList();
    }

    public boolean isOver() {
        return phase == CombatPhase.FINISHED || winner != null;
    }

    public void addDrawEnergy(int amount) {
        playerDrawEnergy = Math.min(10, playerDrawEnergy + amount);
    }
}
