<script setup lang="ts">
import { NButton, NInput, NInputNumber } from 'naive-ui'
import EffectsEditor from './EffectsEditor.vue'
import { emptyPerk, type PerkFormState } from './converters'
import type { EffectFormState } from './form'

// Edits an initial/special perk list. Each perk carries an id, a name, a
// description, a round requirement and a single effect spec.

const props = defineProps<{
  modelValue: PerkFormState[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PerkFormState[]): void
}>()

function patch(index: number, key: keyof PerkFormState, value: unknown) {
  const next = [...props.modelValue]
  next[index] = { ...next[index], [key]: value }
  emit('update:modelValue', next)
}

function patchEffect(index: number, value: EffectFormState[]) {
  const next = [...props.modelValue]
  next[index] = { ...next[index], effect: value[0] ?? emptyPerk().effect }
  emit('update:modelValue', next)
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function add() {
  emit('update:modelValue', [...props.modelValue, emptyPerk()])
}
</script>

<template>
  <div class="perks-editor">
    <div v-for="(perk, index) in modelValue" :key="index" class="perk-row">
      <div class="perk-row-head">
        <span class="dim perk-index">词条 {{ index + 1 }}</span>
        <n-button size="tiny" quaternary type="error" @click="remove(index)">移除</n-button>
      </div>
      <div class="perk-grid">
        <label>ID</label>
        <n-input :value="perk.id" size="small" placeholder="唯一 ID" @update:value="patch(index, 'id', $event)" />
        <label>名称</label>
        <n-input :value="perk.name" size="small" placeholder="词条名" @update:value="patch(index, 'name', $event)" />
        <label>回合要求</label>
        <n-input-number
          :value="perk.roundRequirement"
          size="small"
          style="width: 120px"
          placeholder="0 或 -1"
          @update:value="patch(index, 'roundRequirement', $event)"
        />
        <label>描述</label>
        <n-input :value="perk.description" size="small" placeholder="词条描述" @update:value="patch(index, 'description', $event)" />
      </div>
      <div class="perk-effect">
        <span class="dim field-label">效果</span>
        <EffectsEditor :model-value="[perk.effect]" @update:model-value="patchEffect(index, $event)" />
      </div>
    </div>
    <n-button size="tiny" dashed @click="add">+ 添加词条</n-button>
  </div>
</template>

<style scoped>
.perks-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.perk-row {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg);
}

.perk-row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.perk-index {
  font-size: 12px;
}

.perk-grid {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 6px 10px;
  align-items: center;
}

.perk-grid label {
  font-size: 12px;
  color: var(--text-dim);
}

.perk-effect {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
}
</style>
