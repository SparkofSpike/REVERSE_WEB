package com.test.engine.kingchess.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player state: seat id, score, remaining King lives, elimination flag and
 * the current hand (private pieces plus acquired public pieces).
 */
@Getter
@Setter
public class PlayerState {
    private int seat;
    private int score;
    private final List<PieceKind> hand = new ArrayList<>();

    /** King lives, 5 by default; every King loss burns one (0 = no more returns). */
    private int kingLives = com.test.engine.kingchess.rules.RanzhongRules.STARTING_KING_LIVES;

    /** True once the player is out of the game (no pieces left and no King lives). */
    private boolean eliminated;

    public PlayerState(int seat, List<PieceKind> startingHand) {
        this.seat = seat;
        this.hand.addAll(startingHand);
    }

    public int countInHand(PieceKind kind) {
        return (int) hand.stream().filter(k -> k == kind).count();
    }
}
