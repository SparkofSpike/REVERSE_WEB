package com.test.engine.kingchess.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Per-player state: seat id, score and current hand (private + acquired public pieces). */
@Getter
@Setter
public class PlayerState {
    private int seat;
    private int score;
    private final List<PieceKind> hand = new ArrayList<>();

    public PlayerState(int seat, List<PieceKind> startingHand) {
        this.seat = seat;
        this.hand.addAll(startingHand);
    }

    public boolean hasNoPiecesLeft() {
        return hand.isEmpty();
    }
}
