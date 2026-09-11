package com.test.engine.kingchess.resolve;

import com.test.engine.exception.BusinessException;
import com.test.engine.kingchess.model.EffectAction;
import com.test.engine.kingchess.model.EffectType;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.rules.RanzhongRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectResolverTest {

    private static KingGame game() {
        return KingGame.create(List.of(0, 1), RanzhongRules.STARTING_PRIVATE_HAND);
    }

    private static Piece place(KingGame game, int seat, PieceKind kind, Side side, int cell) {
        Piece piece = new Piece(game.nextPieceId(), kind, seat, game.nextDropOrdinal());
        game.getBoard().field(side).place(cell, piece);
        game.player(seat).getHand().remove(kind);
        return piece;
    }

    private static EffectAction step(Long pieceId) {
        return new EffectAction(EffectType.HORSE_STEP, pieceId, null, null);
    }

    private static EffectAction move(Long pieceId, Integer target) {
        return new EffectAction(EffectType.CHARIOT_MOVE, pieceId, target, null);
    }

    private static EffectAction recallSelf(Long pieceId) {
        return new EffectAction(EffectType.KNIGHT_RECALL, pieceId, null, null);
    }

    private static EffectAction strategistRecall(Long pieceId, Long targetPieceId) {
        return new EffectAction(EffectType.STRATEGIST_RECALL, pieceId, null, targetPieceId);
    }

    // ===================== HORSE_STEP (contract §2.6, arrow ring) =====================

    @Test
    void horseStepsOneCellAlongTheArrowRing() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);

        List<String> events = EffectResolver.resolve(game, Map.of(0, List.of(step(horse.getId()))));

        assertThat(game.getBoard().field(Side.NORTH).at(0)).isNull();
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(horse);
        assertThat(events).isNotEmpty();
    }

    @Test
    void horseWrapsAroundTheEndOfTheRing() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 3);

        EffectResolver.resolve(game, Map.of(0, List.of(step(horse.getId()))));

        // contract §2.2 / DEFAULT-2: 0 → 1 → 2 → 3 → 0
        assertThat(game.getBoard().field(Side.NORTH).at(0)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(3)).isNull();
    }

    @Test
    void horseDoesNotMoveWhenTheNextCellIsOccupied() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);
        Piece blocker = place(game, 0, PieceKind.PROVISION, Side.NORTH, 1);

        List<String> events = EffectResolver.resolve(game, Map.of(0, List.of(step(horse.getId()))));

        assertThat(game.getBoard().field(Side.NORTH).at(0)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(blocker);
        assertThat(events).anyMatch(e -> e.contains("blocked"));
    }

    // ===================== CHARIOT_MOVE =====================

    @Test
    void chariotMovesToAnEmptyCellOfItsOwnField() {
        KingGame game = game();
        Piece chariot = place(game, 0, PieceKind.CHARIOT, Side.NORTH, 0);

        EffectResolver.resolve(game, Map.of(0, List.of(move(chariot.getId(), 3))));

        assertThat(game.getBoard().field(Side.NORTH).at(0)).isNull();
        assertThat(game.getBoard().field(Side.NORTH).at(3)).isSameAs(chariot);
    }

    @Test
    void chariotStaysWhenTheTargetCellIsOccupied() {
        KingGame game = game();
        Piece chariot = place(game, 0, PieceKind.CHARIOT, Side.EAST, 0);
        place(game, 0, PieceKind.SOLDIER, Side.EAST, 2);

        EffectResolver.resolve(game, Map.of(0, List.of(move(chariot.getId(), 2))));

        assertThat(game.getBoard().field(Side.EAST).at(0)).isSameAs(chariot);
        assertThat(game.getBoard().field(Side.EAST).at(2).getKind()).isEqualTo(PieceKind.SOLDIER);
    }

    @Test
    void chariotCannotLeaveItsField() {
        KingGame game = game();
        Piece chariot = place(game, 0, PieceKind.CHARIOT, Side.NORTH, 0);

        assertThatThrownBy(() -> EffectResolver.resolve(game, Map.of(0, List.of(move(chariot.getId(), 4)))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EffectResolver.resolve(game, Map.of(0, List.of(move(chariot.getId(), null)))))
                .isInstanceOf(BusinessException.class);
    }

    // ===================== recalls =====================

    @Test
    void knightRecallReturnsTheKnightToHand() {
        KingGame game = game();
        Piece knight = place(game, 0, PieceKind.KNIGHT, Side.WEST, 2);

        EffectResolver.resolve(game, Map.of(0, List.of(recallSelf(knight.getId()))));

        assertThat(game.getBoard().field(Side.WEST).at(2)).isNull();
        assertThat(game.player(0).getHand()).contains(PieceKind.KNIGHT);
    }

    @Test
    void strategistRecallsAnyOwnPieceOnTheTable() {
        KingGame game = game();
        Piece strategist = place(game, 0, PieceKind.STRATEGIST, Side.SOUTH, 0);
        Piece soldier = place(game, 0, PieceKind.SOLDIER, Side.SOUTH, 3);

        EffectResolver.resolve(game,
                Map.of(0, List.of(strategistRecall(strategist.getId(), soldier.getId()))));

        assertThat(game.getBoard().field(Side.SOUTH).at(3)).isNull();
        assertThat(game.player(0).getHand()).contains(PieceKind.SOLDIER);
        assertThat(game.getBoard().field(Side.SOUTH).at(0)).isSameAs(strategist);
    }

    // ===================== validation =====================

    @Test
    void rejectsPiecesTheSubmitterDoesNotOwn() {
        KingGame game = game();
        Piece enemy = place(game, 1, PieceKind.HORSE, Side.NORTH, 0);

        assertThatThrownBy(() -> EffectResolver.resolve(game, Map.of(0, List.of(step(enemy.getId())))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPiecesThatAreNotOnTheTable() {
        KingGame game = game();
        assertThatThrownBy(() -> EffectResolver.resolve(game, Map.of(0, List.of(step(999L)))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnEffectThatDoesNotMatchThePieceKind() {
        KingGame game = game();
        Piece chariot = place(game, 0, PieceKind.CHARIOT, Side.NORTH, 0);

        assertThatThrownBy(() -> EffectResolver.resolve(game, Map.of(0, List.of(step(chariot.getId())))))
                .isInstanceOf(BusinessException.class);
    }

    // ===================== ordering / idempotence =====================

    @Test
    void movementActionsSettleBeforeRecallActions() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.SOUTH, 1);
        Piece strategist = place(game, 0, PieceKind.STRATEGIST, Side.SOUTH, 0);

        // Submitted recall-first; the resolver must still move before recalling.
        List<String> events = EffectResolver.resolve(game, Map.of(0, List.of(
                strategistRecall(strategist.getId(), horse.getId()),
                step(horse.getId()))));

        assertThat(game.getBoard().field(Side.SOUTH).at(2)).isNull();
        assertThat(game.player(0).getHand()).contains(PieceKind.HORSE);
        int stepIndex = indexOf(events, "stepped");
        int recallIndex = indexOf(events, "recalled");
        assertThat(stepIndex).isGreaterThanOrEqualTo(0);
        assertThat(recallIndex).isGreaterThan(stepIndex);
    }

    @Test
    void eachPieceActsAtMostOncePerRound() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);

        EffectResolver.resolve(game, Map.of(0, List.of(step(horse.getId()), step(horse.getId()))));

        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(2)).isNull();
    }

    @Test
    void effectSettlementKeepsTheRoundAlive() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);
        EffectResolver.resolve(game, Map.of(0, List.of(step(horse.getId()))));
        // the resolver only settles actions; the phase transition belongs to the service
        assertThat(game.getPhase()).isEqualTo(GamePhase.PLACING);
    }

    // ===================== validation is read-only (contract §2.6) =====================

    @Test
    void validateRejectsEveryStaticIllegalConditionWithoutTouchingAnything() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);
        Piece chariot = place(game, 0, PieceKind.CHARIOT, Side.NORTH, 1);
        Piece strategist = place(game, 0, PieceKind.STRATEGIST, Side.SOUTH, 0);
        Piece enemy = place(game, 1, PieceKind.HORSE, Side.EAST, 0);

        List<EffectAction> illegal = List.of(
                new EffectAction(null, horse.getId(), null, null),        // unknown type
                new EffectAction(EffectType.HORSE_STEP, null, null, null), // missing piece id
                step(999L),                                               // not on the table
                step(enemy.getId()),                                      // not the submitter's
                step(chariot.getId()),                                    // wrong kind
                move(chariot.getId(), null),                              // missing target cell
                move(chariot.getId(), Side.CELL_COUNT),                   // off the Field
                strategistRecall(strategist.getId(), null),               // missing target piece
                strategistRecall(strategist.getId(), 999L),               // target off the table
                strategistRecall(strategist.getId(), enemy.getId()));      // target not the submitter's

        for (EffectAction action : illegal) {
            assertThatThrownBy(() -> EffectResolver.validate(game, Map.of(0, List.of(action))))
                    .as("action %s", action)
                    .isInstanceOf(BusinessException.class);
        }

        // validation never mutates: every piece stays where it was
        assertThat(game.getBoard().field(Side.NORTH).at(0)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(chariot);
        assertThat(game.getBoard().field(Side.SOUTH).at(0)).isSameAs(strategist);
        assertThat(game.getBoard().field(Side.EAST).at(0)).isSameAs(enemy);
        assertThat(game.player(0).getHand())
                .containsExactly(PieceKind.KING, PieceKind.QUEEN, PieceKind.MARTYR);
        assertThat(game.getPhase()).isEqualTo(GamePhase.PLACING);
    }

    @Test
    void validateRejectsANullActionEntry() {
        KingGame game = game();
        List<EffectAction> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> EffectResolver.validate(game, Map.of(0, withNull)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anIllegalActionAnywhereAbortsTheSettlementBeforeAnyPieceMoves() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);

        Map<Integer, List<EffectAction>> submissions = new LinkedHashMap<>();
        submissions.put(0, List.of(step(horse.getId())));   // perfectly legal on its own
        submissions.put(1, List.of(step(horse.getId())));   // seat 1 does not own that Horse

        assertThatThrownBy(() -> EffectResolver.resolve(game, submissions))
                .isInstanceOf(BusinessException.class);

        // the legal half of the submission was NOT applied (no half-settled board)
        assertThat(game.getBoard().field(Side.NORTH).at(0)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isNull();
    }

    @Test
    void aRejectedSettlementCanBeRetriedAndAppliesEachEffectExactlyOnce() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);

        Map<Integer, List<EffectAction>> broken = new LinkedHashMap<>();
        broken.put(0, List.of(step(horse.getId())));
        broken.put(1, List.of(step(horse.getId())));
        assertThatThrownBy(() -> EffectResolver.resolve(game, broken))
                .isInstanceOf(BusinessException.class);
        assertThat(game.getBoard().field(Side.NORTH).at(0)).isSameAs(horse);

        // seat 1 fixes its submission; the round now settles exactly once
        Map<Integer, List<EffectAction>> fixed = new LinkedHashMap<>();
        fixed.put(0, List.of(step(horse.getId())));
        fixed.put(1, List.of());
        EffectResolver.resolve(game, fixed);

        assertThat(game.getBoard().field(Side.NORTH).at(0)).isNull();
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(horse);
        // one step, not two: the retry must not re-apply the first attempt
        assertThat(game.getBoard().field(Side.NORTH).at(2)).isNull();
    }

    @Test
    void theSettlementRunsInAscendingSeatOrderWhateverTheCallersMapOrder() {
        KingGame game = game();
        Piece horse = place(game, 0, PieceKind.HORSE, Side.NORTH, 0);
        Piece other = place(game, 1, PieceKind.HORSE, Side.NORTH, 2);
        // the seats are inserted in reverse order on purpose
        Map<Integer, List<EffectAction>> submissions = new LinkedHashMap<>();
        submissions.put(1, List.of(step(other.getId())));
        submissions.put(0, List.of(step(horse.getId())));

        List<String> events = EffectResolver.resolve(game, submissions);

        assertThat(indexOf(events, "Horse " + horse.getId()))
                .isLessThan(indexOf(events, "Horse " + other.getId()));
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isSameAs(horse);
        assertThat(game.getBoard().field(Side.NORTH).at(3)).isSameAs(other);
    }

    // ===================== dynamic conditions are no-ops, never throws =====================

    @Test
    void aTargetTakenOffTheTableByAnEarlierActionIsANoOpInsteadOfAnError() {
        KingGame game = game();
        Piece strategist = place(game, 0, PieceKind.STRATEGIST, Side.NORTH, 0);
        Piece knight = place(game, 0, PieceKind.KNIGHT, Side.NORTH, 1);

        // the Knight is recalled first; the Strategist's recall of it cannot land
        List<String> events = EffectResolver.resolve(game, Map.of(0, List.of(
                recallSelf(knight.getId()),
                strategistRecall(strategist.getId(), knight.getId()))));

        assertThat(events).anyMatch(e -> e.contains("no longer on the table"));
        assertThat(game.getBoard().field(Side.NORTH).at(1)).isNull();
        assertThat(game.player(0).getHand()).contains(PieceKind.KNIGHT);
    }

    @Test
    void anActingPieceRecalledEarlierInTheRoundIsIgnoredInsteadOfThrowing() {
        KingGame game = game();
        Piece first = place(game, 0, PieceKind.STRATEGIST, Side.SOUTH, 0);
        Piece second = place(game, 0, PieceKind.STRATEGIST, Side.SOUTH, 1);

        // each Strategist recalls the other: the second one acts after it was taken
        List<String> events = EffectResolver.resolve(game, Map.of(0, List.of(
                strategistRecall(first.getId(), second.getId()),
                strategistRecall(second.getId(), first.getId()))));

        assertThat(events).anyMatch(e -> e.contains("already left the table"));
        assertThat(game.getBoard().field(Side.SOUTH).at(0)).isSameAs(first);
        assertThat(game.getBoard().field(Side.SOUTH).at(1)).isNull();
        assertThat(game.player(0).getHand()).contains(PieceKind.STRATEGIST);
    }

    private static int indexOf(List<String> events, String needle) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
