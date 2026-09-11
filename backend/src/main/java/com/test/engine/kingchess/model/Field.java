package com.test.engine.kingchess.model;

import lombok.Getter;

/** A player's 4-cell lane on one side of the rotational board. */
@Getter
public class Field {
    private final Side side;
    /** Cell count shares {@link Side#CELL_COUNT}, the same source the service's bounds check uses. */
    private final Piece[] cells = new Piece[Side.CELL_COUNT];

    public Field(Side side) {
        this.side = side;
    }

    /** Occupant of {@code cellIndex} (0-3), or null if empty. */
    public Piece at(int cellIndex) {
        return cells[cellIndex];
    }

    public void place(int cellIndex, Piece piece) {
        cells[cellIndex] = piece;
    }
}
