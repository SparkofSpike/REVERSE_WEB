<script setup lang="ts">
import { NInput, NSelect } from 'naive-ui'
import { ACTION_TYPES, DAMAGE_TYPES } from './form'
import type { EnemyFormState } from './converters'

// Enemy (puppet) form: all fields are flat, so the grid covers everything.

const props = defineProps<{
  modelValue: EnemyFormState
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: EnemyFormState): void
}>()

const damageOptions = DAMAGE_TYPES.map((t) => ({ label: t, value: t }))
const actionOptions = ACTION_TYPES.map((t) => ({ label: t, value: t }))

function patch(key: keyof EnemyFormState, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<template>
  <div class="form-grid">
    <label>ID</label>
    <n-input :value="modelValue.id" placeholder="唯一 ID（字母数字 ._-）" @update:value="patch('id', $event)" />
    <label>名称</label>
    <n-input :value="modelValue.name" placeholder="敌人名称" @update:value="patch('name', $event)" />
    <label>最大生命</label>
    <n-input :value="modelValue.maxHp" placeholder="如 500" @update:value="patch('maxHp', $event)" />
    <label>最大能量</label>
    <n-input :value="modelValue.maxEnergy" placeholder="如 60" @update:value="patch('maxEnergy', $event)" />
    <label>速度骰</label>
    <n-input :value="modelValue.speedDice" placeholder="如 1d6" @update:value="patch('speedDice', $event)" />
    <label>物抗系数</label>
    <n-input :value="modelValue.physicalResistance" placeholder="如 1.0" @update:value="patch('physicalResistance', $event)" />
    <label>法抗系数</label>
    <n-input :value="modelValue.magicResistance" placeholder="如 1.0" @update:value="patch('magicResistance', $event)" />
    <label>基础伤害骰</label>
    <n-input :value="modelValue.baseDamageDice" placeholder="如 1d6" @update:value="patch('baseDamageDice', $event)" />
    <label>伤害类型</label>
    <n-select
      :value="modelValue.baseDamageType"
      :options="damageOptions"
      style="width: 160px"
      @update:value="patch('baseDamageType', $event)"
    />
    <label>格挡骰</label>
    <n-input :value="modelValue.blockDice" placeholder="如 1d6" @update:value="patch('blockDice', $event)" />
    <label>闪避惩罚</label>
    <n-input :value="modelValue.dodgePenalty" placeholder="如 0d3" @update:value="patch('dodgePenalty', $event)" />
    <label>基础行动</label>
    <n-select
      :value="modelValue.baseActions"
      multiple
      :options="actionOptions"
      placeholder="选择行动"
      @update:value="patch('baseActions', $event)"
    />
  </div>
</template>

<style scoped>
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
</style>
