package com.test.engine.kingchess.resolve;

import com.test.engine.kingchess.model.Board;
import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.Field;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.PlayerState;
import com.test.engine.kingchess.rules.RanzhongRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Adjudication for King's Chess — pure logic, no randomness, no persistence.
 *
 * <p>The resolver only applies the rules that are fixed by the manual:
 * d20 ordering, "later drop eats earlier drop", recycling (public pieces to
 * Court, King back to hand, other private pieces removed) and the win checks.
 * Randomness (the d20 values) is supplied by the caller so this stays
 * deterministic and unit-testable.
 */
public final class KingResolver {

    private KingResolver() {
    }

    /**
     * Orders seats by their d20 value descending (highest drops first).
     * Ties break by ascending seat id. TODO(client): confirm tie handling.
     */
    public static List<Integer> orderByD20(Map<Integer, Integer> d20BySeat) {
        return d20BySeat.keySet().stream()
                .sorted(Comparator
                        .comparingInt((Integer seat) -> d20BySeat.get(seat)).reversed()
                        .thenComparingInt(seat -> seat))
                .toList();
    }

    /**
     * Resolves one round: applies every player's secret deployments in d20
     * order, resolves same-cell eats, scores, recycles and checks the win
     * conditions. Clears pending deployments afterwards.
     */
    public static RoundResult resolve(KingGame game, Map<Integer, Integer> d20BySeat) {
        List<String> events = new ArrayList<>();
        List<Integer> order = orderByD20(d20BySeat);

        for (Integer seat : order) {
            for (Deployment dep : game.deploymentsOf(seat)) {
                applyDrop(game, dep, events);
            }
        }

        boolean finished = false;
        Integer winner = null;
        for (PlayerState p : game.getPlayers()) {
            if (p.getScore() >= RanzhongRules.SCORE_TO_WIN) {
                finished = true;
                winner = p.getSeat();
                events.add("Player " + p.getSeat() + " reached the score target");
                break;
            }
        }
        if (!finished) {
            List<Integer> drained = drainedOpponents(game);
            if (drained.size() == game.getPlayers().size() - 1) {
                // exactly one player still has pieces -> winner is the one with pieces
                Integer survivor = game.getPlayers().stream()
                        .map(PlayerState::getSeat)
                        .filter(s -> !drained.contains(s))
                        .findFirst()
                        .orElse(null);
                if (survivor != null) {
                    finished = true;
                    winner = survivor;
                    events.add("All opponents drained of pieces");
                }
            }
        }

        game.setPhase(finished ? GamePhase.FINISHED : GamePhase.ROUND_END);
        game.clearPending();
        return new RoundResult(finished, winner, events);
    }

    private static void applyDrop(KingGame game, Deployment dep, List<String> events) {
        Field field = game.getBoard().field(dep.side());
        Piece occupant = field.at(dep.cellIndex());
        long ordinal = game.getDropCounter() + 1;
        game.setDropCounter(ordinal);
        Piece dropped = new Piece(dep.pieceKind(), dep.seat(), ordinal);

        if (occupant != null) {
            resolveEat(game, dropped, occupant, events);
        }
        field.place(dep.cellIndex(), dropped);
        game.player(dep.seat()).getHand().remove(dep.pieceKind());
    }

    private static void resolveEat(KingGame game, Piece eater, Piece victim, List<String> events) {
        PlayerState eaterState = game.player(eater.getOwnerSeat());

        RanzhongRules.ScoreEvent score = RanzhongRules.eatScore(eater.getKind(), victim.getKind());
        if (score.amount() != 0) {
            if (score.toEater()) {
                eaterState.setScore(eaterState.getScore() + score.amount());
            } else {
                PlayerState victimOwner = game.player(victim.getOwnerSeat());
                victimOwner.setScore(victimOwner.getScore() + score.amount());
            }
        }

        String eatDesc = "Player " + eater.getOwnerSeat() + " ate "
                + victim.getKind() + " from Player " + victim.getOwnerSeat();
        events.add(eatDesc);

        // Recycle / remove the victim.
        PieceKind vk = victim.getKind();
        if (vk == PieceKind.KING) {
            // King returns to its owner's hand.
            game.player(victim.getOwnerSeat()).getHand().add(PieceKind.KING);
            events.add("King returned to Player " + victim.getOwnerSeat() + "'s hand");
        } else if (vk.isPrivatePiece()) {
            // Other private pieces are removed from the table.
            events.add("Private piece removed (" + vk + ")");
        } else {
            // Public pieces are recycled to the central Court.
            game.getBoard().getCourt().add(victim);
            events.add("Public piece recycled to Court (" + vk + ")");
        }
    }

    /** Seats that no longer have any piece (hand empty and nothing on their Field). */
    private static List<Integer> drainedOpponents(KingGame game) {
        List<Integer> drained = new ArrayList<>();
        for (PlayerState p : game.getPlayers()) {
            if (p.getHand().isEmpty() && !boardHasPieceOf(game, p.getSeat())) {
                drained.add(p.getSeat());
            }
        }
        return drained;
    }

    private static boolean boardHasPieceOf(KingGame game, int seat) {
        Board board = game.getBoard();
        for (Field f : board.getFields().values()) {
            for (Piece cell : f.getCells()) {
                if (cell != null && cell.getOwnerSeat() != null && cell.getOwnerSeat() == seat) {
                    return true;
                }
            }
        }
        return false;
    }

    public record RoundResult(boolean finished, Integer winnerSeat, List<String> events) {
    }
}
