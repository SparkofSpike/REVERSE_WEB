package com.test.engine.combat;

import com.test.engine.enums.ActionType;
import com.test.engine.enums.DamageType;
import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.CharacterTemplate;
import com.test.engine.model.EffectSpec;
import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.model.PerformanceSpec;
import com.test.engine.model.Perk;
import com.test.engine.model.PuppetTemplate;
import com.test.engine.model.SkillTemplate;
import com.test.engine.service.PvpEventService;
import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Battle state machine. Owns in-memory battles; each battle advances through
 * phases driven by player interactions and the dummy AI.
 */
@Service
public class CombatEngine {

    private static final int DRAW_ENERGY_CAP = 10;
    /** Design doc default performance bonus: restore this much energy. */
    private static final int PERFORMANCE_DEFAULT_ENERGY = 20;
    private static final int GENERIC_DRAW_INTERVAL = 3;
    private static final int SPECIAL_PERK_INTERVAL = 4;
    private static final int SPECIAL_PERK_MAX_ROUNDS = 3;
    private static final int INITIAL_HAND_SIZE = 2;
    /** Finished battles are reaped after this long (in-memory map hygiene). */
    private static final long BATTLE_TTL_MS = 60 * 60 * 1000L;
    /** PVP decision window per round (auto-submits when it expires). */
    private static final long PVP_DECISION_WINDOW_MS = 30_000L;
    /** PVP: consecutive rounds without a submission end the battle as a loss. */
    private static final int IDLE_SURRENDER_ROUNDS = 3;
    /** How often the deadline sweeper checks PVP battles. */
    private static final long DEADLINE_TICK_MS = 1_000L;

    private final DiceRoller dice;
    private final CardPackLoader cardPackLoader;
    private final SpeedAdjudicator speedAdjudicator;
    private final DamageResolver damageResolver;
    private final EffectExecutor effectExecutor;
    private final PuppetAi puppetAi;
    /** SSE refresh channel; null in plain unit tests. */
    private final PvpEventService pvpEventService;

    private final Map<String, CombatState> battles = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlineSweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pvp-deadline-sweeper");
                t.setDaemon(true);
                return t;
            });

    public CombatEngine(DiceRoller dice, CardPackLoader cardPackLoader,
                        SpeedAdjudicator speedAdjudicator, DamageResolver damageResolver,
                        EffectExecutor effectExecutor, PuppetAi puppetAi,
                        PvpEventService pvpEventService) {
        this.dice = dice;
        this.cardPackLoader = cardPackLoader;
        this.speedAdjudicator = speedAdjudicator;
        this.damageResolver = damageResolver;
        this.effectExecutor = effectExecutor;
        this.puppetAi = puppetAi;
        this.pvpEventService = pvpEventService;
    }

    @PostConstruct
    void startDeadlineSweeper() {
        deadlineSweeper.scheduleAtFixedRate(this::tickDeadlines, DEADLINE_TICK_MS, DEADLINE_TICK_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopDeadlineSweeper() {
        deadlineSweeper.shutdownNow();
    }

    /** Pushes a refresh ping to PVP subscribers of the battle (no-op in tests). */
    private void notifyPvp(String battleId) {
        if (pvpEventService != null) {
            pvpEventService.publish(battleId);
        }
    }

    // ===================== battle lifecycle =====================

    public CombatState createDummyBattle(String packId, List<String> characterIds, String ownerUsername) {
        CardPack pack = cardPackLoader.get(packId);
        if (characterIds == null || characterIds.isEmpty() || characterIds.size() > 4) {
            throw new IllegalArgumentException("a player must deploy 1-4 characters");
        }
        CombatState state = new CombatState();
        // 16 hex chars (64 bits): 8 chars (32 bits) collided after roughly
        // 77k battles and silently overwrote an existing battle
        state.setId(UUID.randomUUID().toString().substring(0, 16));
        state.setOwnerUsername(ownerUsername);
        state.setPackId(packId);
        state.setPhase(CombatPhase.INITIAL_PERK);

        int idx = 1;
        for (String characterId : characterIds) {
            CharacterTemplate t = pack.getCharacters().stream()
                    .filter(c -> c.getId().equals(characterId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown character: " + characterId));
            Combatant c = Combatant.fromTemplate(t, characterId + "-p" + idx, CombatSide.PLAYER);
            state.getCombatants().add(c);
            idx++;
        }

        PuppetTemplate puppetTemplate = cardPackLoader.getPuppet("training-dummy");
        Combatant dummy = createPuppet(puppetTemplate);
        state.getCombatants().add(dummy);

        // generic skill deck: shuffle pack cards, deal the initial hand
        List<GenericSkillTemplate> deck = new ArrayList<>(pack.getGenericSkills());
        Collections.shuffle(deck, dice.random());
        state.setPlayerDeck(deck);
        effectExecutor.drawCards(state.alive(CombatSide.PLAYER).get(0), state, INITIAL_HAND_SIZE);

        state.setInitialPerkOptions(new ArrayList<>(pack.getInitialPerks()));
        state.log(CombatEvent.of(0, "setup", "战斗开始！玩家部署 "
                + state.alive(CombatSide.PLAYER).size() + " 名角色对阵训练木桩。"));
        // reap expired finished battles whenever a new one is created
        long now = System.currentTimeMillis();
        battles.entrySet().removeIf(e -> {
            CombatState s = e.getValue();
            return s.isOver() && s.getCreatedAt() != null
                    && now - s.getCreatedAt().toEpochMilli() > BATTLE_TTL_MS;
        });
        battles.put(state.getId(), state);
        return state;
    }

    /**
     * PVP battle: the host controls the PLAYER side, the guest the ENEMY
     * side. Both sides get their own generic skill deck and initial hand,
     * and both pick their own initial perk before round 1 starts.
     */
    public CombatState createPvpBattle(String packId, List<String> hostCharacterIds,
                                       List<String> guestCharacterIds, String hostUsername, String guestUsername) {
        if (hostCharacterIds == null || hostCharacterIds.isEmpty() || hostCharacterIds.size() > 4
                || guestCharacterIds == null || guestCharacterIds.isEmpty() || guestCharacterIds.size() > 4) {
            throw new IllegalArgumentException("each side must deploy 1-4 characters");
        }
        CardPack pack = cardPackLoader.get(packId);
        CombatState state = new CombatState();
        state.setId(UUID.randomUUID().toString().substring(0, 16));
        state.setOwnerUsername(hostUsername);
        state.setGuestUsername(guestUsername);
        state.setPackId(packId);
        state.setPhase(CombatPhase.INITIAL_PERK);
        state.setDecisionDeadlineAt(System.currentTimeMillis() + PVP_DECISION_WINDOW_MS);

        int idx = 1;
        for (String characterId : hostCharacterIds) {
            CharacterTemplate t = findCharacter(pack, characterId);
            state.getCombatants().add(Combatant.fromTemplate(t, characterId + "-p" + idx, CombatSide.PLAYER));
            idx++;
        }
        idx = 1;
        for (String characterId : guestCharacterIds) {
            CharacterTemplate t = findCharacter(pack, characterId);
            state.getCombatants().add(Combatant.fromTemplate(t, characterId + "-e" + idx, CombatSide.ENEMY));
            idx++;
        }

        // both sides draw their own generic skill hand
        for (CombatSide side : List.of(CombatSide.PLAYER, CombatSide.ENEMY)) {
            List<GenericSkillTemplate> deck = new ArrayList<>(pack.getGenericSkills());
            Collections.shuffle(deck, dice.random());
            state.sideDeck(side).addAll(deck);
            List<Combatant> units = state.alive(side);
            if (!units.isEmpty()) {
                effectExecutor.drawCards(units.get(0), state, INITIAL_HAND_SIZE);
            }
        }

        state.setInitialPerkOptions(new ArrayList<>(pack.getInitialPerks()));
        state.log(CombatEvent.of(0, "setup", "PVP 战斗开始！" + hostUsername + " 部署 "
                + hostCharacterIds.size() + " 名角色对阵 " + guestUsername + " 的 "
                + guestCharacterIds.size() + " 名角色。"));
        reapFinishedBattles();
        battles.put(state.getId(), state);
        return state;
    }

    private CharacterTemplate findCharacter(CardPack pack, String characterId) {
        return pack.getCharacters().stream()
                .filter(c -> c.getId().equals(characterId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown character: " + characterId));
    }

    private void reapFinishedBattles() {
        long now = System.currentTimeMillis();
        battles.entrySet().removeIf(e -> {
            CombatState s = e.getValue();
            return s.isOver() && s.getCreatedAt() != null
                    && now - s.getCreatedAt().toEpochMilli() > BATTLE_TTL_MS;
        });
    }

    private Combatant createPuppet(PuppetTemplate t) {
        Combatant d = new Combatant();
        d.setId("dummy");
        d.setTemplateId(t.getId());
        d.setName(t.getName());
        d.setSide(CombatSide.ENEMY);
        d.setMaxHp(t.getMaxHp());
        d.setHp(t.getMaxHp());
        d.setMaxEnergy(t.getMaxEnergy());
        d.setEnergy(t.getMaxEnergy());
        d.setSpeedDice(t.getSpeedDice());
        d.setPhysicalResistance(t.getPhysicalResistance());
        d.setMagicResistance(t.getMagicResistance());
        d.setBaseDamageDice(t.getBaseDamageDice());
        d.setBaseDamageType(t.getBaseDamageType());
        d.setBlockDice(t.getBlockDice());
        d.setDodgePenalty(t.getDodgePenalty());
        d.setBaseActions(new ArrayList<>(t.getBaseActions()));
        return d;
    }

    public CombatState getBattle(String battleId) {
        CombatState state = battles.get(battleId);
        if (state == null) {
            throw new IllegalArgumentException("battle not found: " + battleId);
        }
        // reap finished battles whose TTL expired (in-memory map hygiene)
        if (state.isOver() && state.getCreatedAt() != null
                && System.currentTimeMillis() - state.getCreatedAt().toEpochMilli() > BATTLE_TTL_MS) {
            battles.remove(battleId);
            throw new IllegalArgumentException("battle not found: " + battleId);
        }
        return state;
    }

    // ===================== PVP deadline sweeper =====================

    /**
     * PVP deadline sweeper: auto-submits expired decision windows so a battle
     * never stalls waiting for a disconnected human. Runs every second on the
     * daemon scheduler; solo battles are unaffected (no deadlines armed).
     */
    public synchronized void tickDeadlines() {
        long now = System.currentTimeMillis();
        for (CombatState state : new ArrayList<>(battles.values())) {
            if (!state.isPvp() || state.isOver()) {
                continue;
            }
            Long deadline = state.getDecisionDeadlineAt();
            if (deadline == null || now < deadline) {
                continue;
            }
            boolean progressed = false;
            if (state.getPhase() == CombatPhase.DECISION) {
                if (state.isExtraActionRound()) {
                    CombatSide active = state.getExtraRoundSide();
                    if (active != null && !state.extraFinished(active)) {
                        finishExtraRound(state, active);
                        progressed = true;
                    }
                } else if (!state.bothSubmitted()) {
                    for (CombatSide side : List.of(CombatSide.PLAYER, CombatSide.ENEMY)) {
                        if (!state.submitted(side)) {
                            autoSubmitDecisions(state, side);
                        }
                    }
                    if (state.bothSubmitted()) {
                        state.log(CombatEvent.of(state.getRound(), "decision", "决策超时，进入速度裁定。"));
                        resolveRound(state);
                    }
                    progressed = true;
                }
            } else if (state.getPhase() == CombatPhase.SPECIAL_PERK && !state.bothSpecialPerksPicked()) {
                for (CombatSide side : List.of(CombatSide.PLAYER, CombatSide.ENEMY)) {
                    if (!state.specialPerkPicked(side)) {
                        autoPickPerk(state, side);
                    }
                }
                if (state.bothSpecialPerksPicked()) {
                    state.setSpecialPerkOptions(List.of());
                    state.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken() + 1);
                    endRound(state);
                }
                progressed = true;
            } else if (state.getPhase() == CombatPhase.INITIAL_PERK && !state.bothInitialPerksPicked()) {
                for (CombatSide side : List.of(CombatSide.PLAYER, CombatSide.ENEMY)) {
                    if (!state.initialPerkPicked(side)) {
                        autoPickInitialPerk(state, side);
                    }
                }
                if (state.bothInitialPerksPicked()) {
                    state.setInitialPerkOptions(List.of());
                    startRound(state);
                }
                progressed = true;
            }
            if (progressed) {
                notifyPvp(state.getId());
            }
        }
    }

    /**
     * Hearthstone-style timeout: the side's turn simply ends. Decisions the
     * player already submitted (partial submissions are allowed) stand as-is;
     * units without a decision skip their action. No AI fills in for a human.
     */
    private void autoSubmitDecisions(CombatState state, CombatSide side) {
        state.getSubmittedThisRound().put(side, true);
        state.log(CombatEvent.of(state.getRound(), "decision",
                sideLabel(state, side) + " 决策超时，自动结束回合。"));
    }

    /** Picks the first special perk option for a timed-out side. */
    private void autoPickPerk(CombatState state, CombatSide side) {
        List<Perk> options = state.getSpecialPerkOptions();
        if (options.isEmpty()) {
            state.getSpecialPerkSubmitted().put(side, true);
            state.log(CombatEvent.of(state.getRound(), "perk",
                    sideLabel(state, side) + " 超时跳过特殊词条。"));
            return;
        }
        Perk perk = options.get(0);
        state.log(CombatEvent.of(state.getRound(), "perk", sideLabel(state, side)
                + " 超时自动选择词条: " + perk.getName() + " — " + perk.getDescription()));
        for (Combatant c : state.alive(side)) {
            effectExecutor.execute(perk.getEffect(), c, state, (String) null);
        }
        state.getSpecialPerkSubmitted().put(side, true);
    }

    /** Picks the first initial perk option for a timed-out side. */
    private void autoPickInitialPerk(CombatState state, CombatSide side) {
        Perk perk = state.getInitialPerkOptions().get(0);
        state.log(CombatEvent.of(0, "perk", sideLabel(state, side)
                + " 超时自动选择初始词条: " + perk.getName() + " — " + perk.getDescription()));
        applyPerkEffect(perk, state, side);
        state.getInitialPerkSelected().put(side, true);
    }

    // ===================== initial perk =====================

    /** Solo alias: the single player always picks for the PLAYER side. */
    public synchronized CombatState selectInitialPerk(String battleId, String perkId) {
        return selectInitialPerk(battleId, perkId, CombatSide.PLAYER);
    }

    /**
     * Initial perk choice. Solo battles start round 1 immediately; PVP waits
     * until BOTH sides picked their perk (each side applies its own choice to
     * its own team).
     */
    public synchronized CombatState selectInitialPerk(String battleId, String perkId, CombatSide side) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.INITIAL_PERK) {
            throw new IllegalStateException("not in initial perk phase");
        }
        if (state.isPvp() && state.initialPerkPicked(side)) {
            throw new IllegalStateException("initial perk already picked for side " + side);
        }
        Perk perk = state.getInitialPerkOptions().stream()
                .filter(p -> p.getId().equals(perkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown initial perk: " + perkId));
        state.log(CombatEvent.of(0, "perk", sideLabel(state, side) + " 选择初始词条: "
                + perk.getName() + " — " + perk.getDescription()));
        applyPerkEffect(perk, state, side);
        if (!state.isPvp()) {
            state.setInitialPerkOptions(List.of());
            startRound(state);
            return state;
        }
        state.getInitialPerkSelected().put(side, true);
        if (state.bothInitialPerksPicked()) {
            state.setInitialPerkOptions(List.of());
            startRound(state);
        }
        notifyPvp(battleId);
        return state;
    }

    private void applyPerkEffect(Perk perk, CombatState state, CombatSide side) {
        if (perk.getEffect() != null) {
            for (Combatant c : state.alive(side)) {
                effectExecutor.execute(perk.getEffect(), c, state, (String) null);
            }
        }
    }

    /** Human-readable side label for logs (username in PVP, 玩家/木桩 in solo). */
    private String sideLabel(CombatState state, CombatSide side) {
        if (state.isPvp()) {
            String username = state.sideUsername(side);
            return username != null ? username : side.name();
        }
        return side == CombatSide.PLAYER ? "玩家" : "木桩";
    }

    // ===================== decision & round execution =====================

    /** Solo alias: submits the player's decisions (the dummy AI decides for the enemy). */
    public synchronized CombatState decide(String battleId, List<ActionDecision> playerDecisions) {
        return decideSide(battleId, CombatSide.PLAYER, playerDecisions);
    }

    /**
     * Main round decision for one side. Solo battles resolve immediately
     * (the dummy AI supplies the enemy decisions); PVP battles buffer the
     * side's decisions until BOTH sides submitted, then resolve the round
     * (fog of war: nothing is revealed before both submissions land).
     */
    public synchronized CombatState decideSide(String battleId, CombatSide side, List<ActionDecision> decisions) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION || state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in decision phase");
        }
        if (state.isPvp() && state.submitted(side)) {
            throw new IllegalStateException("side " + side + " already submitted this round");
        }
        List<Combatant> units = state.alive(side);
        // hearthstone-style: a side may submit FEWER decisions than alive
        // units - unconfigured units simply skip their action (partial
        // submissions also come from the client's end-of-turn timeout)
        if (decisions == null || decisions.size() > units.size()) {
            throw new IllegalArgumentException("decisions must cover at most all alive " + side + " characters");
        }
        // every decision must belong to a DISTINCT alive character of this
        // side: wrong/duplicate ids or enemy ids would otherwise be silently
        // dropped (that character skips its action) or overridden by the AI
        Set<String> decided = new HashSet<>();
        for (ActionDecision d : decisions) {
            Combatant c = state.find(d.getCombatantId());
            if (c == null || c.getSide() != side || c.isDead() || !decided.add(d.getCombatantId())) {
                throw new IllegalArgumentException("invalid decision combatant: " + d.getCombatantId());
            }
        }
        if (!state.isPvp()) {
            state.setPendingDecisions(new ArrayList<>(decisions));
            state.getPendingDecisions().addAll(puppetAi.decide(state));
            state.log(CombatEvent.of(state.getRound(), "decision", "双方完成决策，进入速度裁定。"));
            resolveRound(state);
            return state;
        }
        state.getPendingBySide().put(side, new ArrayList<>(decisions));
        state.getSubmittedThisRound().put(side, true);
        // any submission counts as an active round (resets the idle streak)
        state.markActive(side);
        state.log(CombatEvent.of(state.getRound(), "decision",
                sideLabel(state, side) + " 提交了指令，等待对方。"));
        if (state.bothSubmitted()) {
            state.log(CombatEvent.of(state.getRound(), "decision", "双方完成决策，进入速度裁定。"));
            resolveRound(state);
        }
        notifyPvp(battleId);
        return state;
    }

    /**
     * Extra-action round: the player submits decisions for characters that
     * still hold extra base actions (连续奔袭). Each submitted decision
     * consumes one extra action; when none are left the round finalizes.
     */
    /** Solo alias: the player spends its extra actions. */
    public synchronized CombatState decideExtraActions(String battleId, List<ActionDecision> decisions) {
        return decideExtraActions(battleId, decisions, CombatSide.PLAYER);
    }

    public synchronized CombatState decideExtraActions(String battleId, List<ActionDecision> decisions,
                                                       CombatSide side) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION || !state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in an extra-action round");
        }
        if (state.isPvp() && state.getExtraRoundSide() != side) {
            throw new IllegalStateException("extra actions belong to side " + state.getExtraRoundSide());
        }
        if (decisions == null || decisions.isEmpty()) {
            throw new IllegalArgumentException("at least one extra action decision required");
        }
        // count decisions per combatant within this batch: every submitted
        // decision claims one charge up front, so a single batch can never
        // spend more extra actions than the character actually holds
        Map<String, Integer> batchSpend = new HashMap<>();
        for (ActionDecision d : decisions) {
            Combatant c = state.find(d.getCombatantId());
            int claimed = batchSpend.getOrDefault(d.getCombatantId(), 0);
            if (c == null || c.getSide() != side || c.isDead() || c.getExtraActionsThisTurn() - claimed <= 0) {
                throw new IllegalArgumentException("no extra actions left for " + d.getCombatantId());
            }
            // extra actions are base actions only; 超限技能 grants extra
            // skill usage on top (one charge per extra skill)
            if (d.isSkill() && c.getExtraSkillsThisTurn() <= 0) {
                throw new IllegalArgumentException(
                        "extra actions are base actions only (超限技能 enables skills)");
            }
            batchSpend.put(d.getCombatantId(), claimed + 1);
        }
        state.setPendingDecisions(new ArrayList<>(decisions));
        // no speed re-roll for extra actions: resolve per decision in
        // submission order; a charge is consumed only when the action
        // actually executes (a skill on cooldown / unaffordable must not
        // burn an extra action)
        for (ActionDecision d : decisions) {
            Combatant c = state.find(d.getCombatantId());
            if (c == null) {
                continue;
            }
            // a stunned combatant cannot spend its extra action
            if (!preActionGate(state, c)) {
                continue;
            }
            boolean executed;
            if (d.isSkill()) {
                // the validation above guaranteed a spare extra_skill charge
                c.setExtraSkillsThisTurn(c.getExtraSkillsThisTurn() - 1);
                executed = executeSkill(state, c, d);
            } else {
                executed = executeBaseAction(state, c, d, null);
            }
            if (executed && c.getExtraActionsThisTurn() > 0) {
                c.setExtraActionsThisTurn(c.getExtraActionsThisTurn() - 1);
            }
        }
        if (checkVictory(state)) {
            state.setExtraActionRound(false);
            notifyPvp(battleId);
            return state;
        }
        if (hasExtraCharges(state, side)) {
            state.setPhase(CombatPhase.DECISION);
            state.log(CombatEvent.of(state.getRound(), "extra",
                    "仍有额外行动可继续（或跳过）。"));
            notifyPvp(battleId);
            return state;
        }
        if (!state.isPvp()) {
            state.setExtraActionRound(false);
            executeDeferredEnemyActions(state);
            if (checkVictory(state)) {
                return state;
            }
            endRound(state);
        } else {
            finishExtraRound(state, side);
        }
        notifyPvp(battleId);
        return state;
    }

    /** Solo alias: ends the extra-action window early and finalizes the round. */
    public synchronized CombatState skipExtraActions(String battleId) {
        return skipExtraActions(battleId, CombatSide.PLAYER);
    }

    /** Ends the extra-action window early for the given side. */
    public synchronized CombatState skipExtraActions(String battleId, CombatSide side) {
        CombatState state = getBattle(battleId);
        if (!state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in an extra-action round");
        }
        if (state.isPvp() && state.getExtraRoundSide() != side) {
            throw new IllegalStateException("extra actions belong to side " + state.getExtraRoundSide());
        }
        if (!state.isPvp()) {
            state.setExtraActionRound(false);
            executeDeferredEnemyActions(state);
            if (checkVictory(state)) {
                return state;
            }
            endRound(state);
            return state;
        }
        finishExtraRound(state, side);
        notifyPvp(battleId);
        return state;
    }

    private void resolveRound(CombatState state) {
        if (state.isPvp()) {
            // merge the buffered per-side decisions; pendingDecisions stay the
            // single source of truth for clash/extra-action resolution
            state.setPendingDecisions(state.mergedPendingDecisions());
        }
        state.setPhase(CombatPhase.SPEED);
        List<Combatant> alive = state.allAlive();
        List<Combatant> speedOrder = speedAdjudicator.resolve(alive, state);

        state.log(CombatEvent.of(state.getRound(), "speed", "速度裁定完成："
                + speedOrder.stream().map(c -> c.getName() + "(" + state.getRoundSpeed().get(c.getId()) + ")")
                .reduce((a, b) -> a + " > " + b).orElse(""))
                // structured rolls: combatant id -> resolved speed (drives the
                // frontend dice pop animation)
                .with("speeds", new LinkedHashMap<>(state.getRoundSpeed())));

        state.setPhase(CombatPhase.EXECUTION);
        if (state.isPvp()) {
            resolveRoundPvp(state, speedOrder);
            return;
        }
        // Solo: a round whose player decisions include an extra-action skill
        // (连续奔袭 etc.) splits into player phase -> extra-action round ->
        // enemy phase, so the player's turn continues after the skill and
        // the enemy acts only once the extra actions are spent. Ordinary
        // rounds keep the regular speed-order resolution untouched.
        if (mayGrantExtraActions(state, state.getPendingDecisions())) {
            state.setPendingEnemyDecisions(collectEnemyDecisions(state));
            executeActions(state, speedOrder, CombatSide.PLAYER);
            if (state.isOver()) {
                return;
            }
            boolean extraPending = state.alive(CombatSide.PLAYER).stream()
                    .anyMatch(c -> c.getExtraActionsThisTurn() > 0);
            if (extraPending) {
                enterExtraRound(state, CombatSide.PLAYER);
                return;
            }
            executeDeferredEnemyActions(state);
        } else {
            executeActions(state, speedOrder, null);
        }

        if (state.isOver()) {
            return;
        }
        // special-perk handling lives inside endRound so EVERY round-ending
        // path - plain rounds and extra-action rounds alike - offers the perk
        endRound(state);
    }

    /**
     * PVP round flow: host main actions -> host extra-action window -> guest
     * main actions -> guest extra-action window -> round end. Only the side
     * whose main actions just ran may spend its extra charges, so the two
     * humans never write into the same window.
     */
    private void resolveRoundPvp(CombatState state, List<Combatant> speedOrder) {
        state.setPendingEnemyDecisions(collectEnemyDecisions(state));
        executeActions(state, speedOrder, CombatSide.PLAYER);
        if (state.isOver()) {
            return;
        }
        if (hasExtraCharges(state, CombatSide.PLAYER)) {
            enterExtraRound(state, CombatSide.PLAYER);
            return;
        }
        executeDeferredEnemyActions(state);
        if (state.isOver()) {
            return;
        }
        if (hasExtraCharges(state, CombatSide.ENEMY)) {
            enterExtraRound(state, CombatSide.ENEMY);
            return;
        }
        endRound(state);
    }

    private boolean hasExtraCharges(CombatState state, CombatSide side) {
        return state.alive(side).stream().anyMatch(c -> c.getExtraActionsThisTurn() > 0);
    }

    /** Opens the extra-action window for one side and arms its deadline. */
    private void enterExtraRound(CombatState state, CombatSide side) {
        state.setExtraActionRound(true);
        state.setExtraRoundSide(side);
        // the other side is considered finished for this window: it either
        // already spent its extra actions or never had any
        state.getExtraDone().put(CombatSide.PLAYER, side != CombatSide.PLAYER);
        state.getExtraDone().put(CombatSide.ENEMY, side != CombatSide.ENEMY);
        state.setPhase(CombatPhase.DECISION);
        state.setDecisionDeadlineAt(System.currentTimeMillis() + PVP_DECISION_WINDOW_MS);
        state.log(CombatEvent.of(state.getRound(), "extra",
                sideLabel(state, side) + " 获得额外行动的角色可以继续行动（或跳过）。"));
    }

    /**
     * Closes the current extra-action window. When the host finishes first,
     * the deferred guest main actions run, then the guest window opens if the
     * guest still holds charges; otherwise the round ends. The guest window
     * only opens via enterExtraRound (which flips extraRoundSide), so the
     * side state is the source of truth - never the pre-marked extraDone
     * entries (enterExtraRound marks the other side "finished" for display).
     */
    private void finishExtraRound(CombatState state, CombatSide side) {
        state.getExtraDone().put(side, true);
        if (state.isOver()) {
            return;
        }
        // host window is open and the host has not finished yet: wait
        if (!state.extraFinished(CombatSide.PLAYER)) {
            return;
        }
        // the guest window is open (guest is the active side): round ends
        if (state.getExtraRoundSide() == CombatSide.ENEMY) {
            state.setExtraActionRound(false);
            endRound(state);
            return;
        }
        // host done while the guest window never opened: run the deferred
        // guest main actions, then open the guest window if it holds charges
        executeDeferredEnemyActions(state);
        if (state.isOver()) {
            return;
        }
        if (hasExtraCharges(state, CombatSide.ENEMY)) {
            enterExtraRound(state, CombatSide.ENEMY);
            return;
        }
        state.setExtraActionRound(false);
        endRound(state);
    }

    /** True when any pending decision is a skill that grants extra actions. */
    private boolean mayGrantExtraActions(CombatState state, List<ActionDecision> decisions) {
        for (ActionDecision d : decisions) {
            if (!d.isSkill()) {
                continue;
            }
            Combatant c = state.find(d.getCombatantId());
            if (c == null) {
                continue;
            }
            SkillTemplate skill = c.findSkill(d.getSkillId());
            if (skill != null && skill.getEffects() != null && skill.getEffects().stream()
                    .anyMatch(e -> "extra_actions".equals(e.getType()))) {
                return true;
            }
        }
        return false;
    }

    private List<ActionDecision> collectEnemyDecisions(CombatState state) {
        // mutable list: clash resolution removes the consumed enemy decision
        return state.getPendingDecisions().stream()
                .filter(d -> {
                    Combatant c = state.find(d.getCombatantId());
                    return c != null && c.getSide() == CombatSide.ENEMY;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * Runs the enemy decisions deferred by an extra-action round. Enemy
     * actions resolve without clash (the player phase already settled any
     * mutual-attack clash and removed the consumed decision), and without a
     * speed re-roll.
     */
    private void executeDeferredEnemyActions(CombatState state) {
        List<ActionDecision> enemy = new ArrayList<>(state.getPendingEnemyDecisions());
        state.getPendingEnemyDecisions().clear();
        for (ActionDecision d : enemy) {
            if (state.isOver()) {
                return;
            }
            Combatant c = state.find(d.getCombatantId());
            if (c == null || c.isDead()) {
                continue;
            }
            if (d.isSkill()) {
                executeSkill(state, c, d);
                // permanent-extra-action units (冷漠实现) still auto-strike
                executeExtraActions(state, c, d);
                continue;
            }
            ActionType action;
            try {
                action = ActionType.valueOf(d.getActionType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            switch (action) {
                case ATTACK -> executeAttack(state, c, d, null, "ATTACK", false);
                case DEFEND -> executeDefend(state, c);
                case DODGE -> executeDodge(state, c);
                case GUARD -> executeGuard(state, c, d);
                case COUNTER -> executeCounter(state, c);
                case CHASE -> executeChase(state, c, d, null);
                case PRAY -> executePray(state, c);
            }
            executeExtraActions(state, c, d);
        }
    }

    // ===================== action execution =====================

    private void executeActions(CombatState state, List<Combatant> speedOrder, CombatSide onlySide) {
        Map<String, ActionDecision> decisions = new LinkedHashMap<>();
        for (ActionDecision d : state.getPendingDecisions()) {
            decisions.put(d.getCombatantId(), d);
        }
        // pendingDecisions stay intact until the round fully ends so clash
        // resolution can inspect the opponent's decision

        // pre-roll attack damage for clash resolution (mutual attacks cancel)
        Map<String, DiceResult> preRolled = new LinkedHashMap<>();
        for (Combatant c : speedOrder) {
            ActionDecision d = decisions.get(c.getId());
            if (d != null && !d.isSkill() && ActionType.ATTACK.name().equals(d.getActionType())) {
                preRolled.put(c.getId(), dice.roll(c.getBaseDamageDice()));
            }
        }

        for (Combatant c : speedOrder) {
            if (c.isDead() || state.isOver()) {
                continue;
            }
            if (onlySide != null && c.getSide() != onlySide) {
                continue;
            }
            ActionDecision d = decisions.get(c.getId());
            if (d == null) {
                continue;
            }
            // stun skips the action; bleed resolves before any action
            if (!preActionGate(state, c)) {
                continue;
            }
            if (d.isSkill()) {
                executeSkill(state, c, d);
            } else {
                executeBaseAction(state, c, d, preRolled.get(c.getId()));
            }
            executeExtraActions(state, c, d);
        }
    }

    /**
     * Consumes this round's extra base actions (连续奔袭 etc.): after the
     * main decision the combatant automatically strikes the chosen target
     * (falling back to the last attacked target, then the first alive foe)
     * once per extra action. Extra actions are base actions, so a skill
     * decision contributes nothing itself - it only grants the count.
     */
    private void executeExtraActions(CombatState state, Combatant c, ActionDecision main) {
        // 冷漠实现: a permanently held extra action auto-strikes the last
        // target each round; 连续奔袭 extra actions are player-chosen via
        // the extra-action round instead
        int runs = c.isPermanentExtraAction() ? 1 : 0;
        if (runs <= 0) {
            return;
        }
        String targetId = main.getTargetId();
        if (targetId == null) {
            targetId = c.getLastAttackedTarget();
        }
        if (targetId == null) {
            List<Combatant> foes = state.alive(c.isPlayerSide() ? CombatSide.ENEMY : CombatSide.PLAYER);
            if (!foes.isEmpty()) {
                targetId = foes.get(0).getId();
            }
        }
        if (targetId == null) {
            return;
        }
        for (int i = 0; i < runs; i++) {
            if (c.isDead() || state.isOver()) {
                return;
            }
            if (!preActionGate(state, c)) {
                return;
            }
            executeAttack(state, c, ActionDecision.base(c.getId(), "ATTACK", targetId), null, "ATTACK", true);
        }
    }

    private boolean executeBaseAction(CombatState state, Combatant actor, ActionDecision decision, DiceResult preRolled) {
        ActionType action;
        try {
            action = ActionType.valueOf(decision.getActionType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown action: " + decision.getActionType());
        }
        if (!actor.getBaseActions().contains(action)) {
            state.log(CombatEvent.of(state.getRound(), "action",
                    actor.getName() + " 尝试使用未装配的行动 " + action.label() + "。").with("actorId", actor.getId()).with("action", action.name()));
            return false;
        }
        switch (action) {
            case ATTACK -> executeAttack(state, actor, decision, preRolled, "ATTACK", true);
            case DEFEND -> executeDefend(state, actor);
            case DODGE -> executeDodge(state, actor);
            case GUARD -> executeGuard(state, actor, decision);
            case COUNTER -> executeCounter(state, actor);
            case CHASE -> executeChase(state, actor, decision, preRolled);
            case PRAY -> executePray(state, actor);
        }
        return true;
    }

    private boolean executeAttack(CombatState state, Combatant actor, ActionDecision decision,
                                    DiceResult preRolled, String actionName, boolean clashEnabled) {
        Combatant target = state.find(decision.getTargetId());
        if (target == null || target.isDead()) {
            state.log(CombatEvent.of(state.getRound(), "action", actor.getName() + " 的"
                    + ("CHASE".equals(actionName) ? "追击" : "攻击") + "落空（目标已不在）。")
                .with("actorId", actor.getId()).with("targetId", decision.getTargetId()).with("action", actionName));
            return false;
        }

        Combatant absorber = findGuardAbsorber(state, target);

        int raw = preRolled != null ? preRolled.total() : dice.roll(actor.getBaseDamageDice()).total();
        raw += actor.getBonusDamage();

        // clash: only a mutual attack counts - the target must also be
        // attacking the actor, otherwise every attack would falsely clash
        ActionDecision targetDecision = decisionOf(state, target.getId());
        if (clashEnabled && targetDecision != null && !targetDecision.isSkill()
                && ActionType.ATTACK.name().equals(targetDecision.getActionType())
                && targetDecision.getTargetId() != null
                && targetDecision.getTargetId().equals(actor.getId())) {
            // the mutual attack is consumed by the clash: if the target's
            // action was deferred to the enemy phase it must not run again
            state.getPendingEnemyDecisions().removeIf(d -> d.getCombatantId().equals(target.getId()));
            int targetRaw = dice.roll(target.getBaseDamageDice()).total() + target.getBonusDamage();
            if (raw > targetRaw) {
                state.log(CombatEvent.of(state.getRound(), "clash",
                        actor.getName() + " 与 " + target.getName() + " 对击，抵消后 " + actor.getName() + " 造成 "
                                + (raw - targetRaw) + " 点余伤。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("amount", raw - targetRaw).with("action", "ATTACK"));
                deliverAttackDamage(state, actor, target, raw - targetRaw, absorber, "ATTACK");
            } else if (targetRaw > raw) {
                state.log(CombatEvent.of(state.getRound(), "clash",
                        actor.getName() + " 与 " + target.getName() + " 对击，抵消后 " + target.getName() + " 造成 "
                                + (targetRaw - raw) + " 点余伤。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("amount", targetRaw - raw).with("action", "ATTACK"));
                deliverAttackDamage(state, target, actor, targetRaw - raw, findGuardAbsorber(state, actor), "ATTACK");
            } else {
                state.log(CombatEvent.of(state.getRound(), "clash",
                        actor.getName() + " 与 " + target.getName() + " 对击，伤害完全抵消。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", "ATTACK"));
            }
            grantAttackEnergy(state, actor);
            return true;
        }

        // dodge check: a dodging target compares speed values
        if (target.isDodging()) {
            int attackSpeed = dice.roll(actor.getSpeedDice()).total() + actor.effectiveSpeed();
            if (attackSpeed <= target.getDodgeValue()) {
                state.log(CombatEvent.of(state.getRound(), "dodge",
                        target.getName() + " 成功闪避了 " + actor.getName() + " 的攻击！")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", actionName));
                grantAttackEnergy(state, actor);
                return false;
            }
            state.log(CombatEvent.of(state.getRound(), "dodge",
                    target.getName() + " 闪避失败，" + actor.getName() + " 的攻击命中。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", actionName));
        }

        // counter: a countering target strikes back immediately
        if (target.isCountering()) {
            int counterDamage = dice.roll(target.getBaseDamageDice()).total() + target.getBonusDamage();
            state.log(CombatEvent.of(state.getRound(), "counter",
                    target.getName() + " 反击 " + actor.getName() + "！")
                .with("actorId", target.getId()).with("targetId", actor.getId()).with("amount", counterDamage).with("action", "COUNTER"));
            deliverAttackDamage(state, target, actor, counterDamage, findGuardAbsorber(state, actor), "COUNTER");
            if (actor.isDead()) {
                return false;
            }
        }

        // attack cue: gives the frontend a proper action animation for a
        // plain hit (label, lunge, zoom); clash/dodge/counter log their own
        // events and return earlier
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 对 " + target.getName() + " 发动"
                        + ("CHASE".equals(actionName) ? "追击" : "攻击") + "。")
            .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", actionName));
        deliverAttackDamage(state, actor, target, raw, absorber, actionName);
        grantAttackEnergy(state, actor);
        return true;
    }

    /**
     * Finds who absorbs damage for the target: a guarding ally, or the
     * target itself when no guard is active.
     */
    private Combatant findGuardAbsorber(CombatState state, Combatant target) {
        if (target.isPlayerSide()) {
            for (Combatant c : state.alive(CombatSide.PLAYER)) {
                if (target.getId().equals(c.getGuardTargetId()) && !c.getId().equals(target.getId())) {
                    return c;
                }
            }
            // 盾山 guard bind: the binder absorbs ALL damage for the bound ally
            for (Combatant c : state.alive(CombatSide.PLAYER)) {
                if (target.getId().equals(c.getGuardBindTargetId()) && !c.getId().equals(target.getId())) {
                    return c;
                }
            }
        }
        return null;
    }

    private void deliverAttackDamage(CombatState state, Combatant attacker, Combatant target, int damage, Combatant absorber, String action) {
        Combatant receiver = absorber != null ? absorber : target;
        if (receiver.isDead()) {
            return;
        }
        // the guarded target's -0.2 resistance reduces incoming damage before
        // the guardian takes it
        if (absorber != null) {
            double baseResist = (attacker.getBaseDamageType() == DamageType.MAGIC)
                    ? target.effectiveMagicResistance()
                    : target.effectivePhysicalResistance();
            double guardedResist = Math.max(0.1, baseResist - 0.2);
            if (baseResist > 0.01) {
                damage = (int) Math.round(damage * guardedResist / baseResist);
            }
        }
        DamageResolver.DamageOutcome outcome =
                damageResolver.dealDamage(receiver, damage, attacker.getBaseDamageType(), state, attacker, action);

        if (absorber != null) {
            absorber.setGuardSuccessCount(absorber.getGuardSuccessCount() + 1);
            state.log(CombatEvent.of(state.getRound(), "guard",
                    absorber.getName() + " 守护成功（累计 " + absorber.getGuardSuccessCount() + " 次）。")
                .with("actorId", absorber.getId()).with("targetId", target.getId()).with("action", "GUARD"));
        }

        // lifesteal is based on actual hp lost
        for (StatusEffect s : attacker.getStatusEffects()) {
            if ("lifesteal".equals(s.getType()) && !s.expired()) {
                int heal = (int) Math.round(outcome.getHpLost() * s.getRatio());
                if (heal > 0) {
                    effectExecutor.heal(attacker, heal);
                    state.log(CombatEvent.of(state.getRound(), "heal",
                            attacker.getName() + " 通过吸血恢复 " + heal + " 点生命。")
                .with("targetId", attacker.getId()).with("amount", heal).with("action", "HEAL"));
                }
            }
        }

        attacker.setLastAttackedTarget(target.getId());

        if (receiver.getHp() <= 0) {
            state.resolvePotentialDeath(receiver);
        }
        // compassion heal (老牧师): on losing hp, heal a random ally
        if (receiver.isPlayerSide() && receiver.getTemplate() != null
                && receiver.getTemplate().getCorePassive() != null
                && "compassion_heal".equals(receiver.getTemplate().getCorePassive().getType())) {
            int lost = outcome.getHpLost();
            if (lost > 0) {
                List<Combatant> allies = state.alive(CombatSide.PLAYER).stream()
                        .filter(a -> !a.getId().equals(receiver.getId())).toList();
                if (!allies.isEmpty()) {
                    Combatant buddy = allies.get(dice.random().nextInt(allies.size()));
                    int healed = effectExecutor.heal(buddy,
                            (int) Math.round(lost * receiver.getTemplate().getCorePassive().getRatio()));
                    receiver.setTotalHealGiven(receiver.getTotalHealGiven() + healed);
                    state.log(CombatEvent.of(state.getRound(), "heal",
                            receiver.getName() + " 的和谐友爱恢复了 " + buddy.getName() + " " + healed + " 点生命。")
                .with("targetId", buddy.getId()).with("amount", healed).with("action", "HEAL"));
                }
            }
        }
        checkPerformance(state, receiver);
    }

    private void executeDefend(CombatState state, Combatant actor) {
        actor.setDefending(true);
        int blockValue = dice.roll(actor.getBlockDice()).total();
        actor.setBlockValue(blockValue);
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 进入防御状态（物理/魔法抗性 -0.2），格挡值 " + blockValue + "。")
                .with("actorId", actor.getId()).with("action", "DEFEND"));
        if (actor.getTemplate() != null && actor.getTemplate().getCorePassive() != null
                && "stone_shield".equals(actor.getTemplate().getCorePassive().getType())) {
            actor.setStoneShieldPending(true);
        }
        grantAttackEnergy(state, actor);
    }

    private void executeDodge(CombatState state, Combatant actor) {
        int penalty = 0;
        if (actor.getDodgePenalty() != null) {
            penalty = dice.roll(actor.getDodgePenalty()).total();
        }
        // 教导有方: a teammate with the dodge-training passive shaves 1 point
        // off the dodge penalty of every other combatant (min 0)
        if (penalty > 0 && hasDodgeTrainingTeammate(state, actor)) {
            penalty = Math.max(0, penalty - 1);
            state.log(CombatEvent.of(state.getRound(), "buff",
                    actor.getName() + " 的闪避惩罚因教导有方减少 1 点。"));
        }
        int value = dice.roll(actor.getSpeedDice()).total() + actor.effectiveSpeed() - penalty;
        actor.setDodgeValue(value);
        actor.setDodging(true);
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 进入闪避状态（闪避值 " + value + "）。")
                .with("actorId", actor.getId()).with("action", "DODGE"));
        grantAttackEnergy(state, actor);
    }

    private void executeGuard(CombatState state, Combatant actor, ActionDecision decision) {
        Combatant target = state.find(decision.getTargetId());
        if (target == null || target.isDead() || target.getId().equals(actor.getId())) {
            target = actor;
        }
        // one guard per turn by default; 蟹壳拓展 grants extra guard actions
        if (actor.getGuardTargetId() != null && actor.getExtraGuardsThisTurn() <= 0) {
            state.log(CombatEvent.of(state.getRound(), "action",
                    actor.getName() + " 本回合已守护过，且没有额外的守护次数。")
                    .with("actorId", actor.getId()).with("action", "GUARD"));
            return;
        }
        if (actor.getGuardTargetId() != null) {
            actor.setExtraGuardsThisTurn(actor.getExtraGuardsThisTurn() - 1);
        }
        actor.setGuardTargetId(target.getId());
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 守护 " + target.getName() + "（守护者抗性 -0.1，被守护者 -0.2）。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", "GUARD"));
        grantAttackEnergy(state, actor);
    }

    private void executeCounter(CombatState state, Combatant actor) {
        actor.setCountering(true);
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 进入反击状态（物理/魔法抗性 -0.1）。")
                .with("actorId", actor.getId()).with("action", "COUNTER"));
        grantAttackEnergy(state, actor);
    }

    private void executeChase(CombatState state, Combatant actor, ActionDecision decision, DiceResult preRolled) {
        Combatant target = state.find(decision.getTargetId());
        if (target == null || target.isDead()) {
            state.log(CombatEvent.of(state.getRound(), "action",
                    actor.getName() + " 的追击落空（目标已不在）。")
                .with("actorId", actor.getId()).with("targetId", decision.getTargetId()).with("action", "CHASE"));
            return;
        }
        // chase is a unilateral follow-up strike: it never clashes. The 0d4
        // bonus only applies when the chase truly lands on the SAME target
        // the actor attacked before - capture that target BEFORE executeAttack
        // overwrites it, and require an actual hit (a dodged or interrupted
        // chase must not trigger the bonus)
        boolean lastAttackWasOnTarget = target.getId().equals(actor.getLastAttackedTarget());
        boolean hit = executeAttack(state, actor, decision, preRolled, "CHASE", false);
        if (hit && lastAttackWasOnTarget && !actor.isDead()) {
            int bonus = dice.roll("0d4").total();
            state.log(CombatEvent.of(state.getRound(), "chase",
                    actor.getName() + " 追击追加 " + bonus + " 伤害并恢复 2 点生命。")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("amount", bonus).with("action", "CHASE"));
            if (bonus > 0) {
                damageResolver.dealDamage(target, bonus, DamageType.PHYSICAL, state, actor, "CHASE");
            }
            effectExecutor.heal(actor, 2);
            if (target.getHp() <= 0) {
                state.resolvePotentialDeath(target);
            }
        }
    }

    private void executePray(CombatState state, Combatant actor) {
        int amount = 10;
        actor.setEnergy(Math.min(actor.getMaxEnergy(), actor.getEnergy() + amount));
        state.log(CombatEvent.of(state.getRound(), "action",
                actor.getName() + " 祈思，恢复 " + amount + " 点精力。")
                .with("actorId", actor.getId()).with("amount", amount).with("action", "PRAY"));
        grantAttackEnergy(state, actor);
    }

    private void grantAttackEnergy(CombatState state, Combatant actor) {
        // each side tracks its own draw energy; solo battles only feed the
        // player side (the dummy has no deck to spend it on)
        if (state.isPvp() || actor.isPlayerSide()) {
            state.addDrawEnergy(actor.getSide(), 1);
        }
    }

    private ActionDecision decisionOf(CombatState state, String combatantId) {
        return state.getPendingDecisions().stream()
                .filter(d -> d.getCombatantId().equals(combatantId))
                .findFirst().orElse(null);
    }

    // ===================== skills =====================

    private boolean executeSkill(CombatState state, Combatant caster, ActionDecision decision) {
        SkillTemplate skill = caster.findSkill(decision.getSkillId());
        if (skill == null) {
            throw new IllegalArgumentException("unknown skill: " + decision.getSkillId());
        }
        if (caster.hasCooldown(skill.getId())) {
            state.log(CombatEvent.of(state.getRound(), "skill",
                    caster.getName() + " 的技能 " + skill.getName() + " 仍在冷却。")
                .with("actorId", caster.getId()).with("action", "SKILL"));
            return false;
        }
        int cost = Math.max(0, skill.getEnergyCost() - energyDiscount(caster));
        if (caster.getEnergy() < cost) {
            state.log(CombatEvent.of(state.getRound(), "skill",
                    caster.getName() + " 精力不足，无法使用 " + skill.getName() + "。")
                .with("actorId", caster.getId()));
            return false;
        }
        caster.setEnergy(caster.getEnergy() - cost);
        if (skill.getCooldown() > 0) {
            caster.setCooldown(skill.getId(), skill.getCooldown());
        }
        state.log(CombatEvent.of(state.getRound(), "skill",
                caster.getName() + " 使用技能 " + skill.getName() + "（消耗 " + cost + " 精力）。")
                .with("actorId", caster.getId()).with("targetId", decision.getTargetId())
                .with("action", "SKILL").with("skillId", skill.getId()));
        for (EffectSpec effect : skill.getEffects()) {
            effectExecutor.execute(effect, caster, state, decision.effectiveTargetIds());
        }
        if (caster.getHp() <= 0) {
            state.resolvePotentialDeath(caster);
        }
        checkPerformance(state, caster);
        return true;
    }

    private int energyDiscount(Combatant caster) {
        if (caster.getTemplate() != null && caster.getTemplate().getCorePassive() != null
                && "energy_discount".equals(caster.getTemplate().getCorePassive().getType())) {
            return caster.getTemplate().getCorePassive().getAmount();
        }
        return 0;
    }

    // ===================== generic skills =====================

    /** Solo alias: the single player plays cards from the player hand. */
    public synchronized CombatState playGenericSkill(String battleId, String skillId, String targetId) {
        return playGenericSkill(battleId, skillId, targetId, CombatSide.PLAYER);
    }

    /**
     * Plays a generic skill card from the side's own hand. PVP: only before
     * the side submitted its round decisions (a submitted round is locked).
     * Card plays are public events (like playing a card face-up), so they are
     * not hidden by the fog of war.
     */
    public synchronized CombatState playGenericSkill(String battleId, String skillId, String targetId,
                                                     CombatSide side) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION) {
            throw new IllegalStateException("generic skills can only be played during decision phase");
        }
        if (state.isPvp() && state.submitted(side)) {
            throw new IllegalStateException("side already submitted its round decisions");
        }
        GenericSkillTemplate card = state.sideHand(side).stream()
                .filter(c -> c.getId().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("card not in hand: " + skillId));
        Combatant caster = state.alive(side).get(0);
        state.log(CombatEvent.of(state.getRound(), "card",
                caster.getName() + " 打出通用技能 " + card.getName() + "。")
                .with("actorId", caster.getId()).with("action", "CARD"));
        for (EffectSpec effect : card.getEffects()) {
            effectExecutor.execute(effect, caster, state, targetId);
        }
        state.sideHand(side).remove(card);
        if (card.isConsumed()) {
            state.sideDeck(side).remove(card);
        }
        // a card can kill the last enemy (or the last ally via hp_cost):
        // settle victory immediately so the battle does not linger in
        // DECISION with a dead side still "in play"
        notifyPvp(battleId);
        if (checkVictory(state)) {
            return state;
        }
        return state;
    }

    // ===================== special perks =====================

    private void offerSpecialPerks(CombatState state) {
        // use the battle's own pack (multi-pack support)
        CardPack pack = cardPackLoader.get(state.getPackId() == null ? "test-1" : state.getPackId());
        int roundNumber = state.getRound() / SPECIAL_PERK_INTERVAL;
        boolean isLast = state.getSpecialPerkRoundsTaken() == SPECIAL_PERK_MAX_ROUNDS - 1;
        List<Perk> eligible = pack.getSpecialPerks().stream()
                .filter(p -> p.getRoundRequirement() == 0
                        || p.getRoundRequirement() == roundNumber
                        || (p.getRoundRequirement() == -1 && isLast))
                .toList();
        List<Perk> options = new ArrayList<>(eligible);
        Collections.shuffle(options, dice.random());
        if (options.size() > 3) {
            options = options.subList(0, 3);
        }
        state.setSpecialPerkOptions(options);
        state.setPhase(CombatPhase.SPECIAL_PERK);
        if (state.isPvp()) {
            state.setDecisionDeadlineAt(System.currentTimeMillis() + PVP_DECISION_WINDOW_MS);
        }
        state.log(CombatEvent.of(state.getRound(), "perk", "特殊词条轮！请选择一项词条。"));
    }

    /** Solo alias: the single player picks for the PLAYER side. */
    public synchronized CombatState selectSpecialPerk(String battleId, String perkId) {
        return selectSpecialPerk(battleId, perkId, CombatSide.PLAYER);
    }

    /**
     * Special perk choice. Solo battles continue immediately; PVP waits until
     * both sides picked (each side applies its own choice to its own team).
     */
    public synchronized CombatState selectSpecialPerk(String battleId, String perkId, CombatSide side) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.SPECIAL_PERK) {
            throw new IllegalStateException("not in special perk phase");
        }
        if (state.isPvp() && state.specialPerkPicked(side)) {
            throw new IllegalStateException("special perk already picked for side " + side);
        }
        Perk perk = state.getSpecialPerkOptions().stream()
                .filter(p -> p.getId().equals(perkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown special perk: " + perkId));
        state.log(CombatEvent.of(state.getRound(), "perk", sideLabel(state, side)
                + " 选择特殊词条: " + perk.getName() + " — " + perk.getDescription()));
        for (Combatant c : state.alive(side)) {
            effectExecutor.execute(perk.getEffect(), c, state, (String) null);
        }
        if (state.isOver()) {
            // a perk can kill the opponent outright: ping them so their UI
            // leaves the perk overlay instead of waiting forever
            notifyPvp(battleId);
            return state;
        }
        if (!state.isPvp()) {
            state.setSpecialPerkOptions(List.of());
            state.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken() + 1);
            endRound(state);
            return state;
        }
        state.getSpecialPerkSubmitted().put(side, true);
        if (state.bothSpecialPerksPicked()) {
            state.setSpecialPerkOptions(List.of());
            state.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken() + 1);
            endRound(state);
        }
        notifyPvp(battleId);
        return state;
    }

    /** Solo alias: skips the special perk offer for the player side. */
    public synchronized CombatState skipSpecialPerk(String battleId) {
        return skipSpecialPerk(battleId, CombatSide.PLAYER);
    }

    public synchronized CombatState skipSpecialPerk(String battleId, CombatSide side) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.SPECIAL_PERK) {
            throw new IllegalStateException("not in special perk phase");
        }
        if (state.isPvp() && state.specialPerkPicked(side)) {
            throw new IllegalStateException("special perk already settled for side " + side);
        }
        state.log(CombatEvent.of(state.getRound(), "perk", sideLabel(state, side) + " 跳过本轮特殊词条选择。"));
        if (!state.isPvp()) {
            state.setSpecialPerkOptions(List.of());
            endRound(state);
            return state;
        }
        state.getSpecialPerkSubmitted().put(side, true);
        if (state.bothSpecialPerksPicked()) {
            state.setSpecialPerkOptions(List.of());
            endRound(state);
        }
        notifyPvp(battleId);
        return state;
    }

    // ===================== round transitions =====================

    private void startRound(CombatState state) {
        state.setPhase(CombatPhase.ROUND_START);
        state.setRound(state.getRound() + 1);
        state.setSpecialPerkOffered(false);
        if (state.isPvp()) {
            // idle-surrender check runs BEFORE resetRoundGates clears the
            // submission gates (round 1 has no previous round to judge).
            // "Active" means the side actually SUBMITTED decisions: a timeout
            // only marks submitted=true without touching pendingBySide, so
            // timed-out rounds count as idle.
            if (state.getRound() > 1) {
                for (CombatSide side : List.of(CombatSide.PLAYER, CombatSide.ENEMY)) {
                    if (state.getPendingBySide().containsKey(side)) {
                        state.markActive(side);
                    } else {
                        state.markIdle(side);
                        if (state.idleRounds(side) >= IDLE_SURRENDER_ROUNDS) {
                            finishBySurrender(state, side, sideLabel(state, side)
                                    + " 已离线判负（连续 " + IDLE_SURRENDER_ROUNDS + " 回合无行动）。");
                            return;
                        }
                    }
                }
            }
            // fresh PVP gates for the new round
            state.resetRoundGates();
        }
        state.log(CombatEvent.of(state.getRound(), "round_start",
                "第 " + state.getRound() + " 回合开始，帷幕升起。"));

        int playerRoll;
        int enemyRoll;
        do {
            playerRoll = dice.between(1, 20);
            enemyRoll = dice.between(1, 20);
        } while (playerRoll == enemyRoll);
        state.setFirstStrikeSide(playerRoll >= enemyRoll ? 0 : 1);
        String firstLabel = state.getFirstStrikeSide() == 0 ? sideLabel(state, CombatSide.PLAYER)
                : sideLabel(state, CombatSide.ENEMY);
        state.log(CombatEvent.of(state.getRound(), "round",
                "第 " + state.getRound() + " 回合开始。先手骰点: " + sideLabel(state, CombatSide.PLAYER)
                        + " " + playerRoll + " vs " + sideLabel(state, CombatSide.ENEMY) + " " + enemyRoll
                        + "，" + firstLabel + " 先手。"));

        // generic card draw every 3 rounds, per side
        List<CombatSide> sides = state.isPvp()
                ? List.of(CombatSide.PLAYER, CombatSide.ENEMY) : List.of(CombatSide.PLAYER);
        for (CombatSide side : sides) {
            if (state.getRound() % GENERIC_DRAW_INTERVAL == 0) {
                Combatant caster = state.alive(side).stream().findFirst().orElse(null);
                if (caster != null) {
                    effectExecutor.drawCards(caster, state, 1);
                }
            }
            // pending draw energy converted to a card
            if (state.sideDrawEnergy(side) >= DRAW_ENERGY_CAP) {
                Combatant caster = state.alive(side).stream().findFirst().orElse(null);
                if (caster != null) {
                    effectExecutor.drawCards(caster, state, 1);
                }
                state.addDrawEnergy(side, -state.sideDrawEnergy(side));
            }
        }

        tickRoundStartEffects(state);

        if (checkVictory(state)) {
            return;
        }
        state.setPhase(CombatPhase.DECISION);
        if (state.isPvp()) {
            state.setDecisionDeadlineAt(System.currentTimeMillis() + PVP_DECISION_WINDOW_MS);
        }
    }

    private void tickRoundStartEffects(CombatState state) {
        for (Combatant c : state.allAlive()) {
            List<StatusEffect> effects = new ArrayList<>(c.getStatusEffects());
            for (StatusEffect e : effects) {
                switch (e.getType()) {
                    case "heal_over_time" -> {
                        int amount = e.getDice() != null ? dice.roll(e.getDice()).total() : e.getAmount();
                        int healed = effectExecutor.heal(c, amount);
                        state.log(CombatEvent.of(state.getRound(), "heal",
                                c.getName() + " 回合开始恢复 " + healed + " 点生命（持续效果）。"));
                        if (e.getOwnerId() != null) {
                            Combatant owner = state.find(e.getOwnerId());
                            if (owner != null) {
                                owner.setTotalHealGiven(owner.getTotalHealGiven() + healed);
                            }
                        }
                    }
                    case "shield_over_time" -> {
                        grantShield(c, e.getAmount(), 1);
                        state.log(CombatEvent.of(state.getRound(), "shield",
                                c.getName() + " 回合开始获得 " + e.getAmount() + " 点护盾（仅限当回合）。"));
                    }
                    case "energy_over_time" -> {
                        int amount = e.getDice() != null ? dice.roll(e.getDice()).total() : e.getAmount();
                        c.setEnergy(Math.min(c.getMaxEnergy(), c.getEnergy() + amount));
                        state.log(CombatEvent.of(state.getRound(), "energy",
                                c.getName() + " 回合开始恢复 " + amount + " 点精力（持续效果）。"));
                    }
                    case "draw_over_time" -> {
                        int amount = e.getDice() != null ? dice.roll(e.getDice()).total() : e.getAmount();
                        // the effect draws for the OWNER's side (PVP: each
                        // human draws into their own hand)
                        Combatant caster = e.getOwnerId() != null ? state.find(e.getOwnerId()) : null;
                        if (caster == null || caster.isDead()) {
                            caster = state.alive(CombatSide.PLAYER).stream().findFirst().orElse(null);
                        }
                        if (caster != null) {
                            List<GenericSkillTemplate> hand = state.sideHand(caster.getSide());
                            effectExecutor.drawCards(caster, state, amount);
                            if (e.getMax() > 0 && hand.size() > e.getMax()) {
                                int excess = hand.size() - e.getMax();
                                for (int i = 0; i < excess; i++) {
                                    hand.remove(hand.size() - 1);
                                }
                            }
                            state.log(CombatEvent.of(state.getRound(), "draw",
                                    c.getName() + " 回合开始获得通用技能（养精蓄锐II）。"));
                        }
                    }
                    case "periodic_energy" -> {
                        if (state.getRound() % e.getCount() == 0) {
                            c.setEnergy(Math.min(c.getMaxEnergy(), c.getEnergy() + e.getAmount()));
                            state.log(CombatEvent.of(state.getRound(), "energy",
                                    c.getName() + " 获得 " + e.getAmount() + " 点精力（养精蓄锐）。"));
                        }
                    }
                    case "bloodletting" -> {
                        // 放血: one extra base action per round while active
                        c.setExtraActionsThisTurn(c.getExtraActionsThisTurn() + 1);
                        state.log(CombatEvent.of(state.getRound(), "action",
                                c.getName() + " 放血效果：本回合获得一次额外行动。"));
                    }
                    case "collapse" -> {
                        // 崩溃: lose hp every round start; teammates gain a
                        // one-round shield
                        int loss = Math.min(e.getAmount() > 0 ? e.getAmount() : 10, c.getHp());
                        c.setHp(c.getHp() - loss);
                        state.log(CombatEvent.of(state.getRound(), "damage",
                                c.getName() + " 因崩溃失去 " + loss + " 点生命。")
                                .with("target", c.getId()).with("hpDamage", loss));
                        if (c.getHp() <= 0) {
                            state.resolvePotentialDeath(c);
                        } else {
                            int shield = e.getCount() > 0 ? e.getCount() : 5;
                            for (Combatant mate : state.alive(c.getSide())) {
                                if (mate.getId().equals(c.getId())) {
                                    continue;
                                }
                                grantShield(mate, shield, 1);
                                state.log(CombatEvent.of(state.getRound(), "shield",
                                        mate.getName() + " 获得 " + shield + " 点护盾（崩溃庇护，持续 1 回合）。"));
                            }
                        }
                    }
                    default -> {
                        // ignore
                    }
                }
            }
            // stone shield pending from last round's defense
            if (c.isStoneShieldPending()) {
                c.setStoneShieldPending(false);
                if (c.getTemplate() != null && c.getTemplate().getCorePassive() != null
                        && "stone_shield".equals(c.getTemplate().getCorePassive().getType())) {
                    int amount = dice.roll(c.getTemplate().getCorePassive().getDice()).total();
                    grantShield(c, amount, 1);
                    state.log(CombatEvent.of(state.getRound(), "shield",
                            c.getName() + " 像块石头，回合开始获得 " + amount + " 点护盾。"));
                }
            }
        }
    }

    private void grantShield(Combatant target, int amount, int duration) {
        target.setShield(target.getShield() + amount);
        target.setShieldRemainingRounds(Math.max(target.getShieldRemainingRounds(), duration));
    }

    private void endRound(CombatState state) {
        state.setPhase(CombatPhase.ROUND_END);
        state.log(CombatEvent.of(state.getRound(), "round_end",
                "第 " + state.getRound() + " 回合结束，帷幕落下。"));

        // ally-death performances trigger at round end so a teammate's death
        // mid-round still fires the trigger before the next round starts
        for (Combatant c : state.allAlive()) {
            checkPerformance(state, c);
        }

        // taunt puppets vanish at round end
        List<Combatant> expiredPuppets = state.getCombatants().stream()
                .filter(c -> c instanceof PuppetMinion pm && pm.isExpiresEndOfRound())
                .toList();
        if (!expiredPuppets.isEmpty()) {
            state.getCombatants().removeAll(expiredPuppets);
            state.log(CombatEvent.of(state.getRound(), "status", "木偶消散了。"));
        }

        // draw energy settlement: first +3, second +4
        if (state.isPvp()) {
            CombatSide firstSide = state.getFirstStrikeSide() == 0 ? CombatSide.PLAYER : CombatSide.ENEMY;
            state.addDrawEnergy(firstSide, 3);
            state.addDrawEnergy(CombatState.opposite(firstSide), 4);
            state.log(CombatEvent.of(state.getRound(), "energy",
                    "回合结束：先手获得 3 抽牌能量，后手获得 4（"
                            + sideLabel(state, CombatSide.PLAYER) + " "
                            + state.sideDrawEnergy(CombatSide.PLAYER) + "/" + DRAW_ENERGY_CAP
                            + "，" + sideLabel(state, CombatSide.ENEMY) + " "
                            + state.sideDrawEnergy(CombatSide.ENEMY) + "/" + DRAW_ENERGY_CAP + "）。"));
        } else {
            int firstGain = state.getFirstStrikeSide() == 0 ? 3 : 4;
            state.addDrawEnergy(firstGain);
            state.log(CombatEvent.of(state.getRound(), "energy",
                    "回合结束：先手获得 " + firstGain + " 抽牌能量，后手获得 "
                            + (state.getFirstStrikeSide() == 0 ? 4 : 3)
                            + "（当前 " + state.getPlayerDrawEnergy() + "/" + DRAW_ENERGY_CAP + "）。"));
        }

        for (Combatant c : state.allAlive()) {
            if (c.getShield() > 0 && c.getShieldRemainingRounds() > 0) {
                c.setShieldRemainingRounds(c.getShieldRemainingRounds() - 1);
                if (c.getShieldRemainingRounds() == 0) {
                    c.setShield(0);
                }
            }
            for (StatusEffect e : new ArrayList<>(c.getStatusEffects())) {
                if ("decaying_shield".equals(e.getType())) {
                    int step = e.getAmount();
                    c.setShield(Math.max(0, c.getShield() - step));
                    state.log(CombatEvent.of(state.getRound(), "shield",
                            c.getName() + " 的攻击准备护盾减少 " + step + "（剩余 " + c.getShield() + "）。"));
                }
                e.setRemainingRounds(e.getRemainingRounds() - 1);
            }
            c.getStatusEffects().removeIf(StatusEffect::expired);

            c.setExtraActionsThisTurn(0);
            c.setExtraGuardsThisTurn(0);
            c.setExtraSkillsThisTurn(0);
            c.setSpeedBoostThisRound(0);
            c.setDefending(false);
            c.setBlockValue(0);
            c.setCountering(false);
            c.setDodging(false);
            c.setDodgeValue(0);
            c.setGuardTargetId(null);
            if (c.getGuardBindRounds() > 0) {
                c.setGuardBindRounds(c.getGuardBindRounds() - 1);
                if (c.getGuardBindRounds() == 0) {
                    c.setGuardBindTargetId(null);
                }
            }
            c.tickCooldowns();
        }

        if (checkVictory(state)) {
            return;
        }
        // special perk rounds: normally every 4 rounds; the clock-accelerate
        // generic card advances the next offer by one round (mod 4 == 3).
        // Checked here so extra-action rounds cannot skip the offer;
        // specialPerkOffered guards against re-offering when the perk round
        // itself finalizes (select/skip also land in endRound).
        boolean normalPerkRound = state.getRound() % SPECIAL_PERK_INTERVAL == 0;
        boolean acceleratedPerkRound = state.isSpecialPerkAdvancePending()
                && state.getRound() % SPECIAL_PERK_INTERVAL == SPECIAL_PERK_INTERVAL - 1;
        // 钟表加速 pulls the next offer one round early: the accelerated
        // offer SUBSTITUTES the following normal round instead of stacking
        boolean specialPerkDue = !state.isSpecialPerkAcceleratedConsumed()
                && (normalPerkRound || acceleratedPerkRound);
        // the consumed flag lives exactly until the normal round it skipped
        if (normalPerkRound) {
            state.setSpecialPerkAcceleratedConsumed(false);
        }
        if (specialPerkDue && !state.isSpecialPerkOffered()
                && state.getSpecialPerkRoundsTaken() < SPECIAL_PERK_MAX_ROUNDS) {
            state.setSpecialPerkOffered(true);
            if (acceleratedPerkRound) {
                state.setSpecialPerkAcceleratedConsumed(true);
            }
            state.setSpecialPerkAdvancePending(false);
            offerSpecialPerks(state);
            return;
        }
        startRound(state);
    }

    /**
     * A side surrenders: the opponent wins immediately (PVP only). The winner
     * follows the same PLAYER/ENEMY semantics as checkVictory so records and
     * views stay consistent; subscribers get a refresh ping right away.
     */
    public synchronized CombatState surrender(String battleId, CombatSide side) {
        CombatState state = getBattle(battleId);
        if (!state.isPvp()) {
            throw new IllegalStateException("solo battles have no surrender");
        }
        if (state.isOver()) {
            throw new IllegalStateException("battle already finished");
        }
        finishBySurrender(state, side, sideLabel(state, side) + " 投降，"
                + sideLabel(state, CombatState.opposite(side)) + " 获胜！");
        return state;
    }

    /** Shared surrender settlement: winner, FINISHED, log and SSE ping. */
    private void finishBySurrender(CombatState state, CombatSide side, String message) {
        CombatSide winnerSide = CombatState.opposite(side);
        state.setWinner(winnerSide.name());
        state.setPhase(CombatPhase.FINISHED);
        state.log(CombatEvent.of(state.getRound(), "surrender", message));
        notifyPvp(state.getId());
    }

    private boolean checkVictory(CombatState state) {
        if (state.alive(CombatSide.PLAYER).isEmpty()) {
            state.setWinner("ENEMY");
            state.setPhase(CombatPhase.FINISHED);
            state.log(CombatEvent.of(state.getRound(), "victory", state.isPvp()
                    ? sideLabel(state, CombatSide.PLAYER) + " 的队伍全灭，" + sideLabel(state, CombatSide.ENEMY) + " 获胜！"
                    : "玩家队伍全灭，木桩获胜。"));
            return true;
        }
        if (state.alive(CombatSide.ENEMY).isEmpty()) {
            state.setWinner("PLAYER");
            state.setPhase(CombatPhase.FINISHED);
            state.log(CombatEvent.of(state.getRound(), "victory", state.isPvp()
                    ? sideLabel(state, CombatSide.ENEMY) + " 的队伍被击倒，" + sideLabel(state, CombatSide.PLAYER) + " 获胜！"
                    : "木桩被击倒，玩家获胜！"));
            return true;
        }
        return false;
    }

    // ===================== pre-action gate (stun / bleed) =====================

    private boolean preActionGate(CombatState state, Combatant c) {
        if (c.statusesOfType("stun").stream().anyMatch(e -> !e.expired())) {
            state.log(CombatEvent.of(state.getRound(), "action",
                    c.getName() + " 晕眩中，无法行动。").with("actorId", c.getId()).with("action", "STUN"));
            return false;
        }
        return resolveBleed(state, c);
    }

    /**
     * 流血: performing any action costs HP equal to the current bleed stacks
     * and halves the stacks afterwards. Returns false when the combatant died
     * to the bleed (the pending action is cancelled).
     */
    private boolean resolveBleed(CombatState state, Combatant c) {
        List<StatusEffect> bleeds = c.statusesOfType("bleed");
        if (bleeds.isEmpty()) {
            return true;
        }
        int stacks = bleeds.stream().mapToInt(StatusEffect::getCount).sum();
        if (stacks <= 0) {
            c.getStatusEffects().removeIf(e -> "bleed".equals(e.getType()));
            return true;
        }
        int loss = Math.min(stacks, c.getHp());
        c.setHp(c.getHp() - loss);
        state.log(CombatEvent.of(state.getRound(), "damage",
                c.getName() + " 因流血失去 " + loss + " 点生命（当前 " + stacks + " 层）。")
                .with("target", c.getId()).with("hpDamage", loss).with("action", "BLEED"));
        // half the stacks (floor); zero stacks remove the effect entirely
        int remaining = stacks / 2;
        for (StatusEffect b : bleeds) {
            b.setCount(0);
        }
        if (remaining > 0) {
            bleeds.get(0).setCount(remaining);
        } else {
            c.getStatusEffects().removeIf(e -> "bleed".equals(e.getType()));
        }
        if (c.getHp() <= 0) {
            state.resolvePotentialDeath(c);
            return false;
        }
        return true;
    }

    private boolean hasDodgeTrainingTeammate(CombatState state, Combatant actor) {
        for (Combatant mate : state.alive(actor.getSide())) {
            if (mate.getId().equals(actor.getId())) {
                continue;
            }
            if (mate.getTemplate() != null && mate.getTemplate().getCorePassive() != null
                    && "dodge_training".equals(mate.getTemplate().getCorePassive().getType())) {
                return true;
            }
        }
        return false;
    }

    // ===================== performance =====================

    private void checkPerformance(CombatState state, Combatant c) {
        if (c.isDead() || c.isPerforming() || c.getTemplate() == null || c.getTemplate().getPerformance() == null) {
            return;
        }
        PerformanceSpec perf = c.getTemplate().getPerformance();
        boolean triggered = switch (perf.getTriggerType()) {
            case "hp_below" -> c.getHp() < perf.getThreshold();
            case "energy_below" -> c.getEnergy() < perf.getThreshold();
            case "heal_total" -> c.getTotalHealGiven() >= perf.getThreshold();
            case "guard_success" -> c.getGuardSuccessCount() >= perf.getThreshold();
            case "ally_death" -> state.sideDeaths(c.getSide()) > 0;
            default -> false;
        };
        if (triggered) {
            c.setPerforming(true);
            state.log(CombatEvent.of(state.getRound(), "performance",
                    c.getName() + " 触发演出！" + perf.getDescription()));
            for (EffectSpec effect : perf.getEffects()) {
                effectExecutor.execute(effect, c, state, (String) null);
            }
            // design doc: entering performance grants +2 draw energy (per side)
            if (state.isPvp() || c.isPlayerSide()) {
                state.addDrawEnergy(c.getSide(), 2);
                state.log(CombatEvent.of(state.getRound(), "energy",
                        c.getName() + " 进入演出，抽牌能量 +2（当前 "
                                + state.sideDrawEnergy(c.getSide()) + "/" + DRAW_ENERGY_CAP + "）。"));
            }
            // design doc default: restore energy unless the performance
            // already defines an explicit energy-restore effect
            boolean restoresEnergy = perf.getEffects().stream()
                    .anyMatch(e -> "energy".equals(e.getType()));
            if (!restoresEnergy) {
                c.setEnergy(Math.min(c.getMaxEnergy(), c.getEnergy() + PERFORMANCE_DEFAULT_ENERGY));
                state.log(CombatEvent.of(state.getRound(), "energy",
                        c.getName() + " 演出默认恢复 " + PERFORMANCE_DEFAULT_ENERGY + " 点精力。"));
            }
        }
    }
}
