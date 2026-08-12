<script setup lang="ts">
import { NButton, NInput, NSelect } from 'naive-ui'
import { EFFECT_FIELDS, EFFECT_TYPES, TARGETS, DAMAGE_TYPES, emptyEffect, type EffectFormState } from './form'

// Edits a list of effect specs: each row is a type dropdown plus the fields
// that matter for that type. Emits a new array on any change.

const props = defineProps<{
  modelValue: EffectFormState[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: EffectFormState[]): void
}>()

const effectOptions = EFFECT_TYPES.map((t) => ({ label: t, value: t }))
const targetOptions = TARGETS.map((t) => ({ label: t, value: t }))
const damageOptions = DAMAGE_TYPES.map((t) => ({ label: t, value: t }))

function patch(index: number, key: keyof EffectFormState, value: string) {
  const next = [...props.modelValue]
  next[index] = { ...next[index], [key]: value }
  emit('update:modelValue', next)
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function add() {
  emit('update:modelValue', [...props.modelValue, emptyEffect()])
}

function fieldLabel(field: string): string {
  const labels: Record<string, string> = {
    dice: '骰子（如 1d7）',
    amount: '数值',
    ratio: '比例（0-1）',
    duration: '持续回合',
    count: '次数/目标数',
    max: '上限',
    interval: '间隔回合',
    damageType: '伤害类型'
  }
  return labels[field] ?? field
}
</script>

<template>
  <div class="effects-editor">
    <div v-for="(effect, index) in modelValue" :key="index" class="effect-row">
      <div class="effect-row-head">
        <n-select
          :value="effect.type"
          :options="effectOptions"
          size="small"
          style="width: 170px"
          @update:value="patch(index, 'type', $event)"
        />
        <n-button size="tiny" quaternary type="error" @click="remove(index)">移除</n-button>
      </div>
      <div class="effect-fields">
        <n-select
          :value="effect.target"
          :options="targetOptions"
          size="small"
          label="目标"
          style="width: 130px"
          @update:value="patch(index, 'target', $event)"
        />
        <n-input
          v-if="EFFECT_FIELDS[effect.type]?.includes('dice')"
          :value="effect.dice"
          size="small"
          placeholder="骰子（如 1d7）"
          style="width: 140px"
          @update:value="patch(index, 'dice', $event)"
        />
        <template v-for="field in EFFECT_FIELDS[effect.type] ?? []" :key="field">
          <n-select
            v-if="field === 'damageType'"
            :value="effect.damageType"
            :options="damageOptions"
            size="small"
            style="width: 130px"
            @update:value="patch(index, 'damageType', $event)"
          />
          <n-input
            v-else
            :value="(effect as unknown as Record<string, string>)[field]"
            size="small"
            :placeholder="fieldLabel(field)"
            style="width: 130px"
            @update:value="patch(index, field as keyof EffectFormState, $event)"
          />
        </template>
      </div>
    </div>
    <n-button size="tiny" dashed @click="add">+ 添加效果</n-button>
  </div>
</template>

<style scoped>
.effects-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.effect-row {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--bg);
}

.effect-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.effect-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
