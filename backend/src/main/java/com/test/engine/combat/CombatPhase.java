package com.test.engine.combat;

/**
 * High level battle phases driven by the state machine.
 */
public enum CombatPhase {
    /** Player deploys characters and picks the initial perk. */
    SETUP,
    /** Initial perk three-choice selection. */
    INITIAL_PERK,
    /** Round begins: initiative roll, card draw, status ticks. */
    ROUND_START,
    /** Both sides submit their decisions (fog of war). */
    DECISION,
    /** Unified speed resolution, including last-dash ties. */
    SPEED,
    /** Actions execute in speed order. */
    EXECUTION,
    /** Special perk round (every 4 rounds, up to 3 times). */
    SPECIAL_PERK,
    /** Round ends: energy settlement, shield decay, draws. */
    ROUND_END,
    /** Battle over. */
    FINISHED
}
