package com.test.engine.kingchess.spawn;

import com.test.engine.kingchess.model.Board;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.rules.RanzhongRules;
import com.test.engine.utils.DiceRoller;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Public-piece refresh (contract §2.5-1).
 *
 * <p>Every round the whole table spawns 1–5 public pieces (count from
 * {@code DiceRoller.between(1,5)}), the kinds drawn by {@link RanzhongRules#SPAWN_WEIGHTS}
 * and limited by the Court's remaining stock; they land on random empty cells of
 * the four Fields and simply spawn fewer when cells run out.
 *
 * <p>All randomness goes through the injected {@link DiceRoller} — no scattered
 * {@code Random} instances.
 */
@Component
public class PublicSpawner {

    /** Weight scale used to turn doubles into an integer dice range. */
    private static final int WEIGHT_SCALE = 1000;

    private final DiceRoller diceRoller;

    public PublicSpawner(DiceRoller diceRoller) {
        this.diceRoller = diceRoller;
    }

    /**
     * Spawns this round's public pieces onto the board.
     *
     * @return the pieces placed (possibly fewer than the rolled count when the
     *         Court or the Fields run out of room)
     */
    public List<Piece> spawn(KingGame game) {
        // contract §2.5-1: 全场合计随机刷新 1–5 枚（数量走 DiceRoller.between(1,5)）
        int count = diceRoller.between(RanzhongRules.REFRESH_MIN_COUNT, RanzhongRules.REFRESH_MAX_COUNT);
        List<Piece> spawned = new ArrayList<>();
        if (count <= 0) {
            return spawned;
        }
        Board board = game.getBoard();
        List<Board.Cell> empty = new ArrayList<>(board.emptyCells());
        for (int i = 0; i < count && !empty.isEmpty(); i++) {
            PieceKind kind = pickKind(board);
            if (kind == null) {
                break; // Court exhausted — spawn fewer
            }
            Board.Cell cell = empty.remove(diceRoller.between(0, empty.size() - 1));
            Piece piece = takeFromCourt(board, kind);
            if (piece == null) {
                break;
            }
            piece.setOwnerSeat(null);
            piece.setDropOrdinal(game.nextDropOrdinal());
            board.field(cell.side()).place(cell.cellIndex(), piece);
            spawned.add(piece);
        }
        return spawned;
    }

    /** Weighted pick among the public kinds still available in the Court, or null when empty. */
    private PieceKind pickKind(Board board) {
        List<PieceKind> candidates = new ArrayList<>();
        int total = 0;
        for (PieceKind kind : PieceKind.values()) {
            if (kind.isPrivatePiece()) {
                continue;
            }
            Integer count = board.courtCounts().get(kind);
            Double weight = RanzhongRules.SPAWN_WEIGHTS.get(kind);
            if (count == null || count <= 0 || weight == null || weight <= 0) {
                continue;
            }
            candidates.add(kind);
            total += (int) Math.round(weight * WEIGHT_SCALE);
        }
        if (candidates.isEmpty() || total <= 0) {
            return null;
        }
        int roll = diceRoller.between(1, total);
        int cursor = 0;
        for (PieceKind kind : candidates) {
            cursor += (int) Math.round(RanzhongRules.SPAWN_WEIGHTS.get(kind) * WEIGHT_SCALE);
            if (roll <= cursor) {
                return kind;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Removes one piece of {@code kind} from the Court pool. */
    private Piece takeFromCourt(Board board, PieceKind kind) {
        for (int i = 0; i < board.getCourt().size(); i++) {
            if (board.getCourt().get(i).getKind() == kind) {
                return board.getCourt().remove(i);
            }
        }
        return null;
    }
}
