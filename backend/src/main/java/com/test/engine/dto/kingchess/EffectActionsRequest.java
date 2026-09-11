package com.test.engine.dto.kingchess;

import java.util.List;

/**
 * Request body of {@code POST /api/kingchess/games/{gameId}/effects}
 * (contract §3.5). Submitting again overwrites the seat's whole action list;
 * an empty list is the frontend's timeout submission.
 *
 * @param seat    the acting seat
 * @param actions the seat's effect actions for this round
 */
public record EffectActionsRequest(Integer seat, List<EffectActionItem> actions) {

    /**
     * @param type            HORSE_STEP|CHARIOT_MOVE|KNIGHT_RECALL|STRATEGIST_RECALL
     * @param pieceId         the acting piece (must be on the table and owned by the seat)
     * @param targetCellIndex CHARIOT_MOVE only — destination cell inside the same field
     * @param targetPieceId   STRATEGIST_RECALL only — the seat's own piece to recall
     */
    public record EffectActionItem(String type, Long pieceId, Integer targetCellIndex,
                                   Long targetPieceId) {
    }
}
