package com.test.engine.dto.kingchess;

/**
 * Request body of {@code POST /api/kingchess/games} (contract §3.1).
 *
 * @param playerCount 2..4 (contract §2.1, DEFAULT-1)
 */
public record CreateGameRequest(Integer playerCount) {
}
