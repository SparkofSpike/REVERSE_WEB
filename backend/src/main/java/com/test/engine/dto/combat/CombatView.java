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
    /** Opposing human player; null for solo dummy battles. */
    private String guestUsername;
    /** True when N humans share the PLAYER side against AI enemies. */
    private boolean pve;
    /** PVE: usernames controlling the PLAYER side (host first). */
    private List<String> players;
    /** PVE: who already acted in the current window (submitted / perk picked). */
    private List<String> submittedUsers;
    private CombatPhase phase;
    private int round;
    private String winner;

    // ---- PVP viewer perspective (solo: always PLAYER, flags false) ----
    /** Side controlled by the requesting user. */
    private String mySide;
    /** True when the requesting user already acted in the current window. */
    private boolean mySubmitted;
    /** True when the opponent already acted in the current window. */
    private boolean opponentSubmitted;
    /** Epoch ms by which the current PVP window auto-submits; null in solo. */
    private Long decisionDeadlineAt;
    /** PVP extra-action round: which side's window is currently open. */
    private String extraRoundSide;

    private Integer firstStrikeSide;
    /** The requesting user's own hand (fog of war: the opponent's hand is never exposed). */
    private int playerDrawEnergy;
    private List<GenericSkillTemplate> playerHand;
    private List<Perk> initialPerkOptions;
    private List<Perk> specialPerkOptions;
    private int specialPerkRoundsTaken;
    private boolean extraActionRound;

    private List<CombatantView> combatants;
    private List<CombatEvent> logs;
}
