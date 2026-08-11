package com.test.engine.combat;

import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.model.Perk;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
    /** Opposing human player; null for solo dummy battles (PVP flag). */
    private String guestUsername;
    /** Deaths per side this battle (drives ally-death performances). */
    private final Map<CombatSide, Integer> deaths = new EnumMap<>(CombatSide.class);
    /** The card pack this battle was created from. */
    private String packId;
    private Instant createdAt = Instant.now();

    private List<Combatant> combatants = new ArrayList<>();
    private CombatPhase phase = CombatPhase.SETUP;
    private int round;

    // player-side draw energy and generic skill deck
    private int playerDrawEnergy;
    private List<GenericSkillTemplate> playerHand = new ArrayList<>();
    private List<GenericSkillTemplate> playerDeck = new ArrayList<>();
    /** Enemy-side draw energy and generic skill deck (PVP only). */
    private int enemyDrawEnergy;
    private List<GenericSkillTemplate> enemyHand = new ArrayList<>();
    private List<GenericSkillTemplate> enemyDeck = new ArrayList<>();
    private boolean drawBoostPending;

    // perk offers
    private List<Perk> initialPerkOptions = new ArrayList<>();
    private List<Perk> specialPerkOptions = new ArrayList<>();
    private int specialPerkRoundsTaken;
    private boolean specialPerkAdvancePending;
    private boolean perkSkipped;
    /** True once the perk offer fired for the current round (re-entry guard). */
    private boolean specialPerkOffered;
    /** True once an accelerated offer consumed the next normal perk round. */
    private boolean specialPerkAcceleratedConsumed;

    // round flow
    private Integer firstStrikeSide;
    private List<ActionDecision> pendingDecisions = new ArrayList<>();
    private Map<String, Integer> roundSpeed = new LinkedHashMap<>();
    /** True while a side may spend extra base actions (Relentless Charge etc.). */
    private boolean extraActionRound;
    /** PVP: which side's extra-action window is currently open. */
    private CombatSide extraRoundSide;
    /** PVP: per-side decisions waiting for the opponent to submit. */
    private final Map<CombatSide, List<ActionDecision>> pendingBySide = new EnumMap<>(CombatSide.class);
    /** PVP: sides that already submitted this round's main decisions. */
    private final Map<CombatSide, Boolean> submittedThisRound = new EnumMap<>(CombatSide.class);
    /** PVP: sides that finished their extra-action window. */
    private final Map<CombatSide, Boolean> extraDone = new EnumMap<>(CombatSide.class);
    /** PVP: sides that picked or skipped the special perk offer. */
    private final Map<CombatSide, Boolean> specialPerkSubmitted = new EnumMap<>(CombatSide.class);
    /** PVP: sides that picked the initial perk. */
    private final Map<CombatSide, Boolean> initialPerkSelected = new EnumMap<>(CombatSide.class);
    /** PVP: epoch ms by which the current decision window auto-submits. */
    private Long decisionDeadlineAt;
    /** PVP: consecutive rounds a side ended WITHOUT submitting (idle surrender). */
    private final Map<CombatSide, Integer> idleRounds = new EnumMap<>(CombatSide.class);
    /** Enemy decisions deferred while a side spends extra actions. */
    private List<ActionDecision> pendingEnemyDecisions = new ArrayList<>();

    // ---- PVE multi-player structures ----
    /** True when N humans share the PLAYER side against AI enemies. */
    private boolean pve;
    /** PVE: ordered usernames controlling the PLAYER side (host first). */
    private final List<String> playerUsers = new ArrayList<>();
    /** PVE: per-player generic skill deck, hand and draw energy. */
    private final Map<String, List<GenericSkillTemplate>> handsByUser = new HashMap<>();
    private final Map<String, List<GenericSkillTemplate>> decksByUser = new HashMap<>();
    private final Map<String, Integer> drawEnergyByUser = new HashMap<>();
    /** PVE: per-player submissions for the current decision window. */
    private final Map<String, List<ActionDecision>> pendingByUser = new HashMap<>();
    private final Map<String, Boolean> submittedByUser = new HashMap<>();
    private final Map<String, Boolean> initialPerkSelectedByUser = new HashMap<>();
    private final Map<String, Boolean> specialPerkSubmittedByUser = new HashMap<>();
    private final Map<String, Boolean> extraDoneByUser = new HashMap<>();
    private final Map<String, Integer> idleRoundsByUser = new HashMap<>();
    /** PVE: last reported decision draft per player; timeouts submit the draft. */
    private final Map<String, List<ActionDecision>> draftByUser = new HashMap<>();

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

    /**
     * Central death adjudication shared by every damage source (plain
     * attacks, skills, cards, hp costs): undying (Unyielding) keeps the
     * combatant alive, otherwise the combatant dies and logs a death event.
     */
    public void resolvePotentialDeath(Combatant c) {
        if (c.getHp() > 0 || c.isDead()) {
            return;
        }
        // undying: Unyielding keeps the combatant alive for this and next round
        if (c.getTemplate() != null && c.getTemplate().getCorePassive() != null
                && "undying".equals(c.getTemplate().getCorePassive().getType()) && !c.isUndyingUsed()) {
            c.setUndyingUsed(true);
            c.setHp(1);
            c.setUndyingRounds(2);
            log(CombatEvent.of(getRound(), "performance",
                    c.getName() + " 宁死不屈！本回合和下回合不会倒下。"));
            return;
        }
        if (c.getUndyingRounds() > 0) {
            c.setHp(1);
            c.setUndyingRounds(c.getUndyingRounds() - 1);
            return;
        }
        c.setDead(true);
        deaths.merge(c.getSide(), 1, Integer::sum);
        log(CombatEvent.of(getRound(), "death", c.getName() + " 倒下了！"));
    }

    /** Number of deaths suffered by a side so far in this battle. */
    public int sideDeaths(CombatSide side) {
        return deaths.getOrDefault(side, 0);
    }

    public void addDrawEnergy(int amount) {
        addDrawEnergy(CombatSide.PLAYER, amount);
    }

    // ----- PVP helpers -----

    /** True when this battle pits two human players against each other. */
    public boolean isPvp() {
        return guestUsername != null && !guestUsername.isBlank();
    }

    public int sideDrawEnergy(CombatSide side) {
        return side == CombatSide.PLAYER ? playerDrawEnergy : enemyDrawEnergy;
    }

    public List<GenericSkillTemplate> sideHand(CombatSide side) {
        return side == CombatSide.PLAYER ? playerHand : enemyHand;
    }

    public List<GenericSkillTemplate> sideDeck(CombatSide side) {
        return side == CombatSide.PLAYER ? playerDeck : enemyDeck;
    }

    public void addDrawEnergy(CombatSide side, int amount) {
        if (side == CombatSide.PLAYER) {
            playerDrawEnergy = Math.min(10, playerDrawEnergy + amount);
        } else {
            enemyDrawEnergy = Math.min(10, enemyDrawEnergy + amount);
        }
    }

    /** Side that opposes the given one. */
    public static CombatSide opposite(CombatSide side) {
        return side == CombatSide.PLAYER ? CombatSide.ENEMY : CombatSide.PLAYER;
    }

    /** Human username controlling the given side (owner for PLAYER, guest for ENEMY). */
    public String sideUsername(CombatSide side) {
        return side == CombatSide.PLAYER ? ownerUsername : guestUsername;
    }

    public boolean submitted(CombatSide side) {
        return submittedThisRound.getOrDefault(side, false);
    }

    public boolean bothSubmitted() {
        return submitted(CombatSide.PLAYER) && submitted(CombatSide.ENEMY);
    }

    public boolean extraFinished(CombatSide side) {
        return extraDone.getOrDefault(side, false);
    }

    public boolean bothExtraFinished() {
        return extraFinished(CombatSide.PLAYER) && extraFinished(CombatSide.ENEMY);
    }

    public boolean specialPerkPicked(CombatSide side) {
        return specialPerkSubmitted.getOrDefault(side, false);
    }

    public boolean bothSpecialPerksPicked() {
        return specialPerkPicked(CombatSide.PLAYER) && specialPerkPicked(CombatSide.ENEMY);
    }

    public boolean initialPerkPicked(CombatSide side) {
        return initialPerkSelected.getOrDefault(side, false);
    }

    public boolean bothInitialPerksPicked() {
        return initialPerkPicked(CombatSide.PLAYER) && initialPerkPicked(CombatSide.ENEMY);
    }

    /** Clears per-round PVP gates (called at every round start). */
    public void resetRoundGates() {
        submittedThisRound.clear();
        extraDone.clear();
        specialPerkSubmitted.clear();
        extraRoundSide = null;
        pendingBySide.clear();
    }

    public int idleRounds(CombatSide side) {
        return idleRounds.getOrDefault(side, 0);
    }

    /** A submitted round resets the idle streak. */
    public void markActive(CombatSide side) {
        idleRounds.put(side, 0);
    }

    /** A timed-out round extends the idle streak. */
    public void markIdle(CombatSide side) {
        idleRounds.put(side, idleRounds(side) + 1);
    }

    /** All decisions for a side, merged in side order, with enemy side last. */
    public List<ActionDecision> mergedPendingDecisions() {
        List<ActionDecision> all = new ArrayList<>();
        List<ActionDecision> player = pendingBySide.get(CombatSide.PLAYER);
        List<ActionDecision> enemy = pendingBySide.get(CombatSide.ENEMY);
        if (player != null) {
            all.addAll(player);
        }
        if (enemy != null) {
            all.addAll(enemy);
        }
        return all;
    }

    // ----- PVE helpers (multi-player battles share the PLAYER side) -----

    /** The PLAYER side's controlling usernames in join order (host first). */
    public List<String> playerUsers() {
        return List.copyOf(playerUsers);
    }

    /** Alive combatants controlled by the given PVE player. */
    public List<Combatant> aliveOf(String username) {
        return alive(CombatSide.PLAYER).stream()
                .filter(c -> username.equals(c.getOwnerUsername()))
                .toList();
    }

    /** PVE: the player's own generic skill hand. */
    public List<GenericSkillTemplate> handOf(String username) {
        return handsByUser.computeIfAbsent(username, k -> new ArrayList<>());
    }

    /** PVE: the player's own generic skill deck. */
    public List<GenericSkillTemplate> deckOf(String username) {
        return decksByUser.computeIfAbsent(username, k -> new ArrayList<>());
    }

    public int drawEnergyOf(String username) {
        return drawEnergyByUser.getOrDefault(username, 0);
    }

    public void addDrawEnergy(String username, int amount) {
        drawEnergyByUser.put(username, Math.min(10, drawEnergyByUser.getOrDefault(username, 0) + amount));
    }

    public boolean submittedBy(String username) {
        return submittedByUser.getOrDefault(username, false);
    }

    /** PVE: every player already submitted the current window. */
    public boolean allSubmitted() {
        return !playerUsers.isEmpty() && playerUsers.stream().allMatch(this::submittedBy);
    }

    public boolean extraDoneBy(String username) {
        return extraDoneByUser.getOrDefault(username, false);
    }

    /** PVE: every player finished their extra-action window. */
    public boolean allExtraDone() {
        return !playerUsers.isEmpty() && playerUsers.stream().allMatch(this::extraDoneBy);
    }

    public boolean specialPerkPickedBy(String username) {
        return specialPerkSubmittedByUser.getOrDefault(username, false);
    }

    public boolean allSpecialPerksPicked() {
        return !playerUsers.isEmpty() && playerUsers.stream().allMatch(this::specialPerkPickedBy);
    }

    public boolean initialPerkPickedBy(String username) {
        return initialPerkSelectedByUser.getOrDefault(username, false);
    }

    public boolean allInitialPerksPicked() {
        return !playerUsers.isEmpty() && playerUsers.stream().allMatch(this::initialPerkPickedBy);
    }

    /** PVE: who already acted in the current window (viewer-facing list). */
    public List<String> submittedUsers() {
        if (phase == CombatPhase.DECISION) {
            if (isExtraActionRound()) {
                return playerUsers.stream().filter(this::extraDoneBy).toList();
            }
            return playerUsers.stream().filter(this::submittedBy).toList();
        }
        if (phase == CombatPhase.SPECIAL_PERK) {
            return playerUsers.stream().filter(this::specialPerkPickedBy).toList();
        }
        if (phase == CombatPhase.INITIAL_PERK) {
            return playerUsers.stream().filter(this::initialPerkPickedBy).toList();
        }
        return List.of();
    }

    public int idleRoundsOf(String username) {
        return idleRoundsByUser.getOrDefault(username, 0);
    }

    /** A submitted round resets the idle streak. */
    public void markActive(String username) {
        idleRoundsByUser.put(username, 0);
    }

    /** A timed-out round extends the idle streak. */
    public void markIdle(String username) {
        idleRoundsByUser.put(username, idleRoundsOf(username) + 1);
    }

    /** Clears the per-round PVE gates (called at every round start). */
    public void resetPveRoundGates() {
        submittedByUser.clear();
        extraDoneByUser.clear();
        specialPerkSubmittedByUser.clear();
        pendingByUser.clear();
    }

    /** PVE: all players' decisions merged in player order. */
    public List<ActionDecision> mergedPendingPveDecisions() {
        List<ActionDecision> all = new ArrayList<>();
        for (String username : playerUsers) {
            List<ActionDecision> d = pendingByUser.get(username);
            if (d != null) {
                all.addAll(d);
            }
        }
        return all;
    }
}
