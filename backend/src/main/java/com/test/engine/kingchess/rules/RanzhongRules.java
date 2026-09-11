package com.test.engine.kingchess.rules;

import com.test.engine.kingchess.model.PieceKind;

import java.util.List;
import java.util.Map;

/**
 * Rule constants and scoring mapping for the Ranzhong Dui rule set of King's
 * Chess. All values that the client manual leaves fuzzy or open are gathered
 * here with a {@code DEFAULT-n} marker pointing back at
 * {@code docs/king-chess-m1-contract.md}; the rules themselves (same-cell
 * order, capture destinations, win checks) live in {@code KingResolver} /
 * {@code EffectResolver}.
 */
public final class RanzhongRules {

    private RanzhongRules() {
    }

    /** Minimum / maximum players allowed. */
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;

    /** Score needed to win. Working value; client has not overridden it. */
    public static final int SCORE_TO_WIN = 50;

    /**
     * King lives at the start of a game (contract §2.3).
     * The King is returned to hand when eaten (burning one life); when the
     * player has no usable piece left the King returns to hand and loses one
     * life; when the hand is completely empty and no lives remain the player is
     * eliminated (contract §2.4).
     */
    public static final int STARTING_KING_LIVES = 5;

    /** Alias kept for earlier callers. */
    public static final int KING_LIVES = STARTING_KING_LIVES;

    /** King eats any piece for a flat +3, overriding the victim's own value. */
    public static final int KING_EAT_SCORE = 3;

    /**
     * Queen owner scores when she is eaten — fixed 8 points.
     * Confirmed by the client, 2026-09-09 (corrects the manual's ``rd12'' typo).
     */
    public static final int QUEEN_EAT_SCORE = 8;

    /**
     * Per-round public refresh count range: each round spawns 1–5 public
     * pieces across the four Fields (count random within the range).
     * Confirmed by the client, 2026-09-09; contract §2.5-1 (its unnumbered
     * {@code DEFAULT:} clause — NOT {@code DEFAULT-4}, which is where a captured
     * public piece goes).
     */
    public static final int REFRESH_MIN_COUNT = 1;
    public static final int REFRESH_MAX_COUNT = 5;

    /**
     * Special-effects window (Horse / Chariot / Knight / Strategist / Martyr):
     * every player shares a unified action limit of 16 seconds, after which the
     * window ends. Contract §2.5-5 / §2.6 ({@code DEFAULT-9}: the backend only
     * collects actions and settles once everyone has submitted).
     *
     * <p>规则值，计时由前端展示层实现 — the contract keeps the 16-second countdown in
     * the presentation layer (contract §5, {@code DEFAULT-9}); the backend only collects
     * actions and never drives the clock second by second.
     */
    public static final int SPECIAL_EFFECTS_TIME_LIMIT_SECONDS = 16;

    public static final int PROVISION_SCORE = 4;
    public static final int SOLDIER_SCORE = 2;

    /** d20 sides used for the drop-order roll (contract §2.5-3). */
    public static final int D20_SIDES = 20;

    /** Starting private hand. */
    public static final List<PieceKind> STARTING_PRIVATE_HAND =
            List.of(PieceKind.KING, PieceKind.QUEEN, PieceKind.MARTYR, PieceKind.STRATEGIST);

    /** Public pool composition per game (8 Provisions / 6 Soldiers / 4 Horses / 4 Chariots / 2 Knights). */
    public static final Map<PieceKind, Integer> PUBLIC_POOL_COUNTS =
            Map.of(
                    PieceKind.PROVISION, 8,
                    PieceKind.SOLDIER, 6,
                    PieceKind.HORSE, 4,
                    PieceKind.CHARIOT, 4,
                    PieceKind.KNIGHT, 2);

    /**
     * Spawn weights for the four Fields' public refresh. The client delegated
     * the exact rates to us (2026-09-09) and only fixed the per-round spawn
     * count (REFRESH_MIN_COUNT..REFRESH_MAX_COUNT); these weights are our
     * working default and should be tuned during M1 playtest.
     */
    public static final Map<PieceKind, Double> SPAWN_WEIGHTS =
            Map.of(
                    PieceKind.PROVISION, 0.40,
                    PieceKind.SOLDIER, 0.30,
                    PieceKind.HORSE, 0.15,
                    PieceKind.CHARIOT, 0.10,
                    PieceKind.KNIGHT, 0.05);

    /** Where a captured piece goes (contract §2.3, {@code DEFAULT-4}). */
    public enum CaptureTarget {
        /** Public Soldier / Horse / Chariot / Knight: the eater keeps the piece in hand. */
        EATER_HAND,
        /** Provision: scored by the eater and recycled to the central Court. */
        COURT,
        /** Eaten King: back to its owner's hand while lives remain. */
        OWNER_HAND,
        /** Any other private piece: permanently off the table. */
        REMOVED
    }

    /**
     * Score awarded when {@code eaterKind} eats {@code victimKind}.
     *
     * @return a ScoreEvent: {@code toEater} true → the eater's owner scores;
     *         false → the victim's owner scores (e.g. a Queen owner points
     *         when her Queen is eaten).
     */
    public static ScoreEvent eatScore(PieceKind eaterKind, PieceKind victimKind) {
        if (eaterKind == PieceKind.KING) {
            // contract §2.3: the King eats anything for a flat +3, even a Provision.
            return new ScoreEvent(true, KING_EAT_SCORE);
        }
        return switch (victimKind) {
            case PROVISION -> new ScoreEvent(true, PROVISION_SCORE);
            case SOLDIER -> new ScoreEvent(true, SOLDIER_SCORE);
            case QUEEN -> new ScoreEvent(false, QUEEN_EAT_SCORE);
            // HORSE / CHARIOT / KNIGHT award no direct score (their value is the
            // special-effect trigger); eating a KING scores nothing (the owner
            // simply burns a life); MARTYR / STRATEGIST are private and worth 0.
            default -> new ScoreEvent(true, 0);
        };
    }

    /**
     * Destination of a captured piece (contract §2.3, {@code DEFAULT-4}).
     * The manual's blanket "eaten public pieces are recycled to the Court" is
     * overridden for Soldier / Horse / Chariot / Knight, which the manual
     * explicitly hands to the eater.
     */
    public static CaptureTarget captureTarget(PieceKind victimKind) {
        return switch (victimKind) {
            case SOLDIER, HORSE, CHARIOT, KNIGHT -> CaptureTarget.EATER_HAND;
            case PROVISION -> CaptureTarget.COURT;
            case KING -> CaptureTarget.OWNER_HAND;
            default -> CaptureTarget.REMOVED;
        };
    }

    public record ScoreEvent(boolean toEater, int amount) {
    }
}
