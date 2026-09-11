package com.test.engine.dto.kingchess;

import java.util.List;
import java.util.Map;

/**
 * Full public view of a King's Chess game (contract §4 — field names are frozen
 * and must match the frontend verbatim).
 *
 * @param gameId                session id
 * @param phase                 WAITING|PLACING|RESOLVING|EFFECTS|ROUND_END|FINISHED
 * @param roundNo               1-based round counter
 * @param finished              true once the game is over
 * @param winnerSeat            winning seat, or null
 * @param scoreToWin            score target (50)
 * @param players               per-seat player views
 * @param fields                NORTH|EAST|SOUTH|WEST → exactly 4 cells each (PieceView or null)
 * @param court                 remaining public pieces per kind
 * @param submittedSeats        seats that submitted deployments this round
 * @param submittedEffectSeats  seats that submitted effect actions this round
 * @param lastRolls             seat (as string) → d20 value of the last settlement
 * @param lastDropOrder         seats in drop order, first drop first
 * @param events                event log of the last settlement (English)
 * @param eliminatedSeats       seats that are out of the game
 */
public record KingGameView(
        String gameId,
        String phase,
        int roundNo,
        boolean finished,
        Integer winnerSeat,
        int scoreToWin,
        List<KingPlayerView> players,
        Map<String, List<PieceView>> fields,
        Map<String, Integer> court,
        List<Integer> submittedSeats,
        List<Integer> submittedEffectSeats,
        Map<String, Integer> lastRolls,
        List<Integer> lastDropOrder,
        List<String> events,
        List<Integer> eliminatedSeats) {
}
