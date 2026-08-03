package com.test.engine.enums;

/**
 * Base actions a combatant can take on its turn.
 *
 * <p>ATTACK/DEFEND/DODGE/GUARD/COUNTER are the five universal actions.
 * CHASE and PRAY are character-exclusive actions granted by specific
 * character templates.
 */
public enum ActionType {
    ATTACK("攻击"),
    DEFEND("防御"),
    DODGE("闪避"),
    GUARD("守护"),
    COUNTER("反击"),
    CHASE("追击"),
    PRAY("祈思");

    private final String label;

    ActionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
