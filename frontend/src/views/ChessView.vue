<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import AppNav from '@/components/AppNav.vue'
import { errorMessage } from '@/api/http'
import {
  createGame,
  getGame,
  nextRound,
  resolveRound,
  submitDeployments,
  submitEffects
} from '@/api/kingchess'
import type {
  KingCourtKind,
  KingDeploymentRequest,
  KingEffectActionRequest,
  KingEffectType,
  KingGameView,
  KingPhase,
  KingPieceKind,
  KingPieceView,
  KingSide
} from '@/types'

// 国王棋 · 岚中对 (hot-seat). Every adjudicated value (score, king lives,
// captures, winner) is rendered straight from the backend KingGameView — the
// UI only selects, stages and forwards input (contract §5-8).

const SIDES: KingSide[] = ['NORTH', 'EAST', 'SOUTH', 'WEST']
const CELL_INDEXES = [0, 1, 2, 3]
const COURT_KINDS: KingCourtKind[] = ['PROVISION', 'SOLDIER', 'HORSE', 'CHARIOT', 'KNIGHT']
const HAND_ORDER: KingPieceKind[] = [
  'KING',
  'QUEEN',
  'MARTYR',
  'STRATEGIST',
  'PROVISION',
  'SOLDIER',
  'HORSE',
  'CHARIOT',
  'KNIGHT'
]

const SIDE_LABEL: Record<KingSide, string> = {
  NORTH: '北',
  EAST: '东',
  SOUTH: '南',
  WEST: '西'
}

const PIECE_LABEL: Record<KingPieceKind, string> = {
  KING: '国王',
  QUEEN: '王后',
  MARTYR: '死士',
  STRATEGIST: '谋士',
  PROVISION: '粮草',
  SOLDIER: '兵',
  HORSE: '马',
  CHARIOT: '车',
  KNIGHT: '骑士'
}

const PIECE_GLYPH: Record<KingPieceKind, string> = {
  KING: '♔',
  QUEEN: '♕',
  MARTYR: '†',
  STRATEGIST: '✶',
  PROVISION: '❖',
  SOLDIER: '⚔',
  HORSE: '♞',
  CHARIOT: '♜',
  KNIGHT: '⛨'
}

const PHASE_LABEL: Record<KingPhase, string> = {
  WAITING: '等待开局',
  PLACING: '秘密落子',
  RESOLVING: '结算中',
  EFFECTS: '特殊效果',
  ROUND_END: '本轮结束',
  FINISHED: '对局结束'
}

const EFFECT_LABEL: Record<KingEffectType, string> = {
  HORSE_STEP: '马前进',
  CHARIOT_MOVE: '车移动',
  KNIGHT_RECALL: '骑士收回',
  STRATEGIST_RECALL: '谋士收回'
}

/** Effects round countdown shown at the top of the page (contract §5-4). */
const EFFECT_SECONDS = 16

// ---------- state ----------

const game = ref<KingGameView | null>(null)
const playerCount = ref(2)
/** Seat whose hand/board the hot-seat operators are currently using. */
const activeSeat = ref(0)
/** Piece kind picked from the active seat's hand. */
const selectedKind = ref<KingPieceKind | null>(null)
/** Locally staged deployments for the active seat only (never another seat's). */
const draft = ref<KingDeploymentRequest[]>([])
/** Locally staged effect actions for the active seat. */
const stagedEffects = ref<StagedEffect[]>([])
const secondsLeft = ref(EFFECT_SECONDS)
const baselineScores = ref<Record<number, number>>({})
const busy = ref(false)
const error = ref('')

let timer: number | null = null

// ---------- types local to the view ----------

interface CellView {
  side: KingSide
  index: number
  glyph: string
  label: string
  ownerSeat: number | null
  pending: boolean
  target: boolean
  clickable: boolean
}

interface EffectOption {
  type: KingEffectType
  pieceId: number
  side: KingSide
  cellIndex: number
  kind: KingPieceKind
  needsTargetCell: boolean
  needsTargetPiece: boolean
}

interface StagedEffect extends EffectOption {
  targetCellIndex: number
  targetPieceId: number
}

// ---------- helpers ----------

// Seats the backend will still accept actions from. Eliminated seats are skipped
// everywhere an "operating seat" is meant: the backend's KingGameService rejects
// their submissions (座位 X 已出局) and its resolve/effects gates only wait on
// active seats, so counting them here made 结算本轮 unreachable after the first
// elimination in a 3/4-player game.
function seatOrderOf(view: KingGameView): number[] {
  return view.players
    .filter((player) => !player.eliminated)
    .map((player) => player.seat)
    .sort((a, b) => a - b)
}

function firstUnsubmitted(view: KingGameView, submitted: number[]): number | null {
  return seatOrderOf(view).find((seat) => !submitted.includes(seat)) ?? null
}

// ---------- derived view data ----------

const phase = computed<KingPhase | null>(() => game.value?.phase ?? null)
const players = computed(() => (game.value ? game.value.players.slice().sort((a, b) => a.seat - b.seat) : []))
const seatOrder = computed<number[]>(() => (game.value ? seatOrderOf(game.value) : []))
const courtRows = computed(() =>
  COURT_KINDS.map((kind) => ({
    kind,
    label: PIECE_LABEL[kind],
    glyph: PIECE_GLYPH[kind],
    count: game.value ? game.value.court[kind] : 0
  }))
)

const board = computed<Record<KingSide, CellView[]>>(() => {
  const view = game.value
  const placing = view?.phase === 'PLACING'
  const result = {} as Record<KingSide, CellView[]>
  for (const side of SIDES) {
    result[side] = CELL_INDEXES.map((index) => {
      const piece: KingPieceView | null = view ? view.fields[side][index] ?? null : null
      const pendingKind =
        draft.value.find((item) => item.side === side && item.cellIndex === index)?.pieceKind ?? null
      const pending = pendingKind !== null
      return {
        side,
        index,
        glyph: pendingKind ? PIECE_GLYPH[pendingKind] : piece ? PIECE_GLYPH[piece.kind] : '',
        label: pendingKind ? `待落子 ${PIECE_LABEL[pendingKind]}` : piece ? PIECE_LABEL[piece.kind] : '',
        ownerSeat: pending ? activeSeat.value : piece?.ownerSeat ?? null,
        pending,
        target: placing && !pending && piece === null && selectedKind.value !== null,
        clickable: placing && (pending || (piece === null && selectedKind.value !== null))
      }
    })
  }
  return result
})

const activePlayer = computed(() => players.value.find((player) => player.seat === activeSeat.value) ?? null)

const handRemaining = computed(() => {
  const player = activePlayer.value
  if (!player) return []
  const counts = new Map<KingPieceKind, number>()
  for (const kind of player.hand) counts.set(kind, (counts.get(kind) ?? 0) + 1)
  for (const item of draft.value) counts.set(item.pieceKind, (counts.get(item.pieceKind) ?? 0) - 1)
  return HAND_ORDER.filter((kind) => counts.has(kind)).map((kind) => ({
    kind,
    label: PIECE_LABEL[kind],
    glyph: PIECE_GLYPH[kind],
    count: Math.max(0, counts.get(kind) ?? 0)
  }))
})

const allDeploymentsSubmitted = computed(() => {
  const view = game.value
  if (!view) return false
  const expected = seatOrderOf(view)
  if (expected.length === 0) return false
  return expected.every((seat) => view.submittedSeats.includes(seat))
})

const rollEntries = computed(() => {
  const view = game.value
  if (!view) return []
  return Object.entries(view.lastRolls)
    .map(([seat, roll]) => ({ seat: Number(seat), roll }))
    .sort((a, b) => a.seat - b.seat)
})

const dropOrder = computed(() => (game.value ? game.value.lastDropOrder.slice() : []))
const eventLog = computed(() => (game.value ? game.value.events.slice() : []))
const winner = computed(() => {
  const view = game.value
  if (!view || view.winnerSeat === null) return null
  return view.players.find((player) => player.seat === view.winnerSeat) ?? null
})

const pendingEffectSeats = computed(() => {
  const view = game.value
  if (!view) return []
  return seatOrderOf(view).filter((seat) => !view.submittedEffectSeats.includes(seat))
})

const countdownPercent = computed(() => (secondsLeft.value / EFFECT_SECONDS) * 100)

function seatName(seat: number): string {
  const player = players.value.find((entry) => entry.seat === seat)
  return player ? `座位 ${seat} · ${SIDE_LABEL[player.side]}` : `座位 ${seat}`
}

function sideLabel(side: KingSide): string {
  return SIDE_LABEL[side]
}

function isDeploymentSubmitted(seat: number): boolean {
  return game.value ? game.value.submittedSeats.includes(seat) : false
}

function isEffectSubmitted(seat: number): boolean {
  return game.value ? game.value.submittedEffectSeats.includes(seat) : false
}

function scoreDelta(seat: number): number | null {
  const player = players.value.find((entry) => entry.seat === seat)
  if (!player) return null
  const base = baselineScores.value[seat]
  return base === undefined ? null : player.score - base
}

function emptyCellsIn(side: KingSide): number[] {
  const view = game.value
  if (!view) return []
  return CELL_INDEXES.filter((index) => !view.fields[side][index])
}

// ---------- request plumbing ----------

function applyView(view: KingGameView) {
  const previous = game.value
  const previousPhase = previous?.phase ?? null
  const previousRound = previous?.roundNo ?? null
  game.value = view

  const enteringPlacing = view.phase === 'PLACING' && (previousPhase !== 'PLACING' || previousRound !== view.roundNo)
  const enteringEffects = view.phase === 'EFFECTS' && (previousPhase !== 'EFFECTS' || previousRound !== view.roundNo)

  if (enteringPlacing) {
    // New round: drop every local draft so nobody can peek at a previous seat.
    draft.value = []
    selectedKind.value = null
    stagedEffects.value = []
    activeSeat.value = seatOrderOf(view)[0] ?? 0
    const scores: Record<number, number> = {}
    for (const player of view.players) scores[player.seat] = player.score
    baselineScores.value = scores
    stopTimer()
    return
  }

  if (enteringEffects) {
    stagedEffects.value = []
    activeSeat.value = firstUnsubmitted(view, view.submittedEffectSeats) ?? seatOrderOf(view)[0] ?? 0
    resetCountdown()
    return
  }

  if (view.phase !== 'EFFECTS') {
    stopTimer()
  }
}

async function withBusy(work: () => Promise<KingGameView | void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const view = await work()
    if (view) applyView(view)
  } catch (caught) {
    error.value = errorMessage(caught)
  } finally {
    busy.value = false
  }
}

// ---------- game setup ----------

function handleCreateGame() {
  void withBusy(() => createGame(playerCount.value))
}

function handleRefresh() {
  const view = game.value
  if (!view) return
  void withBusy(() => getGame(view.gameId))
}

// ---------- PLACING ----------

function selectSeat(seat: number) {
  if (busy.value || seat === activeSeat.value) return
  const target = players.value.find((player) => player.seat === seat)
  // Eliminated seats cannot act: the backend rejects their submissions outright
  // (座位 X 已出局), and during EFFECTS the countdown would auto-submit for them.
  if (!target || target.eliminated) return
  activeSeat.value = seat
  // Hot-seat secrecy: the previous seat's staged placements are dropped on
  // switch so the next operator cannot see them.
  if (phase.value === 'PLACING') {
    draft.value = []
    selectedKind.value = null
  }
  if (phase.value === 'EFFECTS') {
    stagedEffects.value = []
    resetCountdown()
  }
}

function switchSeat() {
  const seats = seatOrder.value
  if (seats.length === 0) return
  const current = seats.indexOf(activeSeat.value)
  selectSeat(seats[(current + 1) % seats.length])
}

function pickKind(kind: KingPieceKind) {
  selectedKind.value = selectedKind.value === kind ? null : kind
  error.value = ''
}

function stageDeployment(side: KingSide, index: number, kind: KingPieceKind) {
  // Front end only guards the active seat's own duplicate cells; two different
  // seats sharing a cell is the core "later drop eats the earlier one" play and
  // must never be blocked here (contract §3.3 / §5-2).
  const ownDuplicate = draft.value.some((item) => item.side === side && item.cellIndex === index)
  if (ownDuplicate) return
  draft.value = [...draft.value, { side, cellIndex: index, pieceKind: kind }]
}

function onCellClick(cell: CellView) {
  if (phase.value !== 'PLACING' || busy.value) return
  const staged = draft.value.find((item) => item.side === cell.side && item.cellIndex === cell.index)
  if (staged) {
    undoDeployment(staged)
    return
  }
  if (!selectedKind.value) return
  const remaining = handRemaining.value.find((entry) => entry.kind === selectedKind.value)?.count ?? 0
  if (remaining <= 0) {
    error.value = `${PIECE_LABEL[selectedKind.value]} 已无手牌可落`
    return
  }
  stageDeployment(cell.side, cell.index, selectedKind.value)
}

function undoDeployment(item: KingDeploymentRequest) {
  draft.value = draft.value.filter(
    (entry) => !(entry.side === item.side && entry.cellIndex === item.cellIndex)
  )
}

function handleSubmitDeployments() {
  const view = game.value
  if (!view) return
  const seat = activeSeat.value
  const deployments = draft.value.map((item) => ({ ...item }))
  void withBusy(async () => {
    const next = await submitDeployments(view.gameId, { seat, deployments })
    draft.value = []
    selectedKind.value = null
    const pending = firstUnsubmitted(next, next.submittedSeats)
    if (pending !== null) activeSeat.value = pending
    return next
  })
}

function handleResolve() {
  const view = game.value
  if (!view) return
  void withBusy(() => resolveRound(view.gameId))
}

// ---------- EFFECTS ----------

function ownBoardPieces() {
  const view = game.value
  if (!view) return []
  const seat = activeSeat.value
  const found: { piece: KingPieceView; side: KingSide; cellIndex: number }[] = []
  for (const side of SIDES) {
    view.fields[side].forEach((piece, index) => {
      if (piece && piece.ownerSeat === seat) found.push({ piece, side, cellIndex: index })
    })
  }
  return found
}

const effectOptions = computed<EffectOption[]>(() => {
  const own = ownBoardPieces()
  const options: EffectOption[] = []
  for (const entry of own) {
    const base = {
      pieceId: entry.piece.id,
      side: entry.side,
      cellIndex: entry.cellIndex,
      kind: entry.piece.kind
    }
    if (entry.piece.kind === 'HORSE') {
      options.push({ ...base, type: 'HORSE_STEP', needsTargetCell: false, needsTargetPiece: false })
    } else if (entry.piece.kind === 'CHARIOT') {
      // A chariot move needs an empty cell inside its own field.
      if (emptyCellsIn(entry.side).length > 0) {
        options.push({ ...base, type: 'CHARIOT_MOVE', needsTargetCell: true, needsTargetPiece: false })
      }
    } else if (entry.piece.kind === 'KNIGHT') {
      options.push({ ...base, type: 'KNIGHT_RECALL', needsTargetCell: false, needsTargetPiece: false })
    } else if (entry.piece.kind === 'STRATEGIST') {
      if (own.length > 0) {
        options.push({
          ...base,
          type: 'STRATEGIST_RECALL',
          needsTargetCell: false,
          needsTargetPiece: true
        })
      }
    }
    // MARTYR protection is passive (contract §2.6) — never an action.
  }
  // Each piece triggers at most once per round (contract §2.6 DEFAULT-7).
  return options.filter(
    (option) =>
      !stagedEffects.value.some(
        (action) => action.type === option.type && action.pieceId === option.pieceId
      )
  )
})

const ownPieceChoices = computed(() =>
  ownBoardPieces().map((entry) => ({
    id: entry.piece.id,
    label: `${PIECE_LABEL[entry.piece.kind]} #${entry.piece.id} · ${SIDE_LABEL[entry.side]}${entry.cellIndex + 1}`
  }))
)

function stageEffect(option: EffectOption) {
  const cells = emptyCellsIn(option.side)
  const own = ownBoardPieces()
  stagedEffects.value = [
    ...stagedEffects.value,
    {
      ...option,
      targetCellIndex: option.needsTargetCell ? cells[0] ?? 0 : 0,
      targetPieceId: option.needsTargetPiece ? own[0]?.piece.id ?? 0 : 0
    }
  ]
}

function unstageEffect(action: StagedEffect) {
  stagedEffects.value = stagedEffects.value.filter(
    (entry) => !(entry.type === action.type && entry.pieceId === action.pieceId)
  )
}

function setEffectTargetCell(action: StagedEffect, index: number) {
  action.targetCellIndex = index
}

function setEffectTargetPiece(action: StagedEffect, pieceId: number) {
  action.targetPieceId = pieceId
}

function emptyCellChoices(side: KingSide) {
  return emptyCellsIn(side).map((index) => ({ index, label: `${SIDE_LABEL[side]}${index + 1} 格` }))
}

function toEffectRequest(action: StagedEffect): KingEffectActionRequest {
  const request: KingEffectActionRequest = { type: action.type, pieceId: action.pieceId }
  if (action.needsTargetCell) request.targetCellIndex = action.targetCellIndex
  if (action.needsTargetPiece) request.targetPieceId = action.targetPieceId
  return request
}

function handleSubmitEffects() {
  void submitEffectActions(false)
}

/** auto=true is the 16s timeout path: it submits an empty action list (§5-4). */
async function submitEffectActions(auto: boolean) {
  const view = game.value
  if (!view || busy.value || view.phase !== 'EFFECTS') return
  const seat = activeSeat.value
  if (view.submittedEffectSeats.includes(seat)) {
    const pending = firstUnsubmitted(view, view.submittedEffectSeats)
    if (pending !== null) activeSeat.value = pending
    return
  }
  const actions = auto ? [] : stagedEffects.value.map(toEffectRequest)
  await withBusy(async () => {
    const next = await submitEffects(view.gameId, { seat, actions })
    stagedEffects.value = []
    if (next.phase === 'EFFECTS') {
      const pending = firstUnsubmitted(next, next.submittedEffectSeats)
      if (pending !== null) {
        activeSeat.value = pending
        resetCountdown()
      } else {
        stopTimer()
      }
    }
    return next
  })
}

function resetCountdown() {
  stopTimer()
  secondsLeft.value = EFFECT_SECONDS
  const view = game.value
  if (!view || view.phase !== 'EFFECTS') return
  if (firstUnsubmitted(view, view.submittedEffectSeats) === null) return
  // One fresh 16s window per operating seat: hot-seat means the seats act one
  // after another, so a shared window would auto-submit every later seat.
  timer = window.setInterval(() => {
    if (!game.value || game.value.phase !== 'EFFECTS') {
      stopTimer()
      return
    }
    secondsLeft.value = Math.max(0, secondsLeft.value - 1)
    if (secondsLeft.value === 0) {
      stopTimer()
      void submitEffectActions(true)
    }
  }, 1000)
}

function stopTimer() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

// ---------- ROUND_END / FINISHED ----------

function handleNextRound() {
  const view = game.value
  if (!view) return
  void withBusy(() => nextRound(view.gameId))
}

function handleRestart() {
  game.value = null
  draft.value = []
  selectedKind.value = null
  stagedEffects.value = []
  baselineScores.value = {}
  stopTimer()
  error.value = ''
}

onUnmounted(stopTimer)
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <header class="header">
        <div>
          <h1>国王棋 · 岚中对</h1>
          <p class="dim">四棋场环绕中央棋局 · 热座对局</p>
        </div>
        <div class="header-right">
          <span v-if="game" class="dim small">第 {{ game.roundNo }} 轮</span>
          <span class="phase-badge" :class="phase ? 'phase-' + phase.toLowerCase() : ''">
            {{ phase ? PHASE_LABEL[phase] : '未开局' }}
          </span>
        </div>
      </header>

      <p v-if="error" class="error-banner">{{ error }}</p>

      <!-- ---------- 未开局 ---------- -->
      <section v-if="!game" class="panel setup">
        <h2 class="panel-title">开始一局热座对局</h2>
        <p class="hint dim">
          同一台浏览器轮流操作各座位：2 人取北 / 东，3 人再取南，4 人四场齐开。
          所有裁决（点数、吃子、计分、胜负）由服务端给出。
        </p>
        <div class="count-picker">
          <button
            v-for="count in [2, 3, 4]"
            :key="count"
            type="button"
            class="count-btn"
            :class="{ picked: playerCount === count }"
            @click="playerCount = count"
          >
            {{ count }} 人
          </button>
        </div>
        <div class="actions">
          <button type="button" class="btn primary" :disabled="busy" @click="handleCreateGame">
            开始对局
          </button>
        </div>
      </section>

      <template v-else>
        <!-- ---------- EFFECTS 倒计时 ---------- -->
        <div v-if="phase === 'EFFECTS'" class="countdown">
          <span class="countdown-num" :class="{ urgent: secondsLeft <= 5 }">{{ secondsLeft }}</span>
          <div class="countdown-track">
            <div class="countdown-fill" :style="{ width: countdownPercent + '%' }"></div>
          </div>
          <span class="dim small">
            当前座位 {{ seatName(activeSeat) }} 的效果轮窗口；归零将以空动作提交
          </span>
        </div>

        <div v-if="phase === 'WAITING'" class="panel">
          <h2 class="panel-title">对局已创建，等待服务端开局</h2>
          <div class="actions">
            <button type="button" class="btn primary" :disabled="busy" @click="handleRefresh">
              刷新对局
            </button>
          </div>
        </div>

        <!-- ---------- FINISHED ---------- -->
        <section v-if="phase === 'FINISHED'" class="panel finished">
          <h2 class="panel-title accent">对局结束</h2>
          <p v-if="winner" class="winner">
            胜者 · {{ seatName(winner.seat) }}（{{ winner.score }} 分）
          </p>
          <p v-else class="winner dim">平局 · 全员出局，本局无胜者</p>
          <table class="score-table">
            <thead>
              <tr>
                <th>座位</th>
                <th>方位</th>
                <th>分数</th>
                <th>国王命数</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="player in players" :key="player.seat">
                <td>座位 {{ player.seat }}</td>
                <td>{{ sideLabel(player.side) }}</td>
                <td class="accent">{{ player.score }}</td>
                <td>{{ player.kingLives }}</td>
                <td>
                  <span v-if="player.eliminated" class="tag danger-tag">已出局</span>
                  <span v-else class="tag ok-tag">存活</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="actions">
            <button type="button" class="btn" @click="handleRestart">再来一局</button>
          </div>
        </section>

        <!-- ---------- 棋盘 + 操作面板 ---------- -->
        <div v-if="phase !== 'FINISHED'" class="layout">
          <div class="board-col">
            <div class="board">
              <!-- North field -->
              <div class="row row-north">
                <span class="arrow">↖</span>
                <div class="field field-h">
                  <button
                    v-for="cell in board.NORTH"
                    :key="'N' + cell.index"
                    type="button"
                    class="cell"
                    :class="[
                      cell.pending ? 'pending' : '',
                      !cell.pending && cell.ownerSeat !== null ? 'seat-' + cell.ownerSeat : '',
                      cell.target ? 'target' : '',
                      cell.clickable ? 'clickable' : ''
                    ]"
                    :disabled="!cell.clickable"
                    @click="onCellClick(cell)"
                  >
                    <span v-if="cell.glyph" class="glyph">{{ cell.glyph }}</span>
                    <span v-else class="cell-no">{{ cell.index + 1 }}</span>
                    <span v-if="cell.label" class="cell-tag">{{ cell.label }}</span>
                  </button>
                </div>
                <span class="arrow">↘</span>
              </div>

              <!-- Middle row: West field | Court | East field -->
              <div class="row row-mid">
                <div class="field field-v">
                  <button
                    v-for="cell in board.WEST"
                    :key="'W' + cell.index"
                    type="button"
                    class="cell"
                    :class="[
                      cell.pending ? 'pending' : '',
                      !cell.pending && cell.ownerSeat !== null ? 'seat-' + cell.ownerSeat : '',
                      cell.target ? 'target' : '',
                      cell.clickable ? 'clickable' : ''
                    ]"
                    :disabled="!cell.clickable"
                    @click="onCellClick(cell)"
                  >
                    <span v-if="cell.glyph" class="glyph">{{ cell.glyph }}</span>
                    <span v-else class="cell-no">{{ cell.index + 1 }}</span>
                    <span v-if="cell.label" class="cell-tag">{{ cell.label }}</span>
                  </button>
                </div>

                <div class="court">
                  <span class="court-label">棋局</span>
                  <span class="court-sub dim">剩余公棋</span>
                  <ul class="court-list">
                    <li v-for="row in courtRows" :key="row.kind">
                      <span class="court-glyph">{{ row.glyph }}</span>
                      <span class="court-name">{{ row.label }}</span>
                      <b>{{ row.count }}</b>
                    </li>
                  </ul>
                  <span class="court-foot dim small">d20 定序 · 后落吃先落</span>
                </div>

                <div class="field field-v">
                  <button
                    v-for="cell in board.EAST"
                    :key="'E' + cell.index"
                    type="button"
                    class="cell"
                    :class="[
                      cell.pending ? 'pending' : '',
                      !cell.pending && cell.ownerSeat !== null ? 'seat-' + cell.ownerSeat : '',
                      cell.target ? 'target' : '',
                      cell.clickable ? 'clickable' : ''
                    ]"
                    :disabled="!cell.clickable"
                    @click="onCellClick(cell)"
                  >
                    <span v-if="cell.glyph" class="glyph">{{ cell.glyph }}</span>
                    <span v-else class="cell-no">{{ cell.index + 1 }}</span>
                    <span v-if="cell.label" class="cell-tag">{{ cell.label }}</span>
                  </button>
                </div>
              </div>

              <!-- South field -->
              <div class="row row-south">
                <span class="arrow">↙</span>
                <div class="field field-h">
                  <button
                    v-for="cell in board.SOUTH"
                    :key="'S' + cell.index"
                    type="button"
                    class="cell"
                    :class="[
                      cell.pending ? 'pending' : '',
                      !cell.pending && cell.ownerSeat !== null ? 'seat-' + cell.ownerSeat : '',
                      cell.target ? 'target' : '',
                      cell.clickable ? 'clickable' : ''
                    ]"
                    :disabled="!cell.clickable"
                    @click="onCellClick(cell)"
                  >
                    <span v-if="cell.glyph" class="glyph">{{ cell.glyph }}</span>
                    <span v-else class="cell-no">{{ cell.index + 1 }}</span>
                    <span v-if="cell.label" class="cell-tag">{{ cell.label }}</span>
                  </button>
                </div>
                <span class="arrow">↘</span>
              </div>
            </div>

            <p class="footnote dim">
              棋盘只渲染服务端给出的棋子与归属；分数、国王命数、吃子结果与胜负一律来自响应，
              前端不做任何判定。
            </p>
          </div>

          <aside class="side-col">
            <!-- 玩家面板 -->
            <section class="panel">
              <h2 class="panel-title">玩家</h2>
              <ul class="player-list">
                <li
                  v-for="player in players"
                  :key="player.seat"
                  class="player-row"
                  :class="[
                    'seat-' + player.seat,
                    player.seat === activeSeat ? 'active' : '',
                    player.eliminated ? 'out' : ''
                  ]"
                  @click="selectSeat(player.seat)"
                >
                  <span class="player-dot"></span>
                  <span class="player-name">{{ seatName(player.seat) }}</span>
                  <span class="player-score accent">{{ player.score }}</span>
                  <span class="player-lives" :title="'国王命数'">
                    <span v-for="heart in [0, 1, 2, 3, 4]" :key="heart" :class="{ lit: heart < player.kingLives }">♥</span>
                  </span>
                  <span v-if="player.eliminated" class="tag danger-tag">出局</span>
                  <span v-else-if="isDeploymentSubmitted(player.seat) && phase === 'PLACING'" class="tag ok-tag">
                    已提交
                  </span>
                  <span v-else-if="isEffectSubmitted(player.seat) && phase === 'EFFECTS'" class="tag ok-tag">
                    已提交
                  </span>
                </li>
              </ul>
              <p class="hint dim">点击未出局的座位 = 热座切换到该座位（提交内容互相保密）；已出局的座位不可操作。</p>
            </section>

            <!-- 未提交提示 -->
            <section v-if="phase === 'PLACING' && !allDeploymentsSubmitted" class="panel pending-note">
              <span class="dim">尚未提交部署的座位：</span>
              <span class="accent">{{ seatOrder.filter((seat) => !isDeploymentSubmitted(seat)).join('、') }}</span>
            </section>

            <!-- PLACING -->
            <section v-if="phase === 'PLACING'" class="panel">
              <h2 class="panel-title">
                当前操作座位 · {{ seatName(activeSeat) }}
              </h2>
              <p class="hint dim">
                先在手牌中选择一枚棋子（再次点击取消），再点击棋盘空格记一次待落子；
                点击已有待落子的格子即可撤销。同一座位不可重复占格，但不同座位可以争抢同一格。
              </p>
              <div class="hand">
                <button
                  v-for="entry in handRemaining"
                  :key="entry.kind"
                  type="button"
                  class="hand-chip"
                  :class="{ picked: selectedKind === entry.kind, spent: entry.count === 0 }"
                  :disabled="entry.count === 0"
                  @click="pickKind(entry.kind)"
                >
                  <span class="chip-glyph">{{ entry.glyph }}</span>
                  <span class="chip-name">{{ entry.label }}</span>
                  <span class="chip-count">×{{ entry.count }}</span>
                </button>
              </div>
              <div class="draft">
                <div v-if="draft.length === 0" class="dim small">本轮暂无待落子</div>
                <ul v-else class="draft-list">
                  <li v-for="item in draft" :key="item.side + '-' + item.cellIndex">
                    <span>{{ sideLabel(item.side) }}{{ item.cellIndex + 1 }} 格</span>
                    <span class="accent">{{ PIECE_LABEL[item.pieceKind] }}</span>
                    <button type="button" class="mini" @click="undoDeployment(item)">撤销</button>
                  </li>
                </ul>
              </div>
              <div class="actions">
                <button
                  type="button"
                  class="btn primary"
                  :disabled="busy"
                  @click="handleSubmitDeployments"
                >
                  提交部署（座位 {{ activeSeat }}）
                </button>
                <button type="button" class="btn" :disabled="busy" @click="switchSeat">切换座位</button>
              </div>
              <div v-if="allDeploymentsSubmitted" class="actions">
                <button type="button" class="btn warn" :disabled="busy" @click="handleResolve">
                  结算本轮
                </button>
              </div>
              <p class="hint dim">提交为整份覆盖；切换座位会丢弃本座位的本地暂存，避免他人偷看。</p>
            </section>

            <!-- RESOLVING -->
            <section v-if="phase === 'RESOLVING'" class="panel">
              <h2 class="panel-title">服务端结算中</h2>
              <p class="hint dim">掷 d20、按序落子并处理互吃，结果由响应给出。</p>
              <div class="actions">
                <button type="button" class="btn" :disabled="busy" @click="handleRefresh">刷新对局</button>
              </div>
            </section>

            <!-- EFFECTS -->
            <section v-if="phase === 'EFFECTS'" class="panel">
              <h2 class="panel-title">
                当前操作座位 · {{ seatName(activeSeat) }}
                <span v-if="isEffectSubmitted(activeSeat)" class="tag ok-tag">已提交</span>
              </h2>
              <template v-if="pendingEffectSeats.length > 0">
                <p class="hint dim">
                  选择本座位要执行的效果动作（马前进 / 车移动 / 骑士收回 / 谋士收回），提交后由服务端统一结算；
                  倒计时归零将以空动作提交。
                </p>
                <div class="effect-options">
                  <div v-if="effectOptions.length === 0" class="dim small">当前没有可执行的效果动作</div>
                  <button
                    v-for="option in effectOptions"
                    :key="option.type + '-' + option.pieceId"
                    type="button"
                    class="effect-chip"
                    @click="stageEffect(option)"
                  >
                    <span class="chip-name">{{ EFFECT_LABEL[option.type] }}</span>
                    <span class="dim small">
                      {{ PIECE_LABEL[option.kind] }} #{{ option.pieceId }} · {{ sideLabel(option.side) }}{{ option.cellIndex + 1 }} 格
                    </span>
                  </button>
                </div>
                <ul v-if="stagedEffects.length > 0" class="draft-list">
                  <li v-for="action in stagedEffects" :key="action.type + '-' + action.pieceId">
                    <span class="accent">{{ EFFECT_LABEL[action.type] }}</span>
                    <span class="dim small">#{{ action.pieceId }}</span>
                    <select
                      v-if="action.needsTargetCell"
                      class="mini-select"
                      :value="action.targetCellIndex"
                      @change="setEffectTargetCell(action, Number(($event.target as HTMLSelectElement).value))"
                    >
                      <option v-for="choice in emptyCellChoices(action.side)" :key="choice.index" :value="choice.index">
                        {{ choice.label }}
                      </option>
                    </select>
                    <select
                      v-if="action.needsTargetPiece"
                      class="mini-select"
                      :value="action.targetPieceId"
                      @change="setEffectTargetPiece(action, Number(($event.target as HTMLSelectElement).value))"
                    >
                      <option v-for="choice in ownPieceChoices" :key="choice.id" :value="choice.id">
                        {{ choice.label }}
                      </option>
                    </select>
                    <button type="button" class="mini" @click="unstageEffect(action)">撤销</button>
                  </li>
                </ul>
                <div class="actions">
                  <button type="button" class="btn primary" :disabled="busy" @click="handleSubmitEffects">
                    提交效果动作（座位 {{ activeSeat }}）
                  </button>
                  <button type="button" class="btn" :disabled="busy" @click="switchSeat">切换座位</button>
                </div>
              </template>
              <p v-else class="hint dim">全部在场座位已提交，等待服务端结算效果轮。</p>
            </section>

            <!-- d20 定序 -->
            <section v-if="rollEntries.length > 0" class="panel">
              <h2 class="panel-title">d20 定序</h2>
              <ul class="roll-list">
                <li v-for="entry in rollEntries" :key="entry.seat">
                  <span>{{ seatName(entry.seat) }}</span>
                  <b class="accent">{{ entry.roll }}</b>
                </li>
              </ul>
              <p v-if="dropOrder.length > 0" class="dim small">
                落子顺序：{{ dropOrder.map((seat) => seatName(seat)).join(' → ') }}
              </p>
            </section>

            <!-- ROUND_END -->
            <section v-if="phase === 'ROUND_END'" class="panel">
              <h2 class="panel-title">本轮结束 · 事件日志</h2>
              <ul v-if="eventLog.length > 0" class="event-log">
                <li v-for="(line, index) in eventLog" :key="index">{{ line }}</li>
              </ul>
              <p v-else class="dim small">服务端未返回事件</p>
              <table class="score-table">
                <thead>
                  <tr>
                    <th>座位</th>
                    <th>分数</th>
                    <th>本轮变化</th>
                    <th>国王命数</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="player in players" :key="player.seat">
                    <td>{{ seatName(player.seat) }}</td>
                    <td class="accent">{{ player.score }}</td>
                    <td>
                      <span v-if="scoreDelta(player.seat) === null" class="dim">—</span>
                      <span
                        v-else
                        :class="scoreDelta(player.seat) === 0 ? 'dim' : 'ok'"
                      >{{ (scoreDelta(player.seat) ?? 0) >= 0 ? '+' : '' }}{{ scoreDelta(player.seat) }}</span>
                    </td>
                    <td>{{ player.kingLives }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="actions">
                <button type="button" class="btn primary" :disabled="busy" @click="handleNextRound">
                  下一轮
                </button>
              </div>
            </section>

            <!-- 图例 -->
            <section class="panel">
              <h2 class="panel-title">图例</h2>
              <ul class="legend">
                <li v-for="player in players" :key="player.seat">
                  <span class="player-dot" :class="'seat-' + player.seat"></span>
                  {{ seatName(player.seat) }}
                </li>
                <li><span class="player-dot court-dot"></span>棋局（ownerSeat = null）</li>
              </ul>
            </section>
          </aside>
        </div>
      </template>

      <p class="footnote dim">
        对局状态保存在服务端内存中；刷新页面后可用「刷新对局」重新拉取，裁决始终以后端为准。
      </p>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.header h1 {
  font-size: 22px;
  letter-spacing: 2px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.phase-badge {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--warn);
  border: 1px solid var(--warn);
  border-radius: 4px;
  padding: 3px 10px;
}

.phase-badge.phase-placing {
  color: var(--accent);
  border-color: var(--accent);
}

.phase-badge.phase-effects {
  color: var(--warn);
  border-color: var(--warn);
}

.phase-badge.phase-finished {
  color: var(--danger);
  border-color: var(--danger);
}

.error-banner {
  border: 1px solid var(--danger);
  background: rgba(255, 93, 108, 0.1);
  color: var(--danger);
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 13px;
}

.panel {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-title {
  font-size: 14px;
  letter-spacing: 1px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint {
  font-size: 12px;
  line-height: 1.7;
}

.small {
  font-size: 12px;
}

.setup {
  max-width: 560px;
}

.count-picker {
  display: flex;
  gap: 10px;
}

.count-btn {
  flex: 1;
  padding: 14px 0;
  background: var(--bg-panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text-dim);
  font-size: 15px;
  letter-spacing: 2px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.count-btn.picked {
  color: var(--accent);
  border-color: var(--accent);
  box-shadow: 0 0 18px rgba(76, 194, 255, 0.25);
}

/* ---------- countdown ---------- */

.countdown {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  border: 1px solid var(--warn);
  border-radius: 8px;
  background: rgba(255, 200, 87, 0.07);
}

.countdown-num {
  font-size: 26px;
  font-weight: 800;
  color: var(--warn);
  min-width: 44px;
  text-align: center;
}

.countdown-num.urgent {
  color: var(--danger);
}

.countdown-track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: #141a26;
  border: 1px solid rgba(0, 0, 0, 0.55);
  overflow: hidden;
}

.countdown-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--warn), var(--danger));
  transition: width 1s linear;
}

/* ---------- layout ---------- */

.layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.board-col {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-col {
  flex: 0 0 360px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- board ---------- */

.board {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background:
    linear-gradient(rgba(76, 194, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(76, 194, 255, 0.03) 1px, transparent 1px),
    var(--bg-panel);
  background-size: 28px 28px, 28px 28px, auto;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.field {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-panel-2);
}

.field-h {
  flex-direction: row;
}

.field-v {
  flex-direction: column;
}

.cell {
  width: 68px;
  height: 68px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-dim);
  border: 1px solid var(--border);
  border-radius: 4px;
  background: rgba(11, 14, 20, 0.6);
  cursor: default;
  padding: 2px;
  transition: all 0.15s ease;
}

.cell.clickable {
  cursor: pointer;
  border-color: var(--accent-dim);
}

.cell.target {
  border-color: var(--accent);
  background: rgba(76, 194, 255, 0.09);
}

.cell.pending {
  border: 1px dashed var(--accent);
  background: rgba(76, 194, 255, 0.14);
}

.cell:disabled {
  opacity: 1;
}

.glyph {
  font-size: 24px;
  line-height: 1;
  color: var(--text);
}

.cell-no {
  font-size: 16px;
}

.cell-tag {
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--text-dim);
  white-space: nowrap;
}

/* owner colours */
.seat-0 {
  color: #4cc2ff;
}
.seat-1 {
  color: #ffc857;
}
.seat-2 {
  color: #5ddb8c;
}
.seat-3 {
  color: #c58cff;
}

.cell.seat-0 .glyph,
.cell.seat-1 .glyph,
.cell.seat-2 .glyph,
.cell.seat-3 .glyph {
  color: inherit;
  text-shadow: 0 0 12px currentColor;
}

.cell.seat-0 {
  border-color: rgba(76, 194, 255, 0.7);
}
.cell.seat-1 {
  border-color: rgba(255, 200, 87, 0.7);
}
.cell.seat-2 {
  border-color: rgba(93, 219, 140, 0.7);
}
.cell.seat-3 {
  border-color: rgba(197, 140, 255, 0.7);
}

.arrow {
  font-size: 22px;
  color: var(--accent);
  padding: 0 8px;
}

.court {
  width: 200px;
  height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: rgba(76, 194, 255, 0.08);
}

.court-label {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--accent);
}

.court-sub {
  font-size: 12px;
  letter-spacing: 2px;
}

.court-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 4px;
}

.court-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text);
  min-width: 108px;
}

.court-list b {
  margin-left: auto;
  color: var(--accent);
}

.court-glyph {
  color: var(--text-dim);
}

.court-name {
  color: var(--text-dim);
}

.court-foot {
  margin-top: 4px;
}

.footnote {
  font-size: 12px;
  line-height: 1.7;
}

/* ---------- side column ---------- */

.player-list,
.draft-list,
.roll-list,
.legend,
.event-log {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.player-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-panel-2);
  cursor: pointer;
  transition: all 0.15s ease;
  font-size: 12px;
}

.player-row.active {
  border-color: var(--accent);
  box-shadow: 0 0 16px rgba(76, 194, 255, 0.18);
}

.player-row.out {
  opacity: 0.5;
  cursor: not-allowed;
}

.player-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 10px currentColor;
  display: inline-block;
}

.player-dot.court-dot {
  color: var(--text-dim);
}

.player-name {
  color: var(--text);
}

.player-score {
  margin-left: auto;
  font-weight: 700;
}

.player-lives {
  letter-spacing: 1px;
  color: #2b3348;
}

.player-lives .lit {
  color: var(--danger);
  text-shadow: 0 0 8px rgba(255, 93, 108, 0.7);
}

.tag {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  border-radius: 4px;
  padding: 2px 6px;
}

.ok-tag {
  color: var(--ok);
  border: 1px solid var(--ok);
}

.danger-tag {
  color: var(--danger);
  border: 1px solid var(--danger);
}

.pending-note {
  flex-direction: row;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.hand {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hand-chip,
.effect-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: var(--bg-panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.hand-chip.picked {
  border-color: var(--accent);
  color: var(--accent);
  box-shadow: 0 0 14px rgba(76, 194, 255, 0.25);
}

.hand-chip.spent {
  opacity: 0.4;
  cursor: not-allowed;
}

.chip-glyph {
  font-size: 16px;
}

.chip-count {
  color: var(--text-dim);
}

.effect-chip {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.effect-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.draft-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px dashed var(--border);
  border-radius: 6px;
  font-size: 12px;
}

.mini,
.mini-select {
  margin-left: auto;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-dim);
  font-size: 11px;
  padding: 2px 8px;
  cursor: pointer;
}

.mini-select {
  background: var(--bg-panel-2);
  color: var(--text);
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn {
  padding: 8px 14px;
  background: var(--bg-panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  font-size: 13px;
  letter-spacing: 1px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.btn.primary {
  border-color: var(--accent);
  color: var(--accent);
  box-shadow: 0 0 16px rgba(76, 194, 255, 0.16);
}

.btn.warn {
  border-color: var(--warn);
  color: var(--warn);
}

.roll-list li {
  display: flex;
  justify-content: space-between;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-panel-2);
  font-size: 12px;
}

.event-log li {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-dim);
  padding-left: 10px;
  border-left: 2px solid var(--border);
}

.score-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.score-table th,
.score-table td {
  text-align: left;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}

.score-table th {
  color: var(--text-dim);
  font-weight: 600;
}

.winner {
  font-size: 16px;
  letter-spacing: 2px;
  color: var(--accent);
}

.legend {
  font-size: 12px;
  color: var(--text-dim);
}

.legend li {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ---------- mobile ---------- */
@media (max-width: 1024px) {
  .layout {
    flex-direction: column;
  }
  .side-col {
    flex: 1 1 auto;
    width: 100%;
  }
  .board-col {
    width: 100%;
  }
}

@media (max-width: 768px) {
  .container {
    padding: 16px;
  }
  .board {
    padding: 12px;
  }
  .cell {
    width: 44px;
    height: 44px;
    font-size: 14px;
  }
  .glyph {
    font-size: 18px;
  }
  .cell-tag,
  .cell-no {
    font-size: 9px;
  }
  .court {
    width: 120px;
    height: 120px;
  }
  .court-label {
    font-size: 16px;
    letter-spacing: 2px;
  }
  .court-list li {
    font-size: 10px;
    min-width: 84px;
  }
  .arrow {
    font-size: 16px;
    padding: 0 4px;
  }
  .countdown {
    flex-wrap: wrap;
  }
}
</style>
