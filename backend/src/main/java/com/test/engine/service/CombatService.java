package com.test.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.combat.ActionDecision;
import com.test.engine.combat.CombatEngine;
import com.test.engine.combat.CombatEvent;
import com.test.engine.combat.CombatPhase;
import com.test.engine.combat.CombatSide;
import com.test.engine.combat.CombatState;
import com.test.engine.combat.Combatant;
import com.test.engine.dto.combat.CombatView;
import com.test.engine.dto.combat.CombatantView;
import com.test.engine.dto.combat.SkillView;
import com.test.engine.entity.BattleRecord;
import com.test.engine.entity.User;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.SkillTemplate;
import com.test.engine.repository.BattleRecordRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Battle facade for the API layer: delegates to the engine, converts states
 * to frontend views and persists finished battles with stats. PVP battles
 * route every request to the side controlled by the requesting user and
 * render the view from that user's perspective (own hand only, submission
 * gates, fog of war).
 */
@Service
public class CombatService {

    private final CombatEngine engine;
    private final AuthService authService;
    private final BattleRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public CombatService(CombatEngine engine, AuthService authService,
                         BattleRecordRepository recordRepository, ObjectMapper objectMapper) {
        this.engine = engine;
        this.authService = authService;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public CombatView createDummy(String username, String packId, List<String> characterIds) {
        CombatState state = engine.createDummyBattle(packId, characterIds, username);
        // solo battles: the owner always views from the PLAYER side
        return toView(state, CombatSide.PLAYER);
    }

    public CombatView get(String username, String battleId) {
        CombatState state = engine.getBattle(battleId);
        CombatSide side = sideOf(state, username);
        // a battle settled by the timeout sweeper still needs its records
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView selectInitialPerk(String username, String battleId, String perkId) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.selectInitialPerk(battleId, perkId, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView decide(String username, String battleId, List<ActionDecision> decisions) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.decideSide(battleId, side, decisions);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView decideExtraActions(String username, String battleId, List<ActionDecision> decisions) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.decideExtraActions(battleId, decisions, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView skipExtraActions(String username, String battleId) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.skipExtraActions(battleId, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView playCard(String username, String battleId, String skillId, String targetId) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.playGenericSkill(battleId, skillId, targetId, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView selectSpecialPerk(String username, String battleId, String perkId) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.selectSpecialPerk(battleId, perkId, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    public CombatView skipSpecialPerk(String username, String battleId) {
        CombatSide side = sideOf(engine.getBattle(battleId), username);
        CombatState state = engine.skipSpecialPerk(battleId, side);
        persistIfFinished(state);
        return toView(state, side);
    }

    /** The side controlled by the requesting user (owner=PLAYER, guest=ENEMY). */
    private CombatSide sideOf(CombatState state, String username) {
        if (state.getOwnerUsername().equals(username)) {
            return CombatSide.PLAYER;
        }
        if (state.isPvp() && state.getGuestUsername().equals(username)) {
            return CombatSide.ENEMY;
        }
        throw new BusinessException("无权访问该战斗");
    }

    // ===================== view conversion =====================

    private CombatView toView(CombatState state, CombatSide viewer) {
        CombatView view = new CombatView();
        view.setId(state.getId());
        view.setOwnerUsername(state.getOwnerUsername());
        view.setGuestUsername(state.getGuestUsername());
        view.setPhase(state.getPhase());
        view.setRound(state.getRound());
        view.setWinner(state.getWinner());
        view.setMySide(viewer.name());
        view.setDecisionDeadlineAt(state.getDecisionDeadlineAt());
        view.setExtraRoundSide(state.getExtraRoundSide() == null ? null : state.getExtraRoundSide().name());
        applySubmissionGates(view, state, viewer);
        view.setFirstStrikeSide(state.getFirstStrikeSide());
        // fog of war: each viewer only sees their OWN hand and draw energy
        view.setPlayerDrawEnergy(state.sideDrawEnergy(viewer));
        view.setPlayerHand(state.sideHand(viewer));
        view.setInitialPerkOptions(state.getInitialPerkOptions());
        view.setSpecialPerkOptions(state.getSpecialPerkOptions());
        view.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken());
        view.setExtraActionRound(state.isExtraActionRound());
        view.setLogs(state.getLogs());
        view.setCombatants(state.getCombatants().stream().map(this::toCombatantView).toList());
        return view;
    }

    /**
     * mySubmitted/opponentSubmitted carry the phase-specific meaning: round
     * decisions, extra-action window, perk picks or initial perk choice.
     */
    private void applySubmissionGates(CombatView view, CombatState state, CombatSide viewer) {
        if (!state.isPvp()) {
            return;
        }
        CombatSide opponent = CombatState.opposite(viewer);
        if (state.getPhase() == CombatPhase.DECISION) {
            if (state.isExtraActionRound()) {
                view.setMySubmitted(state.extraFinished(viewer));
                view.setOpponentSubmitted(state.extraFinished(opponent));
            } else {
                view.setMySubmitted(state.submitted(viewer));
                view.setOpponentSubmitted(state.submitted(opponent));
            }
        } else if (state.getPhase() == CombatPhase.SPECIAL_PERK) {
            view.setMySubmitted(state.specialPerkPicked(viewer));
            view.setOpponentSubmitted(state.specialPerkPicked(opponent));
        } else if (state.getPhase() == CombatPhase.INITIAL_PERK) {
            view.setMySubmitted(state.initialPerkPicked(viewer));
            view.setOpponentSubmitted(state.initialPerkPicked(opponent));
        }
    }

    private CombatantView toCombatantView(Combatant c) {
        CombatantView v = new CombatantView();
        v.setId(c.getId());
        v.setTemplateId(c.getTemplateId());
        v.setName(c.getName());
        v.setSide(c.getSide().name());
        v.setHp(c.getHp());
        v.setMaxHp(c.getMaxHp());
        v.setEnergy(c.getEnergy());
        v.setMaxEnergy(c.getMaxEnergy());
        v.setShield(c.getShield());
        v.setShieldRemainingRounds(c.getShieldRemainingRounds());
        v.setDead(c.isDead());
        v.setPerforming(c.isPerforming());
        v.setSkillsUpgraded(c.isSkillsUpgraded());
        v.setDodging(c.isDodging());
        v.setGuardSuccessCount(c.getGuardSuccessCount());
        v.setTotalHealGiven(c.getTotalHealGiven());
        v.setGuardTargetId(c.getGuardTargetId());
        v.setPermanentExtraAction(c.isPermanentExtraAction());
        v.setUndyingUsed(c.isUndyingUsed());
        v.setUndyingRounds(c.getUndyingRounds());
        v.setSpeedDice(c.getSpeedDice());
        v.setPermanentSpeedBonus(c.getPermanentSpeedBonus());
        v.setPhysicalResistance(c.getPhysicalResistance());
        v.setMagicResistance(c.getMagicResistance());
        v.setBaseDamageDice(c.getBaseDamageDice());
        v.setBaseDamageType(c.getBaseDamageType());
        v.setBlockDice(c.getBlockDice());
        v.setDodgePenalty(c.getDodgePenalty());
        v.setBaseActions(c.getBaseActions());
        v.setStatusEffects(c.getStatusEffects());
        v.setCooldowns(c.getCooldowns());
        v.setBonusDamage(c.getBonusDamage());
        v.setExtraActionsThisTurn(c.getExtraActionsThisTurn());
        v.setSkills(c.getSkills().stream().map(this::toSkillView).toList());
        if (c.getTemplate() != null) {
            if (c.getTemplate().getCorePassive() != null) {
                v.setCorePassiveName(c.getTemplate().getCorePassive().getType());
                v.setCorePassiveDescription(c.getTemplate().getCorePassive().getDescription());
            }
            v.setPerformance(c.getTemplate().getPerformance());
        }
        return v;
    }

    private SkillView toSkillView(SkillTemplate s) {
        SkillView v = new SkillView();
        v.setId(s.getId());
        v.setName(s.getName());
        v.setEnergyCost(s.getEnergyCost());
        v.setCooldown(s.getCooldown());
        v.setTargetType(s.getTargetType());
        v.setDescription(s.getDescription());
        v.setUpgraded(s.getUpgraded() != null);
        v.setEffects(s.getEffects());
        return v;
    }

    // ===================== record persistence =====================

    private void persistIfFinished(CombatState state) {
        if (!state.isOver() || state.getWinner() == null) {
            return;
        }
        if (!state.isPvp()) {
            persistRecord(state, state.getOwnerUsername(), CombatSide.PLAYER, null);
            return;
        }
        // both humans get their own record with their own perspective
        persistRecord(state, state.getOwnerUsername(), CombatSide.PLAYER, state.getGuestUsername());
        persistRecord(state, state.getGuestUsername(), CombatSide.ENEMY, state.getOwnerUsername());
    }

    private void persistRecord(CombatState state, String username, CombatSide mySide, String opponentUsername) {
        User user = authService.findByUsername(username);
        // idempotent: the timeout sweeper and later API calls must not double-save
        if (recordRepository.existsByBattleIdAndUserId(state.getId(), user.getId())) {
            return;
        }
        BattleRecord record = new BattleRecord();
        record.setUserId(user.getId());
        record.setBattleId(state.getId());
        record.setPackId(state.getPackId() == null ? "test-1" : state.getPackId());
        record.setWinner(state.getWinner());
        record.setMySide(mySide.name());
        record.setOpponentUsername(opponentUsername);
        record.setRounds(state.getRound());
        record.setPlayerCharacterIds(state.side(mySide).stream()
                .map(Combatant::getTemplateId).toList());
        computeStats(state, record, mySide);
        try {
            record.setLogJson(objectMapper.writeValueAsString(state.getLogs()));
        } catch (JsonProcessingException e) {
            record.setLogJson("[]");
        }
        recordRepository.save(record);
    }

    /**
     * Stats over the user's team damage to the enemy side (solo: the dummy).
     */
    private void computeStats(CombatState state, BattleRecord record, CombatSide mySide) {
        Set<String> enemyIds = state.side(CombatState.opposite(mySide)).stream()
                .map(Combatant::getId)
                .collect(Collectors.toSet());
        int total = 0;
        int maxHit = 0;
        for (CombatEvent event : state.getLogs()) {
            if ("damage".equals(event.getType())) {
                Object target = event.getData().get("target");
                Object hpDamage = event.getData().get("hpDamage");
                boolean onEnemy = state.isPvp()
                        ? target instanceof String s && enemyIds.contains(s)
                        : "dummy".equals(target);
                if (onEnemy && hpDamage instanceof Number n) {
                    int dmg = n.intValue();
                    total += dmg;
                    maxHit = Math.max(maxHit, dmg);
                }
            }
        }
        record.setTotalDamageDealt(total);
        record.setMaxSingleHit(maxHit);
        record.setAvgDamagePerRound(record.getRounds() > 0
                ? Math.round(total * 10.0 / record.getRounds()) / 10.0 : 0.0);
    }
}
