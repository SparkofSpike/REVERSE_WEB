package com.test.engine.kingchess.model;

/** Lifecycle of a single King's Chess game. */
public enum GamePhase {
    /** Waiting for players to join. */
    WAITING,
    /** Secret drop phase — players decide placements, not yet revealed. */
    PLACING,
    /** Open resolution phase — d20 order applied, same-cell eats resolved. */
    RESOLVING,
    /**
     * Special-effects window (Horse / Chariot / Knight / Strategist / Martyr).
     * Everyone acts within a unified 16-second limit, then the window closes.
     * Confirmed by the client, 2026-09-09.
     */
    EFFECTS,
    /** A round finished; transitioning to the next. */
    ROUND_END,
    /** Game over (score reached or opponents drained). */
    FINISHED
}
