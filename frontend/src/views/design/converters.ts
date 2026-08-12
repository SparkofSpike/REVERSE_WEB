import { jsonToFormValue, type EffectFormState } from './form'

// All form-state types and JSON <-> form converters live here (plain .ts, so
// the SFC components below can stay free of ES module exports, which
// <script setup> forbids).

// ---------- effects ----------

export function jsonToEffect(raw: Record<string, unknown>): EffectFormState {
  return {
    type: String(raw.type ?? 'damage'),
    dice: raw.dice == null ? '' : String(raw.dice),
    amount: raw.amount == null ? '' : String(raw.amount),
    ratio: raw.ratio == null ? '' : String(raw.ratio),
    duration: raw.duration == null ? '' : String(raw.duration),
    count: raw.count == null ? '' : String(raw.count),
    max: raw.max == null ? '' : String(raw.max),
    damageType: raw.damageType == null ? '' : String(raw.damageType),
    target: raw.target == null ? '' : String(raw.target),
    interval: raw.interval == null ? '' : String(raw.interval)
  }
}

export function effectToJson(effect: EffectFormState): Record<string, unknown> | null {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(effect)) {
    if (typeof v === 'string') {
      if (v.trim() !== '') out[k] = v.trim()
    } else if (v !== null && v !== undefined) {
      out[k] = v
    }
  }
  // drop an all-empty row (type/target defaults are always present)
  return Object.keys(out).length > 2 ? out : null
}

// ---------- skills ----------

export interface SkillFormState {
  id: string
  name: string
  energyCost: number | null
  cooldown: number | null
  targetType: string
  description: string
  upgraded: boolean
  upgradedName: string
  upgradedEnergyCost: number | null
  upgradedCooldown: number | null
  upgradedDescription: string
  effects: EffectFormState[]
  upgradedEffects: EffectFormState[]
}

export function emptySkill(): SkillFormState {
  return {
    id: '',
    name: '',
    energyCost: null,
    cooldown: null,
    targetType: 'enemy',
    description: '',
    upgraded: false,
    upgradedName: '',
    upgradedEnergyCost: null,
    upgradedCooldown: null,
    upgradedDescription: '',
    effects: [],
    upgradedEffects: []
  }
}

export function jsonToSkill(raw: Record<string, unknown>): SkillFormState {
  const upgraded = (raw.upgraded as Record<string, unknown> | null | undefined) ?? null
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    energyCost: raw.energyCost == null ? null : Number(raw.energyCost),
    cooldown: raw.cooldown == null ? null : Number(raw.cooldown),
    targetType: String(raw.targetType ?? 'enemy'),
    description: String(raw.description ?? ''),
    upgraded: !!upgraded,
    upgradedName: upgraded ? String(upgraded.name ?? '') : '',
    upgradedEnergyCost: upgraded?.energyCost == null ? null : Number(upgraded.energyCost),
    upgradedCooldown: upgraded?.cooldown == null ? null : Number(upgraded.cooldown),
    upgradedDescription: upgraded ? String(upgraded.description ?? '') : '',
    effects: ((raw.effects as unknown[]) ?? []).map((e) => jsonToEffect(e as Record<string, unknown>)),
    upgradedEffects: upgraded
      ? ((upgraded.effects as unknown[]) ?? []).map((e) => jsonToEffect(e as Record<string, unknown>))
      : []
  }
}

export function skillToJson(skill: SkillFormState): Record<string, unknown> {
  const base: Record<string, unknown> = {
    id: skill.id.trim(),
    name: skill.name.trim(),
    energyCost: skill.energyCost,
    cooldown: skill.cooldown,
    targetType: skill.targetType,
    description: skill.description.trim(),
    effects: skill.effects.map(effectToJson).filter(Boolean)
  }
  if (skill.upgraded) {
    const upgraded: Record<string, unknown> = {
      id: base.id,
      name: skill.upgradedName.trim() || base.name,
      energyCost: skill.upgradedEnergyCost ?? base.energyCost,
      cooldown: skill.upgradedCooldown ?? base.cooldown,
      targetType: base.targetType,
      description: skill.upgradedDescription.trim() || base.description,
      effects: skill.upgradedEffects.map(effectToJson).filter(Boolean)
    }
    base.upgraded = upgraded
  }
  return base
}

// ---------- perks ----------

export interface PerkFormState {
  id: string
  name: string
  description: string
  roundRequirement: number | null
  effect: EffectFormState
}

export function emptyPerk(): PerkFormState {
  return {
    id: '',
    name: '',
    description: '',
    roundRequirement: null,
    effect: jsonToEffect({ type: 'damage', target: 'enemy' })
  }
}

export function jsonToPerk(raw: Record<string, unknown>): PerkFormState {
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    description: String(raw.description ?? ''),
    roundRequirement: raw.roundRequirement == null ? null : Number(raw.roundRequirement),
    effect: raw.effect
      ? jsonToEffect(raw.effect as Record<string, unknown>)
      : jsonToEffect({ type: 'damage', target: 'enemy' })
  }
}

export function perkToJson(perk: PerkFormState): Record<string, unknown> {
  const effect = effectToJson(perk.effect)
  const out: Record<string, unknown> = {
    id: perk.id.trim(),
    name: perk.name.trim(),
    description: perk.description.trim(),
    roundRequirement: perk.roundRequirement
  }
  if (effect) {
    out.effect = effect
  }
  return out
}

// ---------- generic skills ----------

export interface GenericSkillFormState {
  id: string
  name: string
  consumed: boolean
  description: string
  effects: EffectFormState[]
}

export function emptyGenericSkill(): GenericSkillFormState {
  return { id: '', name: '', consumed: false, description: '', effects: [] }
}

export function jsonToGenericSkill(raw: Record<string, unknown>): GenericSkillFormState {
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    consumed: !!raw.consumed,
    description: String(raw.description ?? ''),
    effects: ((raw.effects as unknown[]) ?? []).map((e) => jsonToEffect(e as Record<string, unknown>))
  }
}

export function genericSkillToJson(skill: GenericSkillFormState): Record<string, unknown> {
  return {
    id: skill.id.trim(),
    name: skill.name.trim(),
    consumed: skill.consumed,
    description: skill.description.trim(),
    effects: skill.effects.map(effectToJson).filter(Boolean)
  }
}

// ---------- enemy ----------

export interface EnemyFormState {
  id: string
  name: string
  maxHp: string
  maxEnergy: string
  speedDice: string
  physicalResistance: string
  magicResistance: string
  baseDamageDice: string
  baseDamageType: string
  blockDice: string
  dodgePenalty: string
  baseActions: string[]
}

export function emptyEnemy(): EnemyFormState {
  return {
    id: '',
    name: '',
    maxHp: '',
    maxEnergy: '',
    speedDice: '',
    physicalResistance: '',
    magicResistance: '',
    baseDamageDice: '',
    baseDamageType: 'PHYSICAL',
    blockDice: '',
    dodgePenalty: '',
    baseActions: ['ATTACK']
  }
}

export function jsonToEnemy(raw: Record<string, unknown>): EnemyFormState {
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    maxHp: raw.maxHp == null ? '' : String(raw.maxHp),
    maxEnergy: raw.maxEnergy == null ? '' : String(raw.maxEnergy),
    speedDice: String(raw.speedDice ?? ''),
    physicalResistance: raw.physicalResistance == null ? '' : String(raw.physicalResistance),
    magicResistance: raw.magicResistance == null ? '' : String(raw.magicResistance),
    baseDamageDice: String(raw.baseDamageDice ?? ''),
    baseDamageType: String(raw.baseDamageType ?? 'PHYSICAL'),
    blockDice: String(raw.blockDice ?? ''),
    dodgePenalty: String(raw.dodgePenalty ?? ''),
    baseActions: ((raw.baseActions as string[]) ?? []).map(String)
  }
}

export function enemyToJson(state: EnemyFormState): Record<string, unknown> {
  return {
    id: state.id.trim(),
    name: state.name.trim(),
    maxHp: Number(state.maxHp),
    maxEnergy: Number(state.maxEnergy),
    speedDice: state.speedDice.trim(),
    physicalResistance: Number(state.physicalResistance),
    magicResistance: Number(state.magicResistance),
    baseDamageDice: state.baseDamageDice.trim(),
    baseDamageType: state.baseDamageType,
    blockDice: state.blockDice.trim(),
    dodgePenalty: state.dodgePenalty.trim(),
    baseActions: state.baseActions
  }
}

// ---------- character ----------

export interface PassiveFormState {
  enabled: boolean
  type: string
  amount: string
  ratio: string
  dice: string
  duration: string
  description: string
}

export interface PerformanceFormState {
  enabled: boolean
  triggerType: string
  threshold: string
  description: string
  rewardDescription: string
  effects: EffectFormState[]
}

export interface CharacterFormState {
  id: string
  name: string
  description: string
  maxHp: string
  maxEnergy: string
  speedDice: string
  physicalResistance: string
  magicResistance: string
  baseDamageDice: string
  baseDamageType: string
  blockDice: string
  dodgePenalty: string
  baseActions: string[]
  corePassive: PassiveFormState
  performance: PerformanceFormState
  skills: SkillFormState[]
}

export function emptyCharacter(): CharacterFormState {
  return {
    id: '',
    name: '',
    description: '',
    maxHp: '',
    maxEnergy: '',
    speedDice: '',
    physicalResistance: '',
    magicResistance: '',
    baseDamageDice: '',
    baseDamageType: 'PHYSICAL',
    blockDice: '',
    dodgePenalty: '',
    baseActions: ['ATTACK'],
    corePassive: { enabled: false, type: 'undying', amount: '', ratio: '', dice: '', duration: '', description: '' },
    performance: {
      enabled: false,
      triggerType: 'hp_below',
      threshold: '',
      description: '',
      rewardDescription: '',
      effects: []
    },
    skills: []
  }
}

export function jsonToCharacter(raw: Record<string, unknown>): CharacterFormState {
  const passive = (raw.corePassive as Record<string, unknown> | null | undefined) ?? null
  const performance = (raw.performance as Record<string, unknown> | null | undefined) ?? null
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    description: String(raw.description ?? ''),
    maxHp: raw.maxHp == null ? '' : String(raw.maxHp),
    maxEnergy: raw.maxEnergy == null ? '' : String(raw.maxEnergy),
    speedDice: String(raw.speedDice ?? ''),
    physicalResistance: raw.physicalResistance == null ? '' : String(raw.physicalResistance),
    magicResistance: raw.magicResistance == null ? '' : String(raw.magicResistance),
    baseDamageDice: String(raw.baseDamageDice ?? ''),
    baseDamageType: String(raw.baseDamageType ?? 'PHYSICAL'),
    blockDice: String(raw.blockDice ?? ''),
    dodgePenalty: String(raw.dodgePenalty ?? ''),
    baseActions: ((raw.baseActions as string[]) ?? []).map(String),
    corePassive: {
      enabled: !!passive,
      type: String(passive?.type ?? 'undying'),
      amount: passive?.amount == null ? '' : String(passive.amount),
      ratio: passive?.ratio == null ? '' : String(passive.ratio),
      dice: passive?.dice == null ? '' : String(passive.dice),
      duration: passive?.duration == null ? '' : String(passive.duration),
      description: String(passive?.description ?? '')
    },
    performance: {
      enabled: !!performance,
      triggerType: String(performance?.triggerType ?? 'hp_below'),
      threshold: performance?.threshold == null ? '' : String(performance.threshold),
      description: String(performance?.description ?? ''),
      rewardDescription: String(performance?.rewardDescription ?? ''),
      effects: performance
        ? ((performance.effects as unknown[]) ?? []).map((e) => jsonToEffect(e as Record<string, unknown>))
        : []
    },
    skills: ((raw.skills as unknown[]) ?? []).map((s) => jsonToSkill(s as Record<string, unknown>))
  }
}

export function characterToJson(state: CharacterFormState): Record<string, unknown> {
  const out: Record<string, unknown> = {
    id: state.id.trim(),
    name: state.name.trim(),
    description: state.description.trim(),
    maxHp: Number(state.maxHp),
    maxEnergy: Number(state.maxEnergy),
    speedDice: state.speedDice.trim(),
    physicalResistance: Number(state.physicalResistance),
    magicResistance: Number(state.magicResistance),
    baseDamageDice: state.baseDamageDice.trim(),
    baseDamageType: state.baseDamageType,
    blockDice: state.blockDice.trim(),
    dodgePenalty: state.dodgePenalty.trim(),
    baseActions: state.baseActions,
    skills: state.skills.map(skillToJson)
  }
  if (state.corePassive.enabled) {
    const passive: Record<string, unknown> = { type: state.corePassive.type }
    for (const [k, v] of Object.entries({
      amount: state.corePassive.amount,
      ratio: state.corePassive.ratio,
      dice: state.corePassive.dice,
      duration: state.corePassive.duration,
      description: state.corePassive.description
    })) {
      if (typeof v === 'string' && v.trim() !== '') passive[k] = v.trim()
    }
    out.corePassive = passive
  }
  if (state.performance.enabled) {
    const performance: Record<string, unknown> = {
      triggerType: state.performance.triggerType,
      threshold: Number(state.performance.threshold),
      description: state.performance.description.trim(),
      effects: state.performance.effects.map(effectToJson).filter(Boolean)
    }
    if (state.performance.rewardDescription.trim()) {
      performance.rewardDescription = state.performance.rewardDescription.trim()
    }
    out.performance = performance
  }
  return out
}

// ---------- pack ----------

export interface PackFormState {
  id: string
  name: string
  coreId: string
  coreName: string
  initialPerks: PerkFormState[]
  specialPerks: PerkFormState[]
  genericSkills: GenericSkillFormState[]
  characterNames: string[]
  rawCharacters: unknown[]
}

export function emptyPack(): PackFormState {
  return {
    id: '',
    name: '',
    coreId: '',
    coreName: '',
    initialPerks: [],
    specialPerks: [],
    genericSkills: [],
    characterNames: [],
    rawCharacters: []
  }
}

export function jsonToPack(raw: Record<string, unknown>): PackFormState {
  const core = (raw.core as Record<string, unknown> | null | undefined) ?? null
  const characters = (raw.characters as unknown[] | null | undefined) ?? []
  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    coreId: String(core?.id ?? ''),
    coreName: String(core?.name ?? ''),
    initialPerks: ((raw.initialPerks as unknown[]) ?? []).map((p) => jsonToPerk(p as Record<string, unknown>)),
    specialPerks: ((raw.specialPerks as unknown[]) ?? []).map((p) => jsonToPerk(p as Record<string, unknown>)),
    genericSkills: ((raw.genericSkills as unknown[]) ?? []).map((s) => jsonToGenericSkill(s as Record<string, unknown>)),
    characterNames: characters.map(
      (c) => String((c as Record<string, unknown>).name ?? (c as Record<string, unknown>).id ?? '')
    ),
    rawCharacters: characters
  }
}

export function packToJson(state: PackFormState): Record<string, unknown> {
  return {
    id: state.id.trim(),
    name: state.name.trim(),
    core: { id: state.coreId.trim(), name: state.coreName.trim() },
    initialPerks: state.initialPerks.map(perkToJson),
    specialPerks: state.specialPerks.map(perkToJson),
    genericSkills: state.genericSkills.map(genericSkillToJson),
    characters: state.rawCharacters
  }
}

// re-export the generic value helpers used by DesignView
export { jsonToFormValue }
