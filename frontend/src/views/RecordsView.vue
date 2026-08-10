<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listPacks } from '@/api/packs'
import { listRecords } from '@/api/records'
import { errorMessage } from '@/api/http'
import type { BattleRecordSummary, CardPack } from '@/types'

const router = useRouter()
const message = useMessage()

const records = ref<BattleRecordSummary[]>([])
const packs = ref<CardPack[]>([])

onMounted(async () => {
  try {
    const [r, p] = await Promise.all([listRecords(), listPacks()])
    records.value = r
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
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <div class="head">
        <h2>战报</h2>
      </div>

      <div v-if="records.length === 0" class="panel empty">
        <p class="dim">暂无战斗记录，去打一场木桩战吧。</p>
      </div>
      <div v-else class="record-list">
        <div v-for="r in records" :key="r.id" class="panel record-row">
          <div class="record-main">
            <div class="record-top">
              <span class="result" :class="r.winner === (r.mySide ?? 'PLAYER') ? 'ok' : 'danger'">
                {{ r.winner === (r.mySide ?? 'PLAYER') ? '胜利' : '败北' }}
              </span>
              <span v-if="r.opponentUsername" class="record-vs dim">VS {{ r.opponentUsername }}</span>
              <span class="record-rounds dim">{{ r.rounds }} 回合</span>
            </div>
            <div class="record-chars dim">
              {{ r.playerCharacterIds.map(characterName).join(' / ') }}
            </div>
            <div class="record-stats dim">
              <span>总伤害 {{ r.totalDamageDealt }}</span>
              <span>最大单次 {{ r.maxSingleHit }}</span>
              <span>均伤/回合 {{ r.avgDamagePerRound }}</span>
            </div>
            <div class="record-time dim">{{ formatDate(r.createdAt) }}</div>
          </div>
          <n-button size="small" @click="router.push({ name: 'record-detail', params: { id: r.id } })">
            详情
          </n-button>
        </div>
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
  max-width: 900px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head h2 {
  font-size: 20px;
}

.empty {
  text-align: center;
  padding: 40px;
}

.record-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.record-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
}

.record-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.record-top {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.result {
  font-size: 16px;
  font-weight: 700;
}

.record-rounds {
  font-size: 13px;
}

.record-vs {
  font-size: 12px;
  color: var(--warn, #f0a020);
}

.record-chars {
  font-size: 13px;
}

.record-stats {
  display: flex;
  gap: 16px;
  font-size: 12px;
}

.record-time {
  font-size: 12px;
}

/* ---------- mobile: rows wrap instead of overflowing ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
  }
  .record-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .record-main {
    min-width: 0;
    flex: 1 1 100%;
  }
  .record-top {
    flex-wrap: wrap;
  }
  .record-stats {
    flex-wrap: wrap;
    gap: 10px;
  }
  .record-chars {
    word-break: break-word;
  }
}
</style>
