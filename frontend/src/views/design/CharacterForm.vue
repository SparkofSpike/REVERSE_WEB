<script setup lang="ts">
import { NInput, NSelect, NSwitch } from 'naive-ui'
import EffectsEditor from './EffectsEditor.vue'
import SkillsEditor from './SkillsEditor.vue'
import { ACTION_TYPES, DAMAGE_TYPES, PASSIVE_TYPES, TRIGGER_TYPES } from './form'
import type { CharacterFormState, PassiveFormState, PerformanceFormState } from './converters'

// Playable character form: base stats grid, optional core passive, optional
// performance trigger and the skill list.

const props = defineProps<{
  modelValue: CharacterFormState
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: CharacterFormState): void
}>()

const damageOptions = DAMAGE_TYPES.map((t) => ({ label: t, value: t }))
const actionOptions = ACTION_TYPES.map((t) => ({ label: t, value: t }))
const passiveOptions = PASSIVE_TYPES.map((t) => ({ label: t, value: t }))
const triggerOptions = TRIGGER_TYPES.map((t) => ({ label: t, value: t }))

function patch(key: keyof CharacterFormState, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function patchPassive(value: Partial<PassiveFormState>) {
  emit('update:modelValue', { ...props.modelValue, corePassive: { ...props.modelValue.corePassive, ...value } })
}

function patchPerformance(value: Partial<PerformanceFormState>) {
  emit('update:modelValue', { ...props.modelValue, performance: { ...props.modelValue.performance, ...value } })
}
</script>

<template>
  <div class="character-form">
    <div class="form-grid">
      <label>ID</label>
      <n-input :value="modelValue.id" placeholder="唯一 ID（字母数字 ._-）" @update:value="patch('id', $event)" />
      <label>名称</label>
      <n-input :value="modelValue.name" placeholder="角色名称" @update:value="patch('name', $event)" />
      <label>简介</label>
      <n-input :value="modelValue.description" placeholder="角色简介" @update:value="patch('description', $event)" />
      <label>最大生命</label>
      <n-input :value="modelValue.maxHp" placeholder="如 80" @update:value="patch('maxHp', $event)" />
      <label>最大能量</label>
      <n-input :value="modelValue.maxEnergy" placeholder="如 100" @update:value="patch('maxEnergy', $event)" />
      <label>速度骰</label>
      <n-input :value="modelValue.speedDice" placeholder="如 1d7" @update:value="patch('speedDice', $event)" />
      <label>物抗系数</label>
      <n-input :value="modelValue.physicalResistance" placeholder="如 1.0" @update:value="patch('physicalResistance', $event)" />
      <label>法抗系数</label>
      <n-input :value="modelValue.magicResistance" placeholder="如 1.0" @update:value="patch('magicResistance', $event)" />
      <label>基础伤害骰</label>
      <n-input :value="modelValue.baseDamageDice" placeholder="如 1d7" @update:value="patch('baseDamageDice', $event)" />
      <label>伤害类型</label>
      <n-select
        :value="modelValue.baseDamageType"
        :options="damageOptions"
        style="width: 160px"
        @update:value="patch('baseDamageType', $event)"
      />
      <label>格挡骰</label>
      <n-input :value="modelValue.blockDice" placeholder="如 1d7" @update:value="patch('blockDice', $event)" />
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

    <div class="form-section">
      <div class="form-section-head">
        <n-switch :value="modelValue.corePassive.enabled" size="small" @update:value="patchPassive({ enabled: $event })" />
        <span class="dim">核心被动</span>
      </div>
      <div v-if="modelValue.corePassive.enabled" class="form-grid">
        <label>类型</label>
        <n-select
          :value="modelValue.corePassive.type"
          :options="passiveOptions"
          style="width: 200px"
          @update:value="patchPassive({ type: $event })"
        />
        <label>数值</label>
        <n-input :value="modelValue.corePassive.amount" placeholder="如 10" @update:value="patchPassive({ amount: $event })" />
        <label>比例</label>
        <n-input :value="modelValue.corePassive.ratio" placeholder="如 0.5" @update:value="patchPassive({ ratio: $event })" />
        <label>骰子</label>
        <n-input :value="modelValue.corePassive.dice" placeholder="如 1d3" @update:value="patchPassive({ dice: $event })" />
        <label>持续回合</label>
        <n-input :value="modelValue.corePassive.duration" placeholder="如 3" @update:value="patchPassive({ duration: $event })" />
        <label>描述</label>
        <n-input :value="modelValue.corePassive.description" placeholder="被动描述" @update:value="patchPassive({ description: $event })" />
      </div>
    </div>

    <div class="form-section">
      <div class="form-section-head">
        <n-switch :value="modelValue.performance.enabled" size="small" @update:value="patchPerformance({ enabled: $event })" />
        <span class="dim">演出触发</span>
      </div>
      <div v-if="modelValue.performance.enabled" class="form-grid">
        <label>触发条件</label>
        <n-select
          :value="modelValue.performance.triggerType"
          :options="triggerOptions"
          style="width: 200px"
          @update:value="patchPerformance({ triggerType: $event })"
        />
        <label>阈值</label>
        <n-input :value="modelValue.performance.threshold" placeholder="如 40" @update:value="patchPerformance({ threshold: $event })" />
        <label>描述</label>
        <n-input :value="modelValue.performance.description" placeholder="触发条件描述" @update:value="patchPerformance({ description: $event })" />
        <label>追加奖励</label>
        <n-input :value="modelValue.performance.rewardDescription" placeholder="追加效果描述" @update:value="patchPerformance({ rewardDescription: $event })" />
        <label>效果</label>
        <div class="inline-editor">
          <EffectsEditor
            :model-value="modelValue.performance.effects"
            @update:model-value="patchPerformance({ effects: $event })"
          />
        </div>
      </div>
    </div>

    <div class="form-section">
      <div class="form-section-head">
        <span class="dim">技能（最多 3 个，含升级形态）</span>
      </div>
      <SkillsEditor :model-value="modelValue.skills" @update:model-value="patch('skills', $event)" />
    </div>
  </div>
</template>

<style scoped>
.character-form {
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

.form-section {
  border-top: 1px dashed var(--border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.inline-editor {
  min-width: 0;
}
</style>
