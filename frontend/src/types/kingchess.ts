// King's Chess (岚中对) — the module's own type slice.
// Field names follow docs/king-chess-m1-contract.md §4 verbatim.
// Split out of types/index.ts so the module owns its contract types.

/** One of the four fields (棋场); seat 0..3 maps to NORTH/EAST/SOUTH/WEST. */
export type KingSide = 'NORTH' | 'EAST' | 'SOUTH' | 'WEST'

/** Every piece kind: the 4 private kinds plus the 5 public (court) kinds. */
export type KingPieceKind =
  | 'KING'
  | 'QUEEN'
  | 'MARTYR'
  | 'STRATEGIST'
  | 'PROVISION'
  | 'SOLDIER'
  | 'HORSE'
  | 'CHARIOT'
  | 'KNIGHT'

/** Kinds that live in the central court (棋局). */
export type KingCourtKind = 'PROVISION' | 'SOLDIER' | 'HORSE' | 'CHARIOT' | 'KNIGHT'

/** Turn phases; adjudication always happens server-side. */
export type KingPhase =
  | 'WAITING'
  | 'PLACING'
  | 'RESOLVING'
  | 'EFFECTS'
  | 'ROUND_END'
  | 'FINISHED'

/** Special-effect round action types (contract §3.5 / §2.6). */
export type KingEffectType =
  | 'HORSE_STEP'
  | 'CHARIOT_MOVE'
  | 'KNIGHT_RECALL'
  | 'STRATEGIST_RECALL'

/** PieceView — a single piece on a field cell. */
export interface KingPieceView {
  /** long, globally unique and stable. */
  id: number
  kind: KingPieceKind
  /** null for court-owned public pieces. */
  ownerSeat: number | null
  /** long, placement order; 0 while in the court or in a hand. */
  dropOrdinal: number
}

/** KingPlayerView — public per-seat state. */
export interface KingPlayerView {
  seat: number
  side: KingSide
  score: number
  kingLives: number
  eliminated: boolean
  /** Kinds currently in hand; the same kind may appear more than once. */
  hand: KingPieceKind[]
}

/** The four fields; each value is always a length-4 array of cells. */
export type KingFields = Record<KingSide, (KingPieceView | null)[]>

/** Remaining pieces in the central court, keyed by public piece kind. */
export type KingCourt = Record<KingCourtKind, number>

/** KingGameView — the single authoritative view DTO (contract §4). */
export interface KingGameView {
  gameId: string
  phase: KingPhase
  roundNo: number
  finished: boolean
  winnerSeat: number | null
  scoreToWin: number
  players: KingPlayerView[]
  fields: KingFields
  court: KingCourt
  /** Seats that already submitted deployments this round. */
  submittedSeats: number[]
  /** Seats that already submitted effect actions this EFFECTS round. */
  submittedEffectSeats: number[]
  /** Seat number (as string) -> d20 roll of the last resolve. */
  lastRolls: Record<string, number>
  /** Seat numbers, earliest drop first. */
  lastDropOrder: number[]
  events: string[]
  eliminatedSeats: number[]
}

// ---------- king chess request bodies ----------

/** Request body of POST /kingchess/games. */
export interface KingCreateGameRequest {
  playerCount: number
}

/** One deployment step inside a seat's submission. */
export interface KingDeploymentRequest {
  side: KingSide
  cellIndex: number
  pieceKind: KingPieceKind
}

/** Request body of POST /kingchess/games/{gameId}/deployments (overwrite). */
export interface KingSubmitDeploymentsRequest {
  seat: number
  deployments: KingDeploymentRequest[]
}

/** One special-effect action inside a seat's submission. */
export interface KingEffectActionRequest {
  type: KingEffectType
  pieceId: number
  /** Required by CHARIOT_MOVE (target empty cell in the same field). */
  targetCellIndex?: number
  /** Required by STRATEGIST_RECALL (target own piece). */
  targetPieceId?: number
}

/** Request body of POST /kingchess/games/{gameId}/effects (overwrite). */
export interface KingSubmitEffectsRequest {
  seat: number
  actions: KingEffectActionRequest[]
}
