<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NPopconfirm, NSelect, NSpace, NTabPane, NTabs, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import {
  addCharacter,
  createEnemy,
  createPack,
  deleteCharacter,
  deleteEnemy,
  deletePack,
  getEnemy,
  getPack,
  listCharacters,
  listEnemies,
  listPacks,
  updateCharacter,
  updateEnemy,
  updatePack
} from '@/api/design'
import { errorMessage } from '@/api/http'
import type { DesignEntry } from '@/types'

type Tab = 'packs' | 'enemies' | 'characters'

const message = useMessage()

const tab = ref<Tab>('packs')

const packs = ref<DesignEntry[]>([])
const enemies = ref<DesignEntry[]>([])

// character tab state: pick a pack first, then edit one of its characters
const charPackId = ref<string | null>(null)
const characters = ref<DesignEntry[]>([])

// JSON editor state (shared across tabs)
const editorTitle = ref('')
const editor = ref('')
const editorError = ref('')
const isNew = ref(false)
const saving = ref(false)

const PACK_TEMPLATE = `{
  "id": "",
  "name": "",
  "core": { "id": "", "name": "" },
  "initialPerks": [],
  "specialPerks": [],
  "genericSkills": [],
  "characters": []
}`

const ENEMY_TEMPLATE = `{
  "id": "",
  "name": "",
  "maxHp": 100,
  "maxEnergy": 60,
  "speedDice": "1d6",
  "baseDamageDice": "1d6",
  "baseDamageType": "PHYSICAL",
  "physicalResistance": 1.0,
  "magicResistance": 1.0,
  "blockDice": "1d6",
  "dodgePenalty": "0d3",
  "baseActions": ["ATTACK", "DEFEND"]
}`

const CHARACTER_TEMPLATE = `{
  "id": "",
  "name": "",
  "maxHp": 50,
  "maxEnergy": 60,
  "speedDice": "1d6",
  "physicalResistance": 1.0,
  "magicResistance": 1.0,
  "baseDamageDice": "1d6",
  "baseDamageType": "PHYSICAL",
  "blockDice": "1d6",
  "dodgePenalty": "0d3",
  "baseActions": ["ATTACK"],
  "corePassive": null,
  "performance": null,
  "skills": []
}`

onMounted(loadAll)

async function loadAll() {
  try {
    const [p, e] = await Promise.all([listPacks(), listEnemies()])
    packs.value = p
    enemies.value = e
  } catch (err) {
    message.error(errorMessage(err))
  }
}

async function loadCharacters() {
  if (!charPackId.value) {
    characters.value = []
    return
  }
  try {
    characters.value = await listCharacters(charPackId.value)
  } catch (err) {
    characters.value = []
    message.error(errorMessage(err))
  }
}

// ---------- selection ----------

function entryLabel(e: DesignEntry): string {
  return e.name ? `${e.name}（${e.id}）` : e.id
}

function openEditor(title: string, json: string, isCreate: boolean) {
  editorTitle.value = title
  editor.value = json
  editorError.value = ''
  isNew.value = isCreate
}

async function selectPack(entry: DesignEntry) {
  try {
    const data = await getPack(entry.id)
    openEditor(`卡牌包 · ${entry.id}`, JSON.stringify(data, null, 2), false)
  } catch (err) {
    message.error(errorMessage(err))
  }
}

async function selectEnemy(entry: DesignEntry) {
  try {
    const data = await getEnemy(entry.id)
    openEditor(`敌人 · ${entry.id}`, JSON.stringify(data, null, 2), false)
  } catch (err) {
    message.error(errorMessage(err))
  }
}

async function selectCharacter(entry: DesignEntry) {
  if (!charPackId.value) return
  try {
    const pack = (await getPack(charPackId.value)) as { characters?: Array<Record<string, unknown>> }
    const character = (pack.characters ?? []).find((c) => c.id === entry.id)
    if (!character) {
      message.error('角色不存在，列表可能已过期，请重新选择卡牌包')
      return
    }
    openEditor(`角色 · ${entry.id}`, JSON.stringify(character, null, 2), false)
  } catch (err) {
    message.error(errorMessage(err))
  }
}

function onNew() {
  if (tab.value === 'packs') {
    openEditor('新建卡牌包', PACK_TEMPLATE, true)
  } else if (tab.value === 'enemies') {
    openEditor('新建敌人', ENEMY_TEMPLATE, true)
  } else if (charPackId.value) {
    openEditor('新建角色', CHARACTER_TEMPLATE, true)
  } else {
    message.warning('请先选择一个卡牌包')
  }
}

function onCharPackChange() {
  editor.value = ''
  editorTitle.value = ''
  loadCharacters()
}

// ---------- save / delete ----------

function parseEditor(): Record<string, unknown> {
  try {
    const value = JSON.parse(editor.value) as Record<string, unknown>
    editorError.value = ''
    return value
  } catch (err) {
    editorError.value = err instanceof Error ? err.message : 'JSON 解析失败'
    throw new Error(editorError.value)
  }
}

async function save() {
  let body: Record<string, unknown>
  try {
    body = parseEditor()
  } catch {
    message.error(editorError.value || 'JSON 格式错误')
    return
  }
  saving.value = true
  try {
    if (tab.value === 'packs') {
      const id = body.id as string
      if (isNew.value) {
        await createPack(JSON.stringify(body))
      } else {
        await updatePack(id, JSON.stringify(body))
      }
    } else if (tab.value === 'enemies') {
      const id = body.id as string
      if (isNew.value) {
        await createEnemy(JSON.stringify(body))
      } else {
        await updateEnemy(id, JSON.stringify(body))
      }
    } else {
      if (!charPackId.value) return
      const id = body.id as string
      if (isNew.value) {
        await addCharacter(charPackId.value, JSON.stringify(body))
      } else {
        await updateCharacter(charPackId.value, id, JSON.stringify(body))
      }
    }
    message.success('已保存，战斗数据已热更新')
    editorError.value = ''
    await refreshAfterSave()
  } catch (err) {
    message.error(errorMessage(err))
  } finally {
    saving.value = false
  }
}

async function refreshAfterSave() {
  if (tab.value === 'packs') {
    await loadAll()
  } else if (tab.value === 'enemies') {
    await loadAll()
  } else {
    await loadAll()
    await loadCharacters()
  }
}

async function remove() {
  try {
    if (tab.value === 'packs') {
      const id = (JSON.parse(editor.value) as { id: string }).id
      await deletePack(id)
    } else if (tab.value === 'enemies') {
      const id = (JSON.parse(editor.value) as { id: string }).id
      await deleteEnemy(id)
    } else {
      if (!charPackId.value) return
      const id = (JSON.parse(editor.value) as { id: string }).id
      await deleteCharacter(charPackId.value, id)
    }
    message.success('已删除')
    editor.value = ''
    editorTitle.value = ''
    await refreshAfterSave()
  } catch (err) {
    message.error(errorMessage(err))
  }
}

function isCurrent(id: string): boolean {
  try {
    const body = JSON.parse(editor.value) as { id?: string }
    return !!body.id && body.id === id && !isNew.value
  } catch {
    return false
  }
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <div class="head">
        <h2>设计管理</h2>
        <span class="dim">管理员专属 · 保存后立即生效</span>
      </div>

      <n-tabs v-model:value="tab" type="line">
        <n-tab-pane name="packs" tab="卡牌包">
          <div class="editor-layout">
            <div class="entry-list panel">
              <div
                v-for="entry in packs"
                :key="entry.id"
                class="entry-row"
                :class="{ active: !isNew && isCurrent(entry.id) }"
                @click="selectPack(entry)"
              >
                <span class="entry-name">{{ entryLabel(entry) }}</span>
                <span v-if="!entry.custom" class="badge">内置</span>
              </div>
              <div v-if="packs.length === 0" class="dim empty-tip">暂无卡牌包</div>
            </div>
            <div class="editor panel">
              <div v-if="editorTitle" class="editor-body">
                <div class="editor-head">
                  <span class="editor-title">{{ editorTitle }}</span>
                  <n-space>
                    <n-button size="small" @click="onNew">新建</n-button>
                    <n-popconfirm @positive-click="remove">
                      <template #trigger>
                        <n-button size="small" type="error" quaternary :disabled="isNew">删除</n-button>
                      </template>
                      确定删除当前内容？内置内容不可删除。
                    </n-popconfirm>
                    <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
                  </n-space>
                </div>
                <textarea
                  v-model="editor"
                  class="json-editor"
                  spellcheck="false"
                  :class="{ 'has-error': editorError }"
                ></textarea>
                <div v-if="editorError" class="editor-error">{{ editorError }}</div>
              </div>
              <div v-else class="editor-placeholder dim">从左侧选择卡牌包，或点击「新建」</div>
            </div>
          </div>
        </n-tab-pane>

        <n-tab-pane name="enemies" tab="敌人">
          <div class="editor-layout">
            <div class="entry-list panel">
              <div
                v-for="entry in enemies"
                :key="entry.id"
                class="entry-row"
                :class="{ active: !isNew && isCurrent(entry.id) }"
                @click="selectEnemy(entry)"
              >
                <span class="entry-name">{{ entryLabel(entry) }}</span>
                <span v-if="!entry.custom" class="badge">内置</span>
              </div>
              <div v-if="enemies.length === 0" class="dim empty-tip">暂无敌人</div>
            </div>
            <div class="editor panel">
              <div v-if="editorTitle" class="editor-body">
                <div class="editor-head">
                  <span class="editor-title">{{ editorTitle }}</span>
                  <n-space>
                    <n-button size="small" @click="onNew">新建</n-button>
                    <n-popconfirm @positive-click="remove">
                      <template #trigger>
                        <n-button size="small" type="error" quaternary :disabled="isNew">删除</n-button>
                      </template>
                      确定删除当前内容？内置内容不可删除。
                    </n-popconfirm>
                    <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
                  </n-space>
                </div>
                <textarea
                  v-model="editor"
                  class="json-editor"
                  spellcheck="false"
                  :class="{ 'has-error': editorError }"
                ></textarea>
                <div v-if="editorError" class="editor-error">{{ editorError }}</div>
              </div>
              <div v-else class="editor-placeholder dim">从左侧选择敌人，或点击「新建」</div>
            </div>
          </div>
        </n-tab-pane>

        <n-tab-pane name="characters" tab="可用角色">
          <div class="char-bar">
            <n-select
              v-model:value="charPackId"
              :options="packs.map((p) => ({ label: entryLabel(p), value: p.id }))"
              placeholder="选择卡牌包"
              style="width: 260px"
              @update:value="onCharPackChange"
            />
            <n-button size="small" :disabled="!charPackId" @click="onNew">新建角色</n-button>
          </div>
          <div class="editor-layout">
            <div class="entry-list panel">
              <div
                v-for="entry in characters"
                :key="entry.id"
                class="entry-row"
                :class="{ active: !isNew && isCurrent(entry.id) }"
                @click="selectCharacter(entry)"
              >
                <span class="entry-name">{{ entryLabel(entry) }}</span>
              </div>
              <div v-if="characters.length === 0" class="dim empty-tip">先选择卡牌包查看角色</div>
            </div>
            <div class="editor panel">
              <div v-if="editorTitle" class="editor-body">
                <div class="editor-head">
                  <span class="editor-title">{{ editorTitle }}</span>
                  <n-space>
                    <n-button size="small" @click="onNew">新建</n-button>
                    <n-popconfirm @positive-click="remove">
                      <template #trigger>
                        <n-button size="small" type="error" quaternary :disabled="isNew">删除</n-button>
                      </template>
                      确定删除当前角色？
                    </n-popconfirm>
                    <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
                  </n-space>
                </div>
                <textarea
                  v-model="editor"
                  class="json-editor"
                  spellcheck="false"
                  :class="{ 'has-error': editorError }"
                ></textarea>
                <div v-if="editorError" class="editor-error">{{ editorError }}</div>
              </div>
              <div v-else class="editor-placeholder dim">选择卡牌包后从左侧选择角色，或点击「新建角色」</div>
            </div>
          </div>
        </n-tab-pane>
      </n-tabs>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.head h2 {
  font-size: 20px;
}

.head span {
  font-size: 12px;
}

.editor-layout {
  display: flex;
  gap: 12px;
  align-items: stretch;
  min-height: 480px;
}

.entry-list {
  width: 250px;
  flex-shrink: 0;
  padding: 8px;
  overflow-y: auto;
  max-height: 560px;
}

.entry-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.entry-row:hover {
  background: var(--bg-panel-2);
}

.entry-row.active {
  background: rgba(76, 194, 255, 0.12);
  color: var(--accent);
}

.entry-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  font-size: 10px;
  color: var(--text-dim);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  flex-shrink: 0;
}

.empty-tip {
  padding: 16px;
  text-align: center;
  font-size: 12px;
}

.editor {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 12px;
  min-width: 0;
}

.editor-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}

.editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.editor-title {
  font-size: 14px;
  font-weight: 600;
  font-family: Consolas, 'Courier New', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.json-editor {
  flex: 1;
  width: 100%;
  min-height: 380px;
  background: var(--bg);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
  resize: vertical;
  tab-size: 2;
}

.json-editor:focus {
  outline: none;
  border-color: var(--accent-dim);
}

.json-editor.has-error {
  border-color: var(--danger);
}

.editor-error {
  color: var(--danger);
  font-size: 12px;
  font-family: Consolas, 'Courier New', monospace;
}

.editor-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}

.char-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

/* ---------- mobile ---------- */
@media (max-width: 768px) {
  .editor-layout {
    flex-direction: column;
  }
  .entry-list {
    width: 100%;
    max-height: 220px;
  }
  .json-editor {
    min-height: 260px;
  }
}
</style>
