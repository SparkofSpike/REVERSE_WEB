package com.test.engine.combat;

import com.test.engine.enums.DamageType;
import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.EffectSpec;
import com.test.engine.model.GenericSkillTemplate;
import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Translates declarative EffectSpec entries into actual battle operations.
 * Target resolution uses the caster's side; explicit targets for single
 * target effects are supplied by the caller.
 */
@Component
public class EffectExecutor {

    private final DiceRoller dice;
    private final DamageResolver damageResolver;
    private final CardPackLoader cardPackLoader;

    public EffectExecutor(DiceRoller dice, DamageResolver damageResolver, CardPackLoader cardPackLoader) {
        this.dice = dice;
        this.damageResolver = damageResolver;
        this.cardPackLoader = cardPackLoader;
    }

    /**
     * Executes a spec. {@code explicitTarget} is required for ally/ally-random
     * targets and honored for enemy targets when non-null.
     */
    public void execute(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        String type = spec.getType();
        switch (type == null ? "" : type) {
            case "damage" -> applyDamage(spec, caster, state, explicitTarget);
            case "heal" -> applyHeal(spec, caster, state, explicitTarget);
            case "shield" -> applyShield(spec, caster, state, explicitTarget);
            case "energy" -> applyEnergy(spec, caster, state, explicitTarget);
            case "draw" -> drawCards(caster, state, spec.getCount());
            case "draw_boost" -> state.setDrawBoostPending(true);
            case "extra_actions" -> applyExtraActions(spec, caster);
            case "extra_guard" -> applyExtraGuard(spec, caster);
            case "extra_defend" -> applyExtraDefend(spec, caster, state);
            case "extra_skill" -> applyExtraSkill(spec, caster, state);
            case "lifesteal" -> addLifesteal(spec, caster);
            case "damage_bonus" -> addDamageBonus(spec, caster, state);
            case "speed_boost" -> addSpeedBoost(spec, caster, state);
            case "speed_permanent" -> addSpeedPermanent(spec, caster, state);
            case "heal_over_time" -> addStatus(spec, caster, state, "heal_over_time");
            case "shield_over_time" -> addStatus(spec, caster, state, "shield_over_time");
            case "energy_over_time" -> addStatus(spec, caster, state, "energy_over_time");
            case "draw_over_time" -> addStatus(spec, caster, state, "draw_over_time");
            case "max_hp_bonus" -> applyMaxHpBonus(spec, caster, state);
            case "periodic_energy" -> addPeriodicEnergy(spec, caster, state);
            case "decaying_shield" -> applyDecayingShield(spec, caster, state);
            case "hp_cost" -> applyHpCost(spec, caster, state);
            case "puppet" -> spawnPuppet(spec, caster, state);
            case "peek" -> logPeek(caster, state);
            case "accelerate" -> {
                addSpeedBoost(spec, caster, state);
                // clock accelerate: the next special perk offer comes one round early
                state.setSpecialPerkAdvancePending(true);
                state.log(CombatEvent.of(state.getRound(), "effect",
                        "钟表加速：下一次特殊词条轮提前一回合。"));
            }
            case "guard_bind" -> applyGuardBind(spec, caster, state, explicitTarget);
            case "sacrifice_buff" -> applySacrificeBuff(spec, caster, state);
            case "upgrade_skills" -> upgradeSkills(caster, state);
            case "draw_energy" -> {
                state.addDrawEnergy(spec.getAmount());
                state.log(CombatEvent.of(state.getRound(), "energy",
                        caster.getName() + " 获得 " + spec.getAmount() + " 点抽牌能量。"));
            }
            case "heal_end_of_round" -> addEndOfRoundHeal(spec, caster, state);
            default -> state.log(CombatEvent.of(state.getRound(), "effect",
                    "未实现的效果类型: " + type));
        }
    }

    // ----- damage / heal / shield / energy -----

    private void applyDamage(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        DamageType type = spec.getDamageType() == null ? DamageType.PHYSICAL : spec.getDamageType();
        int base = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
        int damage = base + caster.getBonusDamage();
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, explicitTarget, spec.getCount());
        for (Combatant t : targets) {
            if (t.isDead()) {
                continue;
            }
            damageResolver.dealDamage(t, damage, type, state, caster, "SKILL");
            if (t.getHp() <= 0 && !t.isDead()) {
                handleDeath(t, state);
            }
        }
    }

    private void applyHeal(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        int amount = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, explicitTarget, spec.getCount())) {
            if (t.isDead()) {
                continue;
            }
            int actual = heal(t, amount);
            caster.setTotalHealGiven(caster.getTotalHealGiven() + actual);
            state.log(CombatEvent.of(state.getRound(), "heal",
                    t.getName() + " 恢复 " + actual + " 点生命（来自 " + caster.getName() + "）。"));
        }
    }

    private void applyShield(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        int amount = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
        int duration = spec.getDuration() > 0 ? spec.getDuration() : 1;
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, explicitTarget, spec.getCount())) {
            if (t.isDead()) {
                continue;
            }
            grantShield(t, amount, duration);
            state.log(CombatEvent.of(state.getRound(), "shield",
                    t.getName() + " 获得 " + amount + " 点护盾，持续 " + duration + " 回合。"));
        }
    }

    private void applyEnergy(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        int amount = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, explicitTarget, spec.getCount())) {
            t.setEnergy(Math.min(t.getMaxEnergy(), t.getEnergy() + amount));
            state.log(CombatEvent.of(state.getRound(), "energy",
                    t.getName() + " 恢复 " + amount + " 点精力。"));
        }
    }

    // ----- action grants -----

    private void applyExtraActions(EffectSpec spec, Combatant caster) {
        caster.setExtraActionsThisTurn(caster.getExtraActionsThisTurn() + spec.getCount());
    }

    private void applyExtraGuard(EffectSpec spec, Combatant caster) {
        caster.setExtraGuardsThisTurn(caster.getExtraGuardsThisTurn() + spec.getCount());
    }

    private void applyExtraDefend(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            t.setExtraActionsThisTurn(t.getExtraActionsThisTurn() + 1);
            state.log(CombatEvent.of(state.getRound(), "action",
                    t.getName() + " 获得一次额外的防守行动。"));
        }
    }

    private void applyExtraSkill(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            t.setExtraSkillsThisTurn(t.getExtraSkillsThisTurn() + spec.getCount());
        }
    }

    // ----- status effects -----

    private void addLifesteal(EffectSpec spec, Combatant caster) {
        StatusEffect e = StatusEffect.of("lifesteal", spec.getDuration());
        e.setRatio(spec.getRatio());
        e.setOwnerId(caster.getId());
        caster.addStatus(e);
    }

    private void addDamageBonus(EffectSpec spec, Combatant caster, CombatState state) {
        int amount = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
        caster.setBonusDamage(caster.getBonusDamage() + amount);
        state.log(CombatEvent.of(state.getRound(), "buff",
                caster.getName() + " 的伤害永久增加 " + amount + " 点。"));
    }

    private void addSpeedBoost(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            t.setSpeedBoostThisRound(t.getSpeedBoostThisRound() + spec.getAmount());
            state.log(CombatEvent.of(state.getRound(), "buff",
                    t.getName() + " 本回合速度 +" + spec.getAmount() + "。"));
        }
    }

    private void addSpeedPermanent(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            int amount = spec.getDice() != null ? dice.roll(spec.getDice()).total() : spec.getAmount();
            t.setPermanentSpeedBonus(t.getPermanentSpeedBonus() + amount);
            state.log(CombatEvent.of(state.getRound(), "buff",
                    t.getName() + " 的速度永久 +" + amount + "。"));
        }
    }

    private void addStatus(EffectSpec spec, Combatant caster, CombatState state, String type) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            StatusEffect e = StatusEffect.of(type, spec.getDuration());
            e.setDice(spec.getDice());
            e.setAmount(spec.getAmount());
            e.setCount(spec.getCount());
            e.setMax(spec.getMax());
            e.setOwnerId(caster.getId());
            t.addStatus(e);
            state.log(CombatEvent.of(state.getRound(), "status",
                    t.getName() + " 获得持续 " + spec.getDuration() + " 回合的效果: " + type));
        }
    }

    private void addPeriodicEnergy(EffectSpec spec, Combatant caster, CombatState state) {
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, null, spec.getCount())) {
            StatusEffect e = StatusEffect.of("periodic_energy", Integer.MAX_VALUE / 2);
            e.setAmount(spec.getAmount());
            e.setOwnerId(caster.getId());
            e.setCount(spec.getInterval());
            t.addStatus(e);
        }
        state.log(CombatEvent.of(state.getRound(), "status",
                "每 " + spec.getInterval() + " 回合恢复 " + spec.getAmount() + " 点精力（养精蓄锐）。"));
    }

    private void applyDecayingShield(EffectSpec spec, Combatant caster, CombatState state) {
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, null, spec.getCount())) {
            grantShield(t, spec.getAmount(), spec.getDuration());
            StatusEffect e = StatusEffect.of("decaying_shield", spec.getDuration());
            e.setAmount(spec.getCount());
            e.setOwnerId(caster.getId());
            t.addStatus(e);
        }
        state.log(CombatEvent.of(state.getRound(), "shield",
                "获得 " + spec.getAmount() + " 点护盾，每回合结束减少 " + spec.getCount() + "（攻击准备）。"));
    }

    private void applyHpCost(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            if (t.isDead()) {
                continue;
            }
            int cost = Math.min(spec.getAmount(), t.getHp());
            t.setHp(t.getHp() - cost);
            state.log(CombatEvent.of(state.getRound(), "damage",
                    t.getName() + " 损失 " + cost + " 点生命（奉献）。"));
            if (t.getHp() <= 0) {
                handleDeath(t, state);
            }
        }
    }

    private void applyGuardBind(EffectSpec spec, Combatant caster, CombatState state, String explicitTarget) {
        Combatant target = state.find(explicitTarget == null ? caster.getId() : explicitTarget);
        if (target == null || target.isDead()) {
            return;
        }
        caster.setGuardBindTargetId(target.getId());
        caster.setGuardBindRounds(spec.getDuration());
        caster.setGuardBindShield(spec.getAmount());
        grantShield(caster, spec.getAmount(), spec.getDuration());
        state.log(CombatEvent.of(state.getRound(), "status",
                caster.getName() + " 绑定守护 " + target.getName() + "，" + spec.getDuration() + " 回合内替其承受所有伤害。"));
    }

    private void applySacrificeBuff(EffectSpec spec, Combatant caster, CombatState state) {
        List<Combatant> targets = resolveTargets(spec.getTarget(), caster, state, null, spec.getCount());
        for (Combatant t : targets) {
            if (t.isDead()) {
                continue;
            }
            int cost = Math.min(spec.getAmount(), t.getHp());
            t.setHp(t.getHp() - cost);
            t.setEnergy(Math.min(t.getMaxEnergy(), t.getEnergy() + spec.getCount()));
            state.log(CombatEvent.of(state.getRound(), "status",
                    t.getName() + " 失去 " + cost + " HP，获得 " + spec.getCount() + " 精力（冷漠实现）。"));
        }
        // lowest HP ally keeps a permanent extra action
        targets.stream().filter(t -> !t.isDead()).min(java.util.Comparator.comparingInt(Combatant::getHp))
                .ifPresent(t -> {
                    t.setPermanentExtraAction(true);
                    state.log(CombatEvent.of(state.getRound(), "status",
                            t.getName() + " 恒有一颗额外的行动次数（冷漠实现）。"));
                });
    }

    private void upgradeSkills(Combatant caster, CombatState state) {
        int upgraded = 0;
        caster.setSkillsUpgraded(true);
        List<SkillTemplateUpgrade> replacements = new ArrayList<>();
        for (com.test.engine.model.SkillTemplate skill : caster.getSkills()) {
            if (skill.getUpgraded() != null) {
                replacements.add(new SkillTemplateUpgrade(skill.getId(), skill.getUpgraded()));
                upgraded++;
            }
        }
        for (SkillTemplateUpgrade r : replacements) {
            int idx = -1;
            for (int i = 0; i < caster.getSkills().size(); i++) {
                if (caster.getSkills().get(i).getId().equals(r.id())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                // carry any pending cooldown over to the upgraded skill id:
                // cooldowns are keyed by skill id, and the upgraded entry has
                // a new id (e.g. warrior-s3 -> warrior-s3-up). Without the
                // transfer the new id never matches a cooldown key, so the
                // upgraded skill ignores its cooldown entirely.
                Integer pending = caster.getCooldowns().remove(r.id());
                caster.getSkills().set(idx, r.upgraded());
                if (pending != null && pending > 0) {
                    caster.setCooldown(r.upgraded().getId(), pending);
                }
            }
        }
        state.log(CombatEvent.of(state.getRound(), "performance",
                caster.getName() + " 的 " + upgraded + " 个技能升变！"));
    }

    private void addEndOfRoundHeal(EffectSpec spec, Combatant caster, CombatState state) {
        StatusEffect e = StatusEffect.of("heal_end_of_round", 1);
        e.setDice(spec.getDice());
        e.setCount(spec.getCount());
        e.setOwnerId(caster.getId());
        caster.addStatus(e);
    }

    // ----- special units -----

    private void spawnPuppet(EffectSpec spec, Combatant caster, CombatState state) {
        PuppetMinion minion = new PuppetMinion();
        minion.setId("puppet-" + state.getCombatants().size());
        minion.setName("木偶");
        minion.setSide(caster.getSide());
        minion.setMaxHp(spec.getAmount());
        minion.setHp(spec.getAmount());
        minion.setSpeedDice("1d6");
        minion.setBaseDamageDice("1d6");
        minion.setTaunt(true);
        minion.setExpiresEndOfRound(true);
        minion.setOwnerId(caster.getId());
        state.getCombatants().add(minion);
        state.log(CombatEvent.of(state.getRound(), "status",
                caster.getName() + " 生成一个嘲讽木偶（" + spec.getAmount() + " 生命，本回合结束消失）。"));
    }

    private void logPeek(Combatant caster, CombatState state) {
        List<Combatant> enemies = state.alive(CombatSide.ENEMY);
        if (!enemies.isEmpty() && enemies.get(0).getTemplate() != null) {
            state.log(CombatEvent.of(state.getRound(), "peek",
                    "视角窥探：对方 " + enemies.get(0).getName() + " 的技能牌被展示。"));
        }
    }

    // ----- shared helpers -----

    private void grantShield(Combatant target, int amount, int duration) {
        target.setShield(target.getShield() + amount);
        target.setShieldRemainingRounds(Math.max(target.getShieldRemainingRounds(), duration));
    }

    public int heal(Combatant target, int amount) {
        int actual = Math.min(amount, target.getMaxHp() - target.getHp());
        target.setHp(target.getHp() + actual);
        return actual;
    }

    public void drawCards(Combatant caster, CombatState state, int count) {
        int actual = count + (state.isDrawBoostPending() ? 2 : 0);
        state.setDrawBoostPending(false);
        List<GenericSkillTemplate> deck = state.getPlayerDeck();
        for (int i = 0; i < actual; i++) {
            if (deck.isEmpty()) {
                break;
            }
            GenericSkillTemplate card = deck.remove(deck.size() - 1);
            state.getPlayerHand().add(card);
        }
        state.log(CombatEvent.of(state.getRound(), "draw",
                caster.getName() + " 抽取 " + actual + " 张通用技能（手牌 " + state.getPlayerHand().size() + " 张）。"));
    }

    private List<Combatant> resolveTargets(String selector, Combatant caster, CombatState state,
                                           String explicitTarget, int count) {
        if (selector == null) {
            selector = "enemy";
        }
        List<Combatant> result = new ArrayList<>();
        CombatSide own = caster.getSide();
        CombatSide enemySide = own == CombatSide.PLAYER ? CombatSide.ENEMY : CombatSide.PLAYER;
        switch (selector) {
            case "self" -> result.add(caster);
            case "ally" -> {
                Combatant t = state.find(explicitTarget == null ? caster.getId() : explicitTarget);
                if (t != null && t.getSide() == own) {
                    result.add(t);
                }
            }
            case "random_ally" -> {
                List<Combatant> allies = state.alive(own);
                if (!allies.isEmpty()) {
                    result.add(allies.get(dice.random().nextInt(allies.size())));
                }
            }
            case "allies" -> {
                List<Combatant> allies = state.alive(own);
                int n = count > 0 ? Math.min(count, allies.size()) : allies.size();
                result.addAll(allies.subList(0, n));
            }
            case "enemies" -> {
                List<Combatant> enemies = state.alive(enemySide);
                int n = count > 0 ? Math.min(count, enemies.size()) : enemies.size();
                result.addAll(enemies.subList(0, n));
            }
            case "enemy" -> {
                if (explicitTarget != null) {
                    Combatant t = state.find(explicitTarget);
                    if (t != null && t.getSide() == enemySide) {
                        result.add(t);
                    }
                } else {
                    List<Combatant> enemies = state.alive(enemySide);
                    if (!enemies.isEmpty()) {
                        result.add(enemies.get(dice.random().nextInt(enemies.size())));
                    }
                }
            }
            default -> result.add(caster);
        }
        return result;
    }

    private void handleDeath(Combatant target, CombatState state) {
        target.setDead(true);
        state.log(CombatEvent.of(state.getRound(), "death", target.getName() + " 倒下了！"));
    }

    private void applyMaxHpBonus(EffectSpec spec, Combatant caster, CombatState state) {
        for (Combatant t : resolveTargets(spec.getTarget(), caster, state, null, spec.getCount())) {
            t.setMaxHp(t.getMaxHp() + spec.getAmount());
            t.setHp(t.getHp() + spec.getAmount());
            state.log(CombatEvent.of(state.getRound(), "buff",
                    t.getName() + " 的最大生命 +" + spec.getAmount() + "（坚守本心）。"));
        }
    }

    private record SkillTemplateUpgrade(String id, com.test.engine.model.SkillTemplate upgraded) {
    }
}
