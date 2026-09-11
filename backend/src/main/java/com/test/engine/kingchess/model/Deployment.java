package com.test.engine.kingchess.model;

/**
 * One player's secret placement for the round: put a piece of
 * {@code pieceKind} on {@code field side}, {@code cellIndex}.
 *
 * <p>{@code pieceId} is pre-allocated by the service so the submitted drop can
 * be rendered with a stable id before the round is resolved; a value of 0 means
 * "no id yet" (the resolver then allocates one).
 */
public record Deployment(int seat, Side side, int cellIndex, PieceKind pieceKind, long pieceId) {

    public Deployment(int seat, Side side, int cellIndex, PieceKind pieceKind) {
        this(seat, side, cellIndex, pieceKind, 0L);
    }
}
