package com.test.engine.kingchess.rules;

import com.test.engine.kingchess.model.PieceKind;

import java.util.List;
import java.util.Map;

/**
 * Rule constants and scoring mapping for the Ranzhong Dui rule set of King's
 * Chess. All values that the client manual leaves fuzzy or open are gathered
 * here with a {@code TODO(client)} marker so a single change point drives the
 * whole engine; the rules themselves (same-cell order, recycling, win checks)
 * live in {@code KingResolver}.
 */
public final class RanzhongRules {

    private RanzhongRules() {
    }

    /** Minimum / maximum players allowed. */
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;

    /** Score needed to win. TODO(client): confirm (50 is the working value). */
    public static final int SCORE_TO_WIN = 50;

    /** King lives; the King is returned to hand when eaten. TODO(client): confirm counter behavior. */
    public static final int KING_LIVES = 5;

    /** King eats any piece for +3. */
    public static final int KING_EAT_SCORE = 3;

    /** Queen owner scores when she is eaten. TODO(client): "rd12" typo — 8 vs 1d12. */
    public static final int QUEEN_EAT_SCORE = 8;

    public static final int PROVISION_SCORE = 4;
    public static final int SOLDIER_SCORE = 2;

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
     * TODO(client): spawn weights for the four Fields' public refresh.
     * Provisional equal-ish weights; confirm exact rates.
     */
    public static final Map<PieceKind, Double> SPAWN_WEIGHTS =
            Map.of(
                    PieceKind.PROVISION, 0.40,
                    PieceKind.SOLDIER, 0.30,
                    PieceKind.HORSE, 0.15,
                    PieceKind.CHARIOT, 0.10,
                    PieceKind.KNIGHT, 0.05);

    /**
     * Score awarded when {@code eaterKind} eats {@code victimKind}.
     *
     * @return a ScoreEvent: {@code toEater} true → the eater's owner scores;
     *         false → the victim's owner scores (e.g. a Queen owner points
     *         when her Queen is eaten).
     */
    public static ScoreEvent eatScore(PieceKind eaterKind, PieceKind victimKind) {
        if (eaterKind == PieceKind.KING) {
            return new ScoreEvent(true, KING_EAT_SCORE);
        }
        return switch (victimKind) {
            case PROVISION -> new ScoreEvent(true, PROVISION_SCORE);
            case SOLDIER -> new ScoreEvent(true, SOLDIER_SCORE);
            case QUEEN -> new ScoreEvent(false, QUEEN_EAT_SCORE);
            // TODO(client): HORSE / CHARIOT / KNIGHT / KING / MARTYR / STRATEGIST
            // currently award no direct score; confirm with the client.
            default -> new ScoreEvent(true, 0);
        };
    }

    public record ScoreEvent(boolean toEater, int amount) {
    }
}
