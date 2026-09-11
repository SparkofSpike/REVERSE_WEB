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
    void kingEatingProvisionStillScoresOnlyThree() {
        // contract §2.3: 国王吃任何棋固定 +3，覆盖被吃棋自身的分值
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.KING, PieceKind.PROVISION);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(3);
        // the Provision itself is still recycled to the Court
        assertThat(RanzhongRules.captureTarget(PieceKind.PROVISION))
                .isEqualTo(RanzhongRules.CaptureTarget.COURT);
    }

    @Test
    void eatingProvisionScoresFourToEater() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.PROVISION);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(4);
    }

    @Test
    void eatingSoldierScoresTwoToEater() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.CHARIOT, PieceKind.SOLDIER);
        assertThat(s.toEater()).isTrue();
        assertThat(s.amount()).isEqualTo(2);
    }

    @Test
    void eatenQueenScoresHerOwner() {
        // Eater is a non-king; the Queen's owner gets the points.
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.QUEEN);
        assertThat(s.toEater()).isFalse();
        assertThat(s.amount()).isEqualTo(8);
    }

    @Test
    void eatenKingScoresNothing() {
        RanzhongRules.ScoreEvent s = RanzhongRules.eatScore(PieceKind.SOLDIER, PieceKind.KING);
        assertThat(s.amount()).isZero();
    }

    @Test
    void horseChariotKnightScoreZero() {
        for (PieceKind kind : new PieceKind[]{PieceKind.HORSE, PieceKind.CHARIOT, PieceKind.KNIGHT}) {
            assertThat(RanzhongRules.eatScore(PieceKind.SOLDIER, kind).amount()).isZero();
        }
    }

    @Test
    void captureDestinationFollowsContract() {
        // contract §2.3 / DEFAULT-4
        assertThat(RanzhongRules.captureTarget(PieceKind.SOLDIER))
                .isEqualTo(RanzhongRules.CaptureTarget.EATER_HAND);
        assertThat(RanzhongRules.captureTarget(PieceKind.HORSE))
                .isEqualTo(RanzhongRules.CaptureTarget.EATER_HAND);
        assertThat(RanzhongRules.captureTarget(PieceKind.CHARIOT))
                .isEqualTo(RanzhongRules.CaptureTarget.EATER_HAND);
        assertThat(RanzhongRules.captureTarget(PieceKind.KNIGHT))
                .isEqualTo(RanzhongRules.CaptureTarget.EATER_HAND);
        assertThat(RanzhongRules.captureTarget(PieceKind.PROVISION))
                .isEqualTo(RanzhongRules.CaptureTarget.COURT);
        assertThat(RanzhongRules.captureTarget(PieceKind.KING))
                .isEqualTo(RanzhongRules.CaptureTarget.OWNER_HAND);
        assertThat(RanzhongRules.captureTarget(PieceKind.QUEEN))
                .isEqualTo(RanzhongRules.CaptureTarget.REMOVED);
        assertThat(RanzhongRules.captureTarget(PieceKind.MARTYR))
                .isEqualTo(RanzhongRules.CaptureTarget.REMOVED);
        assertThat(RanzhongRules.captureTarget(PieceKind.STRATEGIST))
                .isEqualTo(RanzhongRules.CaptureTarget.REMOVED);
    }

    @Test
    void startingKingLivesIsFive() {
        assertThat(RanzhongRules.STARTING_KING_LIVES).isEqualTo(5);
        assertThat(RanzhongRules.KING_LIVES).isEqualTo(RanzhongRules.STARTING_KING_LIVES);
    }

    @Test
    void poolCompositionMatchesManual() {
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.PROVISION)).isEqualTo(8);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.SOLDIER)).isEqualTo(6);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.HORSE)).isEqualTo(4);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.CHARIOT)).isEqualTo(4);
        assertThat(RanzhongRules.PUBLIC_POOL_COUNTS.get(PieceKind.KNIGHT)).isEqualTo(2);
    }

    @Test
    void spawnWeightsOnlyCoverPublicKinds() {
        assertThat(RanzhongRules.SPAWN_WEIGHTS.keySet()).containsExactlyInAnyOrder(
                PieceKind.PROVISION, PieceKind.SOLDIER, PieceKind.HORSE, PieceKind.CHARIOT,
                PieceKind.KNIGHT);
        assertThat(RanzhongRules.REFRESH_MIN_COUNT).isEqualTo(1);
        assertThat(RanzhongRules.REFRESH_MAX_COUNT).isEqualTo(5);
    }
}
