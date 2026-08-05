<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NSelect, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listPacks } from '@/api/packs'
import { listBuilds } from '@/api/builds'
import { createDummyBattle } from '@/api/combat'
import { errorMessage } from '@/api/http'
import type { Build, CardPack } from '@/types'

const router = useRouter()
const message = useMessage()

const packs = ref<CardPack[]>([])
const builds = ref<Build[]>([])
const starting = ref(false)

const pack = computed(() => packs.value[0] || null)
const characters = computed(() => pack.value?.characters ?? [])

// quick start selection
const selectedChars = ref<string[]>([])

onMounted(async () => {
  try {
    const [p, b] = await Promise.all([listPacks(), listBuilds()])
    packs.value = p
    builds.value = b
    if (characters.value.length > 0) {
      selectedChars.value = [characters.value[0].id]
    }
  } catch (e) {
    message.error(errorMessage(e))
  }
})

async function startFromBuild(build: Build) {
  starting.value = true
  try {
    const battle = await createDummyBattle(build.packId, build.characterIds)
    router.push({ name: 'battle', params: { battleId: battle.id } })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    starting.value = false
  }
}

async function quickStart() {
  if (!pack.value || selectedChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  starting.value = true
  try {
    const battle = await createDummyBattle(pack.value.id, selectedChars.value)
    router.push({ name: 'battle', params: { battleId: battle.id } })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    starting.value = false
  }
}

function characterName(id: string): string {
  return characters.value.find((c) => c.id === id)?.name ?? id
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <section v-if="pack" class="pack-section">
        <div class="pack-header">
          <div>
            <h2>{{ pack.name }} 卡包</h2>
            <p class="dim">核心：{{ pack.core.name }} — 提供的卡组空间参考</p>
          </div>
          <div class="pack-actions">
            <n-select
              v-model:value="selectedChars"
              multiple
              :options="characters.map((c) => ({ label: c.name, value: c.id }))"
              placeholder="选择出战角色（1-4）"
              style="width: min(320px, 100%)"
            />
            <n-button type="primary" :loading="starting" @click="quickStart">
              快速开战
            </n-button>
          </div>
        </div>
        <div class="char-grid">
          <div v-for="c in characters" :key="c.id" class="panel char-card">
            <div class="char-head">
              <span class="char-name">{{ c.name }}</span>
              <span class="dim">{{ c.baseDamageDice }} {{ c.baseDamageType }}</span>
            </div>
            <div class="char-intro dim">{{ c.description }}</div>
            <div class="char-stats dim">
              <span>生命 {{ c.maxHp }}</span>
              <span>精力 {{ c.maxEnergy }}</span>
              <span>速度 {{ c.speedDice }}</span>
              <span>格挡 {{ c.blockDice }}</span>
            </div>
            <div v-if="c.corePassive" class="char-passive">
              {{ c.corePassive.description }}
            </div>
            <div v-if="c.performance" class="char-perf dim">
              <div>触发条件：{{ c.performance.description }}</div>
              <div v-if="c.performance.rewardDescription">
                追加效果：{{ c.performance.rewardDescription }}
              </div>
            </div>
            <div class="char-skills">
              <div v-for="s in c.skills" :key="s.id" class="skill-row">
                <span class="accent">{{ s.name }}</span>
                <span class="dim">{{ s.description }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="build-section">
        <div class="section-head">
          <h3>我的构筑</h3>
          <n-button size="small" @click="router.push({ name: 'builds' })">管理构筑</n-button>
        </div>
        <div v-if="builds.length === 0" class="panel empty">
          <p class="dim">还没有保存的构筑，去构筑管理里创建一套吧。</p>
        </div>
        <div v-else class="build-list">
          <div v-for="b in builds" :key="b.id" class="panel build-row">
            <div class="build-info">
              <span class="build-name">{{ b.name }}</span>
              <span class="dim">
                {{ b.characterIds.map(characterName).join(' / ') }}
              </span>
            </div>
            <n-button type="primary" size="small" :loading="starting" @click="startFromBuild(b)">
              用此构筑开战
            </n-button>
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
  gap: 24px;
}

.pack-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.pack-header h2 {
  font-size: 20px;
  letter-spacing: 1px;
}

.pack-header p {
  margin-top: 4px;
  font-size: 13px;
}

.pack-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.char-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.char-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.char-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.char-name {
  font-size: 16px;
  font-weight: 600;
}

.char-stats {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
}

.char-passive {
  font-size: 12px;
  color: var(--warn);
  border-left: 2px solid var(--warn);
  padding-left: 8px;
}

.char-perf {
  font-size: 12px;
  border-left: 2px solid var(--accent);
  padding-left: 8px;
}

.char-skills {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.skill-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.skill-row .accent {
  white-space: nowrap;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-head h3 {
  font-size: 16px;
}

.empty {
  color: var(--text-dim);
  text-align: center;
  padding: 32px;
}

.build-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.build-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
}

.build-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.build-name {
  font-size: 15px;
  font-weight: 600;
}

/* ---------- mobile: single-column cards, tighter container ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
    gap: 16px;
  }
  .char-grid {
    grid-template-columns: 1fr;
  }
  .pack-actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .pack-actions .n-select {
    flex: 1 1 100%;
  }
}
</style>
