package com.test.engine.kingchess.resolve;

import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.rules.RanzhongRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KingResolverTest {

    private static KingGame game() {
        return KingGame.create(List.of(0, 1), RanzhongRules.STARTING_PRIVATE_HAND);
    }

    @Test
    void ordersByD20DescendingThenSeat() {
        Map<Integer, Integer> rolls = Map.of(0, 12, 1, 20, 2, 7, 3, 12);
        List<Integer> order = KingResolver.orderByD20(rolls);
        assertThat(order).containsExactly(1, 0, 3, 2); // 20, 12(seat0), 12(seat3), 7
    }

    @Test
    void laterDropEatsEarlierOnSameCell() {
        KingGame g = game();
        g.player(0).getHand().add(PieceKind.PROVISION);
        g.player(1).getHand().add(PieceKind.SOLDIER);
        // Player 0 drops first (higher d20), player 1 drops later (lower d20).
        g.addDeployment(new Deployment(0, Side.NORTH, 0, PieceKind.PROVISION));
        g.addDeployment(new Deployment(1, Side.NORTH, 0, PieceKind.SOLDIER));

        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 18, 1, 4));

        // Player 1's soldier occupies the cell; it ate the provision.
        assertThat(g.getBoard().field(Side.NORTH).at(0).getKind()).isEqualTo(PieceKind.SOLDIER);
        assertThat(g.getBoard().field(Side.NORTH).at(0).getOwnerSeat()).isEqualTo(1);
        // The provision is recycled to the Court.
        assertThat(g.getBoard().getCourt()).hasSize(1);
        assertThat(g.getBoard().getCourt().get(0).getKind()).isEqualTo(PieceKind.PROVISION);
        // Eating a Provision scores +4 to the eater (player 1).
        assertThat(g.player(1).getScore()).isEqualTo(4);
        assertThat(r.events()).isNotEmpty();
    }

    @Test
    void eatenKingReturnsToHand() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.addDeployment(new Deployment(0, Side.WEST, 2, PieceKind.KING));
        g.addDeployment(new Deployment(1, Side.WEST, 2, PieceKind.SOLDIER));

        KingResolver.resolve(g, Map.of(0, 19, 1, 3));

        // King went back to player 0's hand, not into the Court.
        assertThat(g.player(0).getHand()).contains(PieceKind.KING);
        assertThat(g.getBoard().getCourt()).isEmpty();
        assertThat(g.getBoard().field(Side.WEST).at(2).getKind()).isEqualTo(PieceKind.SOLDIER);
    }

    @Test
    void eatenQueenScoresHerOwner() {
        KingGame g = game();
        g.player(1).getHand().add(PieceKind.SOLDIER);
        g.addDeployment(new Deployment(0, Side.EAST, 1, PieceKind.QUEEN));
        g.addDeployment(new Deployment(1, Side.EAST, 1, PieceKind.SOLDIER));

        KingResolver.resolve(g, Map.of(0, 16, 1, 5));

        // Queen owner (player 0) scores, and the queen is removed (private piece).
        assertThat(g.player(0).getScore()).isEqualTo(8);
        assertThat(g.getBoard().getCourt()).isEmpty();
    }

    @Test
    void reachingScoreTargetWins() {
        KingGame g = game();
        g.player(0).setScore(49);
        g.player(0).getHand().add(PieceKind.SOLDIER);
        g.player(1).getHand().add(PieceKind.PROVISION);
        // Player 1 drops first (higher d20); player 0 drops later on the same
        // cell and eats the Provision for +4, crossing the 50-point target.
        g.addDeployment(new Deployment(0, Side.NORTH, 0, PieceKind.SOLDIER));
        g.addDeployment(new Deployment(1, Side.NORTH, 0, PieceKind.PROVISION));

        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 1, 1, 20));

        assertThat(r.finished()).isTrue();
        assertThat(r.winnerSeat()).isEqualTo(0);
        assertThat(g.getPhase()).isEqualTo(GamePhase.FINISHED);
    }

    @Test
    void drainingAllOpponentsWins() {
        KingGame g = game();
        // Player 0 keeps a piece; player 1 is drained (no hand, nothing on board).
        g.player(1).getHand().clear();
        g.player(0).getHand().add(PieceKind.KING);
        g.addDeployment(new Deployment(0, Side.SOUTH, 0, PieceKind.KING));

        KingResolver.RoundResult r = KingResolver.resolve(g, Map.of(0, 10, 1, 2));

        assertThat(r.finished()).isTrue();
        assertThat(r.winnerSeat()).isEqualTo(0);
    }
}
