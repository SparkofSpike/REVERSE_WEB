package com.test.engine.kingchess.spawn;

import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.rules.RanzhongRules;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSpawnerTest {

    private static KingGame game() {
        return KingGame.create(List.of(0, 1), RanzhongRules.STARTING_PRIVATE_HAND);
    }

    private static void fillAllCells(KingGame game) {
        for (Side side : Side.values()) {
            for (int i = 0; i < Side.CELL_COUNT; i++) {
                game.getBoard().field(side).place(i,
                        new Piece(game.nextPieceId(), PieceKind.PROVISION, null,
                                game.nextDropOrdinal()));
            }
        }
    }

    @Test
    void spawnsBetweenOneAndFivePieces() {
        for (long seed = 0; seed < 40; seed++) {
            KingGame game = game();
            PublicSpawner spawner = new PublicSpawner(new DiceRoller(seed));

            List<Piece> spawned = spawner.spawn(game);

            // contract §2.5-1: 全场合计随机刷新 1–5 枚
            assertThat(spawned.size()).isBetween(RanzhongRules.REFRESH_MIN_COUNT,
                    RanzhongRules.REFRESH_MAX_COUNT);
            assertThat(game.getBoard().piecesOnField()).hasSize(spawned.size());
            for (Piece piece : spawned) {
                assertThat(piece.getKind().isPrivatePiece()).isFalse();
                assertThat(piece.getOwnerSeat()).isNull();
            }
            // spawned pieces leave the central Court
            assertThat(game.getBoard().getCourt()).hasSize(24 - spawned.size());
        }
    }

    @Test
    void spawnsOnlyPublicWeighedKinds() {
        Set<PieceKind> seen = new TreeSet<>();
        for (long seed = 0; seed < 60; seed++) {
            KingGame game = game();
            PublicSpawner spawner = new PublicSpawner(new DiceRoller(seed));
            spawner.spawn(game).forEach(p -> seen.add(p.getKind()));
        }
        assertThat(seen).isSubsetOf(Set.of(PieceKind.PROVISION, PieceKind.SOLDIER,
                PieceKind.HORSE, PieceKind.CHARIOT, PieceKind.KNIGHT));
        // the two heaviest weights must show up across many rolls
        assertThat(seen).contains(PieceKind.PROVISION, PieceKind.SOLDIER);
    }

    @Test
    void spawnsNothingWhenTheBoardIsFull() {
        KingGame game = game();
        fillAllCells(game);

        assertThat(new PublicSpawner(new DiceRoller(7)).spawn(game)).isEmpty();
        assertThat(game.getBoard().getCourt()).hasSize(24);
    }

    @Test
    void spawnsAtMostTheNumberOfEmptyCells() {
        KingGame game = game();
        fillAllCells(game);
        // free exactly two cells
        game.getBoard().field(Side.NORTH).place(0, null);
        game.getBoard().field(Side.WEST).place(3, null);

        List<Piece> spawned = new PublicSpawner(new DiceRoller(7)).spawn(game);

        assertThat(spawned).hasSizeLessThanOrEqualTo(2);
        assertThat(game.getBoard().emptyCells()).isEmpty();
    }

    @Test
    void spawnsAtMostTheRemainingCourtStock() {
        KingGame game = game();
        List<Piece> court = game.getBoard().getCourt();
        while (court.size() > 1) {
            court.remove(0);
        }

        List<Piece> spawned = new PublicSpawner(new DiceRoller(3)).spawn(game);

        assertThat(spawned).hasSize(1);
        assertThat(game.getBoard().getCourt()).isEmpty();
    }

    @Test
    void spawnsOntoEmptyCellsOnly() {
        KingGame game = game();
        Piece blocker = new Piece(game.nextPieceId(), PieceKind.SOLDIER, 0, game.nextDropOrdinal());
        game.getBoard().field(Side.NORTH).place(2, blocker);

        List<Piece> spawned = new PublicSpawner(new DiceRoller(11)).spawn(game);

        assertThat(game.getBoard().field(Side.NORTH).at(2)).isSameAs(blocker);
        for (Piece piece : spawned) {
            assertThat(piece.getDropOrdinal()).isPositive();
        }
    }

    @Test
    void theSameSeedProducesTheSameSpawn() {
        KingGame first = game();
        KingGame second = game();
        new PublicSpawner(new DiceRoller(42)).spawn(first);
        new PublicSpawner(new DiceRoller(42)).spawn(second);

        assertThat(kindsAndCells(first)).isEqualTo(kindsAndCells(second));
    }

    private static String kindsAndCells(KingGame game) {
        StringBuilder sb = new StringBuilder();
        for (Side side : Side.values()) {
            for (int i = 0; i < Side.CELL_COUNT; i++) {
                Piece piece = game.getBoard().field(side).at(i);
                sb.append(side).append(i).append('=')
                        .append(piece == null ? "-" : piece.getKind()).append(';');
            }
        }
        return sb.toString();
    }
}
