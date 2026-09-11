package com.test.engine.kingchess.model;

/**
 * One submitted special-effects action (contract §3.5).
 *
 * @param type            the effect to run
 * @param pieceId         the acting piece (must belong to the submitting seat and be on the table)
 * @param targetCellIndex {@code CHARIOT_MOVE} only — destination cell inside the same Field
 * @param targetPieceId   {@code STRATEGIST_RECALL} only — the own piece to bring back to hand
 */
public record EffectAction(EffectType type, Long pieceId, Integer targetCellIndex, Long targetPieceId) {
}
