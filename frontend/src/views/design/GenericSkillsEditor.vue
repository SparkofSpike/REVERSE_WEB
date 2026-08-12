<script setup lang="ts">
import { NButton, NInput, NSwitch } from 'naive-ui'
import EffectsEditor from './EffectsEditor.vue'
import { emptyGenericSkill, type GenericSkillFormState } from './converters'

// Edits the generic (universal) skill list of a pack: id, name, consumed flag,
// description and effects.

const props = defineProps<{
  modelValue: GenericSkillFormState[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: GenericSkillFormState[]): void
}>()

function patch(index: number, key: keyof GenericSkillFormState, value: unknown) {
  const next = [...props.modelValue]
  next[index] = { ...next[index], [key]: value }
  emit('update:modelValue', next)
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function add() {
  emit('update:modelValue', [...props.modelValue, emptyGenericSkill()])
}
</script>

<template>
  <div class="gskills-editor">
    <div v-for="(skill, index) in modelValue" :key="index" class="gskill-row">
      <div class="gskill-row-head">
        <span class="dim gskill-index">通用技能 {{ index + 1 }}</span>
        <n-button size="tiny" quaternary type="error" @click="remove(index)">移除</n-button>
      </div>
      <div class="gskill-grid">
        <label>ID</label>
        <n-input :value="skill.id" size="small" placeholder="唯一 ID" @update:value="patch(index, 'id', $event)" />
        <label>名称</label>
        <n-input :value="skill.name" size="small" placeholder="技能名" @update:value="patch(index, 'name', $event)" />
        <label>使用后弃置</label>
        <n-switch :value="skill.consumed" size="small" @update:value="patch(index, 'consumed', $event)" />
        <label>描述</label>
        <n-input :value="skill.description" size="small" placeholder="技能描述" @update:value="patch(index, 'description', $event)" />
      </div>
      <div class="gskill-effects">
        <span class="dim field-label">效果</span>
        <EffectsEditor :model-value="skill.effects" @update:model-value="patch(index, 'effects', $event)" />
      </div>
    </div>
    <n-button size="tiny" dashed @click="add">+ 添加通用技能</n-button>
  </div>
</template>

<style scoped>
.gskills-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.gskill-row {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg);
}

.gskill-row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.gskill-index {
  font-size: 12px;
}

.gskill-grid {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 6px 10px;
  align-items: center;
}

.gskill-grid label {
  font-size: 12px;
  color: var(--text-dim);
}

.gskill-effects {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
}
</style>
