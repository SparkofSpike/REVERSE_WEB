import http from './http'
import type {
  KingGameView,
  KingSubmitDeploymentsRequest,
  KingSubmitEffectsRequest
} from '@/types'

/** Create a hot-seat game for 2..4 players (contract §3.1). */
export function createGame(playerCount: number): Promise<KingGameView> {
  return http.post('/kingchess/games', { playerCount }).then((r) => r.data)
}

/** Fetch the authoritative game view (contract §3.2). */
export function getGame(gameId: string): Promise<KingGameView> {
  return http.get(`/kingchess/games/${gameId}`).then((r) => r.data)
}

/**
 * Submit one seat's deployments (contract §3.3). Overwrite semantics: a second
 * submission for the same seat replaces that seat's whole deployment list.
 */
export function submitDeployments(
  gameId: string,
  request: KingSubmitDeploymentsRequest
): Promise<KingGameView> {
  return http.post(`/kingchess/games/${gameId}/deployments`, request).then((r) => r.data)
}

/** Roll the d20s and resolve placements / captures (contract §3.4). */
export function resolveRound(gameId: string): Promise<KingGameView> {
  return http.post(`/kingchess/games/${gameId}/resolve`, {}).then((r) => r.data)
}

/**
 * Submit one seat's special-effect actions (contract §3.5). Overwrite payload;
 * the server settles once every seat has submitted, then moves to ROUND_END.
 */
export function submitEffects(
  gameId: string,
  request: KingSubmitEffectsRequest
): Promise<KingGameView> {
  return http.post(`/kingchess/games/${gameId}/effects`, request).then((r) => r.data)
}

/** Refresh the public pieces and start the next round (contract §3.6). */
export function nextRound(gameId: string): Promise<KingGameView> {
  return http.post(`/kingchess/games/${gameId}/next-round`, {}).then((r) => r.data)
}
