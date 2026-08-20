<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import {
  getBattle,
  selectInitialPerk,
  decide,
  decideExtraActions,
  saveDraft,
  skipExtraActions,
  playCard,
  selectSpecialPerk,
  skipSpecialPerk,
  surrender as surrenderBattle
} from '@/api/combat'
import { errorMessage } from '@/api/http'
import { battleEventsUrl } from '@/api/pvp'
import type { CombatView, CombatantView, ActionDecision, SkillView, CombatEvent } from '@/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const battle = ref<CombatView | null>(null)
const loading = ref(true)
const submitting = ref(false)

// Per-combatant pending decisions.
interface PendingDecision {
  actionType: string
  skillId: string | null
  targetIds: string[]
}
const pending = ref<Record<string, PendingDecision>>({})
// Selected panel tab.
const panelTab = ref<'actions' | 'skills'>('skills')
// Current target-selection state.
const aimMode = ref<{ combatantId: string; kind: 'action' | 'skill'; id: string } | null>(null)
const aimLine = ref<{ x1: number; y1: number; x2: number; y2: number } | null>(null)

// Failed portrait URLs.
const portraitFailed = ref<Record<string, boolean>>({})

// Transition overlays.
const curtain = ref<{ kind: 'rise' | 'fall'; seq: number } | null>(null)
let curtainSeq = 0
let curtainTimer = 0
let fallTimer = 0
const dashOverlay = ref<{ seq: number } | null>(null)

// First load establishes the event baseline.
let baselineSet = false
let consumedLogs = 0

// Performance animation state.
const performing = ref<Record<string, boolean>>({})
const approaching = ref<Record<string, boolean>>({})
// Clash animation state.
const clashing = ref<Record<string, boolean>>({})
const shaking = ref<Record<string, boolean>>({})
// Per-unit animation offsets.
const animDx = ref<Record<string, number>>({})
// Speed-roll animation state.
interface DiceAnim {
  seq: number
  roll: number
  live: number
  result?: 'win' | 'lose'
}
const diceAnims = ref<Record<string, DiceAnim>>({})
// Last-dash animation state.
const dashIds = ref<string[]>([])
const dashActive = ref(false)
let diceFastTimer = 0
let diceSlowTimer = 0
// Active action label.
const actionBarText = ref('')
// Selected combatant.
const selectedId = ref<string | null>(null)
const selectedCombatant = computed(
  () => battle.value?.combatants.find((c) => c.id === selectedId.value) ?? null
)
function toggleSelect(id: string) {
  cancelAim()
  if (selectedId.value === id) {
    selectedId.value = null
  } else {
    selectedId.value = id
    panelTab.value = 'skills'
  }
}
// Skill panel position within the stage.
const skillPanelPos = ref<{ left?: string; right?: string; top: string } | null>(null)
watch(selectedId, () => {
  if (!selectedId.value) {
    skillPanelPos.value = null
    return
  }
  nextTick(() => {
    const el = document.querySelector(`.unit[data-unit-id="${selectedId.value}"]`) as HTMLElement | null
    const scene = document.querySelector('.stage-scene') as HTMLElement | null
    if (!el || !scene) return
    const left = el.offsetLeft
    const top = el.offsetTop
    const w = el.offsetWidth
    const panelW = 138 * 3 + 16 + 20
    if (left + w + 12 + panelW <= scene.clientWidth) {
      skillPanelPos.value = { left: `${left + w + 12}px`, top: `${Math.max(8, top)}px` }
    } else {
      skillPanelPos.value = { right: `${scene.clientWidth - left + 12}px`, top: `${Math.max(8, top)}px` }
    }
  })
})
// Current speed order.
const speedOrder = ref<{ id: string; name: string; roll: number }[]>([])
// Current speed values.
const currentSpeed = ref<Record<string, number>>({})

// Parse speed-dice bounds.
function speedRange(c: CombatantView | undefined): [number, number] {
  const m = /^(\d+)d(\d+)([+-]\d+)?$/.exec(c?.speedDice ?? '')
  if (!m) return [1, 20]
  const count = Number(m[1])
  const sides = Number(m[2])
  const mod = Number(m[3] ?? 0)
  return [count + mod, count * sides + mod]
}

function randSpeed(c: CombatantView | undefined): number {
  const [min, max] = speedRange(c)
  return min + Math.floor(Math.random() * (max - min + 1))
}

// Fan hand cards toward the center.
function handCardStyle(i: number, n: number): Record<string, string> {
  const mid = (n - 1) / 2
  const angle = Math.max(-14, Math.min(14, (i - mid) * 5))
  return {
    '--hand-rot': angle + 'deg',
    'z-index': String(Math.round(12 - Math.abs(i - mid)))
  }
}

function speedFill(c: CombatantView): string {
  const v = currentSpeed.value[c.id]
  if (v === undefined) return '0%'
  const [, max] = speedRange(c)
  return `${Math.min(100, Math.round((v / max) * 100))}%`
}
// Displayed HP updates with its animation cue.
const displayHp = ref<Record<string, number>>({})
interface FloatNum {
  id: number
  targetId: string
  text: string
  kind: 'damage' | 'heal' | 'action'
  offsetY?: number
}
const floats = ref<FloatNum[]>([])
let floatSeq = 0
const anyPerforming = computed(() => Object.values(performing.value).some(Boolean))

// Camera focus follows the acting side.
const zoomOrigin = computed(() => {
  if (!battle.value) return '50% 62%'
  const acting = Object.keys(performing.value).filter((id) => performing.value[id])
  if (acting.length === 0) return '50% 62%'
  const sides = new Set(
    acting.map((id) => battle.value!.combatants.find((c) => c.id === id)?.side)
  )
  if (sides.size > 1) return '50% 62%'
  const side = battle.value.combatants.find((c) => c.id === acting[0])?.side
  if (side === mySide.value) return '34% 60%'
  if (side !== mySide.value) return '66% 60%'
  return '50% 62%'
})

// Perspective mirrors the requesting user's side.
const mySide = computed<'PLAYER' | 'ENEMY'>(() => battle.value?.mySide ?? 'PLAYER')
const players = computed(() =>
  (battle.value?.combatants ?? []).filter((c) => c.side === mySide.value)
)
const enemies = computed(() =>
  (battle.value?.combatants ?? []).filter((c) => c.side !== mySide.value)
)
const alivePlayers = computed(() => players.value.filter((c) => !c.dead))
const isFinished = computed(() => battle.value?.phase === 'FINISHED')
const inDecision = computed(() => battle.value?.phase === 'DECISION')
const inInitialPerk = computed(() => battle.value?.phase === 'INITIAL_PERK')
const inSpecialPerk = computed(() => battle.value?.phase === 'SPECIAL_PERK')
const inExtraRound = computed(() => battle.value?.extraActionRound ?? false)
// PVE players command only their own characters.
const myActors = computed(() =>
  isPve.value
    ? alivePlayers.value.filter((c) => c.ownerUsername === myUsername.value)
    : alivePlayers.value
)
const extraActors = computed(() => myActors.value.filter((c) => c.extraActionsThisTurn > 0))
// Extra rounds include only actors with remaining actions.
const decisionActors = computed(() => (inExtraRound.value ? extraActors.value : myActors.value))

// Multiplayer decision state.
const isPvp = computed(() => !!battle.value?.guestUsername)
/** Whether this is a co-op PVE battle. */
const isPve = computed(() => battle.value?.pve ?? false)
const myUsername = ref('')
const opponentName = computed(() =>
  isPvp.value
    ? (battle.value?.mySide === 'ENEMY' ? battle.value?.ownerUsername : battle.value?.guestUsername)
    : null
)
const mySubmitted = computed(() => battle.value?.mySubmitted ?? false)
const opponentSubmitted = computed(() => battle.value?.opponentSubmitted ?? false)
/** Users who acted in the current window. */
const submittedUsersText = computed(() =>
  (battle.value?.submittedUsers ?? []).map((u) => `${u} ✓`).join('、')
)
/** Whether the opponent owns the extra-action window. */
const opponentsExtraRound = computed(() =>
  isPvp.value && inExtraRound.value && battle.value?.extraRoundSide
    ? battle.value.extraRoundSide !== mySide.value
    : false
)
/** Whether PVP awaits the opponent. */
const awaitingOpponent = computed(() =>
  isPvp.value && inDecision.value && !isFinished.value
    ? (inExtraRound.value ? opponentsExtraRound.value : mySubmitted.value)
    : false
)
/** Whether PVE awaits teammates. */
const pveWaiting = computed(() =>
  isPve.value && inDecision.value && !isFinished.value && mySubmitted.value
)
/** Remaining PVP decision time. */
const countdown = ref(0)
let countdownTimer = 0
function startCountdown() {
  window.clearInterval(countdownTimer)
  countdown.value = 0
  if (!isPvp.value || !battle.value?.decisionDeadlineAt) {
    return
  }
  const tick = () => {
    const remaining = Math.max(0, Math.ceil((battle.value!.decisionDeadlineAt! - Date.now()) / 1000))
    if (countdown.value !== remaining) {
      countdown.value = remaining
    }
    if (remaining === 0 && !battle.value?.mySubmitted && inDecision.value && !inExtraRound.value) {
      // Submit configured decisions on timeout.
      window.clearInterval(countdownTimer)
      void submitDecisions(true)
    }
  }
  tick()
  countdownTimer = window.setInterval(tick, 1000)
}

// Multiplayer refresh channel.
let eventSource: EventSource | null = null

function connectSse(battleId: string) {
  if (!isPvp.value && !isPve.value) {
    return
  }
  if (eventSource) {
    return
  }
  const source = new EventSource(battleEventsUrl(battleId))
  source.addEventListener('refresh', () => {
    void load()
  })
  eventSource = source
}

onMounted(() => {
  window.addEventListener('mousemove', onAimMove)
  window.addEventListener('keydown', onKeydown)
  preloadAssets()
  load()
  import('@/stores/auth')
    .then((m) => {
      myUsername.value = m.useAuthStore().username
    })
    .catch(() => {})
})

onUnmounted(() => {
  window.removeEventListener('mousemove', onAimMove)
  window.removeEventListener('keydown', onKeydown)
  window.clearInterval(countdownTimer)
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
})

function loadImage(url: string): Promise<void> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve()
    img.onerror = () => resolve()
    img.src = url
  })
}

// Cache battle assets.
const imageCache = new Map<string, Promise<void>>()

function ensureImageLoaded(url: string): Promise<void> {
  if (!imageCache.has(url)) {
    imageCache.set(url, loadImage(url))
  }
  return imageCache.get(url)!
}

function preloadAssets() {
  const urls = [
    '/assets/fight_background.webp?v=2',
    '/assets/curtain_rise.webp',
    '/assets/curtain_fall.webp',
    '/assets/last_dash.webp',
    '/assets/warrior.webp',
    '/assets/mage.webp',
    // Hod pose sheets.
    '/assets/hod_idle.webp',
    '/assets/hod_attack.webp',
    '/assets/hod_defend.webp',
    '/assets/hod_skill1.webp',
    '/assets/hod_skill2.webp',
    '/assets/hod_clash.webp',
    '/assets/hod_hit.webp',
    '/assets/hod_hit_max.webp'
  ]
  for (const u of urls) {
    void ensureImageLoaded(u)
  }
}

// Initialize decisions for newly active combatants.
watch(
  () => alivePlayers.value.map((p) => p.id).join(','),
  (ids) => {
    for (const id of ids.split(',')) {
      if (id && !pending.value[id]) {
        pending.value[id] = newPending()
      }
    }
  },
  { immediate: true }
)

// Consume newly received battle events.
function processLogs(logs: CombatEvent[]) {
  if (!logs) return
  if (!baselineSet) {
    baselineSet = true
    consumedLogs = logs.length
    for (const c of battle.value?.combatants ?? []) {
      displayHp.value[c.id] = c.hp
    }
    return
  }
  for (let i = consumedLogs; i < logs.length; i++) {
    const ev = logs[i]
    if (ev.type === 'round_start') {
      handleRoundStart()
    } else if (ev.type === 'round_end') {
      handleRoundEnd()
    } else if (ev.type === 'last_dash') {
      // Start after the current curtain.
      const delay = Math.max(0, curtainGateUntil - Date.now())
      window.setTimeout(() => triggerDash(), delay)
    }
    consumePerformanceEvent(ev)
    consumedLogs = i + 1
  }
}

// Play one curtain transition at a time.
function playCurtainNow(kind: 'rise' | 'fall', onDone?: () => void) {
  window.clearTimeout(curtainTimer)
  curtain.value = { kind, seq: ++curtainSeq }
  curtainTimer = window.setTimeout(() => {
    curtain.value = null
    onDone?.()
  }, 1900)
}

// Lock the curtain transition as one animation.
let curtainWindowLocked = false

function lockCurtainWindow() {
  if (curtainWindowLocked) return
  curtainWindowLocked = true
  animStart()
}

function unlockCurtainWindow() {
  if (!curtainWindowLocked) return
  curtainWindowLocked = false
  animEnd()
}

function handleRoundEnd() {
  // Preserve the active curtain transition.
  window.clearTimeout(fallTimer)
}

function handleRoundStart() {
  // Reveal the next decision round.
  lockCurtainWindow()
  window.clearTimeout(fallTimer)
  playCurtainNow('fall', () => {
    // Settle HP changes without a cue.
    for (const c of battle.value?.combatants ?? []) {
      displayHp.value[c.id] = c.hp
    }
    unlockCurtainWindow()
  })
}

let dashCooldownUntil = 0

function triggerDash() {
  const now = Date.now()
  // Prevent overlapping dash overlays.
  if (now < dashCooldownUntil) return
  dashCooldownUntil = now + 2600
  dashOverlay.value = { seq: (dashOverlay.value?.seq ?? 0) + 1 }
  animStart()
  window.setTimeout(() => {
    dashOverlay.value = null
    animEnd()
  }, 2100)
}

// Performance cues.
const ACTION_LABELS: Record<string, string> = {
  ATTACK: 'Attack!',
  DEFEND: 'Defend!',
  DODGE: 'Dodge!',
  GUARD: 'Guard!',
  COUNTER: 'Counter!',
  CHASE: 'Chase!',
  PRAY: 'Pray!',
  SKILL: 'Skill!',
  CARD: 'Card!',
  HEAL: 'Heal!'
}

// Map log types to isolated CSS classes.
const LOG_TYPE_CLASS: Record<string, string> = {
  damage: 'log-damage',
  heal: 'log-heal',
  performance: 'log-perf',
  surrender: 'log-surrender'
}

function pushFloat(
  unitId: string,
  text: string,
  kind: 'damage' | 'heal' | 'action',
  stackStep: number,
  ttl: number
) {
  const id = ++floatSeq
  // Stack concurrent floating labels.
  for (const f of floats.value) {
    if (f.targetId === unitId) {
      f.offsetY = (f.offsetY ?? 0) - stackStep
    }
  }
  floats.value.push({ id, targetId: unitId, text, kind, offsetY: 0 })
  window.setTimeout(() => {
    floats.value = floats.value.filter((f) => f.id !== id)
  }, ttl)
}

function floatTop(f: FloatNum): string {
  const base = f.kind === 'action' ? -40 : 34
  return `${base + (f.offsetY ?? 0)}px`
}

function addActionLabel(unitId: string, text: string) {
  // Show a brief action label.
  pushFloat(unitId, text, 'action', 34, 580)
}

function consumePerformanceEvent(ev: CombatEvent) {
  applyPerformance(ev)
}

// Serialized performance steps.
interface QueuedStep {
  kind: 'action' | 'clash' | 'settle' | 'heal' | 'speed' | 'dash'
  ev: CombatEvent
}

const ACTION_STEP = 1050
const SETTLE_STEP = 620
const HEAL_STEP = 680
const CLASH_IMPACT = 620
const CLASH_HOLD = 900
const CLASH_STEP = 1400
const animQueue: QueuedStep[] = []
let pumpRunning = false
// Queue gate for curtain transitions.
let curtainGateUntil = 0

function sleep(ms: number): Promise<void> {
  return new Promise((r) => window.setTimeout(r, ms))
}

function enqueueStep(step: QueuedStep) {
  animQueue.push(step)
  void pumpQueue()
}

async function pumpQueue() {
  if (pumpRunning) return
  pumpRunning = true
  animStart()
  try {
    while (animQueue.length > 0) {
      if (curtainGateUntil > 0) {
        const wait = curtainGateUntil - Date.now()
        if (wait > 0) await sleep(wait)
        curtainGateUntil = 0
      }
      const step = animQueue.shift()!
      await playStep(step)
    }
  } finally {
    actionBarText.value = ''
    animEnd()
    pumpRunning = false
  }
}

async function playStep(step: QueuedStep) {
  const d = (step.ev.data ?? {}) as Record<string, unknown>
  actionBarText.value = step.ev.message ?? ''
  if (step.kind === 'clash') {
    const actorId = d.actorId as string | undefined
    const targetId = d.targetId as string | undefined
    if (!actorId || !targetId) return
    pulseActor(actorId)
    pulseActor(targetId)
    for (const id of [actorId, targetId]) {
      const c = battle.value?.combatants.find((x) => x.id === id)
      if (c && hasPoses(c)) setPose(id, 'clash', CLASH_STEP + 250)
    }
    pushFloat(actorId, 'Clash!', 'action', 34, 900)
    pushFloat(targetId, 'Clash!', 'action', 34, 900)
    const clashX = clashPointX(actorId, targetId)
    clashApproach(actorId, clashX)
    clashApproach(targetId, clashX)
    await sleep(CLASH_IMPACT)
    shakeTarget(actorId)
    shakeTarget(targetId)
    await sleep(CLASH_STEP - CLASH_IMPACT)
    return
  }
  if (step.kind === 'dash') {
    // Animate the last-dash speed roll.
    const ids = (d.ids as string[] | undefined) ?? []
    dashIds.value = ids
    dashActive.value = true
    await sleep(2100)
    const all = battle.value?.combatants.filter((c) => !c.dead) ?? []
    for (const c of all) {
      const prev = diceAnims.value[c.id]
      diceAnims.value[c.id] = {
        seq: (prev?.seq ?? 0) + 1,
        roll: 0,
        live: randSpeed(c)
      }
    }
    window.clearInterval(diceFastTimer)
    diceFastTimer = window.setInterval(() => {
      const next = { ...diceAnims.value }
      for (const id of Object.keys(next)) {
        next[id] = { ...next[id], live: randSpeed(battle.value?.combatants.find((x) => x.id === id)) }
      }
      diceAnims.value = next
    }, 60)
    await sleep(1000)
    window.clearInterval(diceFastTimer)
    window.clearInterval(diceSlowTimer)
    diceSlowTimer = window.setInterval(() => {
      const next = { ...diceAnims.value }
      for (const id of ids) {
        const cur = next[id]
        if (cur) {
          next[id] = { ...cur, live: randSpeed(battle.value?.combatants.find((x) => x.id === id)) }
        }
      }
      diceAnims.value = next
    }, 300)
    await sleep(1600)
    return
  }
  if (step.kind === 'speed') {
    const speeds = d.speeds as Record<string, number> | undefined
    if (speeds) {
      currentSpeed.value = { ...speeds }
      speedOrder.value = Object.entries(speeds)
        .map(([id, roll]) => ({
          id,
          roll,
          name: battle.value?.combatants.find((c) => c.id === id)?.name ?? id
        }))
        .sort((a, b) => b.roll - a.roll)
      if (dashIds.value.length > 0) {
        // Settle last-dash rolls.
        const dash = dashIds.value
        const winRoll = Math.max(...dash.map((id) => speeds[id] ?? 0))
        window.clearInterval(diceSlowTimer)
        const settled: Record<string, DiceAnim> = {}
        for (const [id, roll] of Object.entries(speeds)) {
          const prev = diceAnims.value[id]
          settled[id] = {
            seq: (prev?.seq ?? 0) + 1,
            roll,
            live: roll,
            result: dash.includes(id) ? (roll === winRoll ? 'win' : 'lose') : undefined
          }
        }
        diceAnims.value = settled
        window.setTimeout(() => {
          diceAnims.value = {}
          dashIds.value = []
          dashActive.value = false
        }, 1400)
        await sleep(1500)
        return
      }
      for (const [id, roll] of Object.entries(speeds)) {
        const prev = diceAnims.value[id]
        diceAnims.value[id] = { seq: (prev?.seq ?? 0) + 1, roll, live: roll }
      }
      window.setTimeout(() => {
        diceAnims.value = {}
      }, 1900)
    }
    await sleep(1900)
    return
  }
  if (step.kind === 'action') {
    const actorId = d.actorId as string | undefined
    const action = d.action as string | undefined
    const targetId = d.targetId as string | undefined
    if (!actorId) return
    if (action) addActionLabel(actorId, ACTION_LABELS[action] ?? action)
    pulseActor(actorId)
    const actor = battle.value?.combatants.find((x) => x.id === actorId)
    if (actor && hasPoses(actor)) {
      if (action === 'SKILL' || action === 'CARD') {
        const skillId = d.skillId as string | undefined
        const idx = skillId ? actor.skills.findIndex((sk) => sk.id === skillId) : -1
        setPose(actorId, idx === 1 ? 'skill2' : 'skill1', ACTION_STEP + 250)
      } else if (action === 'ATTACK' || action === 'CHASE' || action === 'COUNTER') {
        setPose(actorId, 'attack', ACTION_STEP + 250)
      } else if (action === 'DEFEND' || action === 'DODGE' || action === 'GUARD' || action === 'PRAY') {
        setPose(actorId, 'defend', ACTION_STEP + 250)
      }
    }
    // Physical attackers lunge toward their target.
    const melee =
      battle.value?.combatants.find((c) => c.id === actorId)?.baseDamageType === 'PHYSICAL'
    if (melee && targetId && targetId !== actorId) {
      approachTarget(actorId, targetId)
    }
    await sleep(ACTION_STEP)
    return
  }
  if (step.kind === 'settle') {
    const t = d.target as string | undefined
    const amount = (d.hpDamage ?? d.raw ?? 0) as number
    if (t) shakeTarget(t)
    if (t && amount > 0) addFloat(t, `-${amount}`, 'damage')
    if (t) {
      const target = battle.value?.combatants.find((x) => x.id === t)
      if (target && hasPoses(target) && amount > 0) {
        setPose(t, amount >= target.maxHp * 0.15 ? 'hit_max' : 'hit', SETTLE_STEP + 250)
      }
    }
    if (t) {
      const real = battle.value?.combatants.find((c) => c.id === t)?.hp
      if (real !== undefined) displayHp.value[t] = real
    }
    await sleep(SETTLE_STEP)
    return
  }
  const targetId = d.targetId as string | undefined
  if (targetId) {
    const healAction = d.action as string | undefined
    if (healAction) addActionLabel(targetId, ACTION_LABELS[healAction] ?? healAction)
    await sleep(340)
    if (d.amount) addFloat(targetId, `+${d.amount}`, 'heal')
    const real = battle.value?.combatants.find((c) => c.id === targetId)?.hp
    if (real !== undefined) displayHp.value[targetId] = real
  }
  await sleep(HEAL_STEP - 340)
}

// Lock decisions while animations play.
const animating = ref(false)
let animCount = 0

function animStart() {
  animCount++
  animating.value = true
}

function animEnd() {
  animCount = Math.max(0, animCount - 1)
  if (animCount === 0) {
    animating.value = false
  }
}

function applyPerformance(ev: CombatEvent) {
  const d = (ev.data ?? {}) as Record<string, unknown>
  const actorId = d.actorId as string | undefined

  if (ev.type === 'clash') {
    enqueueStep({ kind: 'clash', ev })
    return
  }
  if (ev.type === 'last_dash') {
    enqueueStep({ kind: 'dash', ev })
    return
  }
  if (ev.type === 'speed') {
    enqueueStep({ kind: 'speed', ev })
    return
  }
  if (ev.type === 'damage') {
    enqueueStep({ kind: 'settle', ev })
    return
  }
  if (ev.type === 'heal') {
    enqueueStep({ kind: 'heal', ev })
    return
  }
  if (actorId) {
    enqueueStep({ kind: 'action', ev })
  }
}

function pulseActor(id: string) {
  performing.value[id] = true
  window.setTimeout(() => {
    performing.value[id] = false
  }, 980)
}

function approachTarget(id: string, targetId?: string) {
  // Position the attacker just before the target.
  const dx = targetId ? dxToFront(id, targetId) : null
  if (dx !== null) animDx.value[id] = dx
  approaching.value[id] = true
  window.setTimeout(() => {
    approaching.value[id] = false
  }, 860)
}

function clashApproach(id: string, clashX: number | null) {
  // Move both combatants to the clash point.
  if (clashX !== null) {
    const dx = dxToClash(id, clashX)
    if (dx !== null) animDx.value[id] = dx
  }
  clashing.value[id] = true
  window.setTimeout(() => {
    clashing.value[id] = false
  }, CLASH_HOLD)
}

// Layout helpers.

function unitEl(id: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(`.unit[data-unit-id="${id}"]`)
}

// Return the midpoint between the front line and target.
function clashPointX(actorId: string, targetId: string): number | null {
  const all = battle.value?.combatants ?? []
  const enemyId =
    all.find((c) => c.id === targetId)?.side !== mySide.value ? targetId : actorId
  const enemyEl = unitEl(enemyId)
  if (!enemyEl) return null
  let frontEl: HTMLElement | null = null
  for (const c of all) {
    if (c.side !== mySide.value || c.dead) continue
    const el = unitEl(c.id)
    if (!el) continue
    if (!frontEl || el.offsetLeft > frontEl.offsetLeft) frontEl = el
  }
  if (!frontEl) return null
  const fx = frontEl.offsetLeft + frontEl.offsetWidth / 2
  const ex = enemyEl.offsetLeft + enemyEl.offsetWidth / 2
  return (fx + ex) / 2
}

// Return the offset to reach the clash point.
function dxToClash(id: string, clashX: number): number | null {
  const el = unitEl(id)
  if (!el) return null
  const GAP = 12
  const center = el.offsetLeft + el.offsetWidth / 2
  if (center < clashX) {
    return clashX - GAP / 2 - el.offsetWidth - el.offsetLeft
  }
  return clashX + GAP / 2 - el.offsetLeft
}

// Return the offset to approach a target.
function dxToFront(id: string, targetId: string): number | null {
  const el = unitEl(id)
  const targetEl = unitEl(targetId)
  if (!el || !targetEl) return null
  const GAP = 8
  const elRight = el.offsetLeft + el.offsetWidth
  const targetRight = targetEl.offsetLeft + targetEl.offsetWidth
  if (targetEl.offsetLeft >= elRight) {
    return targetEl.offsetLeft - elRight - GAP
  }
  if (el.offsetLeft >= targetRight) {
    return targetRight - el.offsetLeft + GAP
  }
  return null
}

function unitDxStyle(id: string) {
  const dx = animDx.value[id]
  return dx === undefined ? undefined : { '--anim-dx': `${dx}px` }
}

function shakeTarget(id: string) {
  shaking.value[id] = true
  window.setTimeout(() => {
    shaking.value[id] = false
  }, 480)
}

function addFloat(targetId: string, text: string, kind: 'damage' | 'heal') {
  window.setTimeout(() => {
    pushFloat(targetId, text, kind, 26, 1300)
  }, 340)
}

function floatsFor(unitId: string): FloatNum[] {
  return floats.value.filter((f) => f.targetId === unitId)
}

// Templates with event-specific pose sheets.
const POSE_TEMPLATES = new Set(['hod'])
const poses = ref<Record<string, string>>({})
const poseTimers = new Map<string, number>()

function hasPoses(c: CombatantView): boolean {
  return POSE_TEMPLATES.has(c.templateId)
}

function setPose(id: string, pose: string, ttl: number) {
  window.clearTimeout(poseTimers.get(id))
  poses.value[id] = pose
  const timer = window.setTimeout(() => {
    if (poses.value[id] === pose) {
      poses.value[id] = 'idle'
    }
    poseTimers.delete(id)
  }, ttl)
  poseTimers.set(id, timer)
}

// Use the pose sheet when available.
function portraitUrl(c: CombatantView): string {
  if (hasPoses(c)) {
    const pose = poses.value[c.id] ?? 'idle'
    const url = `/assets/${c.templateId}_${pose}.webp`
    return portraitFailed.value[url] ? `/assets/${c.templateId}.webp` : url
  }
  return `/assets/${c.templateId}.webp`
}

function onPortraitError(ev: Event) {
  const el = ev.target as HTMLImageElement
  const path = new URL(el.src).pathname
  portraitFailed.value[path] = true
  // Fall back to the base portrait.
  const m = /^\/assets\/([^_]+)_([a-z0-9]+)\.webp$/.exec(path)
  if (m) {
    for (const [id, pose] of Object.entries(poses.value)) {
      if (pose === m[2]) {
        poses.value[id] = 'idle'
      }
    }
  }
}

// Ignore stale battle responses.
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const battleId = route.params.battleId as string
    const view = await getBattle(battleId)
    if (seq !== loadSeq) {
      return
    }
    battle.value = view
    processLogs(view.logs)
    for (const c of alivePlayers.value) {
      if (!pending.value[c.id]) {
        pending.value[c.id] = newPending()
      }
    }
    connectSse(view.id)
    startCountdown()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    ATTACK: '攻击',
    DEFEND: '防御',
    DODGE: '闪避',
    GUARD: '守护',
    COUNTER: '反击',
    CHASE: '追击',
    PRAY: '祈思'
  }
  return map[action] ?? action
}

function newPending(): PendingDecision {
  return { actionType: 'ATTACK', skillId: null, targetIds: [] }
}

function actionNeedsTarget(action: string): boolean {
  return action === 'ATTACK' || action === 'CHASE'
}

function actionNeedsAllyTarget(action: string): boolean {
  return action === 'GUARD'
}

// Return the maximum number of skill targets.
function skillTargetCap(s: SkillView): number {
  let cap = 1
  for (const e of s.effects ?? []) {
    if (typeof e.count === 'number' && e.count > cap) cap = e.count
  }
  return cap
}

function skillNeedsTarget(s: SkillView | null): boolean {
  if (!s) return false
  return s.targetType !== 'self' && s.targetType !== 'random_ally'
}

function isSkillTargetValid(s: SkillView, c: CombatantView, t: CombatantView): boolean {
  if (s.targetType === 'self') return t.id === c.id
  if (s.targetType === 'enemy' || s.targetType === 'enemies') {
    return t.side !== mySide.value && !t.dead
  }
  if (s.targetType === 'ally' || s.targetType === 'allies' || s.targetType === 'random_ally') {
    return t.side === mySide.value && !t.dead
  }
  return false
}

// Select an action or enter target selection.
function pickAction(c: CombatantView, action: string, ev: MouseEvent) {
  if (!canControl(c) || animating.value) return
  const cur = pending.value[c.id]
  if (cur?.actionType === action && cur.skillId === null) {
    pending.value[c.id] = newPending()
    cancelAim()
    return
  }
  const p = pending.value[c.id] ?? newPending()
  p.actionType = action
  p.skillId = null
  p.targetIds = []
  pending.value[c.id] = { ...p }
  if (actionNeedsTarget(action) || actionNeedsAllyTarget(action)) {
    aimMode.value = { combatantId: c.id, kind: 'action', id: action }
    selectedId.value = null
    updateAimLine(ev.clientX, ev.clientY)
  } else {
    cancelAim()
    selectedId.value = null
  }
}

// Select a skill or enter target selection.
function pickSkill(c: CombatantView, s: SkillView, ev: MouseEvent) {
  if (!canControl(c) || animating.value) return
  if ((c.cooldowns[s.id] ?? 0) > 0) {
    message.warning(`${s.name} 冷却中（还需 ${c.cooldowns[s.id] ?? 0} 回合）`)
    return
  }
  const cur = pending.value[c.id]
  if (cur?.actionType === 'SKILL' && cur.skillId === s.id) {
    pending.value[c.id] = newPending()
    cancelAim()
    return
  }
  pending.value[c.id] = {
    actionType: 'SKILL',
    skillId: s.id,
    targetIds: s.targetType === 'self' ? [c.id] : []
  }
  selectedId.value = null
  if (skillNeedsTarget(s)) {
    aimMode.value = { combatantId: c.id, kind: 'skill', id: s.id }
    updateAimLine(ev.clientX, ev.clientY)
  } else {
    aimMode.value = null
  }
}

function skillActive(c: CombatantView, s: SkillView): boolean {
  const p = pending.value[c.id]
  return !!p && p.actionType === 'SKILL' && p.skillId === s.id
}

// Decision status helpers.
function hasDecision(c: CombatantView): boolean {
  const p = pending.value[c.id]
  if (!p) return false
  if (p.actionType === 'SKILL') return !!p.skillId
  return true
}

function decisionReady(c: CombatantView): boolean {
  const p = pending.value[c.id]
  if (!p) return false
  if (p.actionType === 'SKILL') {
    const s = c.skills.find((x) => x.id === p.skillId)
    if (!s) return false
    return !skillNeedsTarget(s) || p.targetIds.length > 0
  }
  if (actionNeedsTarget(p.actionType) || actionNeedsAllyTarget(p.actionType)) {
    return p.targetIds.length > 0
  }
  return true
}

function combatantName(id: string): string {
  return battle.value?.combatants.find((x) => x.id === id)?.name ?? id
}

// Return a compact decision label.
function decisionTagText(c: CombatantView): string {
  const p = pending.value[c.id]
  if (!p) return '未下令'
  const needsTarget =
    p.actionType === 'SKILL'
      ? skillNeedsTarget(c.skills.find((x) => x.id === p.skillId) ?? null)
      : actionNeedsTarget(p.actionType) || actionNeedsAllyTarget(p.actionType)
  if (!needsTarget) return '已下令'
  if (p.targetIds.length === 0) return '待目标'
  const names = p.targetIds.map(combatantName).join('、')
  return names.length > 8 ? `→${names.slice(0, 8)}…` : `→${names}`
}

function decisionSummary(c: CombatantView): string {
  const p = pending.value[c.id]
  if (!p) return '未下达指令'
  if (p.actionType === 'SKILL') {
    const s = c.skills.find((x) => x.id === p.skillId)
    if (!s) return '未下达指令'
    const t = p.targetIds.map(combatantName).join('、')
    return t ? `${s.name} → ${t}` : `${s.name}`
  }
  const label = actionLabel(p.actionType)
  if (actionNeedsTarget(p.actionType) || actionNeedsAllyTarget(p.actionType)) {
    const t = p.targetIds.map(combatantName).join('、')
    return t ? `${label} → ${t}` : `${label}`
  }
  return label
}

function clearDecision(c: CombatantView) {
  pending.value[c.id] = newPending()
}

// Target-selection helpers.
function onAimMove(ev: MouseEvent) {
  if (!aimMode.value) {
    aimLine.value = null
    return
  }
  updateAimLine(ev.clientX, ev.clientY)
}

// Draw the target guide line.
function updateAimLine(clientX: number, clientY: number) {
  if (!aimMode.value) return
  const scene = document.querySelector('.stage-scene') as HTMLElement | null
  const unit = unitEl(aimMode.value.combatantId)
  if (!scene || !unit) return
  const sr = scene.getBoundingClientRect()
  const ur = unit.getBoundingClientRect()
  aimLine.value = {
    x1: ur.left - sr.left + ur.width / 2,
    y1: ur.top - sr.top + ur.height / 2,
    x2: clientX - sr.left,
    y2: clientY - sr.top
  }
}

function onKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape') cancelAim()
}

// Empty-stage clicks cancel targeting.
function onStageClick(ev: MouseEvent) {
  if (ev.target === ev.currentTarget) cancelAim()
}

function cancelAim() {
  aimMode.value = null
  aimLine.value = null
}

function canLockTarget(t: CombatantView): boolean {
  const p = aimMode.value
  if (!p) return false
  const c = battle.value?.combatants.find((x) => x.id === p.combatantId)
  if (!c || c.dead) return false
  if (p.kind === 'skill') {
    const s = c.skills.find((x) => x.id === p.id)
    return s ? isSkillTargetValid(s, c, t) : false
  }
  if (actionNeedsTarget(p.id)) return t.side !== mySide.value && !t.dead
  if (actionNeedsAllyTarget(p.id)) return t.side === mySide.value && !t.dead && t.id !== c.id
  return false
}

function lockTarget(t: CombatantView) {
  const p = aimMode.value
  if (!p || !canLockTarget(t)) return
  const c = battle.value?.combatants.find((x) => x.id === p.combatantId)
  if (!c) return
  const dec = pending.value[c.id]
  if (!dec) return
  if (p.kind === 'skill') {
    const s = c.skills.find((x) => x.id === p.id)
    if (!s) return
    const cap = skillTargetCap(s)
    if (dec.targetIds.includes(t.id)) {
      message.info('该目标已锁定')
      return
    }
    if (dec.targetIds.length >= cap) {
      message.warning(`最多锁定 ${cap} 个目标`)
      return
    }
    dec.targetIds.push(t.id)
    if (dec.targetIds.length >= cap) cancelAim()
  } else {
    dec.targetIds = [t.id]
    cancelAim()
  }
  pending.value[c.id] = { ...dec }
}

function onUnitClick(c: CombatantView) {
  if (aimMode.value) {
    if (canLockTarget(c)) {
      lockTarget(c)
      return
    }
    cancelAim()
    return
  }
  toggleSelect(c.id)
}

function removeLockedTarget(c: CombatantView, idx: number) {
  const p = pending.value[c.id]
  if (!p) return
  p.targetIds.splice(idx, 1)
  pending.value[c.id] = { ...p }
}

// Reset extra-action selections.
watch(inExtraRound, (on) => {
  if (!on) return
  for (const c of extraActors.value) {
    pending.value[c.id] = newPending()
  }
})

// PVE players command only their own characters.
function canControl(c: CombatantView): boolean {
  if (!isPve.value) return c.side === mySide.value
  return c.ownerUsername === myUsername.value
}

// Keep PVE decision drafts synchronized.
let draftTimer = 0

function scheduleDraft() {
  if (!isPve.value || !inDecision.value || isFinished.value || mySubmitted.value || submitting.value) {
    return
  }
  window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(() => {
    void pushDraft()
  }, 600)
}

async function pushDraft() {
  const b = battle.value
  if (!b || !isPve.value || !inDecision.value || isFinished.value || mySubmitted.value || submitting.value) {
    return
  }
  const decisions = buildDecisionList(true)
  if (!decisions || decisions.length === 0) {
    return
  }
  const seq = loadSeq
  try {
    const view = await saveDraft(b.id, decisions)
    if (seq !== loadSeq) {
      return
    }
    battle.value = view
    processLogs(view.logs)
  } catch (e) {
    console.warn('draft save failed', e)
  }
}

// Report local decision changes as drafts.
watch(pending, () => scheduleDraft(), { deep: true })



/** Whether a decision is ready to submit. */
function isConfigured(c: CombatantView, p: PendingDecision): boolean {
  if (p.actionType === 'SKILL') {
    if (!p.skillId) return false
    const s = c.skills.find((x) => x.id === p.skillId)
    return !(s && skillNeedsTarget(s)) || p.targetIds.length > 0
  }
  if (actionNeedsTarget(p.actionType) || actionNeedsAllyTarget(p.actionType)) {
    return p.targetIds.length > 0
  }
  return true
}

/** Build the current decisions; partial mode skips incomplete ones. */
function buildDecisionList(partial = false): ActionDecision[] | null {
  const decisions: ActionDecision[] = []
  for (const c of decisionActors.value) {
    const p = pending.value[c.id]
    if (!p) continue
    if (partial && !isConfigured(c, p)) continue
    if (p.actionType === 'SKILL') {
      if (!p.skillId) {
        if (!partial) {
          message.warning(`${c.name} 未选择技能`)
        }
        return null
      }
      const s = c.skills.find((x) => x.id === p.skillId)
      if (s && skillNeedsTarget(s) && p.targetIds.length === 0) {
        if (!partial) {
          message.warning(`${c.name} 的技能 ${s.name} 未锁定目标`)
        }
        return null
      }
      decisions.push({
        combatantId: c.id,
        actionType: 'SKILL',
        skillId: p.skillId,
        targetId: p.targetIds[0] ?? null,
        targetIds: [...p.targetIds]
      })
    } else {
      if (actionNeedsTarget(p.actionType) && p.targetIds.length === 0) {
        if (!partial) {
          message.warning(`${c.name} 未选择攻击目标`)
        }
        return null
      }
      if (actionNeedsAllyTarget(p.actionType) && p.targetIds.length === 0) {
        if (!partial) {
          message.warning(`${c.name} 未选择守护目标`)
        }
        return null
      }
      decisions.push({
        combatantId: c.id,
        actionType: p.actionType,
        targetId: p.targetIds[0] ?? null,
        targetIds: [...p.targetIds]
      })
    }
  }
  return decisions
}

async function submitDecisions(partial = false) {
  const decisions = buildDecisionList(partial)
  if (!decisions) {
    return
  }
  if (!partial && decisions.length !== decisionActors.value.length) {
    message.warning(inExtraRound.value ? '请为拥有额外行动的角色下达指令' : '请为所有存活角色下达指令')
    return
  }
  loadSeq++
  submitting.value = true
  try {
    // Gate settlement animations during submission.
    lockCurtainWindow()
    curtainGateUntil = Date.now() + 1900
    if (inExtraRound.value) {
      battle.value = await decideExtraActions(battle.value!.id, decisions)
      processLogs(battle.value.logs)
    } else {
      battle.value = await decide(battle.value!.id, decisions)
      processLogs(battle.value.logs)
    }
    await nextTick()
    playCurtainNow('rise', () => {
      unlockCurtainWindow()
    })
  } catch (e) {
    message.error(errorMessage(e))
    curtainGateUntil = 0
    unlockCurtainWindow()
  } finally {
    submitting.value = false
  }
}

/** Surrender and return home. */
async function surrenderAndLeave() {
  if (!battle.value || isFinished.value) {
    router.push({ name: 'home' })
    return
  }
  const confirmText = isPve.value
    ? '投降后你的角色将退出战斗（队友继续），确定退出吗？'
    : '投降将立即结束本场比赛（判负），确定退出吗？'
  if (!window.confirm(confirmText)) {
    return
  }
  submitting.value = true
  try {
    await surrenderBattle(battle.value.id)
    router.push({ name: 'home' })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function skipExtra() {
  loadSeq++
  submitting.value = true
  try {
    battle.value = await skipExtraActions(battle.value!.id)
    processLogs(battle.value.logs)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function chooseInitialPerk(perkId: string) {
  loadSeq++
  submitting.value = true
  try {
    battle.value = await selectInitialPerk(battle.value!.id, perkId)
    processLogs(battle.value.logs)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function chooseSpecialPerk(perkId: string) {
  loadSeq++
  submitting.value = true
  try {
    battle.value = await selectSpecialPerk(battle.value!.id, perkId)
    processLogs(battle.value.logs)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function skipPerk() {
  loadSeq++
  submitting.value = true
  try {
    battle.value = await skipSpecialPerk(battle.value!.id)
    processLogs(battle.value.logs)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function playCardFromHand(skillId: string, targetId?: string) {
  loadSeq++
  submitting.value = true
  try {
    battle.value = await playCard(battle.value!.id, skillId, targetId)
    processLogs(battle.value.logs)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

function displayHpOf(c: CombatantView): number {
  return displayHp.value[c.id] ?? c.hp
}

function hpPercent(c: CombatantView): string {
  return `${Math.max(0, Math.round((displayHpOf(c) / c.maxHp) * 100))}%`
}

function energyPercent(c: CombatantView): string {
  return `${Math.max(0, Math.round((c.energy / c.maxEnergy) * 100))}%`
}

function statusText(c: CombatantView): string {
  const parts: string[] = []
  if (c.performing) parts.push('演出中')
  if (c.dodging) parts.push('闪避中')
  if (c.permanentExtraAction) parts.push('恒动')
  if (c.undyingRounds > 0) parts.push(`宁死不屈×${c.undyingRounds}`)
  for (const s of c.statusEffects) {
    parts.push(`${s.type}(${s.remainingRounds})`)
  }
  return parts.join(' ')
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main v-if="battle" class="container">
      <div class="head">
        <n-button quaternary :loading="submitting" @click="surrenderAndLeave">投降退出</n-button>
        <div class="head-info">
          <span class="round">第 {{ battle.round }} 回合</span>
          <span v-if="isPvp" class="pvp-tag" :class="battle.mySide === 'PLAYER' ? 'host' : 'guest'">
            {{ battle.mySide === 'PLAYER' ? '先手方' : '后手方' }} · VS {{ opponentName }}
          </span>
          <span v-else-if="isPve" class="pvp-tag host">PVE 联机 · {{ battle.players.length }} 人</span>
          <span v-else class="dim">先手：{{ battle.firstStrikeSide === 0 ? '玩家' : '木桩' }}</span>
          <span class="dim">抽牌能量 {{ battle.playerDrawEnergy }}/10</span>
        </div>
        <n-button size="small" @click="load">刷新</n-button>
      </div>

      <div class="speed-track" v-if="speedOrder.length">
        <template v-for="(sp, i) in speedOrder" :key="sp.id">
          <span v-if="i > 0" class="speed-arrow">›</span>
          <div class="speed-node">
            <span class="speed-name">{{ sp.name }}</span>
            <span class="speed-roll">{{ sp.roll }}</span>
          </div>
        </template>
      </div>

      <div
        v-if="isFinished"
        class="panel result-banner"
        :class="battle.winner === (battle.mySide ?? 'PLAYER') ? 'win' : 'lose'"
      >
        <h2>{{ battle.winner === (battle.mySide ?? 'PLAYER') ? '战斗胜利' : '战斗败北' }}</h2>
        <n-button type="primary" @click="router.push({ name: 'records' })">查看战报</n-button>
      </div>

      <section v-if="inInitialPerk" class="panel perk-panel">
        <h3 v-if="isPvp && battle.mySubmitted">等待对方选择初始词条…</h3>
        <h3 v-else-if="isPve && battle.mySubmitted">等待其他玩家选择：{{ battle.submittedUsers.length }}/{{ battle.players.length }}</h3>
        <h3 v-else>选择初始词条</h3>
        <div class="perk-grid" :class="{ disabled: (isPvp || isPve) && battle.mySubmitted }">
          <div
            v-for="p in battle.initialPerkOptions"
            :key="p.id"
            class="card-face perk-wide"
            @click="chooseInitialPerk(p.id)"
          >
            <img class="face-img" src="/assets/core_perk.webp" alt="" />
            <div class="face-text">
              <div class="face-name">{{ p.name }}</div>
              <div class="face-desc">{{ p.description }}</div>
            </div>
          </div>
        </div>
        <p v-if="isPvp && battle.opponentSubmitted && !battle.mySubmitted" class="dim wait-hint">
          对方已选好词条，等待你选择…
        </p>
        <p v-else-if="isPve && !battle.mySubmitted && battle.submittedUsers.length > 0" class="dim wait-hint">
          已选择：{{ submittedUsersText }}
        </p>
      </section>

      <div class="action-bar" :class="{ active: !!actionBarText }">{{ actionBarText }}</div>

      <div class="stage">
        <div
          class="stage-scene"
          :class="{ dimmed: anyPerforming, zoomed: anyPerforming }"
          :style="{ transformOrigin: zoomOrigin }"
          @click="onStageClick"
        >
        <div class="side-col side-player">
          <div
            v-for="c in players"
            :key="c.id"
            class="unit"
            :data-unit-id="c.id"
            :style="unitDxStyle(c.id)"
            :class="{
              dead: c.dead,
              performing: performing[c.id],
              approaching: approaching[c.id],
              clashing: clashing[c.id],
              shaking: shaking[c.id],
              selected: selectedId === c.id,
              'aim-target': aimMode && canLockTarget(c)
            }"
            @click.stop="onUnitClick(c)"
          >
            <div class="portrait-wrap">
              <img
                v-if="!portraitFailed[portraitUrl(c)]"
                :src="portraitUrl(c)"
                :alt="c.name"
                class="portrait"
                @error="onPortraitError($event)"
              />
              <div v-else class="portrait-placeholder">{{ c.name.charAt(0) }}</div>
              <span v-if="c.performing" class="tag-perform">演出</span>
            </div>
            <div class="float-layer">
              <div
                  v-for="f in floatsFor(c.id)"
                  :key="f.id"
                  class="float-num"
                  :class="f.kind"
                  :style="{ top: floatTop(f) }"
                >
                  {{ f.text }}
                </div>
            </div>
            <div
              v-if="diceAnims[c.id]"
              :key="diceAnims[c.id].seq"
              class="dice-pop"
              :class="{ racing: dashActive }"
            >
              <span class="dice-face">🎲</span>
              <span class="dice-num" :class="diceAnims[c.id].result">{{ diceAnims[c.id].live }}</span>
            </div>
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
                <span
                  v-if="isPve && battle.players.length > 1 && c.ownerUsername"
                  class="owner-tag"
                  :class="{ me: c.ownerUsername === myUsername }"
                >
                  {{ c.ownerUsername }}
                </span>
                <span
                  v-if="inDecision && !c.dead && canControl(c)"
                  class="tag-decision"
                  :class="{ ready: decisionReady(c), waiting: hasDecision(c) && !decisionReady(c) }"
                >
                  {{ decisionTagText(c) }}
                </span>
              </div>
              <div class="bar-row">
                <div class="hp-bar">
                  <div :style="{ width: hpPercent(c) }"></div>
                  <span class="bar-inline">{{ displayHpOf(c) }}/{{ c.maxHp }}</span>
                </div>
              </div>
              <div class="bar-row">
                <div class="energy-bar">
                  <div :style="{ width: energyPercent(c) }"></div>
                  <span class="bar-inline">{{ c.energy }}/{{ c.maxEnergy }}</span>
                </div>
              </div>
              <div class="bar-row">
                <div class="speed-bar">
                  <div :style="{ width: speedFill(c) }"></div>
                  <span class="bar-inline">{{ currentSpeed[c.id] ?? '–' }}</span>
                </div>
              </div>
              <div v-if="statusText(c)" class="unit-status dim">{{ statusText(c) }}</div>
            </div>
          </div>
        </div>

        <div class="side-col side-enemy">
          <div
            v-for="c in enemies"
            :key="c.id"
            class="unit"
            :data-unit-id="c.id"
            :style="unitDxStyle(c.id)"
            :class="{
              dead: c.dead,
              performing: performing[c.id],
              approaching: approaching[c.id],
              clashing: clashing[c.id],
              shaking: shaking[c.id],
              selected: selectedId === c.id,
              'aim-target': aimMode && canLockTarget(c)
            }"
            @click.stop="onUnitClick(c)"
          >
            <div class="portrait-wrap">
              <img
                v-if="!portraitFailed[portraitUrl(c)]"
                :src="portraitUrl(c)"
                :alt="c.name"
                class="portrait"
                @error="onPortraitError($event)"
              />
              <div v-else class="portrait-placeholder">{{ c.name.charAt(0) }}</div>
              <span v-if="c.performing" class="tag-perform">演出</span>
            </div>
            <div class="float-layer">
              <div
                  v-for="f in floatsFor(c.id)"
                  :key="f.id"
                  class="float-num"
                  :class="f.kind"
                  :style="{ top: floatTop(f) }"
                >
                  {{ f.text }}
                </div>
            </div>
            <div
              v-if="diceAnims[c.id]"
              :key="diceAnims[c.id].seq"
              class="dice-pop"
              :class="{ racing: dashActive }"
            >
              <span class="dice-face">🎲</span>
              <span class="dice-num" :class="diceAnims[c.id].result">{{ diceAnims[c.id].live }}</span>
            </div>
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
                <span
                  v-if="inDecision && !c.dead && canControl(c)"
                  class="tag-decision"
                  :class="{ ready: decisionReady(c), waiting: hasDecision(c) && !decisionReady(c) }"
                >
                  {{ decisionTagText(c) }}
                </span>
              </div>
              <div class="bar-row">
                <div class="hp-bar">
                  <div :style="{ width: hpPercent(c) }"></div>
                  <span class="bar-inline">{{ displayHpOf(c) }}/{{ c.maxHp }}</span>
                </div>
              </div>
              <div class="bar-row">
                <div class="energy-bar">
                  <div :style="{ width: energyPercent(c) }"></div>
                  <span class="bar-inline">{{ c.energy }}/{{ c.maxEnergy }}</span>
                </div>
              </div>
              <div class="bar-row">
                <div class="speed-bar">
                  <div :style="{ width: speedFill(c) }"></div>
                  <span class="bar-inline">{{ currentSpeed[c.id] ?? '–' }}</span>
                </div>
              </div>
              <div v-if="statusText(c)" class="unit-status dim">{{ statusText(c) }}</div>
            </div>
          </div>
        </div>

        <svg v-if="aimLine" class="aim-line">
          <defs>
            <marker id="aim-arrow-head" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
              <path d="M0,0 L9,4.5 L0,9 Z" fill="rgba(255,200,87,0.95)" />
            </marker>
          </defs>
          <line
            :x1="aimLine.x1"
            :y1="aimLine.y1"
            :x2="aimLine.x2"
            :y2="aimLine.y2"
            marker-end="url(#aim-arrow-head)"
          />
        </svg>

        <div v-if="curtain" :key="curtain.seq" class="curtain" :class="curtain.kind">
          <img :src="curtain.kind === 'rise' ? '/assets/curtain_rise.webp' : '/assets/curtain_fall.webp'" alt="" />
        </div>

        <div v-if="dashOverlay" :key="dashOverlay.seq" class="dash-moment">
          <img src="/assets/last_dash.webp" alt="决速时刻" />
        </div>

        <div v-if="inDecision" class="hand-overlay">
          <div
            v-for="(card, i) in battle.playerHand"
            :key="card.id"
            class="card-face generic hand-card"
            :style="handCardStyle(i, battle.playerHand.length)"
            @click="playCardFromHand(card.id)"
          >
            <img class="face-img" src="/assets/generic_skill_card.webp" alt="" />
            <div class="face-text dark">
              <div class="face-name">{{ card.name }}</div>
              <div class="face-desc">{{ card.description }}</div>
            </div>
          </div>
          <span v-if="battle.playerHand.length === 0" class="dim hand-empty">无手牌</span>
        </div>

        <div v-if="inSpecialPerk" class="perk-overlay">
          <template v-if="isPvp && battle.mySubmitted">
            <p class="perk-wait">等待对方选择特殊词条…</p>
          </template>
          <template v-else-if="isPve && battle.mySubmitted">
            <p class="perk-wait">等待其他玩家选择：{{ battle.submittedUsers.length }}/{{ battle.players.length }}</p>
          </template>
          <template v-else>
            <div
              v-for="p in battle.specialPerkOptions"
              :key="p.id"
              class="card-face perk"
              @click="chooseSpecialPerk(p.id)"
            >
              <img class="face-img" src="/assets/core_perk.webp" alt="" />
              <div class="face-text">
                <div class="face-name">{{ p.name }}</div>
                <div class="face-desc">{{ p.description }}</div>
              </div>
            </div>
            <span v-if="battle.specialPerkOptions.length === 0" class="dim">无可用词条</span>
            <n-button quaternary size="small" :loading="submitting" @click="skipPerk">跳过本轮</n-button>
          </template>
        </div>

        <div
          v-if="selectedCombatant && skillPanelPos"
          class="skill-panel"
          :style="skillPanelPos"
          @click.stop
        >
          <div class="skill-panel-head">
            <span class="skill-panel-name">{{ selectedCombatant.name }}</span>
            <span class="skill-panel-close" @click="selectedId = null">✕</span>
          </div>
          <div v-if="canControl(selectedCombatant)" class="panel-tabs">
            <button
              class="panel-tab"
              :class="{ active: panelTab === 'actions' }"
              @click="panelTab = 'actions'"
            >
              行动
            </button>
            <button
              class="panel-tab"
              :class="{ active: panelTab === 'skills' }"
              @click="panelTab = 'skills'"
            >
              技能
            </button>
          </div>

          <div v-if="panelTab === 'actions'" class="action-list">
            <button
              v-for="a in selectedCombatant.baseActions"
              :key="a"
              class="action-chip"
              :class="{ active: pending[selectedCombatant.id]?.actionType === a }"
              :disabled="!canControl(selectedCombatant) || animating"
              @click="pickAction(selectedCombatant, a, $event)"
            >
              {{ actionLabel(a) }}
            </button>
          </div>

          <div v-else class="skill-cards" :class="{ readonly: !canControl(selectedCombatant) }">
            <div
              v-for="sk in selectedCombatant.skills"
              :key="sk.id"
              class="card-face skill"
              :class="{
                upgraded: sk.upgraded,
                active: canControl(selectedCombatant) && skillActive(selectedCombatant, sk),
                disabled:
                  canControl(selectedCombatant) &&
                  ((selectedCombatant.cooldowns[sk.id] ?? 0) > 0 || animating)
              }"
              @click="canControl(selectedCombatant) && pickSkill(selectedCombatant, sk, $event)"
            >
              <img
                class="face-img"
                :src="sk.upgraded ? '/assets/advanced_skill.webp' : '/assets/skill_card.webp'"
                alt=""
              />
              <div class="face-text">
                <div class="face-name">
                  {{ sk.name }}
                  <span class="face-cost">{{ sk.energyCost }}EP</span>
                  <span v-if="(selectedCombatant.cooldowns[sk.id] ?? 0) > 0" class="face-cd">
                    CD{{ selectedCombatant.cooldowns[sk.id] }}
                  </span>
                  <span v-if="sk.upgraded" class="face-up">升变</span>
                </div>
                <div class="face-desc">{{ sk.description }}</div>
                <div v-if="skillActive(selectedCombatant, sk) && skillNeedsTarget(sk)" class="locked-targets">
                  <span
                    v-for="(tid, ti) in pending[selectedCombatant.id]?.targetIds ?? []"
                    :key="tid"
                    class="target-chip"
                  >
                    {{ combatantName(tid) }}
                    <b @click.stop="removeLockedTarget(selectedCombatant, ti)">✕</b>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <div
            v-if="canControl(selectedCombatant)"
            class="decision-summary"
            :class="{ ready: decisionReady(selectedCombatant) }"
          >
            <span>{{ decisionSummary(selectedCombatant) }}</span>
            <span
              v-if="hasDecision(selectedCombatant)"
              class="clear-decision"
              @click="clearDecision(selectedCombatant)"
            >
              撤销
            </span>
          </div>
        </div>
        </div>
      </div>

      <div
        v-if="inDecision"
        class="panel decision-panel"
        :class="{ locked: animating || awaitingOpponent || pveWaiting }"
      >
        <div v-if="inExtraRound" class="extra-round-hint">
          ⚡ 额外行动轮：{{ extraActors.map((a) => `${a.name}（剩余 ${a.extraActionsThisTurn}）`).join('、') }}
        </div>
        <div v-if="isPve" class="waiting-hint">
          <span class="dim">
            <template v-if="inExtraRound && !mySubmitted">额外行动中，等待你提交…</template>
            <template v-else>已提交 {{ battle.submittedUsers.length }}/{{ battle.players.length }}<template v-if="battle.submittedUsers.length > 0">：{{ submittedUsersText }}</template></template>
          </span>
        </div>
        <div v-if="awaitingOpponent" class="waiting-hint">
          <span v-if="opponentsExtraRound" class="dim">等待 {{ opponentName }} 完成额外行动…</span>
          <span v-else class="dim">已提交，等待 {{ opponentName }}…</span>
          <span v-if="countdown > 0" class="countdown">⏱ {{ countdown }}s</span>
        </div>
        <div v-else-if="!pveWaiting" class="decision-actions">
          <span v-if="isPvp && opponentSubmitted && !mySubmitted" class="dim wait-hint">
            对方已提交指令，等待你…
          </span>
          <n-button
            v-if="inExtraRound"
            quaternary
            size="small"
            :disabled="animating || submitting"
            @click="skipExtra"
          >
            跳过剩余额外行动
          </n-button>
          <n-button type="primary" :loading="submitting" :disabled="animating" @click="submitDecisions()">
            {{ inExtraRound ? '执行额外行动' : '提交指令' }}
            <template v-if="isPvp && countdown > 0">（{{ countdown }}s）</template>
          </n-button>
        </div>
      </div>
      <section class="panel log-panel">
        <h4>战斗日志</h4>
        <div class="log-list">
          <div v-for="(log, i) in battle.logs" :key="i" class="log-row">
            <span class="log-round dim">R{{ log.round }}</span>
            <span class="log-type" :class="LOG_TYPE_CLASS[log.type]">{{ log.type }}</span>
            <span class="log-message">{{ log.message }}</span>
          </div>
        </div>
      </section>
    </main>

  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.head-info {
  flex: 1;
  display: flex;
  gap: 16px;
  align-items: baseline;
}

.round {
  font-size: 18px;
  font-weight: 700;
}

.result-banner {
  text-align: center;
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.result-banner h2 {
  font-size: 24px;
}

.result-banner.win {
  border-color: rgba(93, 219, 140, 0.5);
}

.result-banner.win h2 {
  color: var(--ok);
}

.result-banner.lose {
  border-color: rgba(255, 93, 108, 0.5);
}

.result-banner.lose h2 {
  color: var(--danger);
}

.perk-panel h3 {
  font-size: 16px;
  margin-bottom: 12px;
}

.perk-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.perk-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  background: var(--bg-panel-2);
}

.perk-card:hover {
  border-color: var(--accent);
  background: rgba(76, 194, 255, 0.08);
}

.perk-name {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}

.perk-desc {
  font-size: 13px;
  color: var(--text-dim);
}

/* Battle stage */

.stage {
  position: relative;
  aspect-ratio: 1776 / 1100;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  transition: filter 0.4s ease;
}

/* Camera scene */
.stage-scene {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6%;
  padding: 18px 30px 170px;
  background:
    linear-gradient(180deg, rgba(11, 14, 20, 0.2), rgba(11, 14, 20, 0.5)),
    url('/assets/fight_background.webp?v=3') bottom / cover no-repeat;
  transition: transform 0.5s ease;
  will-change: transform;
}

.stage-scene.zoomed {
  transform: scale(1.18);
}

.stage-scene.dimmed .unit:not(.performing):not(.dead) {
  opacity: 0.45;
}

.side-col {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  gap: 10px;
}

.unit {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 118px;
  padding: 8px;
  background: transparent;
  border: none;
  transition: transform 0.55s ease, opacity 0.35s ease;
}

.unit.dead {
  opacity: 0.35;
  filter: grayscale(0.9);
}

.unit.shaking {
  animation: unit-shake 0.45s ease;
}

.unit.performing {
  box-shadow: 0 0 28px rgba(76, 194, 255, 0.45);
  z-index: 3;
}

/* Target approach */
.side-player .unit.approaching {
  transform: translateX(var(--anim-dx, 100px));
}

.side-enemy .unit.approaching {
  transform: translateX(var(--anim-dx, -100px));
}

/* Clash approach */
.side-player .unit.clashing {
  --clash-x: var(--anim-dx, calc((min(1200px, 100vw) - 242px) / 2));
  transform: translateX(var(--clash-x));
  z-index: 7;
}

.side-enemy .unit.clashing {
  --clash-x: var(--anim-dx, calc((min(1200px, 100vw) - 242px) / -2));
  transform: translateX(var(--clash-x));
  z-index: 6;
}

/* Clash impact */
.unit.shaking.clashing {
  animation: unit-shake-clash 0.45s ease;
}

@keyframes unit-shake-clash {
  0%,
  100% {
    transform: translateX(var(--clash-x, 0px));
  }
  20% {
    transform: translateX(calc(var(--clash-x, 0px) - 7px));
  }
  40% {
    transform: translateX(calc(var(--clash-x, 0px) + 7px));
  }
  60% {
    transform: translateX(calc(var(--clash-x, 0px) - 5px));
  }
  80% {
    transform: translateX(calc(var(--clash-x, 0px) + 5px));
  }
}

.portrait-wrap {
  position: relative;
  width: 96px;
  height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  transition: transform 0.45s ease;
}

.portrait {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* Enemy-facing portraits */
.side-enemy .portrait,
.side-enemy .portrait-placeholder {
  transform: scaleX(-1);
}

/* Player-side Hod portrait */
.side-player .portrait[src*="hod_"] {
  transform: scaleX(-1);
}

/* Enemy-side Hod portrait */
.side-enemy .portrait[src*="hod_"] {
  transform: scaleX(1);
}

.portrait-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44px;
  font-weight: 700;
  color: rgba(215, 224, 238, 0.75);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.8);
}

.tag-perform {
  position: absolute;
  top: 5px;
  left: 5px;
  font-size: 10px;
  color: var(--warn);
  border: 1px solid var(--warn);
  border-radius: 4px;
  padding: 0 4px;
  background: rgba(11, 14, 20, 0.6);
  z-index: 2;
}

.float-layer {
  position: absolute;
  top: -6px;
  left: 0;
  right: 0;
  height: 0;
  pointer-events: none;
  z-index: 4;
}

.float-num {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  font-size: 21px;
  font-weight: 700;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.9);
  animation: float-up 1.25s ease-out forwards;
}

.float-num.damage,
.float-num.heal {
  top: 34px;
  font-size: 18px;
}

.float-num.damage {
  color: var(--danger);
}

.float-num.heal {
  color: var(--ok);
}

.float-num.action {
  top: -40px;
  font-size: 26px;
  font-weight: 900;
  color: #ffc857;
  letter-spacing: 1px;
  text-shadow: 0 0 14px rgba(255, 200, 87, 0.7), 0 2px 6px rgba(0, 0, 0, 0.95);
  animation: action-pop 1.1s ease-out forwards;
}

/* Speed-roll dice */
.dice-pop {
  position: absolute;
  top: -62px;
  left: 50%;
  width: 36px;
  height: 36px;
  transform: translateX(-50%);
  pointer-events: none;
  z-index: 5;
}

.dice-face {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  line-height: 1;
  animation: dice-roll 1.7s ease forwards;
}

.dice-num {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 800;
  line-height: 1;
  color: #fff;
  text-shadow: 0 0 10px rgba(76, 194, 255, 0.9);
  animation: dice-reveal 1.7s ease forwards;
}

.dice-num.win {
  color: #5ddb8c;
  text-shadow: 0 0 12px rgba(93, 219, 140, 0.95);
}

.dice-num.lose {
  color: #ff5d6c;
  text-shadow: 0 0 12px rgba(255, 93, 108, 0.95);
}

/* Dash speed roll */
.dice-pop.racing .dice-face {
  display: none;
}
.dice-pop.racing .dice-num {
  animation: none;
  opacity: 1;
}

@keyframes dice-roll {
  0% {
    opacity: 0;
    transform: scale(0.3) rotate(-40deg);
  }
  14% {
    opacity: 1;
    transform: scale(1.2) rotate(12deg);
  }
  28% {
    transform: scale(1) rotate(0deg);
  }
  58% {
    opacity: 1;
  }
  66% {
    opacity: 0;
    transform: scale(1.06);
  }
  100% {
    opacity: 0;
  }
}

@keyframes dice-reveal {
  0%,
  66% {
    opacity: 0;
    transform: scale(0.55);
  }
  80% {
    opacity: 1;
    transform: scale(1);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes action-pop {
  0% {
    opacity: 0;
    transform: translate(-50%, 8px) scale(0.5);
  }
  20% {
    opacity: 1;
    transform: translate(-50%, -8px) scale(1.3);
  }
  45% {
    transform: translate(-50%, -12px) scale(1);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -42px) scale(0.95);
  }
}

.info {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.name {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shield-tag {
  color: var(--shield);
  font-size: 11px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 5px;
}

.unit-status {
  font-size: 10px;
  text-align: center;
}

/* Curtains */

.curtain {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  z-index: 5;
  overflow: hidden;
}

.curtain.rise {
  animation: curtain-rise 1.7s ease forwards;
}

.curtain.fall {
  animation: curtain-fall 1.7s ease forwards;
}

.curtain img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* Curtain rise */
@keyframes curtain-rise {
  0% {
    transform: translateY(100%);
    opacity: 0;
  }
  25% {
    transform: translateY(0);
    opacity: 0.88;
  }
  72% {
    transform: translateY(0);
    opacity: 0.88;
  }
  100% {
    transform: translateY(-100%);
    opacity: 0;
  }
}

/* Curtain fall */
@keyframes curtain-fall {
  0% {
    transform: translateY(-100%);
    opacity: 0;
  }
  25% {
    transform: translateY(0);
    opacity: 0.88;
  }
  72% {
    transform: translateY(0);
    opacity: 0.88;
  }
  100% {
    transform: translateY(100%);
    opacity: 0;
  }
}

/* Last-dash overlay */

.dash-moment {
  position: absolute;
  inset: 0;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  z-index: 6;
  animation: dash-burst 1.4s ease-out forwards;
}

.dash-moment img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* Dash burst */
@keyframes dash-burst {
  0% {
    opacity: 0;
    transform: scale(0.35);
  }
  22% {
    opacity: 1;
    transform: scale(1);
  }
  100% {
    opacity: 0;
    transform: scale(1.65);
  }
}

@keyframes unit-shake {
  0%,
  100% {
    transform: translateX(0);
  }
  20% {
    transform: translateX(-7px);
  }
  40% {
    transform: translateX(7px);
  }
  60% {
    transform: translateX(-5px);
  }
  80% {
    transform: translateX(5px);
  }
}

@keyframes float-up {
  0% {
    opacity: 0;
    transform: translate(-50%, 10px) scale(0.8);
  }
  15% {
    opacity: 1;
    transform: translate(-50%, 0) scale(1.12);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -48px) scale(1);
  }
}

/* Decision panel */

.decision-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.decision-panel.locked {
  opacity: 0.6;
  pointer-events: none;
}

.extra-round-hint {
  font-size: 13px;
  color: var(--accent, #4cc2ff);
  margin-bottom: 8px;
  font-weight: 600;
}

/* PVP status */
.pvp-tag {
  font-size: 13px;
  padding: 2px 10px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-dim);
}

.pvp-tag.host {
  color: var(--accent, #4cc2ff);
  border-color: var(--accent, #4cc2ff);
}

.pvp-tag.guest {
  color: var(--warn, #f0a020);
  border-color: var(--warn, #f0a020);
}

/* PVE owner labels */
.owner-tag {
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-dim);
  vertical-align: middle;
}

.owner-tag.me {
  color: var(--accent, #4cc2ff);
  border-color: var(--accent, #4cc2ff);
}

.waiting-hint {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 8px 0;
}

.countdown {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 15px;
  color: var(--warn, #f0a020);
}

.wait-hint {
  font-size: 12px;
}

.perk-wait {
  color: var(--text-dim);
  padding: 24px;
}

.perk-grid.disabled {
  pointer-events: none;
  opacity: 0.55;
}

.decision-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
}

/* Card faces */
.card-face {
  position: relative;
  width: 112px;
  aspect-ratio: 4 / 5;
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.55);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.card-face:hover {
  transform: translateY(-6px) scale(1.05);
  box-shadow: 0 10px 22px rgba(0, 0, 0, 0.7);
  z-index: 3;
}
.face-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.face-text {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  min-height: 42%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 3px;
  padding: 24px 8px 9px;
  color: #fff;
  text-align: center;
  background: linear-gradient(180deg, transparent, rgba(0, 0, 0, 0.9) 34%);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.9);
}
.face-text.dark {
  color: #191919;
  text-shadow: none;
  background: linear-gradient(180deg, transparent, rgba(255, 255, 255, 0.92) 30%);
}
.face-name {
  font-size: 13px;
  font-weight: 800;
  line-height: 1.2;
}
.face-desc {
  font-size: 10px;
  line-height: 1.35;
  opacity: 0.92;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.face-cost {
  margin-left: 5px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
  color: #fff;
  background: rgba(30, 136, 229, 0.92);
  vertical-align: 1px;
}
.face-cd {
  margin-left: 4px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
  color: #fff;
  background: rgba(229, 57, 53, 0.92);
  vertical-align: 1px;
}
.face-up {
  margin-left: 4px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
  color: #3b2a00;
  background: linear-gradient(180deg, #ffe082, #ffb300);
  vertical-align: 1px;
}

/* Hand cards */
.hand-cards {
  display: flex;
  align-items: flex-end;
}
.hand-cards .card-face {
  margin-left: -18px;
}
.hand-cards .card-face:first-child {
  margin-left: 0;
}

/* Perk offers */
.perk-offers .card-face {
  width: 128px;
}
.perk-offers .card-face:nth-child(2) {
  transform: scale(1.08);
  z-index: 2;
}

/* Initial perk cards */
.perk-wide {
  width: 150px;
}

/* Skill panel */
.skill-panel {
  position: absolute;
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
  background: rgba(11, 14, 20, 0.85);
  border: 1px solid rgba(255, 200, 87, 0.3);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.6);
}
.skill-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.skill-panel-name {
  font-size: 14px;
  font-weight: 800;
  color: #ffe08a;
}
.skill-panel-close {
  cursor: pointer;
  color: var(--text-dim);
  font-size: 13px;
  padding: 0 4px;
}
.skill-panel-close:hover {
  color: #fff;
}
.panel-tabs {
  display: flex;
  gap: 6px;
}

.panel-tab {
  flex: 1;
  padding: 5px 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-dim);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.panel-tab.active {
  color: #ffe08a;
  border-color: rgba(255, 200, 87, 0.5);
  background: rgba(255, 200, 87, 0.1);
}

.action-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.action-chip {
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.action-chip:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.action-chip.active {
  color: #ffe08a;
  border-color: rgba(255, 200, 87, 0.6);
  background: rgba(255, 200, 87, 0.12);
}

/* Locked targets */
.locked-targets {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.target-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  font-size: 10px;
  font-weight: 700;
  color: #191919;
  background: #ffe08a;
  border-radius: 999px;
}

.target-chip b {
  cursor: pointer;
  font-size: 11px;
  line-height: 1;
}

/* Read-only skill cards */
.skill-cards.readonly .card-face {
  cursor: default;
  pointer-events: none;
}

/* Skill card state */
.card-face.skill.active {
  outline: 2px solid rgba(255, 200, 87, 0.9);
  outline-offset: 1px;
  box-shadow: 0 0 16px rgba(255, 200, 87, 0.35);
}

.card-face.skill.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* Decision summary */
.decision-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--text-dim);
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
}

.decision-summary.ready {
  color: #7ce0a3;
}

.clear-decision {
  cursor: pointer;
  color: var(--text-dim);
  font-size: 11px;
  padding: 0 4px;
}

.clear-decision:hover {
  color: var(--danger);
}

/* Valid target */
.unit.aim-target {
  outline: 2px solid rgba(255, 200, 87, 0.9);
  outline-offset: 2px;
  border-radius: 10px;
  cursor: crosshair;
}

/* Target guide */
.aim-line {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 45;
}

.aim-line line {
  stroke: rgba(255, 200, 87, 0.9);
  stroke-width: 2.5;
  stroke-dasharray: 7 5;
}

.tag-decision {
  margin-left: 4px;
  font-size: 9px;
  font-weight: 700;
  padding: 0 4px;
  border-radius: 4px;
  vertical-align: 1px;
  color: var(--warn, #ffc857);
  border: 1px solid rgba(255, 200, 87, 0.5);
  max-width: 92px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-decision.ready {
  color: #7ce0a3;
  border-color: rgba(124, 224, 163, 0.5);
}

.tag-decision.waiting {
  color: var(--warn, #ffc857);
  border-color: rgba(255, 200, 87, 0.5);
}

.skill-cards {
  display: flex;
  flex-direction: row;
  gap: 8px;
}
.skill-cards .card-face {
  width: 138px;
}

/* Hand overlay */
.hand-overlay {
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  display: flex;
  align-items: flex-end;
  z-index: 20;
  padding-bottom: 8px;
}
.hand-overlay .card-face {
  width: 126px;
  margin-left: -20px;
}
.hand-overlay .card-face:first-child {
  margin-left: 0;
}
/* Fanned hand cards: keep the text band on the visible card area. */
.hand-overlay .hand-card {
  transform-origin: bottom center;
  transform: translateY(18%) rotate(var(--hand-rot, 0deg));
  transition: transform 0.22s ease;
}
.hand-overlay:hover .hand-card {
  transform: translateY(0) rotate(var(--hand-rot, 0deg));
}
.hand-overlay .hand-card:hover {
  transform: translateY(-10px) rotate(0deg) scale(1.06);
  z-index: 30 !important;
}
.hand-empty {
  padding: 6px 10px;
  background: rgba(11, 14, 20, 0.6);
  border-radius: 6px;
}

/* Special perk overlay */
.perk-overlay {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  gap: 28px;
  z-index: 25;
}
.perk-overlay .card-face {
  width: 128px;
}
.perk-overlay .card-face:nth-child(2) {
  transform: scale(1.08);
  z-index: 2;
}

/* Selected unit */
.unit.selected {
  outline: 2px solid rgba(255, 200, 87, 0.85);
  outline-offset: 2px;
  border-radius: 10px;
}

/* Action bar */
.action-bar {
  min-height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 16px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
  color: #ffe08a;
  background: rgba(11, 14, 20, 0.55);
  border: 1px solid rgba(255, 200, 87, 0.25);
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.9);
  opacity: 0;
  transition: opacity 0.25s ease;
}
.action-bar.active {
  opacity: 1;
}

/* Speed track */
.speed-track {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 8px;
  background: rgba(11, 14, 20, 0.55);
  border: 1px solid var(--border);
  overflow-x: auto;
}
.speed-node {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 6px;
  background: rgba(76, 194, 255, 0.12);
  border: 1px solid rgba(76, 194, 255, 0.35);
  font-size: 12px;
  white-space: nowrap;
}
.speed-name {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.speed-roll {
  font-weight: 800;
  color: #ffe08a;
}
.speed-arrow {
  color: var(--text-dim);
}

/* Hand and log */

.footer-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hand {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  flex-wrap: wrap;
}

.hand > .dim {
  align-self: center;
}

.card {
  width: 170px;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  cursor: pointer;
  background: var(--bg-panel-2);
  transition: border-color 0.2s;
}

.card:hover {
  border-color: var(--accent);
}

.card-name {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 2px;
}

.card-desc {
  font-size: 11px;
}

.log-panel h4 {
  font-size: 14px;
  margin-bottom: 8px;
}

.log-list {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.log-row {
  display: flex;
  gap: 10px;
  padding: 3px 0;
  border-bottom: 1px solid rgba(35, 44, 61, 0.4);
}

.log-round {
  min-width: 40px;
}

.log-type {
  min-width: 64px;
  color: var(--accent);
  font-size: 12px;
  text-transform: uppercase;
}

.log-type.log-damage {
  color: var(--danger);
}

.log-type.log-heal {
  color: var(--ok);
}

.log-type.log-perf {
  color: var(--warn);
}

.log-type.log-surrender {
  color: var(--danger);
  font-weight: 700;
}

.log-message {
  flex: 1;
}

/* Mobile */
@media (max-width: 768px) {
  .container {
    padding: 12px;
    gap: 12px;
  }

  .head {
    flex-wrap: wrap;
    gap: 8px;
  }
  .head-info {
    flex-wrap: wrap;
    gap: 4px 12px;
  }

  .stage {
    min-height: 300px;
  }
  .stage-scene {
    gap: 2%;
    padding: 12px 8px 14px;
  }
  .side-col {
    gap: 6px;
  }
  .unit {
    width: 84px;
    padding: 6px;
    gap: 4px;
  }
  .portrait-wrap {
    width: 64px;
    height: 84px;
  }
  .portrait-placeholder {
    font-size: 28px;
  }
  .unit .info .name {
    font-size: 12px;
  }
  .hp-bar,
  .energy-bar {
    height: 6px;
  }
  .unit-status {
    font-size: 10px;
    text-align: center;
  }
  .side-player .unit.approaching {
    transform: translateX(var(--anim-dx, 70px));
  }
  .side-enemy .unit.approaching {
    transform: translateX(var(--anim-dx, -70px));
  }
  .side-player .unit.clashing {
    --clash-x: var(--anim-dx, 120px);
    transform: translateX(var(--clash-x));
  }
  .side-enemy .unit.clashing {
    --clash-x: var(--anim-dx, -120px);
    transform: translateX(var(--clash-x));
  }
  .float-num {
    font-size: 13px;
  }

  .skill-panel {
    top: 8px !important;
    right: 8px !important;
    left: 8px !important;
    box-sizing: border-box;
  }
  .skill-cards {
    width: 100%;
    overflow-x: auto;
  }
  .skill-cards .card-face {
    width: calc((100% - 16px) / 3);
  }

  .decision-actions {
    justify-content: stretch;
    flex-wrap: wrap;
  }
  .decision-actions .n-button {
    flex: 1 1 100%;
  }

  .hand .card {
    flex: 1 1 100%;
    min-width: 0;
  }
  .log-row {
    align-items: flex-start;
  }
  .log-message {
    font-size: 12px;
    min-width: 0;
    word-break: break-word;
  }
}
</style>
