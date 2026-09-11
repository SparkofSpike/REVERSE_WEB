package com.test.engine.kingchess.resolve;

import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.rules.RanzhongRules;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KingResolverTest {

    private static KingGame game() {
        return KingGame.create(List.of(0, 1), RanzhongRules.STARTING_PRIVATE_HAND);
    }

    private static Deployment drop(int seat, Side side, int cell, PieceKind kind) {
        return new Deployment(seat, side, cell, kind);
    }

    // ===================== ordering =====================

    @Test
    void ordersByD20DescendingThenSeat() {
        Map<Integer, Integer> rolls = new LinkedHashMap<>();
        rolls.put(0, 12);
        rolls.put(1, 20);
        rolls.put(2, 7);
        rolls.put(3, 12);
        List<Integer> order = KingResolver.orderByD20(rolls);
        assertThat(order).containsExactly(1, 0, 3, 2); // 20, 12(seat0), 12(seat3), 7
    }

    @Test
    void recordsRollsAndDropOrderOnTheGame() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 1, PieceKind.SOLDIER)));
        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 3, 1, 17));
        assertThat(g.getLastRolls()).containsEntry(0, 3).containsEntry(1, 17);
        assertThat(g.getLastDropOrder()).containsExactly(1, 0);
        assertThat(g.getEvents()).isNotEmpty();
        assertThat(r.events()).isNotEmpty();
    }

    // ===================== drops and captures =====================

    @Test
    void laterDropEatsEarlierOnSameCell() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.PROVISION);
        g.player(1).getHand().add(PieceKind.SOLDIER);
        // Player 0 drops first (higher d20), player 1 drops later (lower d20).
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 0, PieceKind.PROVISION)));
        g.replaceDeployments(1, List.of(drop(1, Side.NORTH, 0, PieceKind.SOLDIER)));

        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 18, 1, 4));

        // Player 1's soldier occupies the cell; it ate the provision.
        assertThat(g.getBoard().field(Side.NORTH).at(0).getKind()).isEqualTo(PieceKind.SOLDIER);
        assertThat(g.getBoard().field(Side.NORTH).at(0).getOwnerSeat()).isEqualTo(1);
        // Eating a Provision scores +4 to the eater (player 1) and recycles it to the Court.
        assertThat(g.player(1).getScore()).isEqualTo(4);
        assertThat(g.getBoard().courtCount(PieceKind.PROVISION)).isEqualTo(9); // 8 seeded + 1 recycled
        assertThat(r.events()).isNotEmpty();
    }

    @Test
    void uncontestedDropKeepsTheCourtStock() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.PROVISION);
        g.replaceDeployments(0, List.of(drop(0, Side.EAST, 3, PieceKind.PROVISION)));
        KingResolver.resolve(g, Map.of(0, 5, 1, 2));

        // nothing was eaten: the court keeps its full stock
        assertThat(g.getBoard().courtCount(PieceKind.PROVISION)).isEqualTo(8);
        assertThat(g.getBoard().field(Side.EAST).at(3).getKind()).isEqualTo(PieceKind.PROVISION);
    }

    @Test
    void soldierEatenGoesToEaterHandAndScoresTwo() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 0, PieceKind.SOLDIER)));
        g.replaceDeployments(1, List.of(drop(1, Side.NORTH, 0, PieceKind.SOLDIER)));

        KingResolver.resolve(g, Map.of(0, 18, 1, 4));

        // contract §2.3 / DEFAULT-4: the eaten Soldier joins the eater's hand, +2 points.
        assertThat(g.player(1).getScore()).isEqualTo(2);
        assertThat(g.player(1).getHand()).contains(PieceKind.SOLDIER);
        assertThat(g.getBoard().courtCount(PieceKind.SOLDIER)).isEqualTo(6); // not recycled
        assertThat(g.getBoard().field(Side.NORTH).at(0).getOwnerSeat()).isEqualTo(1);
    }

    @Test
    void horseEatenGoesToEaterHandWithoutScore() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.HORSE);
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.EAST, 2, PieceKind.HORSE)));
        g.replaceDeployments(1, List.of(drop(1, Side.EAST, 2, PieceKind.SOLDIER)));

        KingResolver.resolve(g, Map.of(0, 11, 1, 3));

        assertThat(g.player(1).getScore()).isZero();
        assertThat(g.player(1).getHand()).contains(PieceKind.HORSE);
        assertThat(g.getBoard().courtCount(PieceKind.HORSE)).isEqualTo(4);
    }

    @Test
    void eatenQueenScoresHerOwner() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.EAST, 1, PieceKind.QUEEN)));
        g.replaceDeployments(1, List.of(drop(1, Side.EAST, 1, PieceKind.SOLDIER)));

        KingResolver.resolve(g, Map.of(0, 16, 1, 5));

        // Queen owner (player 0) scores, and the queen is removed (private piece).
        assertThat(g.player(0).getScore()).isEqualTo(8);
        assertThat(g.player(0).getHand()).doesNotContain(PieceKind.QUEEN);
        assertThat(g.getBoard().courtCount(PieceKind.PROVISION)).isEqualTo(8);
    }

    // ===================== King lives =====================

    @Test
    void eatenKingReturnsToHandAndLosesALife() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.WEST, 2, PieceKind.KING)));
        g.replaceDeployments(1, List.of(drop(1, Side.WEST, 2, PieceKind.SOLDIER)));

        KingResolver.resolve(g, Map.of(0, 19, 1, 3));

        assertThat(g.player(0).getKingLives()).isEqualTo(4);
        assertThat(g.player(0).getHand()).contains(PieceKind.KING);
        assertThat(g.getBoard().field(Side.WEST).at(2).getKind()).isEqualTo(PieceKind.SOLDIER);
    }

    @Test
    void kingWithLastLifeDoesNotReturnToHand() {
        KingGame g = game();
        g.player(0).setKingLives(1);
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.WEST, 2, PieceKind.KING)));
        g.replaceDeployments(1, List.of(drop(1, Side.WEST, 2, PieceKind.SOLDIER)));

        KingResolver.resolve(g, Map.of(0, 19, 1, 3));

        assertThat(g.player(0).getKingLives()).isZero();
        assertThat(g.player(0).getHand()).doesNotContain(PieceKind.KING);
        assertThat(g.getBoard().field(Side.WEST).at(2).getOwnerSeat()).isEqualTo(1);
    }

    @Test
    void kingEatsAnythingForFlatThree() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.PROVISION);
        g.replaceDeployments(0, List.of(drop(0, Side.SOUTH, 1, PieceKind.KING)));
        g.replaceDeployments(1, List.of(drop(1, Side.SOUTH, 1, PieceKind.PROVISION)));

        KingResolver.resolve(g, Map.of(0, 2, 1, 20));

        assertThat(g.player(0).getScore()).isEqualTo(3);
        assertThat(g.getBoard().courtCount(PieceKind.PROVISION)).isEqualTo(9);
    }

    // ===================== Martyr =====================

    @Test
    void martyrIsEatenInsteadAndDoesNotChain() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(1, List.of(
                drop(1, Side.SOUTH, 1, PieceKind.MARTYR),
                drop(1, Side.SOUTH, 2, PieceKind.SOLDIER)));
        KingResolver.resolve(g, Map.of(0, 1, 1, 20));

        // player 1's pieces safe on the table are protected by its own Martyr
        assertThat(g.getBoard().field(Side.SOUTH).at(1).getKind()).isEqualTo(PieceKind.MARTYR);
        assertThat(g.getBoard().field(Side.SOUTH).at(2).getKind()).isEqualTo(PieceKind.SOLDIER);

        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.SOUTH, 2, PieceKind.SOLDIER)));
        g.replaceDeployments(1, List.of());

        KingResolver.resolve(g, Map.of(0, 20, 1, 1));

        // the Martyr died in place of the Soldier and the attacker took its cell
        assertThat(g.getBoard().field(Side.SOUTH).at(1).getKind()).isEqualTo(PieceKind.SOLDIER);
        assertThat(g.getBoard().field(Side.SOUTH).at(1).getOwnerSeat()).isEqualTo(0);
        // no chaining: the Soldier of player 1 survived in its own cell
        assertThat(g.getBoard().field(Side.SOUTH).at(2).getKind()).isEqualTo(PieceKind.SOLDIER);
        assertThat(g.getBoard().field(Side.SOUTH).at(2).getOwnerSeat()).isEqualTo(1);
        assertThat(g.player(0).getScore()).isZero();
        assertThat(g.player(1).getHand()).doesNotContain(PieceKind.MARTYR);
    }

    @Test
    void martyrDoesNotProtectAgainstAnotherMartyrLoss() {
        KingGame g = game();
        // player 1 has a Martyr; player 0 eats it directly -> the Martyr is simply removed
        g.replaceDeployments(1, List.of(drop(1, Side.SOUTH, 0, PieceKind.MARTYR)));
        KingResolver.resolve(g, Map.of(0, 1, 1, 20));

        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.replaceDeployments(0, List.of(drop(0, Side.SOUTH, 0, PieceKind.SOLDIER)));
        g.replaceDeployments(1, List.of());
        KingResolver.resolve(g, Map.of(0, 20, 1, 1));

        assertThat(g.getBoard().field(Side.SOUTH).at(0).getKind()).isEqualTo(PieceKind.SOLDIER);
        assertThat(g.getBoard().field(Side.SOUTH).at(0).getOwnerSeat()).isEqualTo(0);
    }

    // ===================== phase / win conditions =====================

    @Test
    void dropsMovePhaseToEffectsWhenAnEffectPieceIsOnTheTable() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.HORSE);
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 1, PieceKind.HORSE)));

        KingResolver.resolve(g, Map.of(0, 9, 1, 2));

        assertThat(g.getPhase()).isEqualTo(GamePhase.EFFECTS);
    }

    @Test
    void noEffectPieceEndsTheRound() {
        KingGame g = game();
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 1, PieceKind.KING)));
        g.replaceDeployments(1, List.of());

        KingResolver.resolve(g, Map.of(0, 9, 1, 2));

        assertThat(g.getPhase()).isEqualTo(GamePhase.ROUND_END);
    }

    @Test
    void reachingScoreTargetWins() {
        KingGame g = game();
        g.player(0).setScore(49);
        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.player(1).getHand().add(PieceKind.PROVISION);
        // Player 1 drops first (higher d20); player 0 drops later on the same
        // cell and eats the Provision for +4, crossing the 50-point target.
        g.replaceDeployments(0, List.of(drop(0, Side.NORTH, 0, PieceKind.SOLDIER)));
        g.replaceDeployments(1, List.of(drop(1, Side.NORTH, 0, PieceKind.PROVISION)));

        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 1, 1, 20));

        assertThat(r.finished()).isTrue();
        assertThat(r.winnerSeat()).isEqualTo(0);
        assertThat(g.getPhase()).isEqualTo(GamePhase.FINISHED);
        assertThat(g.getWinnerSeat()).isEqualTo(0);
    }

    @Test
    void lastPlayerStandingWins() {
        KingGame g = game();
        // Player 1 is drained: empty hand, no piece on the table, one life left.
        g.player(1).getHand().clear();
        g.player(1).setKingLives(1);
        KingResolver.checkNoUsablePieces(g);
        assertThat(g.player(1).isEliminated()).isTrue();
        assertThat(g.getEliminatedSeats()).containsExactly(1);

        g.replaceDeployments(0, List.of(drop(0, Side.SOUTH, 0, PieceKind.KING)));
        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 10, 1, 2));

        assertThat(r.finished()).isTrue();
        assertThat(r.winnerSeat()).isEqualTo(0);
        assertThat(g.getPhase()).isEqualTo(GamePhase.FINISHED);
    }

    // ===================== "no usable piece" (contract §2.4) =====================
    @Test
    void anEmptyHandWithTheKingOnTheTableKeepsASingleKingInstance() {
        KingGame g = game();
        Piece king = new Piece(g.nextPieceId(), PieceKind.KING, 0, g.nextDropOrdinal());
        g.getBoard().field(Side.NORTH).place(0, king);
        g.player(0).getHand().clear();

        List<String> events = KingResolver.checkNoUsablePieces(g);

        // contract §2.3: 国王自动回手 — the King comes back FROM the table, so the
        // hand holds the only instance instead of a second one being created
        assertThat(g.getBoard().field(Side.NORTH).at(0)).isNull();
        assertThat(g.player(0).getHand()).containsExactly(PieceKind.KING);
        assertThat(kingInstances(g, 0)).isEqualTo(1);
        assertThat(g.player(0).getKingLives()).isEqualTo(4);
        assertThat(g.player(0).isEliminated()).isFalse();
        assertThat(events).anyMatch(e -> e.contains("taken back from the table"));
    }

    @Test
    void theLifeThatRunsOutDoesNotBringTheKingBack() {
        KingGame g = game();
        Piece king = new Piece(g.nextPieceId(), PieceKind.KING, 0, g.nextDropOrdinal());
        g.getBoard().field(Side.NORTH).place(0, king);
        g.player(0).getHand().clear();
        g.player(0).setKingLives(1);

        KingResolver.checkNoUsablePieces(g);

        // 命数耗尽的那一次不回手：the King stays on the table and the seat is out
        assertThat(g.player(0).isEliminated()).isTrue();
        assertThat(g.player(0).getKingLives()).isZero();
        assertThat(g.player(0).getHand()).doesNotContain(PieceKind.KING);
        assertThat(g.getBoard().field(Side.NORTH).at(0)).isSameAs(king);
        assertThat(kingInstances(g, 0)).isEqualTo(1);
    }

    @Test
    void aKingAlreadyInHandIsNotDuplicated() {
        KingGame g = game();
        List<PieceKind> hand = g.player(0).getHand();
        hand.clear();
        hand.add(PieceKind.KING);

        KingResolver.checkNoUsablePieces(g);

        // the hand is not empty, so the check does not even fire
        assertThat(g.player(0).getKingLives()).isEqualTo(5);
        assertThat(kingInstances(g, 0)).isEqualTo(1);
    }

    // ===================== end of game: winner and draw (contract §2.7) =====================

    @Test
    void everybodyOutInTheSameRoundIsADrawAndTheGameIsSettled() {
        KingGame g = game();
        g.player(0).getHand().clear();
        g.player(0).setKingLives(1);
        g.player(1).getHand().clear();
        g.player(1).setKingLives(1);

        KingResolver.checkNoUsablePieces(g);

        assertThat(g.getEliminatedSeats()).containsExactly(0, 1);
        assertThat(g.activeSeats()).isEmpty();
        // nobody wins, but the match is over: the caller must go to FINISHED
        assertThat(KingResolver.evaluateWinner(g)).isNull();
        assertThat(KingResolver.isSettled(g)).isTrue();
    }

    @Test
    void isSettledIsFalseWhileAtLeastTwoPlayersCanStillAct() {
        KingGame g = game();

        assertThat(KingResolver.evaluateWinner(g)).isNull();
        assertThat(KingResolver.isSettled(g)).isFalse();
    }

    @Test
    void isSettledIsTrueWhenSomebodyReachedTheScoreTarget() {
        KingGame g = game();
        g.player(1).setScore(RanzhongRules.SCORE_TO_WIN);

        assertThat(KingResolver.evaluateWinner(g)).isEqualTo(1);
        assertThat(KingResolver.isSettled(g)).isTrue();
    }

    @Test
    void isSettledIsTrueWhenOnePlayerIsLeftStanding() {
        KingGame g = game();
        g.markEliminated(1);

        assertThat(KingResolver.evaluateWinner(g)).isEqualTo(0);
        assertThat(KingResolver.isSettled(g)).isTrue();
    }

    /** Number of King instances a seat owns, counting both hand and table. */
    private static int kingInstances(KingGame game, int seat) {
        long onField = game.getBoard().piecesOnField().stream()
                .filter(p -> p.ownedBy(seat) && p.getKind() == PieceKind.KING)
                .count();
        return game.player(seat).countInHand(PieceKind.KING) + (int) onField;
    }

    @Test
    void noUsablePieceReturnsTheKingAndBurnsALife() {
        KingGame g = game();
        g.player(0).getHand().clear();

        List<String> events = KingResolver.checkNoUsablePieces(g);

        assertThat(g.player(0).getHand()).containsExactly(PieceKind.KING);
        assertThat(g.player(0).getKingLives()).isEqualTo(4);
        assertThat(g.player(0).isEliminated()).isFalse();
        assertThat(events).isNotEmpty();
    }

    @Test
    void emptyHandAndNoLivesEliminatesThePlayer() {
        KingGame g = game();
        g.player(0).getHand().clear();
        g.player(0).setKingLives(1);

        KingResolver.checkNoUsablePieces(g);

        assertThat(g.player(0).getKingLives()).isZero();
        assertThat(g.player(0).isEliminated()).isTrue();
        assertThat(g.getEliminatedSeats()).contains(0);
        assertThat(g.player(0).getHand()).doesNotContain(PieceKind.KING);
    }

    @Test
    void emptyHandWithNoLivesLeftEliminatesWithoutGoingNegative() {
        KingGame g = game();
        g.player(0).getHand().clear();
        g.player(0).setKingLives(0);

        KingResolver.checkNoUsablePieces(g);

        assertThat(g.player(0).getKingLives()).isZero();
        assertThat(g.player(0).isEliminated()).isTrue();
        assertThat(g.getEliminatedSeats()).containsExactly(0);
    }

    @Test
    void anEmptyHandBurnsALifeEvenWithAPieceStillOnTheTable() {
        KingGame g = game();
        g.player(0).getHand().clear();
        Piece piece = new Piece(g.nextPieceId(), PieceKind.QUEEN, 0, g.nextDropOrdinal());
        g.getBoard().field(Side.NORTH).place(0, piece);

        KingResolver.checkNoUsablePieces(g);

        // an empty hand means the seat cannot drop anything: without the King
        // coming back the game would deadlock (contract §2.4)
        assertThat(g.player(0).getKingLives()).isEqualTo(4);
        assertThat(g.player(0).getHand()).containsExactly(PieceKind.KING);
        assertThat(g.player(0).isEliminated()).isFalse();
    }
}
