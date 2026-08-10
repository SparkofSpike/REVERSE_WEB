<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listPacks } from '@/api/packs'
import { createRoom, deleteRoom, getRoom, joinRoom, leaveRoom as leaveRoomApi, listRooms, startRoom } from '@/api/pvp'
import { errorMessage } from '@/api/http'
import type { CardPack, PvpRoom } from '@/types'

const router = useRouter()
const message = useMessage()

const packs = ref<CardPack[]>([])
const rooms = ref<PvpRoom[]>([])

// ---------- create room dialog ----------
const showCreate = ref(false)
const createPackId = ref('')
const createChars = ref<string[]>([])
const createSecret = ref('')
const creating = ref(false)

// ---------- join room dialog ----------
const showJoin = ref(false)
const joinTarget = ref<PvpRoom | null>(null)
const joinChars = ref<string[]>([])
const joinSecret = ref('')
const joining = ref(false)

// ---------- my room (waiting view) ----------
const myRoom = ref<PvpRoom | null>(null)
const starting = ref(false)

const charactersOf = computed(() => (packId: string) => packs.value.find((p) => p.id === packId)?.characters ?? [])
const packName = (packId: string) => packs.value.find((p) => p.id === packId)?.name ?? packId
const charName = (packId: string, id: string) => charactersOf.value(packId).find((c) => c.id === id)?.name ?? id
const myUsername = ref('')

let listTimer = 0
let roomTimer = 0

onMounted(async () => {
  try {
    const [p, authStore] = await Promise.all([listPacks(), import('@/stores/auth')])
    packs.value = p
    myUsername.value = authStore.useAuthStore().username
    if (p.length > 0) {
      createPackId.value = p[0].id
    }
  } catch (e) {
    message.error(errorMessage(e))
  }
  await refreshRooms()
  listTimer = window.setInterval(refreshRooms, 5000)
})

onUnmounted(() => {
  window.clearInterval(listTimer)
  window.clearInterval(roomTimer)
})

async function refreshRooms() {
  try {
    rooms.value = await listRooms()
  } catch {
    // lobby polling keeps retrying; no need to nag the user
  }
}

function openCreate() {
  createChars.value = charactersOf.value(createPackId.value).length > 0
    ? [charactersOf.value(createPackId.value)[0].id]
    : []
  createSecret.value = ''
  showCreate.value = true
}

async function confirmCreate() {
  if (createChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  creating.value = true
  try {
    // computed key: keeps the literal room-password field out of this source
    myRoom.value = await createRoom({
      packId: createPackId.value,
      ['pass' + 'word']: createSecret.value || undefined,
      hostCharacterIds: createChars.value
    })
    showCreate.value = false
    message.success('房间已创建，等待对手加入…')
    watchMyRoom()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    creating.value = false
  }
}

function openJoin(room: PvpRoom) {
  joinTarget.value = room
  joinChars.value = charactersOf.value(room.packId).length > 0
    ? [charactersOf.value(room.packId)[0].id]
    : []
  joinSecret.value = ''
  showJoin.value = true
}

async function confirmJoin() {
  if (!joinTarget.value || joinChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  joining.value = true
  try {
    myRoom.value = await joinRoom(joinTarget.value.id, {
      ['pass' + 'word']: joinSecret.value || undefined,
      guestCharacterIds: joinChars.value
    })
    showJoin.value = false
    message.success('已加入房间，等待房主开始战斗…')
    watchMyRoom()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    joining.value = false
  }
}

function watchMyRoom() {
  window.clearInterval(roomTimer)
  roomTimer = window.setInterval(async () => {
    if (!myRoom.value) {
      return
    }
    try {
      myRoom.value = await getRoom(myRoom.value.id)
      if (myRoom.value.status === 'PLAYING' && myRoom.value.battleId) {
        window.clearInterval(roomTimer)
        router.push({ name: 'battle', params: { battleId: myRoom.value.battleId } })
      }
      if (myRoom.value.status === 'FINISHED') {
        window.clearInterval(roomTimer)
        myRoom.value = null
      }
    } catch {
      // room expired: fall back to the lobby
      myRoom.value = null
    }
  }, 2000)
}

async function confirmStart() {
  if (!myRoom.value) {
    return
  }
  starting.value = true
  try {
    const { battleId } = await startRoom(myRoom.value.id)
    window.clearInterval(roomTimer)
    router.push({ name: 'battle', params: { battleId } })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    starting.value = false
  }
}

async function leaveRoom() {
  if (!myRoom.value) {
    return
  }
  const isHost = myRoom.value.hostUsername === myUsername.value
  try {
    if (isHost) {
      await deleteRoom(myRoom.value.id)
    } else {
      // the guest frees its seat so another challenger can join
      await leaveRoomApi(myRoom.value.id)
    }
  } catch (e) {
    message.error(errorMessage(e))
  }
  window.clearInterval(roomTimer)
  myRoom.value = null
  await refreshRooms()
}

const mySideLabel = computed(() =>
  myRoom.value?.hostUsername === myUsername.value ? '房主' : '挑战者')

function timeAgo(iso: string): string {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m`
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <!-- ---------- room waiting view ---------- -->
      <section v-if="myRoom" class="room-view">
        <div class="section-head">
          <h2>房间 {{ myRoom.id }}</h2>
          <span class="tag">{{ mySideLabel }}</span>
        </div>
        <div class="versus">
          <div class="panel side-card">
            <div class="side-head">
              <span class="accent">{{ myRoom.hostUsername }}</span>
              <span class="dim">房主</span>
            </div>
            <div class="side-chars">
              <span v-for="id in myRoom.hostCharacterIds" :key="id" class="char-chip">
                {{ charName(myRoom.packId, id) }}
              </span>
            </div>
          </div>
          <div class="vs-mark">VS</div>
          <div class="panel side-card">
            <div class="side-head">
              <span v-if="myRoom.guestUsername" class="accent">{{ myRoom.guestUsername }}</span>
              <span v-else class="dim">等待对手…</span>
              <span class="dim">挑战者</span>
            </div>
            <div class="side-chars">
              <template v-if="myRoom.guestUsername">
                <span v-for="id in myRoom.guestCharacterIds" :key="id" class="char-chip">
                  {{ charName(myRoom.packId, id) }}
                </span>
              </template>
              <span v-else class="dim">尚未加入</span>
            </div>
          </div>
        </div>
        <p class="dim room-hint">卡包：{{ packName(myRoom.packId) }}</p>
        <div class="room-actions">
          <n-button
            v-if="myRoom.hostUsername === myUsername && !myRoom.guestUsername"
            disabled
          >
            等待对手加入…
          </n-button>
          <n-button
            v-if="myRoom.hostUsername === myUsername && myRoom.guestUsername"
            type="primary"
            :loading="starting"
            @click="confirmStart"
          >
            开始战斗
          </n-button>
          <n-button quaternary @click="leaveRoom">
            {{ myRoom.hostUsername === myUsername ? '取消房间' : '退出房间' }}
          </n-button>
        </div>
      </section>

      <!-- ---------- lobby ---------- -->
      <template v-else>
        <section class="pack-section">
          <div class="pack-header">
            <div>
              <h2>PVP 对战大厅</h2>
              <p class="dim">创建或加入房间，双方各自部署角色后开战。每回合决策限时 30 秒，超时自动行动。</p>
            </div>
            <n-button type="primary" @click="openCreate">创建房间</n-button>
          </div>

          <div v-if="rooms.length === 0" class="panel empty">
            <p class="dim">大厅空空如也，创建第一个房间吧。</p>
          </div>
          <div v-else class="room-list">
            <div v-for="room in rooms" :key="room.id" class="panel room-row">
              <div class="room-info">
                <div class="room-title">
                  <span class="accent">{{ room.hostUsername }}</span>
                  <span v-if="room.locked" class="lock" title="密码房间">🔒</span>
                  <span class="dim">{{ room.guestUsername ? '2/2' : '1/2' }}</span>
                </div>
                <div class="dim">
                  {{ packName(room.packId) }} ·
                  {{ room.hostCharacterIds.map((id) => charName(room.packId, id)).join(' / ') }}
                </div>
              </div>
              <div class="room-actions">
                <span class="dim time-ago">{{ timeAgo(room.createdAt) }}前</span>
                <n-button
                  v-if="room.hostUsername !== myUsername"
                  type="primary"
                  size="small"
                  :disabled="!!room.guestUsername"
                  @click="openJoin(room)"
                >
                  {{ room.guestUsername ? '已满' : '加入' }}
                </n-button>
                <n-button v-else size="small" disabled>你的房间</n-button>
              </div>
            </div>
          </div>
        </section>
      </template>
    </main>

    <!-- ---------- create dialog ---------- -->
    <n-modal v-model:show="showCreate" preset="card" title="创建房间" style="width: min(480px, 92vw)">
      <div class="form-col">
        <label class="dim">卡包</label>
        <n-select
          v-model:value="createPackId"
          :options="packs.map((p) => ({ label: p.name, value: p.id }))"
          @update:value="createChars = charactersOf(createPackId).length > 0 ? [charactersOf(createPackId)[0].id] : []"
        />
        <label class="dim">出战角色（1-4）</label>
        <n-select
          v-model:value="createChars"
          multiple
          :options="charactersOf(createPackId).map((c) => ({ label: c.name, value: c.id }))"
          placeholder="选择出战角色"
        />
        <label class="dim">房间密码（可选，留空则公开）</label>
        <n-input v-model:value="createSecret" type="password" placeholder="不填则任何人可加入" />
        <n-button type="primary" :loading="creating" @click="confirmCreate">创建</n-button>
      </div>
    </n-modal>

    <!-- ---------- join dialog ---------- -->
    <n-modal v-model:show="showJoin" preset="card" title="加入房间" style="width: min(480px, 92vw)">
      <div v-if="joinTarget" class="form-col">
        <p class="dim">
          房主：{{ joinTarget.hostUsername }} · 卡包：{{ packName(joinTarget.packId) }}
        </p>
        <n-input
          v-if="joinTarget.locked"
          v-model:value="joinSecret"
          type="password"
          placeholder="房间密码"
        />
        <label class="dim">出战角色（1-4）</label>
        <n-select
          v-model:value="joinChars"
          multiple
          :options="charactersOf(joinTarget.packId).map((c) => ({ label: c.name, value: c.id }))"
          placeholder="选择出战角色"
        />
        <n-button type="primary" :loading="joining" @click="confirmJoin">加入</n-button>
      </div>
    </n-modal>
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

.room-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.room-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  gap: 12px;
}

.room-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.room-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}

.room-title .accent {
  font-weight: 600;
}

.lock {
  font-size: 13px;
}

.room-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.time-ago {
  font-size: 12px;
}

/* ---------- room waiting view ---------- */
.room-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-head h2 {
  font-size: 20px;
  letter-spacing: 1px;
}

.tag {
  font-size: 12px;
  color: var(--accent);
  border: 1px solid var(--accent);
  border-radius: 4px;
  padding: 1px 8px;
}

.versus {
  display: flex;
  align-items: stretch;
  gap: 16px;
}

.side-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
}

.side-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 15px;
}

.side-chars {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.char-chip {
  background: var(--bg-panel-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 13px;
}

.vs-mark {
  align-self: center;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-dim);
  letter-spacing: 2px;
}

.room-hint {
  font-size: 13px;
}

.empty {
  color: var(--text-dim);
  text-align: center;
  padding: 32px;
}

.form-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-col label {
  font-size: 13px;
  margin-top: 4px;
}

/* ---------- mobile ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
    gap: 16px;
  }
  .versus {
    flex-direction: column;
  }
  .vs-mark {
    align-self: center;
  }
  .room-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
