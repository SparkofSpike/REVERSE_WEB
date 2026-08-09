package com.test.engine.combat;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A single decision submitted for one combatant: either a base action or a
 * skill use, plus optional targets.
 */
@Getter
@Setter
public class ActionDecision {

    private String combatantId;
    /** ActionType name (ATTACK...) or "SKILL". */
    private String actionType;
    /** Skill id when actionType is SKILL. */
    private String skillId;
    /** Target combatant id; null for self-targeted or auto decisions. */
    private String targetId;
    /** Multi-target list (skills with count > 1); empty falls back to targetId. */
    private List<String> targetIds = new ArrayList<>();

    public static ActionDecision base(String combatantId, String actionType, String targetId) {
        ActionDecision d = new ActionDecision();
        d.setCombatantId(combatantId);
        d.setActionType(actionType);
        d.setTargetId(targetId);
        return d;
    }

    public static ActionDecision skill(String combatantId, String skillId, String targetId) {
        ActionDecision d = new ActionDecision();
        d.setCombatantId(combatantId);
        d.setActionType("SKILL");
        d.setSkillId(skillId);
        d.setTargetId(targetId);
        return d;
    }

    public boolean isSkill() {
        return "SKILL".equalsIgnoreCase(actionType);
    }

    /** Effective target ids: explicit list, else the single target, else empty. */
    public List<String> effectiveTargetIds() {
        if (targetIds != null && !targetIds.isEmpty()) {
            return targetIds;
        }
        if (targetId != null) {
            return List.of(targetId);
        }
        return List.of();
    }
}
