package com.test.engine.dto.kingchess;

/**
 * One piece on the table (contract §4).
 *
 * @param id          globally unique, stable piece id
 * @param kind        piece kind name
 * @param ownerSeat   owning seat, or null for pieces in the central Court
 * @param dropOrdinal global drop order; 0 for pieces still in the Court or in a hand
 */
public record PieceView(
        long id,
        String kind,
        Integer ownerSeat,
        long dropOrdinal) {
}
