<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NSelect, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import {
  getBattle,
  selectInitialPerk,
  decide,
  playCard,
  selectSpecialPerk,
  skipSpecialPerk
} from '@/api/combat'
import { errorMessage } from '@/api/http'
import type { CombatView, CombatantView, ActionDecision, SkillView } from '@/types'

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

// transition overlays driven by battle log events
const OVERLAY_SRC: Record<string, string> = {
  round_start: '/assets/curtain_rise.png',
  round_end: '/assets/curtain_fall.png',
  last_dash: '/assets/last_dash.png'
}
const overlay = ref<{ src: string; key: number } | null>(null)
const overlayQueue: string[] = []
let consumedLogs = 0

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

onMounted(load)

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

// consume new battle log entries and queue transition overlays.
// getBattle returns the FULL log array every poll, so only process the tail.
watch(
  () => battle.value?.logs,
  (logs) => {
    if (!logs) return
    for (let i = consumedLogs; i < logs.length; i++) {
      const src = OVERLAY_SRC[logs[i].type]
      if (src) overlayQueue.push(src)
      consumedLogs = i + 1
    }
    if (!overlay.value && overlayQueue.length > 0) {
      showNextOverlay()
    }
  },
  { deep: true }
)

function showNextOverlay() {
  const src = overlayQueue.shift()
  if (!src) return
  overlay.value = { src, key: Date.now() }
  window.setTimeout(() => {
    overlay.value = null
  }, 1600)
}

function onOverlayLeave() {
  if (overlayQueue.length > 0) {
    showNextOverlay()
  }
}

function portraitUrl(c: CombatantView): string {
  return `/assets/${c.templateId}.png`
}

async function load() {
  loading.value = true
  try {
    const battleId = route.params.battleId as string
    battle.value = await getBattle(battleId)
    // initialize pending decisions for alive players
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

function selectSkill(c: CombatantView, s: SkillView) {
  if (s.cooldown > 0) {
    message.warning(`${s.name} 冷却中（还需 ${s.cooldown} 回合）`)
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
  const active = pending.value[c.id]?.skillId === s.id ? ' active' : ''
  const cooldown = s.cooldown > 0 ? ' cooldown' : ''
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
  for (const c of alivePlayers.value) {
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
  if (decisions.length !== alivePlayers.value.length) {
    message.warning('请为所有存活角色下达指令')
    return
  }
  submitting.value = true
  try {
    battle.value = await decide(battle.value!.id, decisions)
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

function hpPercent(c: CombatantView): string {
  return `${Math.max(0, Math.round((c.hp / c.maxHp) * 100))}%`
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
          <div
            v-for="p in battle.initialPerkOptions"
            :key="p.id"
            class="perk-card"
            @click="chooseInitialPerk(p.id)"
          >
            <div class="perk-name accent">{{ p.name }}</div>
            <div class="perk-desc">{{ p.description }}</div>
          </div>
        </div>
      </section>

      <!-- special perk -->
      <section v-if="inSpecialPerk" class="panel perk-panel">
        <h3>特殊词条轮</h3>
        <div class="perk-grid">
          <div
            v-for="p in battle.specialPerkOptions"
            :key="p.id"
            class="perk-card"
            @click="chooseSpecialPerk(p.id)"
          >
            <div class="perk-name accent">{{ p.name }}</div>
            <div class="perk-desc">{{ p.description }}</div>
          </div>
        </div>
        <n-button quaternary size="small" :loading="submitting" @click="skipPerk">跳过本轮</n-button>
      </section>

      <!-- battle stage: combatants on the background image -->
      <div class="stage">
        <div class="side-col">
          <h4>我方</h4>
          <div v-for="c in players" :key="c.id" class="unit" :class="{ dead: c.dead }">
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
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="hp-bar"><div :style="{ width: hpPercent(c) }"></div></div>
                <span class="bar-num">{{ c.hp }}/{{ c.maxHp }}</span>
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
          <h4>敌方</h4>
          <div v-for="c in enemies" :key="c.id" class="unit" :class="{ dead: c.dead }">
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
            <div class="info">
              <div class="name">
                {{ c.name }}
                <span v-if="c.shield > 0" class="shield-tag">盾 {{ c.shield }}</span>
              </div>
              <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="hp-bar"><div :style="{ width: hpPercent(c) }"></div></div>
                <span class="bar-num">{{ c.hp }}/{{ c.maxHp }}</span>
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
      </div>

      <!-- decision panel: one control row per alive player -->
      <div v-if="inDecision" class="panel decision-panel">
        <div v-for="c in alivePlayers" :key="c.id" class="decision-unit">
          <span class="actor-name">{{ c.name }}</span>
          <n-select
            v-model:value="pending[c.id].actionType"
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
            <span
              v-for="s in c.skills"
              :key="s.id"
              :class="skillTagClass(c, s)"
              @click="selectSkill(c, s)"
            >
              {{ s.name }}({{ s.energyCost }}){{ s.cooldown > 0 ? ' CD' + s.cooldown : '' }}{{ s.upgraded ? (c.skillsUpgraded ? ' 已升变' : ' 可升变') : '' }}
            </span>
          </div>
          <div
            v-if="pending[c.id].actionType === 'SKILL' && selectedSkill(c)"
            class="skill-detail"
          >
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
        <n-button type="primary" :loading="submitting" @click="submitDecisions">提交指令</n-button>
      </div>

      <!-- generic skill hand -->
      <div v-if="inDecision" class="panel footer-panel">
        <div class="hand">
          <span class="dim">手牌</span>
          <div
            v-for="card in battle.playerHand"
            :key="card.id"
            class="card"
            @click="playCardFromHand(card.id)"
            :title="card.description"
          >
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

    <!-- transition overlay: curtain rise / curtain fall / last dash -->
    <Transition name="overlay" @after-leave="onOverlayLeave">
      <div v-if="overlay" :key="overlay.key" class="overlay">
        <img :src="overlay.src" alt="" />
      </div>
    </Transition>
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

/* ---------- battle stage ---------- */

.stage {
  position: relative;
  min-height: 460px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  background:
    linear-gradient(180deg, rgba(11, 14, 20, 0.25), rgba(11, 14, 20, 0.55)),
    url('/assets/fight_background.png') center / cover no-repeat;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  padding: 20px 28px;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 200px;
}

.side-col h4 {
  font-size: 13px;
  color: var(--text-dim);
  letter-spacing: 2px;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.8);
  margin: 0 0 2px 6px;
}

.side-enemy {
  align-items: flex-end;
}

.side-enemy h4 {
  margin: 0 6px 2px 0;
  text-align: right;
}

.unit {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border-radius: 10px;
  background: rgba(18, 23, 32, 0.72);
  border: 1px solid rgba(35, 44, 61, 0.85);
  backdrop-filter: blur(2px);
  transition: border-color 0.2s, opacity 0.2s;
}

.unit.dead {
  opacity: 0.4;
  filter: grayscale(0.9);
}

.portrait-wrap {
  position: relative;
  width: 150px;
  height: 190px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  overflow: hidden;
  background: radial-gradient(circle at 50% 30%, rgba(76, 194, 255, 0.18), rgba(11, 14, 20, 0.6));
}

.portrait {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.portrait-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 56px;
  font-weight: 700;
  color: rgba(215, 224, 238, 0.35);
  background: radial-gradient(circle at 50% 30%, rgba(76, 194, 255, 0.12), rgba(11, 14, 20, 0.7));
}

.tag-perform {
  position: absolute;
  top: 6px;
  left: 6px;
  font-size: 11px;
  color: var(--warn);
  border: 1px solid var(--warn);
  border-radius: 4px;
  padding: 0 5px;
  background: rgba(11, 14, 20, 0.6);
}

.info {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.name {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.shield-tag {
  color: var(--shield);
  font-size: 12px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.bar-label {
  width: 22px;
  font-size: 11px;
  color: var(--text-dim);
  flex-shrink: 0;
}

.bar-num {
  font-size: 11px;
  min-width: 52px;
  text-align: right;
  color: var(--text-dim);
}

.unit-status {
  font-size: 11px;
  text-align: center;
}

/* ---------- decision panel ---------- */

.decision-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
  max-height: 260px;
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

/* ---------- transition overlay ---------- */

.overlay {
  position: fixed;
  inset: 0;
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(5, 8, 12, 0.6);
}

.overlay img {
  max-width: 68vw;
  max-height: 62vh;
  border-radius: 8px;
  box-shadow: 0 8px 48px rgba(0, 0, 0, 0.7);
}

.overlay-enter-active {
  transition: opacity 0.45s ease, transform 0.45s ease;
}

.overlay-leave-active {
  transition: opacity 0.5s ease;
}

.overlay-enter-from {
  opacity: 0;
  transform: scale(0.92);
}

.overlay-leave-to {
  opacity: 0;
}
</style>
