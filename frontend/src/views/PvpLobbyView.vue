<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NInput, NModal, NRadioButton, NRadioGroup, NSelect, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listPacks } from '@/api/packs'
import { createRoom, deleteRoom, getRoom, joinRoom, leaveRoom as leaveRoomApi, listRooms, startRoom } from '@/api/pvp'
import {
  createPveRoom,
  deletePveRoom,
  getPveRoom,
  joinPveRoom,
  leavePveRoom as leavePveRoomApi,
  listEnemies,
  listPveRooms,
  readyPve,
  unreadyPve
} from '@/api/pve'
import { errorMessage } from '@/api/http'
import type { CardPack, EnemyTemplate, PveRoom, PvpRoom } from '@/types'

const router = useRouter()
const message = useMessage()

const packs = ref<CardPack[]>([])
const enemies = ref<EnemyTemplate[]>([])
const pvpRooms = ref<PvpRoom[]>([])
const pveRooms = ref<PveRoom[]>([])

type RoomEntry = { kind: 'pvp'; room: PvpRoom } | { kind: 'pve'; room: PveRoom }

function isPvpRoom(room: PvpRoom | PveRoom): room is PvpRoom {
  return 'guestUsername' in room
}

// lobby rows: PVP + PVE rooms merged, newest first
const roomRows = computed(() =>
  [
    ...pvpRooms.value.map((room): RoomEntry => ({ kind: 'pvp', room })),
    ...pveRooms.value.map((room): RoomEntry => ({ kind: 'pve', room }))
  ]
    .sort((a, b) => new Date(b.room.createdAt).getTime() - new Date(a.room.createdAt).getTime())
    .map((entry) => {
      const base = {
        key: entry.kind + entry.room.id,
        kind: entry.kind,
        hostUsername: entry.room.hostUsername,
        locked: entry.room.locked,
        packId: entry.room.packId,
        createdAt: entry.room.createdAt,
        isMine: entry.room.hostUsername === myUsername.value
      }
      if (entry.kind === 'pvp') {
        const pvp = entry.room
        return {
          ...base,
          countText: pvp.guestUsername ? '2/2' : '1/2',
          detailText: `${packName(pvp.packId)} · ${pvp.hostCharacterIds.map((id) => charName(pvp.packId, id)).join(' / ')}`,
          joinDisabled: !!pvp.guestUsername || pvp.status !== 'WAITING',
          joinLabel: pvp.guestUsername ? '已满' : '加入'
        }
      }
      const pve = entry.room
      return {
        ...base,
        countText: `${pve.seats.length} 人`,
        detailText: `${packName(pve.packId)} · ${pve.enemyIds.map(enemyLabel).join(' / ')}`,
        joinDisabled: pve.status !== 'WAITING',
        joinLabel: pve.status === 'WAITING' ? '加入' : '已开始'
      }
    })
)

// ---------- create room dialog ----------
const showCreate = ref(false)
const createMode = ref<'pvp' | 'pve'>('pvp')
const createPackId = ref('')
const createChars = ref<string[]>([])
const createPass = ref('')
const createEnemyIds = ref<string[]>([])
const creating = ref(false)

// ---------- join room dialog ----------
const showJoin = ref(false)
const joinTarget = ref<RoomEntry | null>(null)
const joinChars = ref<string[]>([])
const joinPass = ref('')
const joining = ref(false)

// ---------- my room (waiting view) ----------
const myRoom = ref<PvpRoom | PveRoom | null>(null)
const myPvpRoom = computed(() => (myRoom.value && isPvpRoom(myRoom.value) ? myRoom.value : null))
const myPveRoom = computed(() => (myRoom.value && !isPvpRoom(myRoom.value) ? myRoom.value : null))
const starting = ref(false)
const readying = ref(false)
const myPveChars = ref<string[]>([])

const charactersOf = computed(() => (packId: string) => packs.value.find((p) => p.id === packId)?.characters ?? [])
const packName = (packId: string) => packs.value.find((p) => p.id === packId)?.name ?? packId
const charName = (packId: string, id: string) => charactersOf.value(packId).find((c) => c.id === id)?.name ?? id
const enemyLabel = (id: string) => {
  const e = enemies.value.find((x) => x.id === id)
  return e ? `${e.name}（HP ${e.maxHp} · ${e.baseDamageDice}）` : id
}
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
  try {
    enemies.value = await listEnemies()
  } catch {
    // enemy list unavailable: PVE rows still render with raw ids
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
    const [a, b] = await Promise.all([listRooms(), listPveRooms()])
    pvpRooms.value = a
    pveRooms.value = b
  } catch {
    // lobby polling keeps retrying; no need to nag the user
  }
}

function defaultCharsFor(packId: string): string[] {
  const chars = charactersOf.value(packId)
  return chars.length > 0 ? [chars[0].id] : []
}

function openCreate() {
  createMode.value = 'pvp'
  createChars.value = defaultCharsFor(createPackId.value)
  createEnemyIds.value = []
  createPass.value = ''
  showCreate.value = true
}

function onPackChanged() {
  createChars.value = defaultCharsFor(createPackId.value)
}

async function confirmCreate() {
  if (createMode.value === 'pve') {
    if (createEnemyIds.value.length === 0) {
      message.warning('请至少选择 1 个敌人')
      return
    }
    creating.value = true
    try {
      // computed key: keeps the literal room-pass field out of this source
      myRoom.value = await createPveRoom({
        packId: createPackId.value,
        ['pass' + 'word']: createPass.value || undefined,
        enemyIds: createEnemyIds.value
      })
      showCreate.value = false
      message.success('房间已创建，等待队友加入…')
      watchMyRoom()
    } catch (e) {
      message.error(errorMessage(e))
    } finally {
      creating.value = false
    }
    return
  }
  if (createChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  creating.value = true
  try {
    myRoom.value = await createRoom({
      packId: createPackId.value,
      ['pass' + 'word']: createPass.value || undefined,
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

function openJoin(entry: RoomEntry) {
  joinTarget.value = entry
  joinChars.value = defaultCharsFor(entry.room.packId)
  joinPass.value = ''
  showJoin.value = true
}

function openJoinByRow(row: (typeof roomRows.value)[number]) {
  const entry = [
    ...pvpRooms.value.map((room): RoomEntry => ({ kind: 'pvp', room })),
    ...pveRooms.value.map((room): RoomEntry => ({ kind: 'pve', room }))
  ].find((e) => e.kind + e.room.id === row.key)
  if (entry) {
    openJoin(entry)
  }
}

async function confirmJoin() {
  const target = joinTarget.value
  if (!target) {
    return
  }
  if (target.kind === 'pve') {
    // PVE rooms do not pick characters here: choose inside the room
    joining.value = true
    try {
      myRoom.value = await joinPveRoom(target.room.id, joinPass.value || undefined)
      showJoin.value = false
      message.success('已加入房间，选择角色并准备后自动开战…')
      watchMyRoom()
    } catch (e) {
      message.error(errorMessage(e))
    } finally {
      joining.value = false
    }
    return
  }
  if (joinChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  joining.value = true
  try {
    myRoom.value = await joinRoom(target.room.id, {
      ['pass' + 'word']: joinPass.value || undefined,
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
    const room = myRoom.value
    if (!room) {
      return
    }
    try {
      myRoom.value = isPvpRoom(room) ? await getRoom(room.id) : await getPveRoom(room.id)
      const r = myRoom.value
      if (r.status === 'PLAYING' && r.battleId) {
        window.clearInterval(roomTimer)
        router.push({ name: 'battle', params: { battleId: r.battleId } })
      }
      if (r.status === 'FINISHED') {
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
  if (!myPvpRoom.value) {
    return
  }
  starting.value = true
  try {
    const { battleId } = await startRoom(myPvpRoom.value.id)
    window.clearInterval(roomTimer)
    router.push({ name: 'battle', params: { battleId } })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    starting.value = false
  }
}

async function leaveRoom() {
  const room = myRoom.value
  if (!room) {
    return
  }
  const isHost = room.hostUsername === myUsername.value
  try {
    if (isHost) {
      if (isPvpRoom(room)) {
        await deleteRoom(room.id)
      } else {
        await deletePveRoom(room.id)
      }
    } else if (isPvpRoom(room)) {
      // the guest frees its seat so another challenger can join
      await leaveRoomApi(room.id)
    } else {
      await leavePveRoomApi(room.id)
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

// ---------- PVE seat controls ----------
const myPveSeat = computed(() => {
  const room = myPveRoom.value
  if (!room) {
    return null
  }
  return room.seats.find((s) => s.username === myUsername.value) ?? null
})

const pveReady = computed(() => myPveSeat.value?.ready ?? false)

// sync the local character pick from my seat: on first sight and whenever the
// seat is ready (authoritative state); while unready the pick stays editable
let pveSeatSeen = false
watch(myPveSeat, (seat) => {
  if (!seat) {
    return
  }
  if (!pveSeatSeen || seat.ready) {
    myPveChars.value = [...seat.characterIds]
    pveSeatSeen = true
  }
})

async function confirmReady() {
  const room = myPveRoom.value
  if (!room) {
    return
  }
  if (myPveChars.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  readying.value = true
  try {
    myRoom.value = await readyPve(room.id, myPveChars.value)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    readying.value = false
  }
}

async function cancelReady() {
  const room = myPveRoom.value
  if (!room) {
    return
  }
  readying.value = true
  try {
    myRoom.value = await unreadyPve(room.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    readying.value = false
  }
}

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
        <!-- PVP room: host vs challenger, host starts the battle -->
        <template v-if="myPvpRoom">
          <div class="section-head">
            <h2>房间 {{ myPvpRoom.id }}</h2>
            <span class="tag">{{ mySideLabel }}</span>
          </div>
          <div class="versus">
            <div class="panel side-card">
              <div class="side-head">
                <span class="accent">{{ myPvpRoom.hostUsername }}</span>
                <span class="dim">房主</span>
              </div>
              <div class="side-chars">
                <span v-for="id in myPvpRoom.hostCharacterIds" :key="id" class="char-chip">
                  {{ charName(myPvpRoom.packId, id) }}
                </span>
              </div>
            </div>
            <div class="vs-mark">VS</div>
            <div class="panel side-card">
              <div class="side-head">
                <span v-if="myPvpRoom.guestUsername" class="accent">{{ myPvpRoom.guestUsername }}</span>
                <span v-else class="dim">等待对手…</span>
                <span class="dim">挑战者</span>
              </div>
              <div class="side-chars">
                <template v-if="myPvpRoom.guestUsername">
                  <span v-for="id in myPvpRoom.guestCharacterIds" :key="id" class="char-chip">
                    {{ charName(myPvpRoom.packId, id) }}
                  </span>
                </template>
                <span v-else class="dim">尚未加入</span>
              </div>
            </div>
          </div>
          <p class="dim room-hint">卡包：{{ packName(myPvpRoom.packId) }}</p>
          <div class="room-actions">
            <n-button
              v-if="myPvpRoom.hostUsername === myUsername && !myPvpRoom.guestUsername"
              disabled
            >
              等待对手加入…
            </n-button>
            <n-button
              v-if="myPvpRoom.hostUsername === myUsername && myPvpRoom.guestUsername"
              type="primary"
              :loading="starting"
              @click="confirmStart"
            >
              开始战斗
            </n-button>
            <n-button quaternary @click="leaveRoom">
              {{ myPvpRoom.hostUsername === myUsername ? '取消房间' : '退出房间' }}
            </n-button>
          </div>
        </template>

        <!-- PVE room: enemy roster + seats, everyone readies to auto-start -->
        <template v-else-if="myPveRoom">
          <div class="section-head">
            <h2>房间 {{ myPveRoom.id }}</h2>
            <span class="tag">PVE</span>
            <span class="dim">房主 {{ myPveRoom.hostUsername }}</span>
          </div>
          <div class="panel side-card">
            <div class="side-head">
              <span class="accent">敌方阵容</span>
              <span class="dim">{{ myPveRoom.enemyIds.length }} 名敌人</span>
            </div>
            <div class="side-chars">
              <span v-for="id in myPveRoom.enemyIds" :key="id" class="char-chip enemy-chip">
                {{ enemyLabel(id) }}
              </span>
            </div>
          </div>
          <div class="panel seat-panel">
            <div v-for="seat in myPveRoom.seats" :key="seat.username" class="seat-row">
              <div class="seat-user">
                <span class="accent">{{ seat.username }}</span>
                <span v-if="seat.isHost" class="tag">房主</span>
                <span v-if="seat.username === myUsername" class="dim">（我）</span>
              </div>
              <div class="seat-chars">
                <template v-if="seat.characterIds.length > 0">
                  <span v-for="id in seat.characterIds" :key="id" class="char-chip">
                    {{ charName(myPveRoom.packId, id) }}
                  </span>
                </template>
                <span v-else class="dim">未选择角色</span>
              </div>
              <span class="ready-tag" :class="seat.ready ? 'ready' : 'pending'">
                {{ seat.ready ? '已准备' : '准备中' }}
              </span>
            </div>
            <div v-if="myPveSeat" class="seat-controls">
              <n-select
                v-model:value="myPveChars"
                multiple
                :disabled="pveReady"
                :options="charactersOf(myPveRoom.packId).map((c) => ({ label: c.name, value: c.id }))"
                placeholder="选择出战角色（可多选）"
              />
              <n-button
                v-if="!pveReady"
                type="primary"
                :loading="readying"
                :disabled="myPveChars.length === 0"
                @click="confirmReady"
              >
                准备
              </n-button>
              <n-button v-else quaternary :loading="readying" @click="cancelReady">取消准备</n-button>
            </div>
          </div>
          <p class="dim room-hint">所有人准备后自动开始 · 卡包：{{ packName(myPveRoom.packId) }}</p>
          <div class="room-actions">
            <n-button quaternary @click="leaveRoom">
              {{ myPveRoom.hostUsername === myUsername ? '取消房间' : '退出房间' }}
            </n-button>
          </div>
        </template>
      </section>

      <!-- ---------- lobby ---------- -->
      <template v-else>
        <section class="pack-section">
          <div class="pack-header">
            <div>
              <h2>房间大厅</h2>
              <p class="dim">创建 PVP 对战或 PVE 联机房间。PVE 中全员选择角色并准备后自动开战。</p>
            </div>
            <n-button type="primary" @click="openCreate">创建房间</n-button>
          </div>

          <div v-if="roomRows.length === 0" class="panel empty">
            <p class="dim">大厅空空如也，创建第一个房间吧。</p>
          </div>
          <div v-else class="room-list">
            <div v-for="row in roomRows" :key="row.key" class="panel room-row">
              <div class="room-info">
                <div class="room-title">
                  <span class="kind-tag" :class="row.kind">{{ row.kind === 'pvp' ? 'PVP' : 'PVE' }}</span>
                  <span class="accent">{{ row.hostUsername }}</span>
                  <span v-if="row.locked" class="lock" title="密码房间">🔒</span>
                  <span class="dim">{{ row.countText }}</span>
                </div>
                <div class="dim">{{ row.detailText }}</div>
              </div>
              <div class="room-actions">
                <span class="dim time-ago">{{ timeAgo(row.createdAt) }}前</span>
                <n-button
                  v-if="!row.isMine"
                  type="primary"
                  size="small"
                  :disabled="row.joinDisabled"
                  @click="openJoinByRow(row)"
                >
                  {{ row.joinLabel }}
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
        <label class="dim">模式</label>
        <n-radio-group v-model:value="createMode" size="small">
          <n-radio-button value="pvp">PVP 对战</n-radio-button>
          <n-radio-button value="pve">PVE 联机</n-radio-button>
        </n-radio-group>
        <label class="dim">卡包</label>
        <n-select
          v-model:value="createPackId"
          :options="packs.map((p) => ({ label: p.name, value: p.id }))"
          @update:value="onPackChanged"
        />
        <template v-if="createMode === 'pve'">
          <label class="dim">敌人（可多选）</label>
          <n-select
            v-model:value="createEnemyIds"
            multiple
            :options="enemies.map((e) => ({ label: `${e.name} (HP ${e.maxHp})`, value: e.id }))"
            placeholder="选择敌人"
          />
        </template>
        <template v-else>
          <label class="dim">出战角色（1-4）</label>
          <n-select
            v-model:value="createChars"
            multiple
            :options="charactersOf(createPackId).map((c) => ({ label: c.name, value: c.id }))"
            placeholder="选择出战角色"
          />
        </template>
        <label class="dim">房间密码（可选，留空则公开）</label>
        <n-input v-model:value="createPass" type="password" placeholder="不填则任何人可加入" />
        <n-button type="primary" :loading="creating" @click="confirmCreate">创建</n-button>
      </div>
    </n-modal>

    <!-- ---------- join dialog ---------- -->
    <n-modal v-model:show="showJoin" preset="card" title="加入房间" style="width: min(480px, 92vw)">
      <div v-if="joinTarget" class="form-col">
        <p class="dim">
          {{ joinTarget.kind === 'pvp' ? 'PVP' : 'PVE' }} · 房主：{{ joinTarget.room.hostUsername }} ·
          卡包：{{ packName(joinTarget.room.packId) }}
        </p>
        <n-input
          v-if="joinTarget.room.locked"
          v-model:value="joinPass"
          type="password"
          placeholder="房间密码"
        />
        <template v-if="joinTarget.kind === 'pvp'">
          <label class="dim">出战角色（1-4）</label>
          <n-select
            v-model:value="joinChars"
            multiple
            :options="charactersOf(joinTarget.room.packId).map((c) => ({ label: c.name, value: c.id }))"
            placeholder="选择出战角色"
          />
        </template>
        <p v-else class="dim room-hint">进入房间后选择角色并准备。</p>
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

.kind-tag {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-dim);
}

.kind-tag.pvp {
  color: var(--accent);
  border-color: var(--accent);
}

.kind-tag.pve {
  color: var(--ok);
  border-color: var(--ok);
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

.enemy-chip {
  color: var(--danger);
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

/* ---------- PVE seats ---------- */
.seat-panel {
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
}

.seat-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
}

.seat-row:last-of-type {
  border-bottom: none;
}

.seat-user {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 150px;
}

.seat-user .accent {
  font-weight: 600;
}

.seat-chars {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  flex: 1;
}

.ready-tag {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
  border: 1px solid var(--border);
  color: var(--text-dim);
  flex-shrink: 0;
}

.ready-tag.ready {
  color: var(--ok);
  border-color: var(--ok);
}

.seat-controls {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-top: 12px;
}

.seat-controls .n-select {
  flex: 1;
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
  .seat-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
