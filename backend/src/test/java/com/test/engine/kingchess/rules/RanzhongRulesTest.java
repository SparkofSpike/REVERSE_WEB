package com.test.engine.kingchess.rules;

import com.test.engine.kingchess.model.PieceKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RanzhongRulesTest {

    @Test
    void kingEatsAnythingForThree() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.KING, PieceKind.CHARIOT);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(3);
    }

    @Test
    void eatingProvisionScoresFourToEater() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.PROVISION);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(4);
    }

    @Test
    void eatingSoldierScoresTwoToEater() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.KING, PieceKind.SOLDIER);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(3); // king eat overrides victim score
    }

    @Test
    void eatenQueenScoresHerOwner() {
        // Eater is a non-king; the Queen's owner gets the points.
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.QUEEN);
        assertThat(s.toEater()).isFalse();
        assertThat(s.amount()).isEqualTo(8);
    }

    @Test
    void unchartedKindsScoreZero() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.HORSE);
        assertThat(s.amount()).isZero();
    }

    @Test
    void poolCompositionMatchesManual() {
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.PROVISION)).isEqualTo(8);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.SOLDIER)).isEqualTo(6);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.HORSE)).isEqualTo(4);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.CHARIOT)).isEqualTo(4);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.KNIGHT)).isEqualTo(2);
    }
}
