package com.test.engine.kingchess.model;

/**
 * One player's secret placement for the round: put a piece of
 * {@code pieceKind} on {@code field side}, {@code cellIndex}.
 */
public record Deployment(int seat, Side side, int cellIndex, PieceKind pieceKind) {
}
