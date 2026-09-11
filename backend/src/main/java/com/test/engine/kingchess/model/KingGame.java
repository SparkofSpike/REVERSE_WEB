package com.test.engine.kingchess.model;

import com.test.engine.kingchess.rules.RanzhongRules;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregated state of one King's Chess game (Ranzhong Dui rule set).
 *
 * <p>This is a pure domain object — no persistence, no Spring. Adjudication is
 * performed by {@code KingResolver} / {@code EffectResolver} against this state;
 * randomness is supplied from outside (typically {@code DiceRoller}).
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
    private final Map<Integer, List<Deployment>> pendingDeployments = new LinkedHashMap<>();

    /** Seats that have submitted deployments this round (view field {@code submittedSeats}). */
    private final Set<Integer> submittedSeats = new LinkedHashSet<>();

    /** Seats that have submitted special-effects actions this round. */
    private final Set<Integer> submittedEffectSeats = new LinkedHashSet<>();

    /** Secret special-effects actions per seat, collected during EFFECTS. */
    private final Map<Integer, List<EffectAction>> pendingEffectActions = new LinkedHashMap<>();

    /** Seat → d20 value of the last resolution (view field {@code lastRolls}). */
    private final Map<Integer, Integer> lastRolls = new LinkedHashMap<>();

    /** Seat order of the last resolution, first drop first (view field {@code lastDropOrder}). */
    private final List<Integer> lastDropOrder = new ArrayList<>();

    /** Human-readable log of the last settlement (view field {@code events}). */
    private final List<String> events = new ArrayList<>();

    /** Seats that are out of the game. */
    private final List<Integer> eliminatedSeats = new ArrayList<>();

    /** Global drop counter to break same-cell eats by "later drop wins". */
    private long dropCounter = 0;

    /** Global piece-id counter — ids are unique and stable for the whole game. */
    private long pieceIdCounter = 0;

    private Integer winnerSeat;

    public KingGame() {
        this.gameId = UUID.randomUUID().toString();
    }

    /**
     * Creates a game for the given seats and seeds the central Court with the
     * full public pool (contract §2.3: 8 Provisions / 6 Soldiers / 4 Horses /
     * 4 Chariots / 2 Knights).
     */
    public static KingGame create(List<Integer> seats, List<PieceKind> startingHand) {
        KingGame game = new KingGame();
        for (Integer seat : seats) {
            game.players.add(new PlayerState(seat, startingHand));
            game.pendingDeployments.put(seat, new ArrayList<>());
            game.pendingEffectActions.put(seat, new ArrayList<>());
        }
        for (PieceKind kind : PieceKind.values()) {
            if (kind.isPrivatePiece()) {
                continue;
            }
            int count = RanzhongRules.PUBLIC_POOL_COUNTS.getOrDefault(kind, 0);
            for (int i = 0; i < count; i++) {
                game.board.getCourt().add(new Piece(game.nextPieceId(), kind));
            }
        }
        game.phase = GamePhase.PLACING;
        return game;
    }

    /** Hands out the next globally unique piece id. */
    public long nextPieceId() {
        return ++pieceIdCounter;
    }

    public long nextDropOrdinal() {
        return ++dropCounter;
    }

    public PlayerState player(int seat) {
        return players.stream()
                .filter(p -> p.getSeat() == seat)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no player at seat " + seat));
    }

    public boolean hasSeat(int seat) {
        return players.stream().anyMatch(p -> p.getSeat() == seat);
    }

    public List<Integer> seats() {
        return players.stream().map(PlayerState::getSeat).toList();
    }

    /** Non-eliminated seats, in seat order. */
    public List<Integer> activeSeats() {
        return players.stream()
                .filter(p -> !p.isEliminated())
                .map(PlayerState::getSeat)
                .toList();
    }

    /** Replaces a seat's whole round submission (contract §3.3: 覆盖式). */
    public void replaceDeployments(int seat, List<Deployment> deployments) {
        pendingDeployments.put(seat, new ArrayList<>(deployments));
        submittedSeats.add(seat);
    }

    public List<Deployment> deploymentsOf(int seat) {
        return pendingDeployments.getOrDefault(seat, List.of());
    }

    public void markEliminated(int seat) {
        PlayerState state = player(seat);
        state.setEliminated(true);
        if (!eliminatedSeats.contains(seat)) {
            eliminatedSeats.add(seat);
        }
    }

    /** Clears the secret per-round submissions (called after settlement). */
    public void clearPending() {
        pendingDeployments.values().forEach(List::clear);
        pendingEffectActions.values().forEach(List::clear);
        submittedSeats.clear();
        submittedEffectSeats.clear();
    }
}
