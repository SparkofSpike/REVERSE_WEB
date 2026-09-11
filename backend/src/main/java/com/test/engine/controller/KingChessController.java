package com.test.engine.controller;

import com.test.engine.dto.kingchess.CreateGameRequest;
import com.test.engine.dto.kingchess.DeploymentsRequest;
import com.test.engine.dto.kingchess.EffectActionsRequest;
import com.test.engine.dto.kingchess.KingGameView;
import com.test.engine.exception.BusinessException;
import com.test.engine.kingchess.model.Deployment;
import com.test.engine.kingchess.model.EffectAction;
import com.test.engine.kingchess.model.EffectType;
import com.test.engine.kingchess.model.PieceKind;
import com.test.engine.kingchess.model.Side;
import com.test.engine.service.KingGameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST surface of King's Chess (contract §3). Every route sits under
 * {@code /api/kingchess} and therefore requires a JWT like the rest of
 * {@code /api/**}. All adjudication lives in {@link KingGameService}.
 */
@RestController
@RequestMapping("/api/kingchess")
public class KingChessController {

    private final KingGameService kingGameService;

    public KingChessController(KingGameService kingGameService) {
        this.kingGameService = kingGameService;
    }

    /** Contract §3.1 — create a hot-seat game. */
    @PostMapping("/games")
    public KingGameView create(@RequestBody(required = false) CreateGameRequest request) {
        if (request == null || request.playerCount() == null) {
            throw new BusinessException("玩家数量不能为空");
        }
        return kingGameService.create(request.playerCount());
    }

    /** Contract §3.2 — read the authoritative view. */
    @GetMapping("/games/{gameId}")
    public KingGameView get(@PathVariable String gameId) {
        return kingGameService.get(gameId);
    }

    /** Contract §3.3 — submit (覆盖式) a seat's secret deployments. */
    @PostMapping("/games/{gameId}/deployments")
    public KingGameView deploy(@PathVariable String gameId, @RequestBody DeploymentsRequest request) {
        if (request == null || request.seat() == null) {
            throw new BusinessException("座位号不能为空");
        }
        return kingGameService.deploy(gameId, request.seat(), toDeployments(request));
    }

    /** Contract §3.4 — roll d20 and settle the drops. */
    @PostMapping("/games/{gameId}/resolve")
    public KingGameView resolve(@PathVariable String gameId) {
        return kingGameService.resolve(gameId);
    }

    /** Contract §3.5 — submit a seat's special-effects actions. */
    @PostMapping("/games/{gameId}/effects")
    public KingGameView submitEffects(@PathVariable String gameId,
                                      @RequestBody EffectActionsRequest request) {
        if (request == null || request.seat() == null) {
            throw new BusinessException("座位号不能为空");
        }
        return kingGameService.submitEffects(gameId, request.seat(), toEffectActions(request));
    }

    /** Contract §3.6 — refresh public pieces and start the next round. */
    @PostMapping("/games/{gameId}/next-round")
    public KingGameView nextRound(@PathVariable String gameId) {
        return kingGameService.nextRound(gameId);
    }

    private List<Deployment> toDeployments(DeploymentsRequest request) {
        List<Deployment> deployments = new ArrayList<>();
        List<DeploymentsRequest.DeploymentItem> items =
                request.deployments() == null ? List.of() : request.deployments();
        for (DeploymentsRequest.DeploymentItem item : items) {
            if (item == null) {
                continue;
            }
            Side side = parseSide(item.side());
            PieceKind kind = parseKind(item.pieceKind());
            if (item.cellIndex() == null) {
                throw new BusinessException("格子编号不能为空");
            }
            deployments.add(new Deployment(request.seat(), side, item.cellIndex(), kind));
        }
        return deployments;
    }

    private List<EffectAction> toEffectActions(EffectActionsRequest request) {
        List<EffectAction> actions = new ArrayList<>();
        List<EffectActionsRequest.EffectActionItem> items =
                request.actions() == null ? List.of() : request.actions();
        for (EffectActionsRequest.EffectActionItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.type() == null || item.type().isBlank()) {
                throw new BusinessException("特殊效果类型不能为空");
            }
            EffectType type = EffectType.parse(item.type());
            if (type == null) {
                throw new BusinessException("未知的特殊效果类型：" + item.type());
            }
            actions.add(new EffectAction(type, item.pieceId(), item.targetCellIndex(),
                    item.targetPieceId()));
        }
        return actions;
    }

    private Side parseSide(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("棋场方位不能为空");
        }
        for (Side side : Side.values()) {
            if (side.name().equalsIgnoreCase(raw.trim())) {
                return side;
            }
        }
        throw new BusinessException("非法棋场方位：" + raw);
    }

    private PieceKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("棋子类型不能为空");
        }
        for (PieceKind kind : PieceKind.values()) {
            if (kind.name().equalsIgnoreCase(raw.trim())) {
                return kind;
            }
        }
        throw new BusinessException("非法棋子类型：" + raw);
    }
}
