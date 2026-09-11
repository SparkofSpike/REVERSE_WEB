package com.test.engine.kingchess.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The rotational board: four Fields around a central Court (public pool). */
@Getter
public class Board {
    private final Map<Side, Field> fields = new EnumMap<>(Side.class);
    private final List<Piece> court = new ArrayList<>();

    public Board() {
        for (Side side : Side.values()) {
            fields.put(side, new Field(side));
        }
    }

    /** One addressable cell of the board. */
    public record Cell(Side side, int cellIndex) {
    }

    public Field field(Side side) {
        return fields.get(side);
    }

    /** Every piece currently on the table, in field order then cell order. */
    public List<Piece> piecesOnField() {
        List<Piece> all = new ArrayList<>();
        for (Side side : Side.values()) {
            for (Piece piece : field(side).getCells()) {
                if (piece != null) {
                    all.add(piece);
                }
            }
        }
        return all;
    }

    /** The cell index of {@code piece} on {@code side}, or -1 when it is not there. */
    public int cellOf(Side side, Piece piece) {
        Piece[] cells = field(side).getCells();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == piece) {
                return i;
            }
        }
        return -1;
    }

    /** Locates a piece by its stable id; returns null when it is not on the table. */
    public Piece findPieceById(long pieceId) {
        for (Piece piece : piecesOnField()) {
            if (piece.getId() == pieceId) {
                return piece;
            }
        }
        return null;
    }

    /** Removes {@code piece} from whatever cell holds it; true when it was on the table. */
    public boolean removePiece(Piece piece) {
        for (Side side : Side.values()) {
            Field f = field(side);
            for (int i = 0; i < f.getCells().length; i++) {
                if (f.getCells()[i] == piece) {
                    f.place(i, null);
                    return true;
                }
            }
        }
        return false;
    }

    /** Remaining public pieces per kind, in the contract's fixed key order. */
    public Map<PieceKind, Integer> courtCounts() {
        Map<PieceKind, Integer> counts = new LinkedHashMap<>();
        for (PieceKind kind : PieceKind.values()) {
            if (!kind.isPrivatePiece()) {
                counts.put(kind, 0);
            }
        }
        for (Piece piece : court) {
            counts.merge(piece.getKind(), 1, Integer::sum);
        }
        return counts;
    }

    public int courtCount(PieceKind kind) {
        return (int) court.stream().filter(p -> p.getKind() == kind).count();
    }

    /** Empty cells across all four Fields, in field order then cell order. */
    public List<Cell> emptyCells() {
        List<Cell> empty = new ArrayList<>();
        for (Side side : Side.values()) {
            Piece[] cells = field(side).getCells();
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == null) {
                    empty.add(new Cell(side, i));
                }
            }
        }
        return empty;
    }
}
