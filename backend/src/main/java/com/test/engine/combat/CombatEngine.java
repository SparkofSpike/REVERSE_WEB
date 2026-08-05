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
import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final DiceRoller dice;
    private final CardPackLoader cardPackLoader;
    private final SpeedAdjudicator speedAdjudicator;
    private final DamageResolver damageResolver;
    private final EffectExecutor effectExecutor;
    private final PuppetAi puppetAi;

    private final Map<String, CombatState> battles = new ConcurrentHashMap<>();

    public CombatEngine(DiceRoller dice, CardPackLoader cardPackLoader,
                        SpeedAdjudicator speedAdjudicator, DamageResolver damageResolver,
                        EffectExecutor effectExecutor, PuppetAi puppetAi) {
        this.dice = dice;
        this.cardPackLoader = cardPackLoader;
        this.speedAdjudicator = speedAdjudicator;
        this.damageResolver = damageResolver;
        this.effectExecutor = effectExecutor;
        this.puppetAi = puppetAi;
    }

    // ===================== battle lifecycle =====================

    public CombatState createDummyBattle(String packId, List<String> characterIds, String ownerUsername) {
        CardPack pack = cardPackLoader.get(packId);
        if (characterIds == null || characterIds.isEmpty() || characterIds.size() > 4) {
            throw new IllegalArgumentException("a player must deploy 1-4 characters");
        }
        CombatState state = new CombatState();
        state.setId(UUID.randomUUID().toString().substring(0, 8));
        state.setOwnerUsername(ownerUsername);
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
        battles.put(state.getId(), state);
        return state;
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
        return state;
    }

    // ===================== initial perk =====================

    public CombatState selectInitialPerk(String battleId, String perkId) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.INITIAL_PERK) {
            throw new IllegalStateException("not in initial perk phase");
        }
        Perk perk = state.getInitialPerkOptions().stream()
                .filter(p -> p.getId().equals(perkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown initial perk: " + perkId));
        state.setInitialPerkOptions(List.of());
        state.log(CombatEvent.of(0, "perk", "选择初始词条: " + perk.getName() + " — " + perk.getDescription()));
        applyPerkEffect(perk, state);
        startRound(state);
        return state;
    }

    private void applyPerkEffect(Perk perk, CombatState state) {
        if (perk.getEffect() != null) {
            List<Combatant> players = state.alive(CombatSide.PLAYER);
            for (Combatant c : players) {
                effectExecutor.execute(perk.getEffect(), c, state, null);
            }
        }
    }

    // ===================== decision & round execution =====================

    public CombatState decide(String battleId, List<ActionDecision> playerDecisions) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION || state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in decision phase");
        }
        List<Combatant> players = state.alive(CombatSide.PLAYER);
        if (playerDecisions == null || playerDecisions.size() != players.size()) {
            throw new IllegalArgumentException("decisions for all alive player characters required");
        }
        state.setPendingDecisions(new ArrayList<>(playerDecisions));
        state.getPendingDecisions().addAll(puppetAi.decide(state));

        state.log(CombatEvent.of(state.getRound(), "decision", "双方完成决策，进入速度裁定。"));
        resolveRound(state);
        return state;
    }

    /**
     * Extra-action round: the player submits decisions for characters that
     * still hold extra base actions (连续奔袭). Each submitted decision
     * consumes one extra action; when none are left the round finalizes.
     */
    public CombatState decideExtraActions(String battleId, List<ActionDecision> decisions) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION || !state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in an extra-action round");
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
            if (c == null || c.isDead() || c.getExtraActionsThisTurn() - claimed <= 0) {
                throw new IllegalArgumentException("no extra actions left for " + d.getCombatantId());
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
            boolean executed = d.isSkill()
                    ? executeSkill(state, c, d)
                    : executeBaseAction(state, c, d, null);
            if (executed && c.getExtraActionsThisTurn() > 0) {
                c.setExtraActionsThisTurn(c.getExtraActionsThisTurn() - 1);
            }
        }
        if (checkVictory(state)) {
            state.setExtraActionRound(false);
            return state;
        }
        boolean extraPending = state.alive(CombatSide.PLAYER).stream()
                .anyMatch(c -> c.getExtraActionsThisTurn() > 0);
        if (extraPending) {
            state.setPhase(CombatPhase.DECISION);
            state.log(CombatEvent.of(state.getRound(), "extra",
                    "仍有额外行动可继续（或跳过）。"));
            return state;
        }
        state.setExtraActionRound(false);
        executeDeferredEnemyActions(state);
        if (checkVictory(state)) {
            return state;
        }
        endRound(state);
        return state;
    }

    /** Ends the extra-action window early and finalizes the round. */
    public CombatState skipExtraActions(String battleId) {
        CombatState state = getBattle(battleId);
        if (!state.isExtraActionRound()) {
            throw new IllegalStateException("battle is not in an extra-action round");
        }
        state.setExtraActionRound(false);
        executeDeferredEnemyActions(state);
        if (checkVictory(state)) {
            return state;
        }
        endRound(state);
        return state;
    }

    private void resolveRound(CombatState state) {
        state.setPhase(CombatPhase.SPEED);
        List<Combatant> alive = state.allAlive();
        List<Combatant> speedOrder = speedAdjudicator.resolve(alive, state);

        state.log(CombatEvent.of(state.getRound(), "speed", "速度裁定完成："
                + speedOrder.stream().map(c -> c.getName() + "(" + state.getRoundSpeed().get(c.getId()) + ")")
                .reduce((a, b) -> a + " > " + b).orElse("")));

        state.setPhase(CombatPhase.EXECUTION);
        // A round whose player decisions include an extra-action skill
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
                state.setExtraActionRound(true);
                state.setPhase(CombatPhase.DECISION);
                state.log(CombatEvent.of(state.getRound(), "extra",
                        "获得额外行动的角色可以继续行动（或跳过）。"));
                return;
            }
            executeDeferredEnemyActions(state);
        } else {
            executeActions(state, speedOrder, null);
        }

        if (state.isOver()) {
            return;
        }
        // special perk rounds: normally every 4 rounds; the clock-accelerate
        // generic card advances the next offer by one round (mod 4 == 3)
        boolean specialPerkDue = state.getRound() % SPECIAL_PERK_INTERVAL == 0
                || (state.isSpecialPerkAdvancePending()
                    && state.getRound() % SPECIAL_PERK_INTERVAL == SPECIAL_PERK_INTERVAL - 1);
        if (specialPerkDue && state.getSpecialPerkRoundsTaken() < SPECIAL_PERK_MAX_ROUNDS) {
            state.setSpecialPerkAdvancePending(false);
            offerSpecialPerks(state);
            return;
        }
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

    private void executeAttack(CombatState state, Combatant actor, ActionDecision decision, DiceResult preRolled,
                               String actionName, boolean clashEnabled) {
        Combatant target = state.find(decision.getTargetId());
        if (target == null || target.isDead()) {
            state.log(CombatEvent.of(state.getRound(), "action", actor.getName() + " 的"
                    + ("CHASE".equals(actionName) ? "追击" : "攻击") + "落空（目标已不在）。")
                .with("actorId", actor.getId()).with("targetId", decision.getTargetId()).with("action", actionName));
            return;
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
            return;
        }

        // dodge check: a dodging target compares speed values
        if (target.isDodging()) {
            int attackSpeed = dice.roll(actor.getSpeedDice()).total() + actor.effectiveSpeed();
            if (attackSpeed <= target.getDodgeValue()) {
                state.log(CombatEvent.of(state.getRound(), "dodge",
                        target.getName() + " 成功闪避了 " + actor.getName() + " 的攻击！")
                .with("actorId", actor.getId()).with("targetId", target.getId()).with("action", actionName));
                grantAttackEnergy(state, actor);
                return;
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
                return;
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
        // chase is a unilateral follow-up strike: it never clashes
        executeAttack(state, actor, decision, preRolled, "CHASE", false);
        // design doc: when the chase strikes the same target as the last
        // attack, it adds 0d4 damage and restores 2 HP
        if (target.getId().equals(actor.getLastAttackedTarget())) {
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
        // draw energy is only tracked for the player side (dummy has no deck)
        if (actor.isPlayerSide()) {
            state.addDrawEnergy(1);
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
                .with("actorId", caster.getId()).with("targetId", decision.getTargetId()).with("action", "SKILL"));
        for (EffectSpec effect : skill.getEffects()) {
            effectExecutor.execute(effect, caster, state, decision.getTargetId());
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

    public CombatState playGenericSkill(String battleId, String skillId, String targetId) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.DECISION) {
            throw new IllegalStateException("generic skills can only be played during decision phase");
        }
        GenericSkillTemplate card = state.getPlayerHand().stream()
                .filter(c -> c.getId().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("card not in hand: " + skillId));
        Combatant caster = state.alive(CombatSide.PLAYER).get(0);
        state.log(CombatEvent.of(state.getRound(), "card",
                caster.getName() + " 打出通用技能 " + card.getName() + "。")
                .with("actorId", caster.getId()).with("action", "CARD"));
        for (EffectSpec effect : card.getEffects()) {
            effectExecutor.execute(effect, caster, state, targetId);
        }
        state.getPlayerHand().remove(card);
        if (card.isConsumed()) {
            state.getPlayerDeck().remove(card);
        }
        if (state.isOver()) {
            return state;
        }
        return state;
    }

    // ===================== special perks =====================

    private void offerSpecialPerks(CombatState state) {
        CardPack pack = cardPackLoader.get("test-1");
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
        state.log(CombatEvent.of(state.getRound(), "perk", "特殊词条轮！请选择一项词条。"));
    }

    public CombatState selectSpecialPerk(String battleId, String perkId) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.SPECIAL_PERK) {
            throw new IllegalStateException("not in special perk phase");
        }
        Perk perk = state.getSpecialPerkOptions().stream()
                .filter(p -> p.getId().equals(perkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown special perk: " + perkId));
        state.setSpecialPerkOptions(List.of());
        state.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken() + 1);
        state.log(CombatEvent.of(state.getRound(), "perk",
                "选择特殊词条: " + perk.getName() + " — " + perk.getDescription()));
        List<Combatant> players = state.alive(CombatSide.PLAYER);
        for (Combatant c : players) {
            effectExecutor.execute(perk.getEffect(), c, state, null);
        }
        if (state.isOver()) {
            return state;
        }
        endRound(state);
        return state;
    }

    public CombatState skipSpecialPerk(String battleId) {
        CombatState state = getBattle(battleId);
        if (state.getPhase() != CombatPhase.SPECIAL_PERK) {
            throw new IllegalStateException("not in special perk phase");
        }
        state.setSpecialPerkOptions(List.of());
        state.log(CombatEvent.of(state.getRound(), "perk", "跳过本轮特殊词条选择。"));
        endRound(state);
        return state;
    }

    // ===================== round transitions =====================

    private void startRound(CombatState state) {
        state.setPhase(CombatPhase.ROUND_START);
        state.setRound(state.getRound() + 1);
        state.log(CombatEvent.of(state.getRound(), "round_start",
                "第 " + state.getRound() + " 回合开始，帷幕升起。"));

        int playerRoll;
        int enemyRoll;
        do {
            playerRoll = dice.between(1, 20);
            enemyRoll = dice.between(1, 20);
        } while (playerRoll == enemyRoll);
        state.setFirstStrikeSide(playerRoll >= enemyRoll ? 0 : 1);
        state.log(CombatEvent.of(state.getRound(), "round",
                "第 " + state.getRound() + " 回合开始。先手骰点: 玩家 " + playerRoll
                        + " vs 木桩 " + enemyRoll
                        + (state.getFirstStrikeSide() == 0 ? "，玩家先手。" : "，木桩先手。")));

        // generic card draw every 3 rounds
        if (state.getRound() % GENERIC_DRAW_INTERVAL == 0) {
            Combatant caster = state.alive(CombatSide.PLAYER).stream().findFirst().orElse(null);
            if (caster != null) {
                effectExecutor.drawCards(caster, state, 1);
            }
        }
        // pending draw energy converted to a card
        if (state.getPlayerDrawEnergy() >= DRAW_ENERGY_CAP) {
            Combatant caster = state.alive(CombatSide.PLAYER).stream().findFirst().orElse(null);
            if (caster != null) {
                effectExecutor.drawCards(caster, state, 1);
            }
            state.setPlayerDrawEnergy(0);
        }

        tickRoundStartEffects(state);

        if (checkVictory(state)) {
            return;
        }
        state.setPhase(CombatPhase.DECISION);
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
                        Combatant caster = state.alive(CombatSide.PLAYER).stream().findFirst().orElse(null);
                        if (caster != null) {
                            int before = state.getPlayerHand().size();
                            effectExecutor.drawCards(caster, state, amount);
                            if (e.getMax() > 0 && state.getPlayerHand().size() > e.getMax()) {
                                int excess = state.getPlayerHand().size() - e.getMax();
                                for (int i = 0; i < excess; i++) {
                                    state.getPlayerHand().remove(state.getPlayerHand().size() - 1);
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

        // taunt puppets vanish at round end
        List<Combatant> expiredPuppets = state.getCombatants().stream()
                .filter(c -> c instanceof PuppetMinion pm && pm.isExpiresEndOfRound())
                .toList();
        if (!expiredPuppets.isEmpty()) {
            state.getCombatants().removeAll(expiredPuppets);
            state.log(CombatEvent.of(state.getRound(), "status", "木偶消散了。"));
        }

        // draw energy settlement: first +3, second +4
        int firstGain = state.getFirstStrikeSide() == 0 ? 3 : 4;
        int secondGain = state.getFirstStrikeSide() == 0 ? 4 : 3;
        state.addDrawEnergy(firstGain);
        state.log(CombatEvent.of(state.getRound(), "energy",
                "回合结束：先手获得 " + firstGain + " 抽牌能量，后手获得 " + secondGain
                        + "（当前 " + state.getPlayerDrawEnergy() + "/" + DRAW_ENERGY_CAP + "）。"));

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
        startRound(state);
    }

    private boolean checkVictory(CombatState state) {
        if (state.alive(CombatSide.PLAYER).isEmpty()) {
            state.setWinner("ENEMY");
            state.setPhase(CombatPhase.FINISHED);
            state.log(CombatEvent.of(state.getRound(), "victory", "玩家队伍全灭，木桩获胜。"));
            return true;
        }
        if (state.alive(CombatSide.ENEMY).isEmpty()) {
            state.setWinner("PLAYER");
            state.setPhase(CombatPhase.FINISHED);
            state.log(CombatEvent.of(state.getRound(), "victory", "木桩被击倒，玩家获胜！"));
            return true;
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
            default -> false;
        };
        if (triggered) {
            c.setPerforming(true);
            state.log(CombatEvent.of(state.getRound(), "performance",
                    c.getName() + " 触发演出！" + perf.getDescription()));
            for (EffectSpec effect : perf.getEffects()) {
                effectExecutor.execute(effect, c, state, null);
            }
            // design doc: entering performance grants +2 draw energy (player side)
            if (c.isPlayerSide()) {
                state.addDrawEnergy(2);
                state.log(CombatEvent.of(state.getRound(), "energy",
                        c.getName() + " 进入演出，抽牌能量 +2（当前 " + state.getPlayerDrawEnergy() + "/" + DRAW_ENERGY_CAP + "）。"));
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
