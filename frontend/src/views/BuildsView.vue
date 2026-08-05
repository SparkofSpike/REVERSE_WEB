<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NModal, NSelect, NSpace, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listPacks } from '@/api/packs'
import { listBuilds, createBuild, updateBuild, deleteBuild } from '@/api/builds'
import { errorMessage } from '@/api/http'
import type { Build, CardPack } from '@/types'

const message = useMessage()

const packs = ref<CardPack[]>([])
const builds = ref<Build[]>([])
const pack = computed(() => packs.value[0] || null)
const characters = computed(() => pack.value?.characters ?? [])

const showModal = ref(false)
const editing = ref<Build | null>(null)
const saving = ref(false)

const name = ref('')
const characterIds = ref<string[]>([])
const initialPerkId = ref<string | null>(null)

onMounted(load)

async function load() {
  try {
    const [p, b] = await Promise.all([listPacks(), listBuilds()])
    packs.value = p
    builds.value = b
  } catch (e) {
    message.error(errorMessage(e))
  }
}

function openCreate() {
  editing.value = null
  name.value = ''
  characterIds.value = []
  initialPerkId.value = null
  showModal.value = true
}

function openEdit(build: Build) {
  editing.value = build
  name.value = build.name
  characterIds.value = [...build.characterIds]
  initialPerkId.value = build.initialPerkId
  showModal.value = true
}

async function save() {
  if (!name.value.trim()) {
    message.warning('请输入构筑名称')
    return
  }
  if (characterIds.value.length === 0) {
    message.warning('请至少选择 1 个角色')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: name.value.trim(),
      packId: pack.value!.id,
      characterIds: characterIds.value,
      initialPerkId: initialPerkId.value
    }
    if (editing.value) {
      await updateBuild(editing.value.id, payload)
      message.success('构筑已更新')
    } else {
      await createBuild(payload)
      message.success('构筑已创建')
    }
    showModal.value = false
    await load()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}

async function remove(build: Build) {
  try {
    await deleteBuild(build.id)
    message.success('构筑已删除')
    await load()
  } catch (e) {
    message.error(errorMessage(e))
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
      <div class="head">
        <h2>构筑管理</h2>
        <n-button type="primary" @click="openCreate">新建构筑</n-button>
      </div>

      <div v-if="builds.length === 0" class="panel empty">
        <p class="dim">暂无构筑，点击右上角新建。</p>
      </div>
      <div v-else class="build-list">
        <div v-for="b in builds" :key="b.id" class="panel build-row">
          <div class="build-info">
            <span class="build-name">{{ b.name }}</span>
            <span class="dim">
              {{ b.characterIds.map(characterName).join(' / ') }}
            </span>
            <span v-if="b.initialPerkId" class="dim">
              初始词条：{{ pack?.initialPerks.find((p) => p.id === b.initialPerkId)?.name ?? b.initialPerkId }}
            </span>
          </div>
          <n-space>
            <n-button size="small" @click="openEdit(b)">编辑</n-button>
            <n-button size="small" type="error" quaternary @click="remove(b)">删除</n-button>
          </n-space>
        </div>
      </div>
    </main>

    <n-modal v-model:show="showModal" preset="card" :title="editing ? '编辑构筑' : '新建构筑'" style="width: min(520px, 94vw)">
      <div class="form">
        <label>构筑名称</label>
        <n-input v-model:value="name" placeholder="例如：极限爆发队" />

        <label>出战角色（1-4 个）</label>
        <n-select
          v-model:value="characterIds"
          multiple
          :options="characters.map((c) => ({ label: c.name, value: c.id }))"
          placeholder="选择角色"
        />

        <label>初始词条</label>
        <n-select
          v-model:value="initialPerkId"
          clearable
          :options="(pack?.initialPerks ?? []).map((p) => ({ label: p.name, value: p.id }))"
          placeholder="选择初始词条（可选）"
        />

        <n-button type="primary" block :loading="saving" @click="save">保存</n-button>
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
  max-width: 900px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.head h2 {
  font-size: 20px;
}

.empty {
  text-align: center;
  padding: 40px;
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
  padding: 14px 16px;
}

.build-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.build-name {
  font-size: 15px;
  font-weight: 600;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form label {
  font-size: 13px;
  color: var(--text-dim);
  margin-top: 8px;
}

/* ---------- mobile: card lists stack, dialogs fill the screen ---------- */
@media (max-width: 768px) {
  .container {
    padding: 16px;
  }
  .build-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .build-info {
    min-width: 0;
    flex: 1 1 auto;
  }
  .build-name {
    word-break: break-word;
  }
}
</style>
