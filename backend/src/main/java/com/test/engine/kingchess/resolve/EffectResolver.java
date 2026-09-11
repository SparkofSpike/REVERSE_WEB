package com.test.engine.kingchess.resolve;

import com.test.engine.exception.BusinessException;
import com.test.engine.kingchess.model.Board;
import com.test.engine.kingchess.model.EffectAction;
import com.test.engine.kingchess.model.EffectType;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.PlayerState;
import com.test.engine.kingchess.model.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Special-effects round settlement (contract §2.6).
 *
 * <p>Movement actions ({@code HORSE_STEP} / {@code CHARIOT_MOVE}) are settled
 * before recall actions ({@code KNIGHT_RECALL} / {@code STRATEGIST_RECALL})
 * — {@code DEFAULT-7}: 逐项线性结算，先公棋后私棋，每枚棋子每轮至多触发一次.
 * Within a pass, seats run in ascending order and each seat's actions in
 * submission order.
 */
public final class EffectResolver {

    private EffectResolver() {
    }

    /**
     * Settles every submitted effect action.
     *
     * <p><b>Atomic</b>: the whole submission is validated first
     * ({@link #validate}) and the board is only touched once every action is
     * statically legal. A rejected submission therefore leaves the board and the
     * {@link KingGame} exactly as they were, so a seat can correct its actions and
     * resubmit safely (contract §3.5 re-submission is 覆盖式) instead of seeing an
     * earlier settlement applied twice.
     *
     * <p>The submissions are snapshotted before anything runs, so the settlement
     * cannot observe the caller's collections changing under it.
     *
     * @param actionsBySeat seat → submitted actions (already覆盖式 replaced by the service)
     * @return the event log lines produced by the settlement
     * @throws BusinessException when an action references an unknown type, a null
     *         piece id, a piece that is not on the table, a piece that is not the
     *         submitter's, a piece of the wrong kind, or points off the Field
     */
    public static List<String> resolve(KingGame game, Map<Integer, List<EffectAction>> actionsBySeat) {
        Map<Integer, List<EffectAction>> submissions = snapshot(actionsBySeat);
        // contract §2.6: nothing is mutated until the whole submission is legal
        validate(game, submissions);

        List<String> events = new ArrayList<>();
        Set<Long> triggered = new HashSet<>();

        // Pass 1: movement actions, Pass 2: recall actions (contract §2.6).
        for (boolean movementPass : new boolean[]{true, false}) {
            for (Integer seat : submissions.keySet()) {
                for (EffectAction action : submissions.get(seat)) {
                    if (action.type().isMovement() != movementPass) {
                        continue;
                    }
                    apply(game, seat, action, action.type(), triggered, events);
                }
            }
        }
        return events;
    }

    /**
     * Statically validates a whole submission set <b>without touching any
     * state</b>: unknown type, null piece id, piece not on the table, not the
     * submitter's, wrong kind, {@code CHARIOT_MOVE} without / with an off-Field
     * {@code targetCellIndex}, {@code STRATEGIST_RECALL} without / with a foreign
     * {@code targetPieceId}.
     *
     * <p>Only conditions that are static for the submitting seat are rejected
     * here. The order-dependent ones (a target cell that is already occupied, a
     * piece another action of the same round takes off the table) are NOT errors:
     * contract §2.6 settles them as "该动作不生效" (see {@link #apply}).
     *
     * @throws BusinessException on the first statically illegal action
     */
    public static void validate(KingGame game, Map<Integer, List<EffectAction>> actionsBySeat) {
        for (Map.Entry<Integer, List<EffectAction>> entry : snapshot(actionsBySeat).entrySet()) {
            for (EffectAction action : entry.getValue()) {
                validateAction(game, entry.getKey(), action);
            }
        }
    }

    /**
     * Copy of the submissions, in ascending seat order, that tolerates {@code null}
     * entries (they are rejected by {@link #validateAction} rather than blowing up
     * as an NPE).
     */
    private static Map<Integer, List<EffectAction>> snapshot(
            Map<Integer, List<EffectAction>> actionsBySeat) {
        Map<Integer, List<EffectAction>> snapshot = new LinkedHashMap<>();
        if (actionsBySeat == null) {
            return snapshot;
        }
        actionsBySeat.keySet().stream().sorted().forEach(seat -> snapshot.put(seat,
                Collections.unmodifiableList(new ArrayList<>(
                        actionsBySeat.getOrDefault(seat, List.of())))));
        return snapshot;
    }

    private static void validateAction(KingGame game, int seat, EffectAction action) {
        if (action == null) {
            throw new BusinessException("特殊效果动作不能为空");
        }
        EffectType type = action.type();
        if (type == null) {
            throw new BusinessException("未知的特殊效果类型");
        }
        if (action.pieceId() == null) {
            throw new BusinessException("缺少棋子 id");
        }
        Board board = game.getBoard();
        Piece piece = board.findPieceById(action.pieceId());
        if (piece == null) {
            throw new BusinessException("棋子 " + action.pieceId() + " 不在场上");
        }
        if (!piece.ownedBy(seat)) {
            throw new BusinessException("棋子 " + action.pieceId() + " 不属于座位 " + seat);
        }
        PieceKind required = requiredKind(type);
        if (piece.getKind() != required) {
            throw new BusinessException("棋子 " + action.pieceId() + " 不是 " + required
                    + "，无法执行 " + type);
        }
        switch (type) {
            case CHARIOT_MOVE -> {
                Integer target = action.targetCellIndex();
                if (target == null) {
                    throw new BusinessException("车移动必须指定目标格 targetCellIndex");
                }
                if (target < 0 || target >= Side.CELL_COUNT) {
                    throw new BusinessException("目标格必须在 0-" + (Side.CELL_COUNT - 1) + " 之间");
                }
            }
            case STRATEGIST_RECALL -> {
                if (action.targetPieceId() == null) {
                    throw new BusinessException("谋士收回必须指定目标棋子 targetPieceId");
                }
                Piece target = board.findPieceById(action.targetPieceId());
                if (target == null) {
                    throw new BusinessException("棋子 " + action.targetPieceId() + " 不在场上");
                }
                if (!target.ownedBy(seat)) {
                    throw new BusinessException("棋子 " + action.targetPieceId()
                            + " 不属于座位 " + seat);
                }
            }
            default -> {
                // HORSE_STEP / KNIGHT_RECALL carry no extra targets
            }
        }
    }

    /** The piece kind an effect type acts through. */
    private static PieceKind requiredKind(EffectType type) {
        return switch (type) {
            case HORSE_STEP -> PieceKind.HORSE;
            case CHARIOT_MOVE -> PieceKind.CHARIOT;
            case KNIGHT_RECALL -> PieceKind.KNIGHT;
            case STRATEGIST_RECALL -> PieceKind.STRATEGIST;
        };
    }

    /**
     * Applies one already-validated action.
     *
     * <p>Every condition still checked here is dynamic (an earlier action of the
     * same settlement moved the piece or took it off the table), so failing one
     * makes the action a no-op with an event line instead of raising — contract
     * §2.6 ("目标格被占则本回合不移动" and alike). This keeps the apply phase from
     * ever becoming a throw site after the board has begun to change.
     */
    private static void apply(KingGame game, int seat, EffectAction action, EffectType type,
                              Set<Long> triggered, List<String> events) {
        Board board = game.getBoard();
        Piece piece = board.findPieceById(action.pieceId());
        if (piece == null) {
            events.add("Piece " + action.pieceId() + " already left the table (" + type
                    + " ignored)");
            return;
        }
        Board.Cell origin = locationOf(board, piece);
        if (origin == null) {
            events.add("Piece " + piece.getId() + " is not on the table (" + type + " ignored)");
            return;
        }
        // DEFAULT-7: 每枚棋子每轮至多触发一次
        if (!triggered.add(piece.getId())) {
            events.add("Piece " + piece.getId() + " already acted this round (" + type + " ignored)");
            return;
        }

        switch (type) {
            case HORSE_STEP -> {
                int next = Side.nextCell(origin.cellIndex());
                Piece occupant = board.field(origin.side()).at(next);
                if (occupant != null) {
                    events.add("Horse " + piece.getId() + " blocked on "
                            + origin.side() + "-" + next + ": cell occupied");
                    return;
                }
                board.field(origin.side()).place(origin.cellIndex(), null);
                board.field(origin.side()).place(next, piece);
                events.add("Horse " + piece.getId() + " stepped "
                        + origin.side() + "-" + origin.cellIndex() + " → " + origin.side() + "-" + next);
            }
            case CHARIOT_MOVE -> {
                Integer target = action.targetCellIndex();
                if (target == null || target < 0 || target >= Side.CELL_COUNT) {
                    events.add("Chariot " + piece.getId()
                            + " has no target cell inside its Field (" + type + " ignored)");
                    return;
                }
                if (target == origin.cellIndex()) {
                    events.add("Chariot " + piece.getId() + " stayed put");
                    return;
                }
                Piece occupant = board.field(origin.side()).at(target);
                if (occupant != null) {
                    events.add("Chariot " + piece.getId() + " blocked on "
                            + origin.side() + "-" + target + ": cell occupied");
                    return;
                }
                board.field(origin.side()).place(origin.cellIndex(), null);
                board.field(origin.side()).place(target, piece);
                events.add("Chariot " + piece.getId() + " moved "
                        + origin.side() + "-" + origin.cellIndex() + " → " + origin.side() + "-" + target);
            }
            case KNIGHT_RECALL -> {
                // contract DEFAULT-8: a Knight eaten by a faster player already belongs
                // to that player, so its previous owner fails the ownership check above.
                recall(game, piece, events, "Knight");
            }
            case STRATEGIST_RECALL -> {
                Long targetId = action.targetPieceId();
                Piece target = targetId == null ? null : board.findPieceById(targetId);
                if (target == null) {
                    events.add("Strategist " + piece.getId() + ": target " + targetId
                            + " is no longer on the table (recall ignored)");
                    return;
                }
                if (!target.ownedBy(seat)) {
                    events.add("Strategist " + piece.getId() + ": target " + targetId
                            + " no longer belongs to Player " + seat + " (recall ignored)");
                    return;
                }
                recall(game, target, events, "Strategist");
            }
        }
    }

    /** Removes a piece from the table and returns its kind to its owner's hand. */
    private static void recall(KingGame game, Piece piece, List<String> events, String source) {
        Board board = game.getBoard();
        Board.Cell cell = locationOf(board, piece);
        board.removePiece(piece);
        PlayerState owner = game.player(piece.getOwnerSeat());
        owner.getHand().add(piece.getKind());
        events.add(source + " recalled " + piece.getKind() + " "
                + (cell == null ? "" : "from " + cell.side() + "-" + cell.cellIndex())
                + " to Player " + piece.getOwnerSeat() + "'s hand");
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
}
