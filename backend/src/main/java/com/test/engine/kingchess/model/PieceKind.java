package com.test.engine.kingchess.model;

/**
 * Chess piece kinds in the Ranzhong Dui rule set of King's Chess.
 *
 * <p>Private pieces belong to a single player; public pieces live in the
 * central Court pool and are recycled there when eaten (King excepted).
 */
public enum PieceKind {
    // private pieces (player-owned)
    KING(true),
    QUEEN(true),
    MARTYR(true),
    STRATEGIST(true),
    // public pieces (shared pool)
    PROVISION(false),
    SOLDIER(false),
    HORSE(false),
    CHARIOT(false),
    KNIGHT(false);

    private final boolean privatePiece;

    PieceKind(boolean privatePiece) {
        this.privatePiece = privatePiece;
    }

    /** True for player-owned (private) pieces, false for the shared pool. */
    public boolean isPrivatePiece() {
        return privatePiece;
    }
}
