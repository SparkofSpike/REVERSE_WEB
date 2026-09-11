package com.test.engine.kingchess.model;

/**
 * A cardinal side of the rotational board; each side hosts one Field.
 *
 * <p>Seats map clockwise onto the sides: seat 0 → NORTH, 1 → EAST, 2 → SOUTH,
 * 3 → WEST (contract §2.1, {@code DEFAULT-1}: a 2-player game uses seats 0/1,
 * a 3-player game seats 0/1/2).
 */
public enum Side {
    NORTH,
    EAST,
    SOUTH,
    WEST;

    /** Number of cells in every Field (contract §2.1). */
    public static final int CELL_COUNT = 4;

    /** Seat index of this side (NORTH = 0 … WEST = 3). */
    public int seat() {
        return ordinal();
    }

    public static Side ofSeat(int seat) {
        Side[] values = values();
        if (seat < 0 || seat >= values.length) {
            throw new IllegalArgumentException("no side for seat " + seat);
        }
        return values[seat];
    }

    /**
     * Next cell along the arrow ring of a Field: {@code 0 → 1 → 2 → 3 → 0}
     * (contract §2.2, {@code DEFAULT-2}). Wraps around.
     */
    public static int nextCell(int cellIndex) {
        return Math.floorMod(cellIndex + 1, CELL_COUNT);
    }
}
