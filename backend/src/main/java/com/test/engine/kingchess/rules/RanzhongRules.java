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

    /** Score needed to win. Working value; client has not overridden it. */
    public static final int SCORE_TO_WIN = 50;

    /**
     * King lives. The King is returned to hand when eaten; when the player has
     * no usable piece left the King returns to hand and loses one life;
     * when the hand is completely empty (King out of lives / nothing to drop)
     * the player is eliminated. Confirmed by the client, 2026-09-09.
     */
    public static final int KING_LIVES = 5;

    /** King eats any piece for +3. */
    public static final int KING_EAT_SCORE = 3;

    /**
     * Queen owner scores when she is eaten — fixed 8 points.
     * Confirmed by the client, 2026-09-09 (corrects the manual's ``rd12'' typo).
     */
    public static final int QUEEN_EAT_SCORE = 8;

    /**
     * Per-round public refresh count range: each round spawns 1–5 public
     * pieces across the four Fields (count random within the range).
     * Confirmed by the client, 2026-09-09.
     */
    public static final int REFRESH_MIN_COUNT = 1;
    public static final int REFRESH_MAX_COUNT = 5;

    /**
     * Special-effects window (Horse / Chariot / Knight / Strategist / Martyr):
     * every player shares a unified action limit of 16 seconds, after which the
     * window ends. Confirmed by the client, 2026-09-09.
     */
    public static final int SPECIAL_EFFECTS_TIME_LIMIT_SECONDS = 16;

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
            // HORSE / CHARIOT / KNIGHT award no direct score (their value is the
            // special-effect trigger); KING's eat score is handled above;
            // MARTYR / STRATEGIST are private and have no eat score.
            default -> new ScoreEvent(true, 0);
        };
    }

    public record ScoreEvent(boolean toEater, int amount) {
    }
}
