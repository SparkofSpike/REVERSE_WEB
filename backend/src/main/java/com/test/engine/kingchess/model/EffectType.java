package com.test.engine.kingchess.model;

/**
 * Special-effects-round actions (contract §2.6).
 *
 * <p>Movement actions ({@link #isMovement()}) are settled before recall actions
 * ({@code DEFAULT-7}: "逐项线性结算，先公棋后私棋").
 */
public enum EffectType {
    /** A Horse of your own on the table steps one cell along the arrow ring. */
    HORSE_STEP(true),
    /** A Chariot of your own on the table moves to any empty cell of its Field. */
    CHARIOT_MOVE(true),
    /** A Knight of your own on the table is recalled into your hand. */
    KNIGHT_RECALL(false),
    /** A Strategist of your own recalls any piece of yours from the table. */
    STRATEGIST_RECALL(false);

    private final boolean movement;

    EffectType(boolean movement) {
        this.movement = movement;
    }

    public boolean isMovement() {
        return movement;
    }

    /** Parses a contract action type; {@code null} when unknown. */
    public static EffectType parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (EffectType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }
}
