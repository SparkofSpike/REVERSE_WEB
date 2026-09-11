package com.test.engine.kingchess.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single chess piece instance.
 *
 * <p>{@code id} is the globally unique, stable identity handed to the frontend
 * ({@code PieceView.id}), {@code ownerSeat} is null for pieces sitting in the
 * shared Court pool, and {@code dropOrdinal} is the global drop counter used to
 * decide "later drop eats earlier drop" on a shared cell (0 = still in the
 * Court pool or in a hand).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Piece {
    private long id;
    private PieceKind kind;
    private Integer ownerSeat;
    private long dropOrdinal;

    /** Ids are handed out by {@code KingGame#nextPieceId()}. */
    public Piece(long id, PieceKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public boolean ownedBy(int seat) {
        return ownerSeat != null && ownerSeat == seat;
    }
}
