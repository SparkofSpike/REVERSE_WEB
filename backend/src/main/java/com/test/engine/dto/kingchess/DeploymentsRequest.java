package com.test.engine.dto.kingchess;

import java.util.List;

/**
 * Request body of {@code POST /api/kingchess/games/{gameId}/deployments}
 * (contract §3.3). Submitting again overwrites the whole round submission for
 * the seat; an empty list means "no drop this round".
 *
 * <p>Cross-seat contests for the same cell are legal — they are exactly what
 * "later drop eats earlier drop" resolves; only duplicates inside one seat's own
 * submission are rejected (contract §3.3 errata).
 *
 * @param seat        the acting seat
 * @param deployments the seat's drops for this round
 */
public record DeploymentsRequest(Integer seat, List<DeploymentItem> deployments) {

    /**
     * @param side      NORTH|EAST|SOUTH|WEST
     * @param cellIndex 0..3
     * @param pieceKind a kind currently in the seat's hand
     */
    public record DeploymentItem(String side, Integer cellIndex, String pieceKind) {
    }
}
