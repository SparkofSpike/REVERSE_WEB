<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NSelect, useMessage } from 'naive-ui'
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
import type { CombatView, CombatantView, ActionDecision, SkillView, CombatEvent } from '@/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const battle = ref<CombatView | null>(null)
const loading = ref(true)
const submitting = ref(false)

// per-combatant pending decision
interface PendingDecision {
  actionType: string
  skillId: string | null
  targetId: string | null
}
const pending = ref<Record<string, PendingDecision>>({})
const targetDummy = ref('dummy')

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
let riseTimer = 0
// Performance gate: while the curtain chain (fall -> rise) is playing,
// incoming performance cues are buffered and released after the rise ends,
// so action labels never pop on top of a curtain.
let perfGate = false
const perfBuffer: CombatEvent[] = []
const dashOverlay = ref<{ seq: number } | null>(null)

// Log consumption: the first load is only a baseline (never replays old
// transitions after a refresh); later responses consume only the tail.
let baselineSet = false
let consumedLogs = 0

// ---------------- performance animation state ----------------
const performing = ref<Record<string, boolean>>({})
const approaching = ref<Record<string, boolean>>({})
const shaking = ref<Record<string, boolean>>({})
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
// the acting unit's side so it reads as a real dolly-in, not a sprite grow
const zoomOrigin = computed(() => {
  if (!battle.value) return '50% 65%'
  const acting = Object.keys(performing.value).find((id) => performing.value[id])
  const side = battle.value.combatants.find((c) => c.id === acting)?.side
  if (side === 'ENEMY') return '66% 62%'
  if (side === 'PLAYER') return '34% 62%'
  return '50% 65%'
})

const players = computed(() =>
  (battle.value?.combatants ?? []).filter((c) => c.side === 'PLAYER')
)
const enemies = computed(() =>
  (battle.value?.combatants ?? []).filter((c) => c.side === 'ENEMY')
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

onMounted(() => {
  // fire-and-forget warm-up: never blocks the battle screen
  preloadAssets()
  load()
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
    '/assets/mage.webp'
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
        pending.value[id] = { actionType: 'ATTACK', skillId: null, targetId: targetDummy.value }
      }
    }
  },
  { immediate: true }
)

// consume new battle log entries: curtains, last-dash and performance cues.
watch(
  () => battle.value?.logs,
  (logs) => {
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
        triggerDash()
      }
      consumePerformanceEvent(ev)
      consumedLogs = i + 1
    }
  },
  { deep: true }
)

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
  // new decision round begins), so nothing further happens here
  window.clearTimeout(fallTimer)
  window.clearTimeout(riseTimer)
}

function handleRoundStart() {
  // a new decision round begins: drop the curtain so the player can issue
  // orders again
  lockCurtainWindow()
  window.clearTimeout(fallTimer)
  perfGate = true
  playCurtainNow('fall', () => {
    perfGate = false
    flushPerf()
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
  if (perfGate) {
    perfBuffer.push(ev)
    return
  }
  applyPerformance(ev)
}

function flushPerf() {
  if (perfBuffer.length === 0) return
  const batch = perfBuffer.splice(0, perfBuffer.length)
  for (const ev of batch) applyPerformance(ev)
}

// Global serial action queue: every action (attack, chase, clash, counter,
// skill, card, heal) plays to completion - label, lunge, hit, damage
// settlement - before the next one starts, regardless of actor. Damage
// events settle right after the action they belong to; a damage with no
// action cue ahead of it for the same actor gets an implicit lunge of its
// own so the attacker visibly moves before the hit lands.
interface QueuedStep {
  kind: 'action' | 'settle' | 'heal'
  ev: CombatEvent
}

const ACTION_STEP = 1050 // label + lunge + pulse complete
const SETTLE_STEP = 620 // shake + damage number + hp sync complete
const HEAL_STEP = 680 // label + heal number + hp sync complete
const animQueue: QueuedStep[] = []
let pumpRunning = false
// while the fall curtain plays (right after submitting), queued steps wait
let curtainGateUntil = 0

function sleep(ms: number): Promise<void> {
  return new Promise((r) => window.setTimeout(r, ms))
}

function enqueueStep(step: QueuedStep) {
  if (step.kind === 'settle') {
    const d = (step.ev.data ?? {}) as Record<string, unknown>
    const actorId = d.actorId as string | undefined
    const action = d.action as string | undefined
    const last = animQueue[animQueue.length - 1]
    const lastActor = last?.ev.data
      ? (last.ev.data as Record<string, unknown>).actorId
      : undefined
    const attackLike = action === 'ATTACK' || action === 'CHASE' || action === 'COUNTER'
    if (actorId && attackLike && !(last?.kind === 'action' && lastActor === actorId)) {
      animQueue.push({ kind: 'action', ev: step.ev })
    }
  }
  animQueue.push(step)
  void pumpQueue()
}

async function pumpQueue() {
  if (pumpRunning) return
  pumpRunning = true
  animStart()
  try {
    while (animQueue.length > 0) {
      while (Date.now() < curtainGateUntil) {
        await sleep(100)
      }
      const step = animQueue.shift()!
      await playStep(step)
    }
  } finally {
    animEnd()
    pumpRunning = false
  }
}

async function playStep(step: QueuedStep) {
  const d = (step.ev.data ?? {}) as Record<string, unknown>
  if (step.kind === 'action') {
    const actorId = d.actorId as string | undefined
    const action = d.action as string | undefined
    const targetId = d.targetId as string | undefined
    if (!actorId) return
    if (action) addActionLabel(actorId, ACTION_LABELS[action] ?? action)
    pulseActor(actorId)
    // melee fighters lunge at their target on every offensive action
    // (plain attacks, chase, skills, clash, counter); magic casters
    // strike from their spot
    const melee =
      battle.value?.combatants.find((c) => c.id === actorId)?.baseDamageType === 'PHYSICAL'
    if (melee && targetId && targetId !== actorId) {
      approachTarget(actorId)
    }
    await sleep(ACTION_STEP)
    return
  }
  if (step.kind === 'settle') {
    const t = d.target as string | undefined
    const amount = (d.hpDamage ?? d.raw ?? 0) as number
    if (t) shakeTarget(t)
    if (t && amount > 0) addFloat(t, `-${amount}`, 'damage')
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

function approachTarget(id: string) {
  approaching.value[id] = true
  window.setTimeout(() => {
    approaching.value[id] = false
  }, 860)
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

function portraitUrl(c: CombatantView): string {
  return `/assets/${c.templateId}.webp`
}

async function load() {
  loading.value = true
  try {
    const battleId = route.params.battleId as string
    battle.value = await getBattle(battleId)
    for (const c of alivePlayers.value) {
      if (!pending.value[c.id]) {
        pending.value[c.id] = { actionType: 'ATTACK', skillId: null, targetId: targetDummy.value }
      }
    }
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}

function actionOptions(c: CombatantView) {
  return [
    ...c.baseActions.map((a) => ({ label: actionLabel(a), value: a })),
    { label: '技能', value: 'SKILL' }
  ]
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

function needsTarget(action: string): boolean {
  return action === 'ATTACK' || action === 'CHASE'
}

function needsGuardTarget(action: string): boolean {
  return action === 'GUARD'
}

// entering the extra-action round resets every extra actor's selection:
// a stale skill choice (e.g. the just-used 连续奔袭, now on cooldown) must
// never be re-submitted and burn a charge doing nothing
watch(inExtraRound, (on) => {
  if (!on) return
  for (const c of extraActors.value) {
    pending.value[c.id] = { actionType: 'ATTACK', skillId: null, targetId: targetDummy.value }
  }
})

function onActionTypeChange(c: CombatantView, action: string) {
  if (action === 'SKILL') return
  // leaving SKILL must clear the pending skill, otherwise the skill tag
  // stays highlighted and the old skill leaks into the next decision
  const p = pending.value[c.id]
  p.skillId = null
  p.targetId = needsTarget(action) ? targetDummy.value : null
}

function selectSkill(c: CombatantView, s: SkillView) {
  if ((c.cooldowns[s.id] ?? 0) > 0) {
    message.warning(`${s.name} 冷却中（还需 ${c.cooldowns[s.id] ?? 0} 回合）`)
    return
  }
  pending.value[c.id] = {
    actionType: 'SKILL',
    skillId: s.id,
    targetId: s.targetType === 'self' ? c.id : null
  }
}

function selectedSkill(c: CombatantView): SkillView | null {
  const id = pending.value[c.id]?.skillId
  return c.skills.find((s) => s.id === id) ?? null
}

function skillNeedsTarget(s: SkillView | null): boolean {
  if (!s) return false
  return s.targetType !== 'self' && s.targetType !== 'allies'
}

function skillTagClass(c: CombatantView, s: SkillView): string {
  const active = pending.value[c.id]?.actionType === 'SKILL' && pending.value[c.id]?.skillId === s.id ? ' active' : ''
  const cooldown = (c.cooldowns[s.id] ?? 0) > 0 ? ' cooldown' : ''
  return 'skill-tag' + active + cooldown
}

function skillTargetOptions(skillTargetType: string, c: CombatantView) {
  const allies = players.value.filter((p) => !p.dead && p.id !== c.id)
  if (skillTargetType === 'ally' || skillTargetType === 'random_ally') {
    return allies.map((a) => ({ label: a.name, value: a.id }))
  }
  if (skillTargetType === 'allies') {
    return allies.map((a) => ({ label: a.name, value: a.id }))
  }
  if (skillTargetType === 'enemy' || skillTargetType === 'enemies') {
    return enemies.value.filter((e) => !e.dead).map((e) => ({ label: e.name, value: e.id }))
  }
  return []
}

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
      decisions.push({
        combatantId: c.id,
        actionType: 'SKILL',
        skillId: p.skillId,
        targetId: p.targetId ?? undefined
      })
    } else {
      if (needsTarget(p.actionType) && !p.targetId) {
        message.warning(`${c.name} 未选择攻击目标`)
        return
      }
      if (needsGuardTarget(p.actionType) && !p.targetId) {
        message.warning(`${c.name} 未选择守护目标`)
        return
      }
      decisions.push({
        combatantId: c.id,
        actionType: p.actionType,
        targetId: p.targetId ?? undefined
      })
    }
  }
  if (decisions.length !== decisionActors.value.length) {
    message.warning(inExtraRound.value ? '请为拥有额外行动的角色下达指令' : '请为所有存活角色下达指令')
    return
  }
  submitting.value = true
  try {
    if (inExtraRound.value) {
      battle.value = await decideExtraActions(battle.value!.id, decisions)
    } else {
      battle.value = await decide(battle.value!.id, decisions)
    }
    // decision round over: raise the curtain, then gate the settlement
    // animations behind it (the queue waits until the rise has played).
    // The response may carry a round_start whose fall-curtain handler runs
    // in the same tick - await nextTick() so the rise wins (the submit is
    // the decision-round boundary, not round_start)
    await nextTick()
    lockCurtainWindow()
    curtainGateUntil = Date.now() + 1900
    playCurtainNow('rise', () => unlockCurtainWindow())
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    submitting.value = false
  }
}

async function skipExtra() {
  submitting.value = true
  try {
    battle.value = await skipExtraActions(battle.value!.id)
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
          <span class="dim">先手：{{ battle.firstStrikeSide === 0 ? '玩家' : '木桩' }}</span>
          <span class="dim">抽牌能量 {{ battle.playerDrawEnergy }}/10</span>
        </div>
        <n-button size="small" @click="load">刷新</n-button>
      </div>

      <!-- victory banner -->
      <div v-if="isFinished" class="panel result-banner" :class="battle.winner === 'PLAYER' ? 'win' : 'lose'">
        <h2>{{ battle.winner === 'PLAYER' ? '战斗胜利' : '战斗败北' }}</h2>
        <n-button type="primary" @click="router.push({ name: 'records' })">查看战报</n-button>
      </div>

      <!-- initial perk -->
      <section v-if="inInitialPerk" class="panel perk-panel">
        <h3>选择初始词条</h3>
        <div class="perk-grid">
          <div v-for="p in battle.initialPerkOptions" :key="p.id" class="perk-card" @click="chooseInitialPerk(p.id)">
            <div class="perk-name accent">{{ p.name }}</div>
            <div class="perk-desc">{{ p.description }}</div>
          </div>
        </div>
      </section>

      <!-- battle stage: face-to-face showdown in the middle of the field -->
      <div class="stage">
        <div
          class="stage-scene"
          :class="{ dimmed: anyPerforming, zoomed: anyPerforming }"
          :style="{ transformOrigin: zoomOrigin }"
        >
        <div class="side-col side-player">
          <div
            v-for="c in players"
            :key="c.id"
            class="unit"
            :class="{
              dead: c.dead,
              performing: performing[c.id],
              approaching: approaching[c.id],
              shaking: shaking[c.id]
            }"
          >
            <div class="portrait-wrap">
              <img
                v-if="!portraitFailed[c.id]"
                :src="portraitUrl(c)"
                :alt="c.name"
                class="portrait"
                @error="portraitFailed[c.id] = true"
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
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="hp-bar"><div :style="{ width: hpPercent(c) }"></div></div>
                <span class="bar-num">{{ displayHpOf(c) }}/{{ c.maxHp }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">EP</span>
                <div class="energy-bar"><div :style="{ width: energyPercent(c) }"></div></div>
                <span class="bar-num">{{ c.energy }}/{{ c.maxEnergy }}</span>
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
            :class="{
              dead: c.dead,
              performing: performing[c.id],
              approaching: approaching[c.id],
              shaking: shaking[c.id]
            }"
          >
            <div class="portrait-wrap">
              <img
                v-if="!portraitFailed[c.id]"
                :src="portraitUrl(c)"
                :alt="c.name"
                class="portrait"
                @error="portraitFailed[c.id] = true"
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
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="hp-bar"><div :style="{ width: hpPercent(c) }"></div></div>
                <span class="bar-num">{{ displayHpOf(c) }}/{{ c.maxHp }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">EP</span>
                <div class="energy-bar"><div :style="{ width: energyPercent(c) }"></div></div>
                <span class="bar-num">{{ c.energy }}/{{ c.maxEnergy }}</span>
              </div>
              <div v-if="statusText(c)" class="unit-status dim">{{ statusText(c) }}</div>
            </div>
          </div>
        </div>

        <!-- natural curtain overlay inside the stage -->
        <div v-if="curtain" :key="curtain.seq" class="curtain" :class="curtain.kind">
          <img :src="curtain.kind === 'rise' ? '/assets/curtain_rise.webp' : '/assets/curtain_fall.webp'" alt="" />
        </div>

        <!-- last-dash moment: natural reveal inside the stage -->
        <div v-if="dashOverlay" :key="dashOverlay.seq" class="dash-moment">
          <img src="/assets/last_dash.webp" alt="决速时刻" />
        </div>
        </div>
      </div>

      <!-- decision panel: one control row per alive player (extra-action
           rounds only show characters that still hold extra actions) -->
      <div v-if="inDecision" class="panel decision-panel" :class="{ locked: animating }">
        <div v-if="inExtraRound" class="extra-round-hint">
          ⚡ 额外行动轮：{{ extraActors.map((a) => `${a.name}（剩余 ${a.extraActionsThisTurn}）`).join('、') }}
        </div>
        <div v-for="c in decisionActors" :key="c.id" class="decision-unit">
          <span class="actor-name">{{ c.name }}</span>
          <n-select
            v-model:value="pending[c.id].actionType"
            @update:value="onActionTypeChange(c, $event)"
            :options="actionOptions(c)"
            size="small"
            style="width: 120px"
          />
          <n-select
            v-if="pending[c.id].actionType === 'SKILL'"
            v-model:value="pending[c.id].skillId"
            :options="c.skills.map((s) => ({
              label: `${s.name} (${s.energyCost})${s.upgraded ? (c.skillsUpgraded ? ' 已升变' : ' 可升变') : ''}`,
              value: s.id
            }))"
            size="small"
            style="width: 160px"
          />
          <n-select
            v-if="needsTarget(pending[c.id].actionType) || (pending[c.id].actionType === 'SKILL' && pending[c.id].skillId)"
            v-model:value="pending[c.id].targetId"
            :options="
              pending[c.id].actionType === 'SKILL'
                ? skillTargetOptions(c.skills.find((s) => s.id === pending[c.id].skillId)?.targetType ?? '', c)
                : enemies.filter((e) => !e.dead).map((e) => ({ label: e.name, value: e.id }))
            "
            size="small"
            style="width: 120px"
          />
          <n-select
            v-if="needsGuardTarget(pending[c.id].actionType)"
            v-model:value="pending[c.id].targetId"
            :options="players.filter((p) => !p.dead && p.id !== c.id).map((p) => ({ label: p.name, value: p.id }))"
            size="small"
            style="width: 120px"
          />
          <div class="skill-hint">
            <span v-for="s in c.skills" :key="s.id" :class="skillTagClass(c, s)" @click="selectSkill(c, s)">
              {{ s.name }}({{ s.energyCost }}){{ (c.cooldowns[s.id] ?? 0) > 0 ? ' CD' + (c.cooldowns[s.id] ?? 0) : '' }}{{ s.upgraded ? (c.skillsUpgraded ? ' 已升变' : ' 可升变') : '' }}
            </span>
          </div>
          <div v-if="pending[c.id].actionType === 'SKILL' && selectedSkill(c)" class="skill-detail">
            <div class="skill-desc dim">{{ selectedSkill(c)?.description }}</div>
            <n-select
              v-if="skillNeedsTarget(selectedSkill(c))"
              v-model:value="pending[c.id].targetId"
              :options="skillTargetOptions(selectedSkill(c)?.targetType ?? '', c)"
              size="small"
              style="width: 170px"
              placeholder="选择目标"
            />
          </div>
        </div>
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
        </n-button>
      </div>

      <!-- generic skill hand + special perk offers -->
      <div v-if="inDecision || inSpecialPerk" class="panel footer-panel">
        <div v-if="inSpecialPerk" class="hand">
          <span class="dim">特殊词条</span>
          <div v-for="p in battle.specialPerkOptions" :key="p.id" class="card" @click="chooseSpecialPerk(p.id)" :title="p.description">
            <div class="card-name accent">{{ p.name }}</div>
            <div class="card-desc dim">{{ p.description }}</div>
          </div>
          <span v-if="battle.specialPerkOptions.length === 0" class="dim">无可用词条</span>
          <n-button quaternary size="small" :loading="submitting" @click="skipPerk">跳过本轮</n-button>
        </div>
        <div v-if="inDecision" class="hand">
          <span class="dim">手牌</span>
          <div v-for="card in battle.playerHand" :key="card.id" class="card" @click="playCardFromHand(card.id)" :title="card.description">
            <div class="card-name accent">{{ card.name }}</div>
            <div class="card-desc dim">{{ card.description }}</div>
          </div>
          <span v-if="battle.playerHand.length === 0" class="dim">无手牌</span>
        </div>
      </div>

      <!-- battle log -->
      <section class="panel log-panel">
        <h4>战斗日志</h4>
        <div class="log-list">
          <div v-for="(log, i) in battle.logs" :key="i" class="log-row">
            <span class="log-round dim">R{{ log.round }}</span>
            <span class="log-type" :class="log.type">{{ log.type }}</span>
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
  min-height: 400px;
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
  padding: 18px 30px 24px;
  background:
    linear-gradient(180deg, rgba(11, 14, 20, 0.2), rgba(11, 14, 20, 0.5)),
    url('/assets/fight_background.webp?v=2') center / cover no-repeat;
  transition: transform 0.5s ease;
  will-change: transform;
}

.stage-scene.zoomed {
  transform: scale(1.16);
}

.stage-scene.dimmed .unit:not(.performing):not(.dead) {
  opacity: 0.45;
  transform: scale(0.92);
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
  border-radius: 10px;
  background: rgba(18, 23, 32, 0.66);
  border: 1px solid rgba(35, 44, 61, 0.85);
  backdrop-filter: blur(2px);
  transition: transform 0.55s ease, opacity 0.35s ease, border-color 0.2s;
}

.unit.dead {
  opacity: 0.35;
  filter: grayscale(0.9);
}

.unit.shaking {
  animation: unit-shake 0.45s ease;
}

.unit.performing {
  border-color: rgba(76, 194, 255, 0.85);
  box-shadow: 0 0 28px rgba(76, 194, 255, 0.4);
  z-index: 3;
  transform: scale(1.22);
}

.side-player .unit.approaching {
  transform: translateX(100px);
}

.side-enemy .unit.approaching {
  transform: translateX(-100px);
}

.side-player .unit.performing.approaching {
  transform: translateX(100px) scale(1.22);
}

.side-enemy .unit.performing.approaching {
  transform: translateX(-100px) scale(1.22);
}

.portrait-wrap {
  position: relative;
  width: 96px;
  height: 126px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  overflow: hidden;
  background: radial-gradient(circle at 50% 30%, rgba(76, 194, 255, 0.16), rgba(11, 14, 20, 0.6));
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

.portrait-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44px;
  font-weight: 700;
  color: rgba(215, 224, 238, 0.35);
  background: radial-gradient(circle at 50% 30%, rgba(76, 194, 255, 0.12), rgba(11, 14, 20, 0.7));
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

.bar-label {
  width: 20px;
  font-size: 10px;
  color: var(--text-dim);
  flex-shrink: 0;
}

.bar-num {
  font-size: 10px;
  min-width: 48px;
  text-align: right;
  color: var(--text-dim);
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

.decision-unit {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.actor-name {
  font-size: 14px;
  font-weight: 600;
  min-width: 90px;
}

.extra-round-hint {
  font-size: 13px;
  color: var(--accent, #4cc2ff);
  margin-bottom: 8px;
  font-weight: 600;
}

.skill-hint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.skill-tag {
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-dim);
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.skill-tag:hover {
  border-color: var(--accent);
  color: var(--text);
}

.skill-tag.active {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(76, 194, 255, 0.12);
}

.skill-tag.cooldown {
  opacity: 0.45;
  cursor: not-allowed;
}

.skill-detail {
  margin-top: 4px;
  padding: 6px 8px;
  border: 1px dashed var(--border);
  border-radius: 6px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.skill-desc {
  margin-bottom: 0;
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

.log-type.damage {
  color: var(--danger);
}

.log-type.heal {
  color: var(--ok);
}

.log-type.performance {
  color: var(--warn);
}

.log-message {
  flex: 1;
}
</style>
