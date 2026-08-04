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
import type { CombatView, CombatantView, ActionDecision } from '@/types'

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
  return c.baseActions.map((a) => ({ label: actionLabel(a), value: a }))
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

      <!-- enemy side (top) -->
      <section class="panel side">
        <h4>敌方</h4>
        <div class="unit-row">
          <div v-for="c in enemies" :key="c.id" class="unit" :class="{ dead: c.dead }">
            <div class="unit-head">
              <span class="unit-name">{{ c.name }}</span>
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
            <div class="unit-stats dim">
              速度 {{ c.speedDice }} | {{ c.baseDamageDice }} {{ c.baseDamageType }}
            </div>
          </div>
        </div>
      </section>

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

      <!-- player side -->
      <section class="panel side">
        <h4>我方</h4>
        <div class="unit-row">
          <div v-for="c in players" :key="c.id" class="unit" :class="{ dead: c.dead }">
            <div class="unit-head">
              <span class="unit-name">{{ c.name }}</span>
              <span v-if="c.performing" class="tag-perform">演出</span>
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

            <!-- decision controls -->
            <div v-if="inDecision && !c.dead" class="decision-row">
              <n-select
                v-model:value="pending[c.id].actionType"
                :options="actionOptions(c)"
                size="small"
                style="width: 130px"
              />
              <n-select
                v-if="pending[c.id].actionType === 'SKILL'"
                v-model:value="pending[c.id].skillId"
                :options="c.skills.map((s) => ({
                  label: `${s.name} (${s.energyCost})${s.upgraded ? ' 升变' : ''}`,
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
                style="width: 130px"
              />
              <n-select
                v-if="needsGuardTarget(pending[c.id].actionType)"
                v-model:value="pending[c.id].targetId"
                :options="players.filter((p) => !p.dead && p.id !== c.id).map((p) => ({ label: p.name, value: p.id }))"
                size="small"
                style="width: 130px"
              />
            </div>
            <div v-if="inDecision && !c.dead" class="skill-hint dim">
              <template v-for="s in c.skills" :key="s.id">
                {{ s.name }}({{ s.energyCost }}){{ s.cooldown > 0 ? ' CD' + s.cooldown : '' }}
              </template>
            </div>
          </div>
        </div>
      </section>

      <!-- decision footer -->
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
        <n-button type="primary" :loading="submitting" @click="submitDecisions">
          提交指令
        </n-button>
      </div>
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

.unit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.unit-row .unit {
  flex: 1 1 300px;
}

.side h4 {
  font-size: 14px;
  margin-bottom: 10px;
  color: var(--text-dim);
  letter-spacing: 1px;
}

.unit {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
  background: var(--bg-panel-2);
}

.unit.dead {
  opacity: 0.45;
}

.unit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.unit-name {
  font-size: 15px;
  font-weight: 600;
}

.tag-perform {
  font-size: 11px;
  color: var(--warn);
  border: 1px solid var(--warn);
  border-radius: 4px;
  padding: 0 4px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.bar-label {
  width: 24px;
  font-size: 11px;
  color: var(--text-dim);
}

.bar-num {
  font-size: 11px;
  min-width: 56px;
  text-align: right;
  color: var(--text-dim);
}

.unit-status {
  font-size: 11px;
  margin-top: 4px;
}

.unit-stats {
  font-size: 12px;
  margin-top: 6px;
}

.decision-row {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.skill-hint {
  font-size: 11px;
  margin-top: 4px;
}

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
  max-height: 320px;
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
