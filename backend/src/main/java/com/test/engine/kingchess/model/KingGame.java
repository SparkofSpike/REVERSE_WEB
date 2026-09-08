package com.test.engine.kingchess.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregated state of one King's Chess game (Ranzhong Dui rule set).
 *
 * <p>This is a pure domain object — no persistence, no Spring. Adjudication
 * is performed by {@code KingResolver} against this state; randomness is
 * supplied from outside (typically {@code DiceRoller}).
 */
@Getter
@Setter
public class KingGame {
    private String gameId;
    private GamePhase phase = GamePhase.WAITING;
    private int roundNo = 0;

    private final Board board = new Board();
    private final List<PlayerState> players = new ArrayList<>();

    /** Secret drops per seat, collected during PLACING; cleared each round. */
    private final Map<Integer, List<Deployment>> pendingDeployments = new HashMap<>();

    /** Global drop counter to break same-cell eats by "later drop wins". */
    private long dropCounter = 0;

    public KingGame() {
        this.gameId = UUID.randomUUID().toString();
    }

    public static KingGame create(List<Integer> seats, List<PieceKind> startingHand) {
        KingGame game = new KingGame();
        for (Integer seat : seats) {
            game.players.add(new PlayerState(seat, startingHand));
            game.pendingDeployments.put(seat, new ArrayList<>());
        }
        game.phase = GamePhase.PLACING;
        return game;
    }

    public PlayerState player(int seat) {
        return players.stream()
                .filter(p -> p.getSeat() == seat)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no player at seat " + seat));
    }

    public void addDeployment(Deployment deployment) {
        pendingDeployments.computeIfAbsent(deployment.seat(), k -> new ArrayList<>())
                .add(deployment);
    }

    public List<Deployment> deploymentsOf(int seat) {
        return pendingDeployments.getOrDefault(seat, List.of());
    }

    public void clearPending() {
        pendingDeployments.values().forEach(List::clear);
    }
}
