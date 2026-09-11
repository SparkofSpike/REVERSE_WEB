package com.test.engine.service;

import com.test.engine.dto.kingchess.KingGameView;
import com.test.engine.dto.kingchess.KingPlayerView;
import com.test.engine.dto.kingchess.PieceView;
import com.test.engine.exception.BusinessException;
import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.EffectAction;
import com.test.engine.kingchess.model.GamePhase;
import com.test.engine.kingchess.model.KingGame;
import com.test.engine.kingchess.model.Piece;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.PlayerState;
import com.test.engine.kingchess.model.Side;
import com.test.engine.kingchess.resolve.EffectResolver;
import com.test.engine.kingchess.resolve.KingResolver;
import com.test.engine.kingchess.rules.RanzhongRules;
import com.test.engine.kingchess.spawn.PublicSpawner;
import com.test.engine.utils.DiceRoller;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * In-memory facade for King's Chess sessions (contract §1: hot-seat, no
 * persistence, no SSE). The backend owns every adjudication — order, captures,
 * scores, lives and the winner — and the frontend only renders these views.
 *
 * <p>All illegal states raise {@link BusinessException} (Chinese message), which
 * the shared handler renders as HTTP 400.
 */
@Service
public class KingGameService {

    /** Live sessions; M1 keeps them in memory only (contract §1). */
    private final Map<String, KingGame> games = new ConcurrentHashMap<>();

    private final PublicSpawner publicSpawner;
    private final DiceRoller diceRoller;

    public KingGameService(PublicSpawner publicSpawner, DiceRoller diceRoller) {
        this.publicSpawner = publicSpawner;
        this.diceRoller = diceRoller;
    }

    /** Contract §3.1 — creates a session for 2..4 hot-seat players (DEFAULT-1). */
    public KingGameView create(int playerCount) {
        if (playerCount < RanzhongRules.MIN_PLAYERS || playerCount > RanzhongRules.MAX_PLAYERS) {
            throw new BusinessException("玩家数量必须在 " + RanzhongRules.MIN_PLAYERS + "-"
                    + RanzhongRules.MAX_PLAYERS + " 之间");
        }
        List<Integer> seats = IntStream.range(0, playerCount).boxed().toList();
        KingGame game = KingGame.create(seats, RanzhongRules.STARTING_PRIVATE_HAND);
        game.setRoundNo(1);
        // contract §2.5-1: round 1 refreshes public pieces too
        List<Piece> spawned = publicSpawner.spawn(game);
        game.getEvents().add("Game created for " + playerCount + " players, "
                + spawned.size() + " public piece(s) spawned");
        games.put(game.getGameId(), game);
        return toView(game);
    }

    /** Contract §3.2 — reads a session. */
    public KingGameView get(String gameId) {
        return toView(require(gameId));
    }

    /**
     * Live domain state behind a session id, exposed for tests and diagnostics.
     * The API layer always speaks in {@link KingGameView}s.
     */
    public KingGame liveState(String gameId) {
        return require(gameId);
    }

    /** Contract §3.3 — submits (覆盖式) one seat's deployments for the round. */
    public KingGameView deploy(String gameId, int seat, List<Deployment> deployments) {
        KingGame game = require(gameId);
        requirePhase(game, GamePhase.PLACING, "提交部署");
        requireSeat(game, seat);

        // contract DEFAULT-5: 每轮可放置的枚数不限（受手牌存量约束），而不是无限供应
        List<Deployment> submitted = new ArrayList<>();
        List<Deployment> requested = deployments == null ? List.of() : deployments;
        List<PieceKind> remainingHand = new ArrayList<>(game.player(seat).getHand());
        Set<String> ownCells = new HashSet<>();
        for (Deployment deployment : requested) {
            if (deployment.side() == null) {
                throw new BusinessException("棋场方位不能为空");
            }
            if (deployment.cellIndex() < 0 || deployment.cellIndex() >= Side.CELL_COUNT) {
                throw new BusinessException("格子编号必须在 0-" + (Side.CELL_COUNT - 1) + " 之间");
            }
            if (deployment.pieceKind() == null) {
                throw new BusinessException("棋子类型不能为空");
            }
            // contract §3.3: only duplicates inside one seat's own submission are illegal —
            // cross-seat contests on the same cell are the point of "later drop eats earlier drop".
            if (!ownCells.add(deployment.side() + "-" + deployment.cellIndex())) {
                throw new BusinessException("同一座位不能把多枚棋子放到同一格（"
                        + deployment.side() + "-" + deployment.cellIndex() + "）");
            }
            if (!remainingHand.remove(deployment.pieceKind())) {
                throw new BusinessException("座位 " + seat + " 手牌中没有 " + deployment.pieceKind());
            }
            // pre-allocate the piece id so the submitted drop can be rendered before resolve
            submitted.add(new Deployment(seat, deployment.side(), deployment.cellIndex(),
                    deployment.pieceKind(), game.nextPieceId()));
        }

        game.replaceDeployments(seat, submitted);
        return toView(game);
    }

    /** Contract §3.4 — rolls d20 for everyone and settles the drop phase. */
    public KingGameView resolve(String gameId) {
        KingGame game = require(gameId);
        requirePhase(game, GamePhase.PLACING, "结算本轮");
        List<Integer> activeSeats = game.activeSeats();
        for (Integer seat : activeSeats) {
            if (!game.getSubmittedSeats().contains(seat)) {
                throw new BusinessException("座位 " + seat + " 尚未提交部署");
            }
        }
        Map<Integer, Integer> rolls = new LinkedHashMap<>();
        for (Integer seat : activeSeats) {
            rolls.put(seat, diceRoller.roll(1, RanzhongRules.D20_SIDES).total());
        }
        game.setPhase(GamePhase.RESOLVING);
        KingResolver.resolve(game, rolls);
        return toView(game);
    }

    /**
     * Contract §3.5 — collects a seat's effect actions and settles the whole
     * effects round as soon as every active seat has submitted.
     *
     * <p>Atomic by contract §2.6: the submission is validated <em>before</em> it is
     * recorded and before any board mutation, so an illegal submission leaves the
     * board and the game state exactly as they were and the seat can simply
     * correct and resubmit (the submission is 覆盖式). The settlement itself runs
     * against a snapshot of the active seats and of the pending actions.
     */
    public KingGameView submitEffects(String gameId, int seat, List<EffectAction> actions) {
        KingGame game = require(gameId);
        requirePhase(game, GamePhase.EFFECTS, "提交特殊效果动作");
        requireSeat(game, seat);

        List<EffectAction> submitted = new ArrayList<>(actions == null ? List.of() : actions);
        // pre-validate the new submission: a rejected one changes nothing at all
        EffectResolver.validate(game, Map.of(seat, submitted));

        game.getPendingEffectActions().put(seat, submitted);
        game.getSubmittedEffectSeats().add(seat);

        // snapshot of the seats taking part in this round: the settlement below must
        // not be able to watch the active set change under it
        List<Integer> activeSeats = List.copyOf(game.activeSeats());
        if (game.getSubmittedEffectSeats().containsAll(activeSeats)) {
            // resolves against the snapshot; throws (without touching the board) when
            // a stored submission turned out illegal, so a retry is always safe
            List<String> events = EffectResolver.resolve(game, game.getPendingEffectActions());
            game.setPhase(GamePhase.ROUND_END);
            game.clearPending();
            game.getEvents().clear();
            game.getEvents().addAll(events);
        }
        return toView(game);
    }

    /** Contract §3.6 — refreshes public pieces, checks "no usable piece", round++ → PLACING. */
    public KingGameView nextRound(String gameId) {
        KingGame game = require(gameId);
        requirePhase(game, GamePhase.ROUND_END, "进入下一轮");

        List<String> events = new ArrayList<>();
        List<Piece> spawned = publicSpawner.spawn(game);
        events.add(spawned.size() + " public piece(s) spawned");
        // contract §2.4: the "no usable piece" check runs when entering PLACING
        events.addAll(KingResolver.checkNoUsablePieces(game));
        game.setRoundNo(game.getRoundNo() + 1);

        Integer winner = KingResolver.evaluateWinner(game);
        if (winner != null) {
            game.setPhase(GamePhase.FINISHED);
            game.setWinnerSeat(winner);
            events.add("Player " + winner + " wins");
        } else if (KingResolver.isSettled(game)) {
            // contract §2.4 / §2.7: every player went out in the same round — a draw.
            // The game must end here: looping back to PLACING with nobody left to
            // act would leave a match that can never finish.
            game.setPhase(GamePhase.FINISHED);
            game.setWinnerSeat(null);
            events.add("Every player was eliminated in the same round: the game is a draw");
        } else {
            game.setPhase(GamePhase.PLACING);
        }
        game.clearPending();
        game.getEvents().clear();
        game.getEvents().addAll(events);
        return toView(game);
    }

    // ===================== view mapping =====================

    public KingGameView toView(KingGame game) {
        Map<String, List<PieceView>> fields = new LinkedHashMap<>();
        for (Side side : Side.values()) {
            List<PieceView> cells = new ArrayList<>();
            for (Piece piece : game.getBoard().field(side).getCells()) {
                cells.add(piece == null ? null : new PieceView(piece.getId(), piece.getKind().name(),
                        piece.getOwnerSeat(), piece.getDropOrdinal()));
            }
            fields.put(side.name(), cells);
        }

        // Contract §2.5-2 / manual: "每个人的行动轮决策都是秘密进行的，你不能得知
        // 其他人的落子处在何地"。Submitted-but-unresolved drops are therefore NEVER
        // rendered into the view - not even to the seat that made them. Progress is
        // signalled only through submittedSeats; resolve() is what reveals the drops.
        Map<String, Integer> court = new LinkedHashMap<>();
        game.getBoard().courtCounts().forEach((kind, count) -> court.put(kind.name(), count));

        List<KingPlayerView> players = game.getPlayers().stream()
                .sorted(Comparator.comparingInt(PlayerState::getSeat))
                .map(p -> new KingPlayerView(p.getSeat(), Side.ofSeat(p.getSeat()).name(),
                        p.getScore(), p.getKingLives(), p.isEliminated(),
                        p.getHand().stream().map(Enum::name).toList()))
                .toList();

        Map<String, Integer> lastRolls = new LinkedHashMap<>();
        game.getLastRolls().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> lastRolls.put(String.valueOf(e.getKey()), e.getValue()));

        return new KingGameView(
                game.getGameId(),
                game.getPhase().name(),
                game.getRoundNo(),
                game.getPhase() == GamePhase.FINISHED,
                game.getWinnerSeat(),
                RanzhongRules.SCORE_TO_WIN,
                players,
                fields,
                court,
                game.getSubmittedSeats().stream().sorted().toList(),
                game.getSubmittedEffectSeats().stream().sorted().toList(),
                lastRolls,
                List.copyOf(game.getLastDropOrder()),
                List.copyOf(game.getEvents()),
                game.getEliminatedSeats().stream().sorted().toList());
    }

    // ===================== guards =====================

    private KingGame require(String gameId) {
        KingGame game = gameId == null ? null : games.get(gameId);
        if (game == null) {
            throw new BusinessException("对局不存在");
        }
        return game;
    }

    private void requireSeat(KingGame game, int seat) {
        if (!game.hasSeat(seat)) {
            throw new BusinessException("座位 " + seat + " 不在本局中");
        }
        if (game.player(seat).isEliminated()) {
            throw new BusinessException("座位 " + seat + " 已出局");
        }
    }

    private void requirePhase(KingGame game, GamePhase expected, String action) {
        if (game.getPhase() == GamePhase.FINISHED) {
            throw new BusinessException("对局已结束，无法" + action);
        }
        if (game.getPhase() != expected) {
            throw new BusinessException("当前阶段（" + game.getPhase() + "）无法" + action);
        }
    }
}
