package com.test.engine.dto.kingchess;

import java.util.List;

/**
 * One seat's public state (contract §4).
 *
 * @param seat       seat index (0-3)
 * @param side       NORTH|EAST|SOUTH|WEST
 * @param score      current score
 * @param kingLives  remaining King lives (starts at 5)
 * @param eliminated true once the seat is out of the game
 * @param hand       piece kinds currently in hand (repeats allowed)
 */
public record KingPlayerView(
        int seat,
        String side,
        int score,
        int kingLives,
        boolean eliminated,
        List<String> hand) {
}
