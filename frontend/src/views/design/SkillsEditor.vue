<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NInput, NInputNumber, NSelect, NSwitch } from 'naive-ui'
import EffectsEditor from './EffectsEditor.vue'
import { SKILL_TARGETS, type EffectFormState } from './form'
import { emptySkill, type SkillFormState } from './converters'

// Edits the skills array of a character. Each skill row carries the plain
// fields plus an optional "upgraded" variant (same id, stronger numbers) that
// folds open on demand.

const props = defineProps<{
  modelValue: SkillFormState[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: SkillFormState[]): void
}>()

const openUpgraded = ref<Record<number, boolean>>({})

const targetOptions = SKILL_TARGETS.map((t) => ({ label: t, value: t }))

function patch(index: number, key: keyof SkillFormState, value: unknown) {
  const next = [...props.modelValue]
  next[index] = { ...next[index], [key]: value }
  emit('update:modelValue', next)
}

function patchEffects(index: number, key: 'effects' | 'upgradedEffects', value: EffectFormState[]) {
  patch(index, key, value)
}

function remove(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function add() {
  emit('update:modelValue', [...props.modelValue, emptySkill()])
}
</script>

<template>
  <div class="skills-editor">
    <div v-for="(skill, index) in modelValue" :key="index" class="skill-row">
      <div class="skill-row-head">
        <span class="skill-index dim">技能 {{ index + 1 }}</span>
        <n-button size="tiny" quaternary type="error" @click="remove(index)">移除</n-button>
      </div>
      <div class="skill-grid">
        <label>ID</label>
        <n-input :value="skill.id" size="small" placeholder="唯一 ID（字母数字 ._-）" @update:value="patch(index, 'id', $event)" />
        <label>名称</label>
        <n-input :value="skill.name" size="small" placeholder="技能名" @update:value="patch(index, 'name', $event)" />
        <label>能量消耗</label>
        <n-input-number :value="skill.energyCost" size="small" style="width: 120px" @update:value="patch(index, 'energyCost', $event)" />
        <label>冷却回合</label>
        <n-input-number :value="skill.cooldown" size="small" style="width: 120px" @update:value="patch(index, 'cooldown', $event)" />
        <label>目标类型</label>
        <n-select
          :value="skill.targetType"
          :options="targetOptions"
          size="small"
          style="width: 130px"
          @update:value="patch(index, 'targetType', $event)"
        />
        <label>描述</label>
        <n-input :value="skill.description" size="small" placeholder="技能描述" @update:value="patch(index, 'description', $event)" />
      </div>
      <div class="skill-effects">
        <span class="dim field-label">效果</span>
        <EffectsEditor :model-value="skill.effects" @update:model-value="patchEffects(index, 'effects', $event)" />
      </div>
      <div class="upgraded">
        <div class="upgraded-head">
          <n-switch :value="skill.upgraded" size="small" @update:value="patch(index, 'upgraded', $event)" />
          <span class="dim">升级形态（数值更强，ID 与基础一致）</span>
          <n-button
            v-if="skill.upgraded"
            size="tiny"
            quaternary
            @click="openUpgraded[index] = !openUpgraded[index]"
          >
            {{ openUpgraded[index] ? '收起' : '编辑' }}
          </n-button>
        </div>
        <div v-if="skill.upgraded && openUpgraded[index]" class="skill-grid">
          <label>名称</label>
          <n-input :value="skill.upgradedName" size="small" placeholder="留空继承基础名" @update:value="patch(index, 'upgradedName', $event)" />
          <label>能量消耗</label>
          <n-input-number :value="skill.upgradedEnergyCost" size="small" style="width: 120px" @update:value="patch(index, 'upgradedEnergyCost', $event)" />
          <label>冷却回合</label>
          <n-input-number :value="skill.upgradedCooldown" size="small" style="width: 120px" @update:value="patch(index, 'upgradedCooldown', $event)" />
          <label>描述</label>
          <n-input :value="skill.upgradedDescription" size="small" placeholder="留空继承基础描述" @update:value="patch(index, 'upgradedDescription', $event)" />
        </div>
        <div v-if="skill.upgraded && openUpgraded[index]" class="skill-effects">
          <span class="dim field-label">升级效果</span>
          <EffectsEditor
            :model-value="skill.upgradedEffects"
            @update:model-value="patchEffects(index, 'upgradedEffects', $event)"
          />
        </div>
      </div>
    </div>
    <n-button size="tiny" dashed @click="add">+ 添加技能</n-button>
  </div>
</template>

<style scoped>
.skills-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skill-row {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg);
}

.skill-row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.skill-index {
  font-size: 12px;
}

.skill-grid {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 6px 10px;
  align-items: center;
}

.skill-grid label {
  font-size: 12px;
  color: var(--text-dim);
}

.skill-effects {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
}

.upgraded {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.upgraded-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
</style>
