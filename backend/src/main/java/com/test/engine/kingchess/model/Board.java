package com.test.engine.kingchess.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.EnumMap;
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

    public Field field(Side side) {
        return fields.get(side);
    }
}
