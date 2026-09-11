package com.test.engine.kingchess;

import com.test.engine.dto.kingchess.KingGameView;
import com.test.engine.dto.kingchess.PieceView;
import com.test.engine.exception.BusinessException;
import com.test.engine.kingchess.model.Board;
import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.EffectAction;
import com.test.engine.kingchess.model.EffectType;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.spawn.PublicSpawner;
import com.test.engine.service.KingGameService;
import com.test.engine.utils.DiceRoller;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end service tests: every rule decision must be visible in the returned
 * {@code KingGameView} (the frontend never adjudicates).
 */
class KingGameServiceTest {

    private static KingGameService service(long seed) {
        DiceRoller diceRoller = new DiceRoller(seed);
        return new KingGameService(new PublicSpawner(diceRoller), diceRoller);
    }

    private static Deployment drop(int seat, Side side, int cell, PieceKind kind) {
        return new Deployment(seat, side, cell, kind);
    }

    /** Cells that are still empty after the opening public refresh. */
    private static List<int[]> emptyCells(KingGameView view) {
        List<int[]> empty = new ArrayList<>();
        for (Side side : Side.values()) {
            List<PieceView> cells = view.fields().get(side.name());
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i) == null) {
                    empty.add(new int[]{side.ordinal(), i});
                }
            }
        }
        return empty;
    }

    private static Side side(int[] cell) {
        return Side.values()[cell[0]];
    }

    private static PieceView at(KingGameView view, int[] cell) {
        return view.fields().get(side(cell).name()).get(cell[1]);
    }

    @Test
    void createsAGameInPlacingPhaseWithTheContractShape() {
        KingGameView view = service(1).create(2);

        assertThat(view.gameId()).isNotBlank();
        assertThat(view.phase()).isEqualTo("PLACING");
        assertThat(view.roundNo()).isEqualTo(1);
        assertThat(view.finished()).isFalse();
        assertThat(view.winnerSeat()).isNull();
        assertThat(view.scoreToWin()).isEqualTo(50);
        assertThat(view.players()).hasSize(2);
        assertThat(view.players().get(0).side()).isEqualTo("NORTH");
        assertThat(view.players().get(1).side()).isEqualTo("EAST");
        assertThat(view.players().get(0).hand())
                .containsExactly("KING", "QUEEN", "MARTYR", "STRATEGIST");
        assertThat(view.players().get(0).kingLives()).isEqualTo(5);
        assertThat(view.players().get(0).eliminated()).isFalse();
        assertThat(view.players().get(0).score()).isZero();
        assertThat(view.fields().keySet()).containsExactly("NORTH", "EAST", "SOUTH", "WEST");
        view.fields().values().forEach(cells -> assertThat(cells).hasSize(4));
        assertThat(view.court().keySet())
                .containsExactly("PROVISION", "SOLDIER", "HORSE", "CHARIOT", "KNIGHT");
        assertThat(view.submittedSeats()).isEmpty();
        assertThat(view.submittedEffectSeats()).isEmpty();
        assertThat(view.lastRolls()).isEmpty();
        assertThat(view.lastDropOrder()).isEmpty();
        assertThat(view.eliminatedSeats()).isEmpty();
        assertThat(view.events()).isNotEmpty();
        // contract §2.5-1: round 1 already refreshed 1–5 public pieces
        assertThat(courtTotal(view)).isBetween(19, 23);
    }

    @Test
    void supportsThreeAndFourPlayers() {
        assertThat(service(2).create(3).players()).hasSize(3);
        KingGameView four = service(3).create(4);
        assertThat(four.players()).hasSize(4);
        assertThat(four.players().get(3).side()).isEqualTo("WEST");
    }

    @Test
    void rejectsPlayerCountsOutsideTheAllowedRange() {
        assertThatThrownBy(() -> service(1).create(1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service(1).create(5)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service(1).create(0)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnknownGames() {
        assertThatThrownBy(() -> service(1).get("missing")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service(1).resolve("missing")).isInstanceOf(BusinessException.class);
    }

    @Test
    void aSubmittedDropStaysSecretUntilResolve() {
        KingGameService service = service(1);
        KingGameView created = service.create(2);
        String gameId = created.gameId();
        List<int[]> empty = emptyCells(created);
        int[] first = empty.get(0);
        int[] second = empty.get(1);

        KingGameView afterFirst = service.deploy(gameId, 0,
                List.of(drop(0, side(first), first[1], PieceKind.KING)));
        assertThat(afterFirst.submittedSeats()).containsExactly(0);
        // manual: 每个人的行动轮决策都是秘密进行的 —— 提交本身不得泄到棋盘上
        assertThat(at(afterFirst, first)).isNull();

        // a second submission replaces the first one entirely
        KingGameView afterSecond = service.deploy(gameId, 0,
                List.of(drop(0, side(second), second[1], PieceKind.QUEEN)));
        assertThat(afterSecond.submittedSeats()).containsExactly(0);
        assertThat(at(afterSecond, first)).isNull();
        assertThat(at(afterSecond, second)).isNull();

        // resolve is what reveals the committed drop
        service.deploy(gameId, 1, List.of());
        KingGameView resolved = service.resolve(gameId);
        assertThat(at(resolved, first)).isNull();
        assertThat(at(resolved, second).kind()).isEqualTo("QUEEN");
        assertThat(at(resolved, second).ownerSeat()).isEqualTo(0);
    }

    @Test
    void aDeployedDropGetsAnIdWhenTheRoundResolves() {
        KingGameService service = service(1);
        KingGameView created = service.create(2);
        String gameId = created.gameId();
        int[] cell = emptyCells(created).get(0);

        service.deploy(gameId, 0, List.of(drop(0, side(cell), cell[1], PieceKind.KING)));
        service.deploy(gameId, 1, List.of());
        KingGameView resolved = service.resolve(gameId);

        assertThat(at(resolved, cell).id()).isPositive();
        assertThat(at(resolved, cell).kind()).isEqualTo("KING");
        assertThat(at(resolved, cell).dropOrdinal()).isPositive();
    }

    @Test
    void deployAllowsAnEmptySubmission() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        KingGameView view = service.deploy(gameId, 0, List.of());

        assertThat(view.submittedSeats()).containsExactly(0);
        assertThat(view.phase()).isEqualTo("PLACING");
    }

    @Test
    void deployRejectsAPieceTheSeatDoesNotHold() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        assertThatThrownBy(() -> service.deploy(gameId, 0,
                List.of(drop(0, Side.NORTH, 0, PieceKind.SOLDIER))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deployRejectsDuplicatesInsideOneSeatsOwnSubmission() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        assertThatThrownBy(() -> service.deploy(gameId, 0, List.of(
                drop(0, Side.NORTH, 0, PieceKind.KING),
                drop(0, Side.NORTH, 0, PieceKind.QUEEN))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deployRejectsCellsOutsideTheField() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        assertThatThrownBy(() -> service.deploy(gameId, 0,
                List.of(drop(0, Side.NORTH, 4, PieceKind.KING))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deployRejectsAnUnknownSeat() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        assertThatThrownBy(() -> service.deploy(gameId, 2, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveRequiresEverySeatToHaveSubmitted() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();
        service.deploy(gameId, 0, List.of());

        assertThatThrownBy(() -> service.resolve(gameId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveFillsRollsOrderAndEventsThenEndsTheRound() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();
        service.deploy(gameId, 0, List.of(drop(0, Side.NORTH, 0, PieceKind.KING)));
        service.deploy(gameId, 1, List.of());

        KingGameView view = service.resolve(gameId);

        assertThat(view.phase()).isEqualTo("ROUND_END");
        assertThat(view.lastRolls()).containsOnlyKeys("0", "1");
        view.lastRolls().values().forEach(v -> assertThat(v).isBetween(1, 20));
        assertThat(view.lastDropOrder()).containsExactlyInAnyOrder(0, 1);
        assertThat(view.events()).anyMatch(e -> e.contains("Player 0 dropped KING"));
        assertThat(view.submittedSeats()).isEmpty();
        assertThat(view.fields().get("NORTH").get(0).kind()).isEqualTo("KING");
        assertThat(view.fields().get("NORTH").get(0).ownerSeat()).isEqualTo(0);
    }

    @Test
    void nextRoundRefreshesSpawnsAndAdvancesTheRound() {
        KingGameService service = service(5);
        String gameId = service.create(2).gameId();
        int courtBefore = courtTotal(service.get(gameId));
        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        service.resolve(gameId);

        KingGameView view = service.nextRound(gameId);

        assertThat(view.phase()).isEqualTo("PLACING");
        assertThat(view.roundNo()).isEqualTo(2);
        assertThat(view.submittedSeats()).isEmpty();
        // 1–5 public pieces were refreshed out of the central Court
        int courtAfter = courtTotal(view);
        assertThat(courtBefore - courtAfter).isBetween(1, 5);
    }

    @Test
    void nextRoundIsRejectedOutsideRoundEnd() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();

        assertThatThrownBy(() -> service.nextRound(gameId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void effectsAreRejectedWhenNobodyHasAnEffectOnTheTable() {
        KingGameService service = service(3);
        String gameId = service.create(2).gameId();
        service.deploy(gameId, 0, List.of(drop(0, Side.NORTH, 0, PieceKind.KING)));
        service.deploy(gameId, 1, List.of());
        service.resolve(gameId);

        // no piece with a special effect on the table → the round skips EFFECTS
        assertThat(service.get(gameId).phase()).isEqualTo("ROUND_END");
        assertThatThrownBy(() -> service.submitEffects(gameId, 0, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void strategistRecallIsAdjudicatedByTheBackend() {
        KingGameService service = service(3);
        String gameId = service.create(2).gameId();
        // the Strategist is the effect-bearing private piece every seat starts with
        service.deploy(gameId, 0, List.of(drop(0, Side.NORTH, 0, PieceKind.STRATEGIST)));
        service.deploy(gameId, 1, List.of());
        KingGameView afterResolve = service.resolve(gameId);
        assertThat(afterResolve.phase()).isEqualTo("EFFECTS");

        long strategistId = afterResolve.fields().get("NORTH").get(0).id();
        KingGameView afterFirstSeat = service.submitEffects(gameId, 0, List.of(
                new EffectAction(EffectType.STRATEGIST_RECALL, strategistId, null, strategistId)));

        // one seat is still pending, so the window stays open
        assertThat(afterFirstSeat.phase()).isEqualTo("EFFECTS");
        assertThat(afterFirstSeat.submittedEffectSeats()).containsExactly(0);

        KingGameView settled = service.submitEffects(gameId, 1, List.of());

        assertThat(settled.phase()).isEqualTo("ROUND_END");
        assertThat(settled.submittedEffectSeats()).isEmpty();
        // the backend recalled the Strategist: off the table, back in hand
        assertThat(settled.fields().get("NORTH").get(0)).isNull();
        assertThat(settled.players().get(0).hand()).contains("STRATEGIST");
        assertThat(settled.events()).anyMatch(e -> e.contains("Strategist recalled"));
    }

    @Test
    void deployIsRejectedOutsideThePlacingPhase() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();
        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        service.resolve(gameId);

        assertThatThrownBy(() -> service.deploy(gameId, 0,
                List.of(drop(0, Side.NORTH, 0, PieceKind.KING))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.resolve(gameId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void crossSeatContestsOnOneCellAreLegalAndTheLaterDropWins() {
        KingGameService service = service(1);
        String gameId = service.create(2).gameId();
        service.deploy(gameId, 0, List.of(drop(0, Side.NORTH, 0, PieceKind.KING)));
        service.deploy(gameId, 1, List.of(drop(1, Side.NORTH, 0, PieceKind.QUEEN)));

        KingGameView view = service.resolve(gameId);

        assertThat(view.phase()).isEqualTo("ROUND_END");
        List<Integer> order = view.lastDropOrder();
        int lastDropper = order.get(order.size() - 1);
        assertThat(view.fields().get("NORTH").get(0).ownerSeat()).isEqualTo(lastDropper);
        assertThat(view.fields().get("NORTH").get(0).kind())
                .isEqualTo(lastDropper == 0 ? "KING" : "QUEEN");
    }

    @Test
    void aDrainedPlayerIsEliminatedWhenTheNextRoundStarts() {
        KingGameService service = service(7);
        String gameId = service.create(2).gameId();
        // seat 1 is drained: empty hand, nothing on the table, one life left
        KingGame game = service.liveState(gameId);
        game.player(1).getHand().clear();
        game.player(1).setKingLives(1);

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        KingGameView resolved = service.resolve(gameId);

        // contract §2.4: the check runs when the next round starts, not mid-round
        assertThat(resolved.phase()).isEqualTo("ROUND_END");
        assertThat(resolved.eliminatedSeats()).isEmpty();

        KingGameView next = service.nextRound(gameId);

        assertThat(next.phase()).isEqualTo("FINISHED");
        assertThat(next.finished()).isTrue();
        assertThat(next.winnerSeat()).isEqualTo(0);
        assertThat(next.eliminatedSeats()).containsExactly(1);
        assertThat(next.players().get(1).eliminated()).isTrue();
        assertThat(next.players().get(1).kingLives()).isZero();
        assertThat(next.events()).anyMatch(e -> e.contains("eliminated"));
    }

    @Test
    void aDrainedPlayerWithLivesLeftGetsTheKingBackInstead() {
        KingGameService service = service(7);
        String gameId = service.create(2).gameId();
        KingGame game = service.liveState(gameId);
        game.player(1).getHand().clear();

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        service.resolve(gameId);
        KingGameView next = service.nextRound(gameId);

        assertThat(next.phase()).isEqualTo("PLACING");
        assertThat(next.players().get(1).kingLives()).isEqualTo(4);
        assertThat(next.players().get(1).hand()).containsExactly("KING");
        assertThat(next.players().get(1).eliminated()).isFalse();
        assertThat(next.eliminatedSeats()).isEmpty();
    }

    // ===================== atomic effects round (contract §2.6) =====================

    /** An empty cell whose ring successor is empty too, so a Horse step is never blocked. */
    private static Board.Cell freeRingCell(KingGame game) {
        for (Side side : Side.values()) {
            for (int i = 0; i < Side.CELL_COUNT; i++) {
                if (game.getBoard().field(side).at(i) == null
                        && game.getBoard().field(side).at(Side.nextCell(i)) == null) {
                    return new Board.Cell(side, i);
                }
            }
        }
        throw new IllegalStateException("no pair of adjacent empty cells for the test Horse");
    }

    /** Puts a public piece of {@code kind} on the board, owned by {@code seat}. */
    private static Piece placeOnBoard(KingGame game, int seat, PieceKind kind, Board.Cell cell) {
        Piece piece = new Piece(game.nextPieceId(), kind, seat, game.nextDropOrdinal());
        game.getBoard().field(cell.side()).place(cell.cellIndex(), piece);
        return piece;
    }

    /** Kings of {@code seat} anywhere in the view: hand + table. */
    private static int kingInstances(KingGameView view, int seat) {
        int total = view.players().get(seat).hand().stream()
                .filter("KING"::equals).toList().size();
        for (Side side : Side.values()) {
            for (PieceView piece : view.fields().get(side.name())) {
                if (piece != null && piece.ownerSeat() != null && piece.ownerSeat() == seat
                        && "KING".equals(piece.kind())) {
                    total++;
                }
            }
        }
        return total;
    }

    @Test
    void aRejectedEffectSubmissionLeavesTheBoardIntactAndCanSafelyBeRetried() {
        KingGameService service = service(3);
        String gameId = service.create(2).gameId();
        KingGame game = service.liveState(gameId);
        Board.Cell cell = freeRingCell(game);
        Piece horse = placeOnBoard(game, 0, PieceKind.HORSE, cell);

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        assertThat(service.resolve(gameId).phase()).isEqualTo("EFFECTS");

        KingGameView open = service.submitEffects(gameId, 0, List.of(
                new EffectAction(EffectType.HORSE_STEP, horse.getId(), null, null)));
        assertThat(open.phase()).isEqualTo("EFFECTS");
        assertThat(open.submittedEffectSeats()).containsExactly(0);

        // seat 1 references a piece that is not on the table: the whole round is rejected
        assertThatThrownBy(() -> service.submitEffects(gameId, 1, List.of(
                new EffectAction(EffectType.HORSE_STEP, 999_999L, null, null))))
                .isInstanceOf(BusinessException.class);

        KingGameView rejected = service.get(gameId);
        assertThat(rejected.phase()).isEqualTo("EFFECTS");
        // the rejected submission was not recorded either
        assertThat(rejected.submittedEffectSeats()).containsExactly(0);
        // and seat 0's already-submitted Horse step was NOT applied half-way
        assertThat(game.getBoard().field(cell.side()).at(cell.cellIndex())).isSameAs(horse);
        assertThat(game.getBoard().field(cell.side()).at(Side.nextCell(cell.cellIndex()))).isNull();

        // the seat corrects its submission: the round settles exactly once
        KingGameView settled = service.submitEffects(gameId, 1, List.of());

        assertThat(settled.phase()).isEqualTo("ROUND_END");
        assertThat(settled.submittedEffectSeats()).isEmpty();
        assertThat(game.getBoard().field(cell.side()).at(cell.cellIndex())).isNull();
        assertThat(game.getBoard().field(cell.side()).at(Side.nextCell(cell.cellIndex())))
                .isSameAs(horse);
        // one cell only: a retried settlement must not walk the Horse twice
        assertThat(game.getBoard().field(cell.side())
                .at(Side.nextCell(Side.nextCell(cell.cellIndex())))).isNotSameAs(horse);
    }

    @Test
    void aRejectedEffectSubmissionDoesNotFreezeTheRoundForTheOtherSeats() {
        KingGameService service = service(3);
        String gameId = service.create(2).gameId();
        KingGame game = service.liveState(gameId);
        Board.Cell cell = freeRingCell(game);
        Piece horse = placeOnBoard(game, 0, PieceKind.HORSE, cell);

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        service.resolve(gameId);

        // seat 0 submits an illegal action first: still nothing is recorded
        assertThatThrownBy(() -> service.submitEffects(gameId, 0, List.of(
                new EffectAction(EffectType.CHARIOT_MOVE, horse.getId(), 1, null))))
                .isInstanceOf(BusinessException.class);
        assertThat(service.get(gameId).submittedEffectSeats()).isEmpty();

        KingGameView afterSeat0 = service.submitEffects(gameId, 0, List.of(
                new EffectAction(EffectType.HORSE_STEP, horse.getId(), null, null)));
        assertThat(afterSeat0.submittedEffectSeats()).containsExactly(0);
        assertThat(afterSeat0.phase()).isEqualTo("EFFECTS");

        KingGameView settled = service.submitEffects(gameId, 1, List.of());
        assertThat(settled.phase()).isEqualTo("ROUND_END");
        assertThat(game.getBoard().field(cell.side()).at(Side.nextCell(cell.cellIndex())))
                .isSameAs(horse);
    }

    // ===================== single King instance (contract §2.3 / §2.4) =====================

    @Test
    void aSeatWithTheKingOnTheTableGetsExactlyOneKingBack() {
        KingGameService service = service(7);
        String gameId = service.create(2).gameId();
        KingGame game = service.liveState(gameId);
        Board.Cell cell = game.getBoard().emptyCells().get(0);
        Piece king = placeOnBoard(game, 1, PieceKind.KING, cell);
        // seat 1 used up its hand while its King was still standing on the table
        game.player(1).getHand().clear();

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        assertThat(service.resolve(gameId).phase()).isEqualTo("ROUND_END");

        KingGameView next = service.nextRound(gameId);

        assertThat(next.phase()).isEqualTo("PLACING");
        assertThat(next.players().get(1).hand()).containsExactly("KING");
        assertThat(next.players().get(1).kingLives()).isEqualTo(4);
        // the King on the table was taken back instead of a second one appearing
        assertThat(kingInstances(next, 1)).isEqualTo(1);
        assertThat(next.fields().get(cell.side().name()).get(cell.cellIndex())).isNull();
        assertThat(game.getBoard().field(cell.side()).at(cell.cellIndex())).isNotSameAs(king);
    }

    // ===================== draw when everyone goes out together (contract §2.7) =====================

    @Test
    void aSimultaneousKnockoutFinishesTheGameAsADrawInsteadOfLoopingForever() {
        KingGameService service = service(7);
        String gameId = service.create(2).gameId();
        KingGame game = service.liveState(gameId);
        game.player(0).getHand().clear();
        game.player(0).setKingLives(1);
        game.player(1).getHand().clear();
        game.player(1).setKingLives(1);

        service.deploy(gameId, 0, List.of());
        service.deploy(gameId, 1, List.of());
        assertThat(service.resolve(gameId).phase()).isEqualTo("ROUND_END");

        KingGameView next = service.nextRound(gameId);

        assertThat(next.phase()).isEqualTo("FINISHED");
        assertThat(next.finished()).isTrue();
        assertThat(next.winnerSeat()).isNull();
        assertThat(next.eliminatedSeats()).containsExactly(0, 1);
        assertThat(next.events()).anyMatch(e -> e.contains("draw"));
        // never back to PLACING: every write is refused from here on
        assertThatThrownBy(() -> service.nextRound(gameId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deploy(gameId, 0, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.submitEffects(gameId, 0, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThat(service.get(gameId).phase()).isEqualTo("FINISHED");
    }

    private static int courtTotal(KingGameView view) {
        return view.court().values().stream().mapToInt(Integer::intValue).sum();
    }
}
