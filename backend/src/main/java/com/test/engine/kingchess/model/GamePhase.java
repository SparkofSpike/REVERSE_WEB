package com.test.engine.kingchess.model;

/** Lifecycle of a single King's Chess game. */
public enum GamePhase {
    /** Waiting for players to join. */
    WAITING,
    /** Secret drop phase — players decide placements, not yet revealed. */
    PLACING,
    /** Open resolution phase — d20 order applied, same-cell eats resolved. */
    RESOLVING,
    /** A round finished; transitioning to the next. */
    ROUND_END,
    /** Game over (score reached or opponents drained). */
    FINISHED
}
