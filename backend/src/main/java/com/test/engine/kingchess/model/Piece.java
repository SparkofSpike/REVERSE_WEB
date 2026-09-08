package com.test.engine.kingchess.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single chess piece on the board or in a hand.
 *
 * <p>{@code ownerSeat} is null for pieces sitting in the shared Court pool;
 * {@code dropOrdinal} is the global drop counter used to decide "later drop
 * eats earlier drop" on a shared cell.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Piece {
    private PieceKind kind;
    private Integer ownerSeat;
    private long dropOrdinal;
}
