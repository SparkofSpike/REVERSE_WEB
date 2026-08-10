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
  skipExtraActions,
  playCard,
  selectSpecialPerk,
  skipSpecialPerk
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

// per-combatant pending decision. Targets are locked by dragging the
// chosen action/skill card onto a unit (multi-target skills accept several).
interface PendingDecision {
  actionType: string
  skillId: string | null
  targetIds: string[]
}
const pending = ref<Record<string, PendingDecision>>({})
// selected-combatant panel tabs: default to the skills page
const panelTab = ref<'actions' | 'skills'>('skills')
// aim-to-lock state: which chosen item is being aimed at a unit
const aimMode = ref<{ combatantId: string; kind: 'action' | 'skill'; id: string } | null>(null)
const aimLine = ref<{ x1: number; y1: number; x2: number; y2: number } | null>(null)

// portrait images: /assets/{templateId}.png, falling back to a placeholder
const portraitFailed = ref<Record<string, boolean>>({})

// ---------------- transition overlays ----------------
// Curtains play inside the stage (natural, non-blocking, latest-wins).
// The last-dash moment uses a separate full-screen channel so it is never
// starved by per-round curtain events.
const curtain = ref<{ kind: 'rise' | 'fall'; seq: number } | null>(null)
let curtainSeq = 0
let curtainTimer = 0
let fallTimer = 0
const dashOverlay = ref<{ seq: number } | null>(null)

// Log consumption: the first load is only a baseline (never replays old
// transitions after a refresh); later responses consume only the tail.
let baselineSet = false
let consumedLogs = 0

// ---------------- performance animation state ----------------
const performing = ref<Record<string, boolean>>({})
const approaching = ref<Record<string, boolean>>({})
// clash: both fighters charge further in and meet mid-field
const clashing = ref<Record<string, boolean>>({})
const shaking = ref<Record<string, boolean>>({})
// precise lunge/clash offsets (px): computed per unit from the live layout
// so a fighter always lands in front of its locked target (attack) or on the
// front-line engagement point (clash), no matter how many units are deployed
const animDx = ref<Record<string, number>>({})
// speed-roll dice: pops out on each combatant's head, holds, then morphs
// into the resolved number (dice emoji placeholder until the artist asset)
interface DiceAnim {
  seq: number
  roll: number
  live: number
  result?: 'win' | 'lose'
}
const diceAnims = ref<Record<string, DiceAnim>>({})
// last-dash performance: participant ids + whether the dash show is playing
const dashIds = ref<string[]>([])
const dashActive = ref(false)
let diceFastTimer = 0
let diceSlowTimer = 0
// top action bar: text of the action currently playing
const actionBarText = ref('')
// selected combatant: click a unit to inspect its skill cards on the right
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
    // the tab panel always opens on the skills page by default
    panelTab.value = 'skills'
  }
}
// skill panel position: anchored inside the stage scene, next to the unit
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
      // plenty of room on the right: panel opens to the right of the unit
      skillPanelPos.value = { left: `${left + w + 12}px`, top: `${Math.max(8, top)}px` }
    } else {
      // unit near the right edge: panel opens to its left
      skillPanelPos.value = { right: `${scene.clientWidth - left + 12}px`, top: `${Math.max(8, top)}px` }
    }
  })
})
// bottom speed track: current-round speed order (fastest first)
const speedOrder = ref<{ id: string; name: string; roll: number }[]>([])
// per-unit speed bar value for the current round
const currentSpeed = ref<Record<string, number>>({})

// min/max possible values of a combatant's speed dice, e.g. "2d6+2" -> [4, 14]
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

// hearthstone-style hand: each card fans toward the middle and sits
// half-hidden until the hand is hovered
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
// the HP bar keeps its old value until the matching damage/heal cue lands,
// so HP does not drop for every unit at once when the response arrives
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

// camera focus: the whole scene (background + units) zooms in, anchored on
// the acting unit's side so it reads as a real dolly-in, not a sprite grow.
// During a clash both sides perform at once, so the camera stays centered
// on the mid-field collision point.
const zoomOrigin = computed(() => {
  if (!battle.value) return '50% 62%'
  const acting = Object.keys(performing.value).filter((id) => performing.value[id])
  if (acting.length === 0) return '50% 62%'
  const sides = new Set(
    acting.map((id) => battle.value!.combatants.find((c) => c.id === id)?.side)
  )
  if (sides.size > 1) return '50% 62%'
  const side = battle.value.combatants.find((c) => c.id === acting[0])?.side
  if (side === 'ENEMY') return '66% 60%'
  if (side === 'PLAYER') return '34% 60%'
  return '50% 62%'
})

// PVP perspective: 'players'/'enemies' mirror the requesting user's side
// (guest controls the ENEMY team); solo battles always view from PLAYER
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
const extraActors = computed(() => alivePlayers.value.filter((c) => c.extraActionsThisTurn > 0))
// main rounds decide for every alive player; extra rounds only for those
// who still hold extra base actions
const decisionActors = computed(() => (inExtraRound.value ? extraActors.value : alivePlayers.value))

// PVP helpers: opponent name, submission gates and the 30s decision window
const isPvp = computed(() => !!battle.value?.guestUsername)
const opponentName = computed(() =>
  isPvp.value
    ? (battle.value?.mySide === 'ENEMY' ? battle.value?.ownerUsername : battle.value?.guestUsername)
    : null
)
const mySubmitted = computed(() => battle.value?.mySubmitted ?? false)
const opponentSubmitted = computed(() => battle.value?.opponentSubmitted ?? false)
/** Extra window held by the opponent (PVP): my team waits. */
const opponentsExtraRound = computed(() =>
  isPvp.value && inExtraRound.value && battle.value?.extraRoundSide
    ? battle.value.extraRoundSide !== mySide.value
    : false
)
/** Decision window locked because I already acted and await the opponent. */
const awaitingOpponent = computed(() =>
  isPvp.value && inDecision.value && !isFinished.value
    ? (inExtraRound.value ? opponentsExtraRound.value : mySubmitted.value)
    : false
)
/** Countdown seconds for the current PVP decision window (0 when idle). */
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
    if (remaining === 0 && !battle.value?.mySubmitted && inDecision.value) {
      // window expired client-side: hand over to the backend sweeper which
      // auto-submits; a refresh reveals the resolved round
      window.clearInterval(countdownTimer)
    }
  }
  tick()
  countdownTimer = window.setInterval(tick, 1000)
}

// PVP SSE refresh channel: the server pings on every state change, then we
// pull the real (authenticated) state. EventSource cannot carry headers, so
// the endpoint only carries the ping - no data, no auth needed.
let eventSource: EventSource | null = null

function connectSse(battleId: string) {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (!isPvp.value) {
    return
  }
  const source = new EventSource(battleEventsUrl(battleId))
  source.addEventListener('refresh', () => {
    void load()
  })
  source.onerror = () => {
    // browser retries automatically; nothing to do
  }
  eventSource = source
}

onMounted(() => {
  window.addEventListener('mousemove', onAimMove)
  window.addEventListener('keydown', onKeydown)
  // fire-and-forget warm-up: never blocks the battle screen
  preloadAssets()
  load()
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

// warm the browser cache in the background; never blocks the battle screen
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
    // hod pose sheet (idle/attack/defend/skills/clash/hit)
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

// keep per-combatant pending decisions in sync with alive players.
// summons (e.g. puppet minions) can appear mid-battle; rendering accesses
// pending[c.id].actionType and would crash on a missing entry.
watch(
  () => alivePlayers.value.map((p) => p.id).join(','),
  (ids) => {
    for (const id of ids.split(',')) {
      if (id && !pending.value[id]) {
        pending.value[id] = { actionType: 'ATTACK', skillId: null, targetIds: [] }
      }
    }
  },
  { immediate: true }
)

// consume new battle log entries: curtains, last-dash and performance cues.
// Explicit log consumption: every API call site invokes processLogs with
// the fresh response, so event handling is deterministic and never depends
// on Vue watch timing (watch-based consumption could stall or pile up).
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
      // wait for the rise curtain (and the queue gate) to finish before
      // showing the overlay, otherwise the dash flash overlaps the curtain
      const delay = Math.max(0, curtainGateUntil - Date.now())
      window.setTimeout(() => triggerDash(), delay)
    }
    consumePerformanceEvent(ev)
    consumedLogs = i + 1
  }
}

// Curtain cooldown flow: one curtain at a time (~1.9s). Round order is
// performance cues -> (pause) -> curtain fall -> curtain rise, so actions
// are never hidden behind a falling curtain and the rise never cuts the fall.
function playCurtainNow(kind: 'rise' | 'fall', onDone?: () => void) {
  window.clearTimeout(curtainTimer)
  curtain.value = { kind, seq: ++curtainSeq }
  curtainTimer = window.setTimeout(() => {
    curtain.value = null
    onDone?.()
  }, 1900)
}

// The curtain window (fall delay + fall + rise) is locked as ONE unit.
// round_end and round_start arrive adjacent in the same response, so a
// shared flag prevents double-counting the lock and the new chain is the
// single unlock point - otherwise the panel would stay locked forever.
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
  // round_end merely marks the end of the settlement; the curtains are
  // driven by the decision-round boundaries (rise on submit, fall when a
  // new decision round begins). The rise curtain must NEVER be cancelled
  // here: its completion callback releases the animation lock, and losing
  // it would let the panel unlock early and submissions overlap the
  // still-playing animations (queue residue -> duplicate cues).
  window.clearTimeout(fallTimer)
}

function handleRoundStart() {
  // a new decision round begins: drop the curtain so the player can issue
  // orders again
  lockCurtainWindow()
  window.clearTimeout(fallTimer)
  playCurtainNow('fall', () => {
    // decision-round sync: any HP change without a cue settles now
    for (const c of battle.value?.combatants ?? []) {
      displayHp.value[c.id] = c.hp
    }
    unlockCurtainWindow()
  })
}

let dashCooldownUntil = 0

function triggerDash() {
  const now = Date.now()
  // cooldown: consecutive last-dash moments never stack on each other
  if (now < dashCooldownUntil) return
  dashCooldownUntil = now + 2600
  dashOverlay.value = { seq: (dashOverlay.value?.seq ?? 0) + 1 }
  animStart()
  window.setTimeout(() => {
    dashOverlay.value = null
    animEnd()
  }, 2100)
}

// ---------- performance cues from structured event data ----------
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

// log type -> extra class. The raw type string is NEVER used as a class:
// it collides with page classes (e.g. "card", "round") and pollutes the row.
const LOG_TYPE_CLASS: Record<string, string> = {
  damage: 'log-damage',
  heal: 'log-heal',
  performance: 'log-perf'
}

function pushFloat(
  unitId: string,
  text: string,
  kind: 'damage' | 'heal' | 'action',
  stackStep: number,
  ttl: number
) {
  const id = ++floatSeq
  // later floats push earlier ones upward, like stacked labels in other games
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
  // short flash over the lunge-out phase; it must be gone before the unit
  // runs back, otherwise the label reads as a duplicate cue
  pushFloat(unitId, text, 'action', 34, 580)
}

function consumePerformanceEvent(ev: CombatEvent) {
  // no buffering: every event goes straight into the serial queue, which
  // is gated by the curtain window (curtainGateUntil) - buffering here was
  // the source of rounds playing without animation when a curtain callback
  // was overridden, and of multi-round event pile-ups
  applyPerformance(ev)
}

// Global serial action queue: every action (attack, chase, clash, counter,
// skill, card, heal) plays to completion - label, lunge, hit, damage
// settlement - before the next one starts, regardless of actor. Damage
// events settle right after the action they belong to; a damage with no
// action cue ahead of it for the same actor gets an implicit lunge of its
// own so the attacker visibly moves before the hit lands.
interface QueuedStep {
  kind: 'action' | 'clash' | 'settle' | 'heal' | 'speed' | 'dash'
  ev: CombatEvent
}

const ACTION_STEP = 1050 // label + lunge + pulse complete
const SETTLE_STEP = 620 // shake + damage number + hp sync complete
const HEAL_STEP = 680 // label + heal number + hp sync complete
// clash: both fighters charge toward each other, collide at the midpoint
// (impact flash + stagger), then run back to their own spot
const CLASH_IMPACT = 620 // charge-in duration; the collision moment
const CLASH_HOLD = 900 // stay engaged before running back
const CLASH_STEP = 1400 // whole clash cue (charge + impact + return)
const animQueue: QueuedStep[] = []
let pumpRunning = false
// while the fall curtain plays (right after submitting), queued steps wait
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
  // action bar mirrors the human-readable message of the running cue
  actionBarText.value = step.ev.message ?? ''
  if (step.kind === 'clash') {
    // mutual attack: BOTH fighters charge into each other, collide at the
    // midpoint (impact burst + stagger), then run back to their own spot
    const actorId = d.actorId as string | undefined
    const targetId = d.targetId as string | undefined
    if (!actorId || !targetId) return
    pulseActor(actorId)
    pulseActor(targetId)
    for (const id of [actorId, targetId]) {
      const c = battle.value?.combatants.find((x) => x.id === id)
      if (c && hasPoses(c)) setPose(id, 'clash', CLASH_STEP + 250)
    }
    // "Clash!" label on both fighters' heads, styled like the Attack!/Defend!
    // action labels (longer ttl so it is still visible at the impact moment)
    pushFloat(actorId, 'Clash!', 'action', 34, 900)
    pushFloat(targetId, 'Clash!', 'action', 34, 900)
    // engagement point between the player's front line and the locked enemy
    const clashX = clashPointX(actorId, targetId)
    clashApproach(actorId, clashX)
    clashApproach(targetId, clashX)
    await sleep(CLASH_IMPACT)
    // impact shake: the clash keyframes keep the fighters ON the collision
    // spot (via --clash-x), so the shake reads as a real hit, not a jump
    // back home
    shakeTarget(actorId)
    shakeTarget(targetId)
    await sleep(CLASH_STEP - CLASH_IMPACT)
    return
  }
  if (step.kind === 'dash') {
    // last-dash performance: after the overlay fades, every combatant's
    // speed number scrambles fast on its head, then the tied pair slows
    // down; the following speed step locks the final values (winner green,
    // loser red). The plain speed-dice animation is untouched.
    const ids = (d.ids as string[] | undefined) ?? []
    dashIds.value = ids
    dashActive.value = true
    // the overlay starts when the queue gate opens (same moment as this
    // step) and runs 2.1s - wait it out before the scramble
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
    // the tied pair slows down
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
      // speed track: fastest first
      speedOrder.value = Object.entries(speeds)
        .map(([id, roll]) => ({
          id,
          roll,
          name: battle.value?.combatants.find((c) => c.id === id)?.name ?? id
        }))
        .sort((a, b) => b.roll - a.roll)
      if (dashIds.value.length > 0) {
        // dash round: lock the scrambled numbers onto the final rolls;
        // the tied pair shows winner green / loser red
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
      // normal round: plain dice pop animation (unchanged)
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
    // pose by action: skills split into skill1/skill2 art by skill index
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
    // melee fighters lunge at their target on every offensive action
    // (plain attacks, chase, skills, clash, counter); magic casters
    // strike from their spot
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
    // pose: big single hits (>= 15% max HP) use the max-damage art
    if (t) {
      const target = battle.value?.combatants.find((x) => x.id === t)
      if (target && hasPoses(target) && amount > 0) {
        setPose(t, amount >= target.maxHp * 0.15 ? 'hit_max' : 'hit', SETTLE_STEP + 250)
      }
    }
    // HP bar settles together with the damage cue, not all at once
    if (t) {
      const real = battle.value?.combatants.find((c) => c.id === t)?.hp
      if (real !== undefined) displayHp.value[t] = real
    }
    await sleep(SETTLE_STEP)
    return
  }
  // heal
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

// While any animation is running (action cues, curtains, last dash) the
// decision panel stays locked, so a new submission can never interleave
// with a still-playing animation and scramble the order.
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
    // the overlay (triggerDash) is the one-shot cue; this step plays the
    // duel process animation
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
  // action / skill / clash / chase / counter / card: serialized globally
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
  // lunge exactly to the locked target's front (plus a small gap), so the
  // attacker visibly reaches the unit it is hitting - no matter how many
  // units are deployed on either side
  const dx = targetId ? dxToFront(id, targetId) : null
  if (dx !== null) animDx.value[id] = dx
  approaching.value[id] = true
  window.setTimeout(() => {
    approaching.value[id] = false
  }, 860)
}

function clashApproach(id: string, clashX: number | null) {
  // deeper charge: both fighters meet at the engagement point (between the
  // player's frontmost unit and the locked enemy) and collide face to face
  if (clashX !== null) {
    const dx = dxToClash(id, clashX)
    if (dx !== null) animDx.value[id] = dx
  }
  clashing.value[id] = true
  window.setTimeout(() => {
    clashing.value[id] = false
  }, CLASH_HOLD)
}

// ---- layout math ---------------------------------------------------------
// The unit's offsetParent is the .stage-scene (position: absolute), so
// offsetLeft/offsetWidth stay in un-zoomed layout coordinates - the scene's
// camera dolly (transform: scale) never skews the computed travel distance.

function unitEl(id: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(`.unit[data-unit-id="${id}"]`)
}

// engagement point for a clash: the midpoint between the player's frontmost
// living unit (closest to the enemy line) and the locked enemy. With a wide
// player formation the fight happens at the front line, not deep inside the
// player's own ranks (the stage center would land in the player's formation).
function clashPointX(actorId: string, targetId: string): number | null {
  const all = battle.value?.combatants ?? []
  // the enemy participant in this clash (the locked enemy)
  const enemyId =
    all.find((c) => c.id === targetId)?.side === 'ENEMY' ? targetId : actorId
  const enemyEl = unitEl(enemyId)
  if (!enemyEl) return null
  // the player's frontmost living unit: the one closest to the enemy line
  let frontEl: HTMLElement | null = null
  for (const c of all) {
    if (c.side !== 'PLAYER' || c.dead) continue
    const el = unitEl(c.id)
    if (!el) continue
    if (!frontEl || el.offsetLeft > frontEl.offsetLeft) frontEl = el
  }
  if (!frontEl) return null
  const fx = frontEl.offsetLeft + frontEl.offsetWidth / 2
  const ex = enemyEl.offsetLeft + enemyEl.offsetWidth / 2
  return (fx + ex) / 2
}

// horizontal offset (px) that stops the fighter just short of the engagement
// point: the left-side fighter ends with its right edge at clashX - GAP/2,
// the right-side one with its left edge at clashX + GAP/2 - so the two
// collide face to face with a small gap instead of overlapping
function dxToClash(id: string, clashX: number): number | null {
  const el = unitEl(id)
  if (!el) return null
  const GAP = 12
  const center = el.offsetLeft + el.offsetWidth / 2
  if (center < clashX) {
    // coming from the left: right edge lands just left of the point
    return clashX - GAP / 2 - el.offsetWidth - el.offsetLeft
  }
  // coming from the right: left edge lands just right of the point
  return clashX + GAP / 2 - el.offsetLeft
}

// horizontal offset (px) that places the unit right in front of the target,
// leaving a small gap; direction is picked from the actual layout
function dxToFront(id: string, targetId: string): number | null {
  const el = unitEl(id)
  const targetEl = unitEl(targetId)
  if (!el || !targetEl) return null
  const GAP = 8
  const elRight = el.offsetLeft + el.offsetWidth
  const targetRight = targetEl.offsetLeft + targetEl.offsetWidth
  if (targetEl.offsetLeft >= elRight) {
    // target is to the right: stop just left of it
    return targetEl.offsetLeft - elRight - GAP
  }
  if (el.offsetLeft >= targetRight) {
    // target is to the left: stop just right of it
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
  // numbers appear a beat later (after the action label) and stack upward
  window.setTimeout(() => {
    pushFloat(targetId, text, kind, 26, 1300)
  }, 340)
}

function floatsFor(unitId: string): FloatNum[] {
  return floats.value.filter((f) => f.targetId === unitId)
}

// ---------- pose sheet: characters with per-event art ----------
// Characters in POSE_TEMPLATES switch their portrait by battle event:
// idle (default), attack, defend, skill1/skill2 (by skill index), clash,
// hit (light damage) and hit_max (single hit >= 15% of max HP).
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

// portrait url with pose art; a missing pose file falls back to the base
// portrait, and a missing base portrait falls back to the placeholder
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
  // pose art failed: snap units back to their idle pose so the base
  // portrait shows instead of a broken image
  const m = /^\/assets\/([^_]+)_([a-z0-9]+)\.webp$/.exec(path)
  if (m) {
    for (const [id, pose] of Object.entries(poses.value)) {
      if (pose === m[2]) {
        poses.value[id] = 'idle'
      }
    }
  }
}

async function load() {
  loading.value = true
  try {
    const battleId = route.params.battleId as string
    battle.value = await getBattle(battleId)
    processLogs(battle.value.logs)
    for (const c of alivePlayers.value) {
      if (!pending.value[c.id]) {
        pending.value[c.id] = { actionType: 'ATTACK', skillId: null, targetIds: [] }
      }
    }
    connectSse(battle.value.id)
    startCountdown()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
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

// max targets a skill can lock: the largest count among its effects (1 default)
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
  // note: exact selectors only - "enemies" does NOT contain the substring
  // "enemy" (it is e-n-e-m-i-e-s), so includes() would silently fail
  if (s.targetType === 'enemy' || s.targetType === 'enemies') {
    return t.side === 'ENEMY' && !t.dead
  }
  if (s.targetType === 'ally' || s.targetType === 'allies' || s.targetType === 'random_ally') {
    return t.side === 'PLAYER' && !t.dead
  }
  return false
}

// click an action in the selected-combatant panel. Target-requiring
// actions (attack/guard) enter aim mode; re-clicking the picked action
// cancels the choice.
function pickAction(c: CombatantView, action: string, ev: MouseEvent) {
  if (c.side !== 'PLAYER' || animating.value) return
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
    // close the tab panel so the stage is fully visible, and draw the
    // arrow right away (the pointer may not move after the click)
    selectedId.value = null
    updateAimLine(ev.clientX, ev.clientY)
  } else {
    cancelAim()
    selectedId.value = null
  }
}

// click a skill card: self-targeted skills lock immediately; target
// skills enter aim mode (click a unit to lock). Re-clicking the picked
// card cancels the choice.
function pickSkill(c: CombatantView, s: SkillView, ev: MouseEvent) {
  if (c.side !== 'PLAYER' || animating.value) return
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
  // close the tab panel so the stage is fully visible; target skills draw
  // the arrow immediately from the click position
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

// ---- decision status helpers (used by the panel and the decision bar) ----
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

// compact per-unit decision tag shown on the battlefield (the tab panel
// closes after picking, so the unit must carry the locked-target feedback)
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

// ---- aim-to-lock: click a target-requiring card, then click a unit ----
// A dashed line follows the pointer from the character; clicking a valid
// unit locks it (multi-target skills accept one click per target until the
// cap is reached). ESC, clicking an invalid unit, or clicking the picked
// card again cancels aiming without dropping the choice.
function onAimMove(ev: MouseEvent) {
  if (!aimMode.value) {
    aimLine.value = null
    return
  }
  updateAimLine(ev.clientX, ev.clientY)
}

// draws the guide line from the character's unit toward the given pointer
// position (stage-local coordinates)
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

// empty-scene clicks cancel aiming; clicks on units/panels are handled by
// their own handlers (and stop propagation), so aim mode survives picking
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
  if (actionNeedsTarget(p.id)) return t.side === 'ENEMY' && !t.dead
  if (actionNeedsAllyTarget(p.id)) return t.side === 'PLAYER' && !t.dead && t.id !== c.id
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
    // multi-target skills keep aiming until the cap is reached
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
    // clicking an invalid unit cancels aiming but keeps the choice
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

// entering the extra-action round resets every extra actor's selection:
// a stale skill choice (e.g. the just-used 连续奔袭, now on cooldown) must
// never be re-submitted and burn a charge doing nothing
watch(inExtraRound, (on) => {
  if (!on) return
  for (const c of extraActors.value) {
    pending.value[c.id] = { actionType: 'ATTACK', skillId: null, targetIds: [] }
  }
})



async function submitDecisions() {
  const decisions: ActionDecision[] = []
  for (const c of decisionActors.value) {
    const p = pending.value[c.id]
    if (!p) continue
    if (p.actionType === 'SKILL') {
      if (!p.skillId) {
        message.warning(`${c.name} 未选择技能`)
        return
      }
      const s = c.skills.find((x) => x.id === p.skillId)
      if (s && skillNeedsTarget(s) && p.targetIds.length === 0) {
        message.warning(`${c.name} 的技能 ${s.name} 未锁定目标`)
        return
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
        message.warning(`${c.name} 未选择攻击目标`)
        return
      }
      if (actionNeedsAllyTarget(p.actionType) && p.targetIds.length === 0) {
        message.warning(`${c.name} 未选择守护目标`)
        return
      }
      decisions.push({
        combatantId: c.id,
        actionType: p.actionType,
        targetId: p.targetIds[0] ?? null,
        targetIds: [...p.targetIds]
      })
    }
  }
  if (decisions.length !== decisionActors.value.length) {
    message.warning(inExtraRound.value ? '请为拥有额外行动的角色下达指令' : '请为所有存活角色下达指令')
    return
  }
  submitting.value = true
  try {
    // decision round over: raise the curtain and gate the settlement
    // animations behind it. Arm the gate BEFORE the request so the
    // response events are already gated when the watch consumes them, and
    // await nextTick() so the round_start fall-curtain handler (if any)
    // runs first and the rise wins (the submit is the decision-round
    // boundary, not round_start)
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
    // release the gate/lock armed before the request on failure
    curtainGateUntil = 0
    unlockCurtainWindow()
  } finally {
    submitting.value = false
  }
}

async function skipExtra() {
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
      <!-- battle header -->
      <div class="head">
        <n-button quaternary @click="router.push({ name: 'home' })">离开</n-button>
        <div class="head-info">
          <span class="round">第 {{ battle.round }} 回合</span>
          <span v-if="isPvp" class="pvp-tag" :class="battle.mySide === 'PLAYER' ? 'host' : 'guest'">
            {{ battle.mySide === 'PLAYER' ? '先手方' : '后手方' }} · VS {{ opponentName }}
          </span>
          <span v-else class="dim">先手：{{ battle.firstStrikeSide === 0 ? '玩家' : '木桩' }}</span>
          <span class="dim">抽牌能量 {{ battle.playerDrawEnergy }}/10</span>
        </div>
        <n-button size="small" @click="load">刷新</n-button>
      </div>

      <!-- top speed track: current-round speed order -->
      <div class="speed-track" v-if="speedOrder.length">
        <template v-for="(sp, i) in speedOrder" :key="sp.id">
          <span v-if="i > 0" class="speed-arrow">›</span>
          <div class="speed-node">
            <span class="speed-name">{{ sp.name }}</span>
            <span class="speed-roll">{{ sp.roll }}</span>
          </div>
        </template>
      </div>

      <!-- victory banner (PVP: relative to the viewer's side) -->
      <div
        v-if="isFinished"
        class="panel result-banner"
        :class="battle.winner === (battle.mySide ?? 'PLAYER') ? 'win' : 'lose'"
      >
        <h2>{{ battle.winner === (battle.mySide ?? 'PLAYER') ? '战斗胜利' : '战斗败北' }}</h2>
        <n-button type="primary" @click="router.push({ name: 'records' })">查看战报</n-button>
      </div>

      <!-- initial perk (PVP: each side picks its own, then waits) -->
      <section v-if="inInitialPerk" class="panel perk-panel">
        <h3 v-if="isPvp && battle.mySubmitted">等待对方选择初始词条…</h3>
        <h3 v-else>选择初始词条</h3>
        <div class="perk-grid" :class="{ disabled: isPvp && battle.mySubmitted }">
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
      </section>

      <!-- top action bar: text of the action currently playing -->
      <div class="action-bar" :class="{ active: !!actionBarText }">{{ actionBarText }}</div>

      <!-- battle stage: face-to-face showdown in the middle of the field -->
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
            <!-- speed-roll dice: pops out, holds, morphs into the number -->
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
                  v-if="inDecision && !c.dead && c.side === 'PLAYER'"
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
            <!-- speed-roll dice: pops out, holds, morphs into the number -->
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
                  v-if="inDecision && !c.dead && c.side === 'PLAYER'"
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

        <!-- aim line: dashed guide from the character to the pointer -->
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

        <!-- natural curtain overlay inside the stage -->
        <div v-if="curtain" :key="curtain.seq" class="curtain" :class="curtain.kind">
          <img :src="curtain.kind === 'rise' ? '/assets/curtain_rise.webp' : '/assets/curtain_fall.webp'" alt="" />
        </div>

        <!-- last-dash moment: natural reveal inside the stage -->
        <div v-if="dashOverlay" :key="dashOverlay.seq" class="dash-moment">
          <img src="/assets/last_dash.webp" alt="决速时刻" />
        </div>

        <!-- generic skill hand: overlaid at the bottom of the battlefield -->
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

        <!-- special perk offers: centered on the battlefield (middle card out).
             PVP: each side picks its own offer, then waits for the opponent. -->
        <div v-if="inSpecialPerk" class="perk-overlay">
          <template v-if="isPvp && battle.mySubmitted">
            <p class="perk-wait">等待对方选择特殊词条…</p>
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

        <!-- selected-combatant tab panel: actions + skills, anchored next
             to the unit. Default tab is skills; clicking a card issues the
             decision, target-requiring ones are locked by dragging the card
             onto a unit (multi-target skills accept one drag per target). -->
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
          <!-- order tabs only exist for player combatants; the enemy panel
               is read-only (skill info only, no order controls) -->
          <div v-if="selectedCombatant.side === 'PLAYER'" class="panel-tabs">
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

          <!-- actions tab: base actions; attack/guard drag onto a unit -->
          <div v-if="panelTab === 'actions'" class="action-list">
            <button
              v-for="a in selectedCombatant.baseActions"
              :key="a"
              class="action-chip"
              :class="{ active: pending[selectedCombatant.id]?.actionType === a }"
              :disabled="selectedCombatant.side !== 'PLAYER' || animating"
              @click="pickAction(selectedCombatant, a, $event)"
            >
              {{ actionLabel(a) }}
            </button>
          </div>

          <!-- skills tab (default): click to use, drag onto units to lock -->
          <div v-else class="skill-cards" :class="{ readonly: selectedCombatant.side !== 'PLAYER' }">
            <div
              v-for="sk in selectedCombatant.skills"
              :key="sk.id"
              class="card-face skill"
              :class="{
                upgraded: sk.upgraded,
                active: selectedCombatant.side === 'PLAYER' && skillActive(selectedCombatant, sk),
                disabled:
                  selectedCombatant.side === 'PLAYER' &&
                  ((selectedCombatant.cooldowns[sk.id] ?? 0) > 0 || animating)
              }"
              @click="selectedCombatant.side === 'PLAYER' && pickSkill(selectedCombatant, sk, $event)"
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

          <!-- current decision summary (player only) -->
          <div
            v-if="selectedCombatant.side === 'PLAYER'"
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

      <!-- decision panel: per-character status + submit. Orders are issued
           from the tab panel (click a unit), so this bar only shows the
           pending state and the submit button. PVP locks the panel while
           awaiting the opponent and shows the 30s countdown on the button. -->
      <div
        v-if="inDecision"
        class="panel decision-panel"
        :class="{ locked: animating || awaitingOpponent }"
      >
        <div v-if="inExtraRound" class="extra-round-hint">
          ⚡ 额外行动轮：{{ extraActors.map((a) => `${a.name}（剩余 ${a.extraActionsThisTurn}）`).join('、') }}
        </div>
        <div v-if="awaitingOpponent" class="waiting-hint">
          <span v-if="opponentsExtraRound" class="dim">等待 {{ opponentName }} 完成额外行动…</span>
          <span v-else class="dim">已提交，等待 {{ opponentName }}…</span>
          <span v-if="countdown > 0" class="countdown">⏱ {{ countdown }}s</span>
        </div>
        <div v-else class="decision-actions">
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
          <n-button type="primary" :loading="submitting" :disabled="animating" @click="submitDecisions">
            {{ inExtraRound ? '执行额外行动' : '提交指令' }}
            <template v-if="isPvp && countdown > 0">（{{ countdown }}s）</template>
          </n-button>
        </div>
      </div>



      <!-- battle log -->
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

/* ---------- battle stage: face-to-face showdown ---------- */

.stage {
  position: relative;
  /* background art mostly fits: the stage is shorter than the image and
     the background aligns to the BOTTOM, so the top of the art is cropped
     a bit while the bottom (where units and the hand sit) stays intact */
  aspect-ratio: 1776 / 1100;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  transition: filter 0.4s ease;
}

/* the scene is the camera subject: background + units zoom together */
.stage-scene {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6%;
  /* tall bottom padding reserves the hand-card zone inside the field */
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
  /* focus is expressed by darkening only - every unit stays at its size so
     the camera dolly reads as a real zoom on the whole scene */
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
  /* no card box around the character: transparent, the battlefield shows
     through (per design: units stand directly on the field) */
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
  /* no solo scale here: the whole scene zooms in (camera dolly), so all
     units grow together - scaling only one unit reads as sprite growth */
  box-shadow: 0 0 28px rgba(76, 194, 255, 0.45);
  z-index: 3;
}

/* lunge: the exact travel distance is computed per unit from the live
   layout (unitDxStyle -> --anim-dx), so the fighter always lands in front
   of its locked target; the fixed values are only a fallback */
.side-player .unit.approaching {
  transform: translateX(var(--anim-dx, 100px));
}

.side-enemy .unit.approaching {
  transform: translateX(var(--anim-dx, -100px));
}

/* clash: both fighters charge to the dead center of the stage and overlap
   at the collision point; the distance is computed per unit (--anim-dx),
   so they never fall short or cross through each other */
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

/* clash impact shake: keeps each fighter on its collision spot instead of
   resetting the transform (unit-shake would snap them back home) */
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
  /* no backdrop: the character art stands directly on the battlefield */
  background: transparent;
  transition: transform 0.45s ease;
}

.portrait {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* enemies face the players */
.side-enemy .portrait,
.side-enemy .portrait-placeholder {
  transform: scaleX(-1);
}

/* the hod art sheet faces left: flip it so she looks at the enemy */
.side-player .portrait[src*="hod_"] {
  transform: scaleX(-1);
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

/* ---------- speed-roll dice: pops out, holds, morphs into the number ---------- */
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

/* dash performance: number visible from the start, dice face hidden */
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

/* ---------- curtains (natural, inside the stage) ---------- */

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

/* rise: sweep up from the bottom like a curtain opening */
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

/* fall: drop down from the top like a curtain closing */
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

/* ---------- last dash overlay (full screen, high priority) ---------- */

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

/* burst: expand outward from the center while fading out fast */
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

/* ---------- decision panel ---------- */

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

/* ---------- PVP: opponent tag, waiting states, countdown ---------- */
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

/* ---------- card faces: artist art + bottom text overlay ---------- */
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
  left: 6px;
  right: 6px;
  bottom: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 7px;
  border-radius: 6px;
  color: #fff;
  background: linear-gradient(180deg, transparent, rgba(0, 0, 0, 0.85) 30%);
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
  -webkit-line-clamp: 2;
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

/* hand: hearthstone-style overlap */
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

/* special perk offers: middle card stands out */
.perk-offers .card-face {
  width: 128px;
}
.perk-offers .card-face:nth-child(2) {
  transform: scale(1.08);
  z-index: 2;
}

/* initial perk cards */
.perk-wide {
  width: 150px;
}

/* selected-combatant skill panel */
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

/* locked targets chips inside the selected skill card */
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

/* enemy panel cards are read-only info: no hover lift, no clicks */
.skill-cards.readonly .card-face {
  cursor: default;
  pointer-events: none;
}

/* selected card highlight + cooldown look */
.card-face.skill.active {
  outline: 2px solid rgba(255, 200, 87, 0.9);
  outline-offset: 1px;
  box-shadow: 0 0 16px rgba(255, 200, 87, 0.35);
}

.card-face.skill.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* current decision summary row at the bottom of the panel */
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

/* unit aim highlight: valid target while locking */
.unit.aim-target {
  outline: 2px solid rgba(255, 200, 87, 0.9);
  outline-offset: 2px;
  border-radius: 10px;
  cursor: crosshair;
}

/* aim guide line: dashed, follows the pointer */
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

/* hand overlay: inside the battlefield, bottom center */
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
/* hearthstone fan: cards rotate toward the middle, half hidden by default,
   the whole hand rises when hovered (bottom overflow is cropped by the
   stage's overflow:hidden, which reads as the cards sliding out of view) */
.hand-overlay .hand-card {
  transform-origin: bottom center;
  transform: translateY(50%) rotate(var(--hand-rot, 0deg));
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

/* special perk offers: centered on the battlefield, middle card stands out.
   No backdrop panel - the cards float directly on the field with room to
   breathe between them. */
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

/* selected unit highlight */
.unit.selected {
  outline: 2px solid rgba(255, 200, 87, 0.85);
  outline-offset: 2px;
  border-radius: 10px;
}

/* ---------- top action bar: text of the running cue ---------- */
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

/* ---------- top speed track: current-round speed order ---------- */
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

/* ---------- hand / log ---------- */

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

.log-message {
  flex: 1;
}

/* ---------- mobile: compact battle stage and stacked decision panel ---------- */
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
