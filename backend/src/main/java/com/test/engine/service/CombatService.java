package com.test.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.combat.ActionDecision;
import com.test.engine.combat.CombatEngine;
import com.test.engine.combat.CombatEvent;
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

/**
 * Battle facade for the API layer: delegates to the engine, converts states
 * to frontend views and persists finished battles with stats.
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
        return toView(state);
    }

    public CombatView get(String username, String battleId) {
        return toView(owned(username, battleId));
    }

    public CombatView selectInitialPerk(String username, String battleId, String perkId) {
        CombatState state = owned(username, battleId);
        engine.selectInitialPerk(battleId, perkId);
        persistIfFinished(state);
        return toView(state);
    }

    public CombatView decide(String username, String battleId, List<ActionDecision> decisions) {
        CombatState state = owned(username, battleId);
        engine.decide(battleId, decisions);
        persistIfFinished(state);
        return toView(state);
    }

    public CombatView decideExtraActions(String username, String battleId, List<ActionDecision> decisions) {
        CombatState state = owned(username, battleId);
        engine.decideExtraActions(battleId, decisions);
        persistIfFinished(state);
        return toView(state);
    }

    public CombatView skipExtraActions(String username, String battleId) {
        CombatState state = owned(username, battleId);
        engine.skipExtraActions(battleId);
        persistIfFinished(state);
        return toView(state);
    }

    public CombatView playCard(String username, String battleId, String skillId, String targetId) {
        CombatState state = owned(username, battleId);
        engine.playGenericSkill(battleId, skillId, targetId);
        return toView(state);
    }

    public CombatView selectSpecialPerk(String username, String battleId, String perkId) {
        CombatState state = owned(username, battleId);
        engine.selectSpecialPerk(battleId, perkId);
        persistIfFinished(state);
        return toView(state);
    }

    public CombatView skipSpecialPerk(String username, String battleId) {
        CombatState state = owned(username, battleId);
        engine.skipSpecialPerk(battleId);
        persistIfFinished(state);
        return toView(state);
    }

    private CombatState owned(String username, String battleId) {
        CombatState state = engine.getBattle(battleId);
        if (!state.getOwnerUsername().equals(username)) {
            throw new BusinessException("无权访问该战斗");
        }
        return state;
    }

    // ===================== view conversion =====================

    private CombatView toView(CombatState state) {
        CombatView view = new CombatView();
        view.setId(state.getId());
        view.setOwnerUsername(state.getOwnerUsername());
        view.setPhase(state.getPhase());
        view.setRound(state.getRound());
        view.setWinner(state.getWinner());
        view.setFirstStrikeSide(state.getFirstStrikeSide());
        view.setPlayerDrawEnergy(state.getPlayerDrawEnergy());
        view.setPlayerHand(state.getPlayerHand());
        view.setInitialPerkOptions(state.getInitialPerkOptions());
        view.setSpecialPerkOptions(state.getSpecialPerkOptions());
        view.setSpecialPerkRoundsTaken(state.getSpecialPerkRoundsTaken());
        view.setExtraActionRound(state.isExtraActionRound());
        view.setLogs(state.getLogs());
        view.setCombatants(state.getCombatants().stream().map(this::toCombatantView).toList());
        return view;
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
        User user = authService.findByUsername(state.getOwnerUsername());
        BattleRecord record = new BattleRecord();
        record.setUserId(user.getId());
        record.setBattleId(state.getId());
        record.setPackId("test-1");
        record.setWinner(state.getWinner());
        record.setRounds(state.getRound());
        record.setPlayerCharacterIds(state.side(CombatSide.PLAYER).stream()
                .map(Combatant::getTemplateId).toList());
        computeStats(state, record);
        try {
            record.setLogJson(objectMapper.writeValueAsString(state.getLogs()));
        } catch (JsonProcessingException e) {
            record.setLogJson("[]");
        }
        recordRepository.save(record);
    }

    /**
     * Stats over the player team's damage to the dummy.
     */
    private void computeStats(CombatState state, BattleRecord record) {
        int total = 0;
        int maxHit = 0;
        for (CombatEvent event : state.getLogs()) {
            if ("damage".equals(event.getType())) {
                Object target = event.getData().get("target");
                Object hpDamage = event.getData().get("hpDamage");
                if ("dummy".equals(target) && hpDamage instanceof Number n) {
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
