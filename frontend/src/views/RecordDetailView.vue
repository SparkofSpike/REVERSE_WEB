<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { getRecord } from '@/api/records'
import { listPacks } from '@/api/packs'
import { errorMessage } from '@/api/http'
import type { BattleRecordDetail, CardPack } from '@/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const record = ref<BattleRecordDetail | null>(null)
const packs = ref<CardPack[]>([])

// log type -> extra class. The raw type string is NEVER used as a class:
// it collides with page classes (e.g. "card", "round") and pollutes the row.
const LOG_TYPE_CLASS: Record<string, string> = {
  damage: 'log-damage',
  heal: 'log-heal',
  performance: 'log-perf'
}

onMounted(async () => {
  try {
    const id = Number(route.params.id)
    const [r, p] = await Promise.all([getRecord(id), listPacks()])
    record.value = r
    packs.value = p
  } catch (e) {
    message.error(errorMessage(e))
  }
})

function characterName(id: string): string {
  const pack = packs.value[0]
  return pack?.characters.find((c) => c.id === id)?.name ?? id
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <div class="head">
        <n-button quaternary @click="router.push({ name: 'records' })">返回战报</n-button>
        <h2 v-if="record" class="title">战报详情</h2>
      </div>

      <template v-if="record">
        <div class="panel summary">
          <div class="result" :class="record.winner === (record.mySide ?? 'PLAYER') ? 'ok' : 'danger'">
            {{ record.winner === (record.mySide ?? 'PLAYER') ? '胜利' : '败北' }}
          </div>
          <div class="summary-grid">
            <div class="summary-item">
              <span class="dim">出战角色</span>
              <span>{{ record.playerCharacterIds.map(characterName).join(' / ') }}</span>
            </div>
            <div class="summary-item">
              <span class="dim">战斗回合</span>
              <span>{{ record.rounds }}</span>
            </div>
            <div class="summary-item">
              <span class="dim">总伤害</span>
              <span class="accent">{{ record.totalDamageDealt }}</span>
            </div>
            <div class="summary-item">
              <span class="dim">最大单次伤害</span>
              <span>{{ record.maxSingleHit }}</span>
            </div>
            <div class="summary-item">
              <span class="dim">平均每回合伤害</span>
              <span>{{ record.avgDamagePerRound }}</span>
            </div>
            <div class="summary-item">
              <span class="dim">战斗时间</span>
              <span>{{ formatDate(record.createdAt) }}</span>
            </div>
          </div>
        </div>

        <div class="panel log-panel">
          <h3>战斗日志</h3>
          <div class="log-list">
            <div v-for="(log, i) in record.logs" :key="i" class="log-row">
              <span class="log-round dim">R{{ log.round }}</span>
              <span class="log-type" :class="LOG_TYPE_CLASS[log.type]">{{ log.type }}</span>
              <span class="log-message">{{ log.message }}</span>
            </div>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title {
  font-size: 20px;
}

.summary {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.result {
  font-size: 22px;
  font-weight: 700;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 14px;
}

.summary-item .dim {
  font-size: 12px;
}

.log-panel h3 {
  font-size: 15px;
  margin-bottom: 12px;
}

.log-list {
  max-height: 480px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
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
  min-width: 0;
  word-break: break-word;
}

/* ---------- mobile: logs wrap, grid goes single column ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
  }
  .summary-grid {
    grid-template-columns: 1fr 1fr;
  }
  .log-row {
    gap: 6px;
  }
  .log-message {
    font-size: 12px;
    min-width: 0;
    word-break: break-word;
  }
}
</style>
