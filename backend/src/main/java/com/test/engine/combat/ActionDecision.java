package com.test.engine.combat;

import lombok.Getter;
import lombok.Setter;

/**
 * A single decision submitted for one combatant: either a base action or a
 * skill use, plus an optional target.
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
}
