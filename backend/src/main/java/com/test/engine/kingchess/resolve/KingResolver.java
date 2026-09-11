package com.test.engine.kingchess.resolve;

import com.test.engine.kingchess.model.Board;
import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.PlayerState;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.rules.RanzhongRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Adjudication for the drop half of a round (contract §2.3 / §2.5).
 *
 * <p>Pure logic, no randomness: the d20 values are supplied by the caller so the
 * resolver stays deterministic and unit-testable. It applies the drop order,
 * the same-cell "later drop eats earlier drop" rule, the Martyr substitution,
 * capture destinations, scoring, elimination and the win checks.
 */
public final class KingResolver {

    private KingResolver() {
    }

    /**
     * Orders seats by their d20 value descending (highest drops first).
     * Ties break by ascending seat id (contract §2.5-3, {@code DEFAULT-6}).
     */
    public static List<Integer> orderByD20(Map<Integer, Integer> d20BySeat) {
        return d20BySeat.keySet().stream()
                .sorted(Comparator
                        .comparingInt((Integer seat) -> d20BySeat.get(seat)).reversed()
                        .thenComparingInt(seat -> seat))
                .toList();
    }

    /**
     * Resolves one round: applies every player's secret deployments in d20 order,
     * settles same-cell eats (later drop eats earlier drop), scores, recycles /
     * hands over captured pieces, burns King lives, then evaluates the win
     * conditions and moves the phase to {@code EFFECTS}, {@code ROUND_END} or
     * {@code FINISHED}.
     */
    public static RoundResult resolve(KingGame game, Map<Integer, Integer> d20BySeat) {
        List<String> events = new ArrayList<>();
        List<Integer> order = orderByD20(d20BySeat);

        game.getLastRolls().clear();
        game.getLastRolls().putAll(d20BySeat);
        game.getLastDropOrder().clear();
        game.getLastDropOrder().addAll(order);

        for (Integer seat : order) {
            for (Deployment dep : List.copyOf(game.deploymentsOf(seat))) {
                applyDrop(game, dep, events);
            }
        }

        Integer winner = evaluateWinner(game);
        if (winner != null) {
            game.setPhase(GamePhase.FINISHED);
            game.setWinnerSeat(winner);
            events.add("Player " + winner + " wins");
        } else if (hasExecutableEffects(game)) {
            game.setPhase(GamePhase.EFFECTS);
        } else {
            game.setPhase(GamePhase.ROUND_END);
        }

        game.getEvents().clear();
        game.getEvents().addAll(events);
        game.clearPending();
        return new RoundResult(winner != null, winner, events);
    }

    /** Applies one deployment, eating the occupant of the target cell when there is one. */
    private static void applyDrop(KingGame game, Deployment dep, List<String> events) {
        PlayerState owner = game.player(dep.seat());
        if (!owner.getHand().remove(dep.pieceKind())) {
            events.add("Player " + dep.seat() + " could not drop " + dep.pieceKind()
                    + " (not in hand)");
            return;
        }
        Piece dropped = new Piece(
                dep.pieceId() != 0L ? dep.pieceId() : game.nextPieceId(),
                dep.pieceKind(), dep.seat(), game.nextDropOrdinal());
        Board board = game.getBoard();
        Piece occupant = board.field(dep.side()).at(dep.cellIndex());
        boolean alreadyPlaced = false;
        if (occupant != null) {
            // contract §2.5-4: 同一格「后落吃先落」
            alreadyPlaced = resolveEat(game, dropped, occupant, events);
        }
        if (!alreadyPlaced) {
            board.field(dep.side()).place(dep.cellIndex(), dropped);
        }
        events.add("Player " + dep.seat() + " dropped " + dep.pieceKind()
                + " on " + dep.side() + "-" + dep.cellIndex());
    }

    /**
     * Settles {@code eater} eating {@code victim}.
     *
     * @return true when the eater has already been placed on the table (Martyr
     *         substitution placed it on the Martyr's cell)
     */
    private static boolean resolveEat(KingGame game, Piece eater, Piece victim, List<String> events) {
        // contract §2.3 / DEFAULT-3: while a Martyr is in play anywhere on the
        // table ("在其拥有者场上的期间" — the design doc §2 glosses this as
        // "落地后己方所有棋子受保护"), every piece of that owner is protected: the
        // capture is swapped onto the Martyr, which is then removed. The
        // substitution does not chain — the eater simply takes the Martyr's cell.
        if (victim.getKind() != PieceKind.MARTYR && victim.getOwnerSeat() != null) {
            Piece martyr = findPieceOnField(game, victim.getOwnerSeat(), PieceKind.MARTYR);
            if (martyr != null) {
                int victimOwnerSeat = victim.getOwnerSeat();
                Board board = game.getBoard();
                Board.Cell martyrCell = locationOf(board, martyr);
                board.removePiece(martyr);
                if (martyrCell != null) {
                    board.field(martyrCell.side()).place(martyrCell.cellIndex(), eater);
                }
                scoreCapture(game, eater, martyr, events);
                events.add("Player " + victimOwnerSeat + "'s Martyr died in place of "
                        + victim.getKind() + " (eaten by Player " + eater.getOwnerSeat() + ")");
                return true;
            }
        }

        Integer victimOwner = victim.getOwnerSeat();
        events.add("Player " + eater.getOwnerSeat() + " ate " + victim.getKind()
                + (victimOwner == null ? " from the Court" : " of Player " + victimOwner)
                + " with " + eater.getKind());
        scoreCapture(game, eater, victim, events);
        return false;
    }

    /** Scores a capture and routes the captured piece (contract §2.3, DEFAULT-4). */
    private static void scoreCapture(KingGame game, Piece eater, Piece victim, List<String> events) {
        RanzhongRules.ScoreEvent score = RanzhongRules.eatScore(eater.getKind(), victim.getKind());
        if (score.amount() != 0) {
            if (score.toEater()) {
                PlayerState eaterOwner = game.player(eater.getOwnerSeat());
                eaterOwner.setScore(eaterOwner.getScore() + score.amount());
            } else if (victim.getOwnerSeat() != null) {
                PlayerState victimOwner = game.player(victim.getOwnerSeat());
                victimOwner.setScore(victimOwner.getScore() + score.amount());
            }
        }

        switch (RanzhongRules.captureTarget(victim.getKind())) {
            case EATER_HAND -> {
                game.player(eater.getOwnerSeat()).getHand().add(victim.getKind());
                victim.setOwnerSeat(eater.getOwnerSeat());
                events.add("Captured " + victim.getKind() + " joins Player "
                        + eater.getOwnerSeat() + "'s hand");
            }
            case COURT -> {
                victim.setOwnerSeat(null);
                victim.setDropOrdinal(0);
                game.getBoard().getCourt().add(victim);
                events.add("Captured " + victim.getKind() + " recycled to the Court");
            }
            case OWNER_HAND -> {
                if (victim.getOwnerSeat() == null) {
                    break;
                }
                PlayerState victimOwner = game.player(victim.getOwnerSeat());
                victimOwner.setKingLives(victimOwner.getKingLives() - 1);
                if (victimOwner.getKingLives() > 0) {
                    victimOwner.getHand().add(PieceKind.KING);
                    events.add("King returns to Player " + victim.getOwnerSeat()
                            + "'s hand (" + victimOwner.getKingLives() + " lives left)");
                } else {
                    victimOwner.setKingLives(0);
                    events.add("Player " + victim.getOwnerSeat()
                            + " loses the King for good (no lives left)");
                }
            }
            case REMOVED -> events.add("Captured private piece removed ("
                    + victim.getKind() + ")");
        }
    }

    /**
     * Contract §2.4: at the start of a round, every player with an empty hand
     * gets the King back and loses one life; with no life left they are
     * eliminated.
     *
     * <p>"No usable piece" means an EMPTY HAND, not "nothing left on the table".
     * An empty hand is exactly what stops a seat from dropping anything; also
     * requiring the table to be empty left every seat cyclically unable to act —
     * the game deadlocked forever with nobody eliminated (observed in the
     * end-to-end run: 600+ rounds, scores and lives frozen). Pieces still
     * standing on a Field do not restore the ability to drop.
     *
     * <p>The King <em>returns</em> to the hand (contract §2.3: 国王自动回手), so at
     * most one King instance may exist per player: a King still standing on the
     * table is taken back into the hand first, and only when the player still has
     * lives left is one added. On the life that runs out the King is left where it
     * is and the player is eliminated.
     *
     * <p>Never called by {@link #resolve} (that only settles the drops) — the
     * round-start caller is responsible for surfacing the returned lines in the
     * view.
     *
     * @return event log lines for the caller (the service appends them in
     *         {@code nextRound}) — {@link #resolve} replaces the log wholesale
     */
    public static List<String> checkNoUsablePieces(KingGame game) {
        List<String> events = new ArrayList<>();
        for (PlayerState p : game.getPlayers()) {
            if (p.isEliminated()) {
                continue;
            }
            if (!p.getHand().isEmpty()) {
                continue;
            }
            int lives = p.getKingLives();
            if (lives <= 0) {
                // the King was already lost for good: nothing can come back
                p.setKingLives(0);
                game.markEliminated(p.getSeat());
                events.add("Player " + p.getSeat()
                        + " has no piece left and no King lives — eliminated");
                continue;
            }
            p.setKingLives(lives - 1);
            if (p.getKingLives() > 0) {
                boolean recalledFromTable = recallKingFromTable(game, p);
                p.getHand().add(PieceKind.KING);
                events.add("Player " + p.getSeat() + " had no usable piece: King returned to hand ("
                        + p.getKingLives() + " lives left"
                        + (recalledFromTable ? ", taken back from the table" : "") + ")");
            } else {
                // contract §2.3: 命尽（=0）则不再回手 — the King stays put and the seat is out
                game.markEliminated(p.getSeat());
                events.add("Player " + p.getSeat()
                        + " lost the last King life and had no usable piece — eliminated");
            }
        }
        return events;
    }

    /**
     * Takes {@code p}'s King off the table when it is standing there, so the hand
     * can hold the only instance of it (contract §2.3: 国王自动回手).
     *
     * @return true when a King was actually taken back
     */
    private static boolean recallKingFromTable(KingGame game, PlayerState p) {
        Piece king = findPieceOnField(game, p.getSeat(), PieceKind.KING);
        if (king == null) {
            return false;
        }
        game.getBoard().removePiece(king);
        return true;
    }

    /**
     * Contract §2.7: a player at or above the score target wins immediately, and
     * so does the last player still in the game.
     *
     * @return the winning seat, or null when the game continues <em>or</em> when
     *         nobody is left standing (a draw — see {@link #isSettled})
     */
    public static Integer evaluateWinner(KingGame game) {
        for (PlayerState p : game.getPlayers()) {
            if (!p.isEliminated() && p.getScore() >= RanzhongRules.SCORE_TO_WIN) {
                return p.getSeat();
            }
        }
        List<PlayerState> active = game.getPlayers().stream()
                .filter(p -> !p.isEliminated())
                .toList();
        if (active.size() == 1 && game.getPlayers().size() > 1) {
            return active.get(0).getSeat();
        }
        return null;
    }

    /**
     * True when the match cannot go on, whether or not it has a winner: someone
     * reached the score target, exactly one player is left standing, or the last
     * players all went out in the same round — the draw case that
     * {@link #evaluateWinner} deliberately reports as {@code null}.
     *
     * <p>Callers must move the phase to {@code FINISHED} whenever this is true
     * (contract §2.7): falling back to {@code PLACING} with nobody able to act any
     * more would leave a game that can never end and never produce a winner.
     */
    public static boolean isSettled(KingGame game) {
        if (evaluateWinner(game) != null) {
            return true;
        }
        return game.getPlayers().size() > 1 && game.activeSeats().isEmpty();
    }

    /**
     * True when at least one player still owns a piece with a special effect on
     * the table (contract §3.4: the round moves to {@code EFFECTS} only then).
     */
    public static boolean hasExecutableEffects(KingGame game) {
        for (Piece piece : game.getBoard().piecesOnField()) {
            if (piece.getOwnerSeat() == null || game.player(piece.getOwnerSeat()).isEliminated()) {
                continue;
            }
            switch (piece.getKind()) {
                case HORSE, CHARIOT, KNIGHT, STRATEGIST -> {
                    return true;
                }
                default -> {
                    // no action in the special-effects round
                }
            }
        }
        return false;
    }

    private static Piece findPieceOnField(KingGame game, int seat, PieceKind kind) {
        return game.getBoard().piecesOnField().stream()
                .filter(p -> p.ownedBy(seat) && p.getKind() == kind)
                .findFirst()
                .orElse(null);
    }

    private static Board.Cell locationOf(Board board, Piece piece) {
        for (Side side : Side.values()) {
            int index = board.cellOf(side, piece);
            if (index >= 0) {
                return new Board.Cell(side, index);
            }
        }
        return null;
    }

    public record RoundResult(boolean finished, Integer winnerSeat, List<String> events) {
    }
}
