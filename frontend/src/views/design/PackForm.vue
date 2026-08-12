<script setup lang="ts">
import { NInput } from 'naive-ui'
import PerksEditor from './PerksEditor.vue'
import GenericSkillsEditor from './GenericSkillsEditor.vue'
import type { PackFormState } from './converters'

// Card pack form: identity fields, core reference, perk lists and generic
// skills. Characters are managed on the "可用角色" tab, so this form only
// shows their names read-only and keeps the raw array untouched on save.

const props = defineProps<{
  modelValue: PackFormState
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PackFormState): void
}>()

function patch(key: keyof PackFormState, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<template>
  <div class="pack-form">
    <div class="form-grid">
      <label>ID</label>
      <n-input :value="modelValue.id" placeholder="唯一 ID（字母数字 ._-）" @update:value="patch('id', $event)" />
      <label>名称</label>
      <n-input :value="modelValue.name" placeholder="卡牌包名称" @update:value="patch('name', $event)" />
      <label>核心 ID</label>
      <n-input :value="modelValue.coreId" placeholder="核心 ID，如 core-faith" @update:value="patch('coreId', $event)" />
      <label>核心名称</label>
      <n-input :value="modelValue.coreName" placeholder="核心名称" @update:value="patch('coreName', $event)" />
    </div>

    <div v-if="modelValue.characterNames.length > 0" class="character-note">
      <span class="dim">已包含角色：</span>
      <span class="character-names">{{ modelValue.characterNames.join('、') }}</span>
      <span class="dim">（在「可用角色」标签页编辑角色）</span>
    </div>

    <div class="form-section">
      <div class="form-section-head">
        <span class="dim">初始词条</span>
      </div>
      <PerksEditor :model-value="modelValue.initialPerks" @update:model-value="patch('initialPerks', $event)" />
    </div>

    <div class="form-section">
      <div class="form-section-head">
        <span class="dim">特殊词条</span>
      </div>
      <PerksEditor :model-value="modelValue.specialPerks" @update:model-value="patch('specialPerks', $event)" />
    </div>

    <div class="form-section">
      <div class="form-section-head">
        <span class="dim">通用技能</span>
      </div>
      <GenericSkillsEditor :model-value="modelValue.genericSkills" @update:model-value="patch('genericSkills', $event)" />
    </div>
  </div>
</template>

<style scoped>
.pack-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 10px 14px;
  align-items: center;
}

.form-grid label {
  font-size: 13px;
  color: var(--text-dim);
}

.character-note {
  font-size: 13px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.character-names {
  color: var(--accent);
}

.form-section {
  border-top: 1px dashed var(--border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-section-head {
  font-size: 13px;
}
</style>
