// Shared form-state types and option lists for the design editor forms.
// All values come from the backend model (see backend combat/ enums and
// EffectExecutor); keep them in sync when the backend gains new kinds.

export const ACTION_TYPES = ['ATTACK', 'DEFEND', 'DODGE', 'GUARD', 'COUNTER', 'CHASE', 'PRAY'] as const

export const DAMAGE_TYPES = ['PHYSICAL', 'MAGIC', 'BREAK', 'PIERCE'] as const

export const TARGETS = ['self', 'ally', 'allies', 'enemy', 'enemies', 'random_ally'] as const

export const SKILL_TARGETS = ['enemy', 'enemies', 'ally', 'allies', 'self'] as const

export const EFFECT_TYPES = [
  'damage',
  'heal',
  'shield',
  'energy',
  'draw',
  'draw_boost',
  'draw_energy',
  'draw_over_time',
  'extra_actions',
  'extra_guard',
  'extra_defend',
  'extra_skill',
  'lifesteal',
  'damage_bonus',
  'speed_boost',
  'speed_permanent',
  'heal_over_time',
  'shield_over_time',
  'energy_over_time',
  'max_hp_bonus',
  'periodic_energy',
  'decaying_shield',
  'hp_cost',
  'puppet',
  'peek',
  'accelerate',
  'guard_bind',
  'sacrifice_buff',
  'upgrade_skills',
  'bleed',
  'bloodletting',
  'collapse',
  'stun',
  'heal_end_of_round'
] as const

export const PASSIVE_TYPES = [
  'compassion_heal',
  'dodge_training',
  'energy_discount',
  'stone_shield',
  'undying'
] as const

export const TRIGGER_TYPES = ['hp_below', 'energy_below', 'heal_total', 'guard_success', 'ally_death'] as const

// which fields an effect shows depends on its type
export const EFFECT_FIELDS: Record<string, string[]> = {
  damage: ['dice', 'damageType', 'count'],
  heal: ['dice', 'amount', 'count'],
  shield: ['dice', 'amount', 'duration'],
  energy: ['dice', 'amount'],
  draw: ['count'],
  draw_boost: [],
  draw_energy: ['amount'],
  draw_over_time: ['dice', 'duration', 'count', 'max'],
  extra_actions: ['count', 'duration'],
  extra_guard: ['count', 'duration'],
  extra_defend: ['count', 'duration'],
  extra_skill: ['count'],
  lifesteal: ['ratio', 'duration'],
  damage_bonus: ['dice'],
  speed_boost: ['amount'],
  speed_permanent: ['dice'],
  heal_over_time: ['dice', 'duration', 'count'],
  shield_over_time: ['amount', 'duration', 'count'],
  energy_over_time: ['dice', 'duration', 'count'],
  max_hp_bonus: ['amount'],
  periodic_energy: ['amount', 'interval'],
  decaying_shield: ['amount', 'count', 'duration'],
  hp_cost: ['amount'],
  puppet: [],
  peek: [],
  accelerate: ['amount'],
  guard_bind: ['amount', 'duration'],
  sacrifice_buff: ['amount', 'count'],
  upgrade_skills: ['count'],
  bleed: ['count'],
  bloodletting: ['duration'],
  collapse: ['amount', 'count', 'duration'],
  stun: ['duration'],
  heal_end_of_round: ['dice']
}

// fields whose form value is a string but the JSON value must be a number
export const NUMERIC_FIELDS = new Set([
  'maxHp',
  'maxEnergy',
  'physicalResistance',
  'magicResistance',
  'energyCost',
  'cooldown',
  'roundRequirement',
  'amount',
  'ratio',
  'duration',
  'count',
  'max',
  'interval',
  'threshold'
])

export interface EffectFormState {
  type: string
  dice: string
  amount: string
  ratio: string
  duration: string
  count: string
  max: string
  damageType: string
  target: string
  interval: string
}

export function emptyEffect(): EffectFormState {
  return { type: 'damage', dice: '', amount: '', ratio: '', duration: '', count: '', max: '', damageType: 'PHYSICAL', target: 'enemy', interval: '' }
}

/** Converts a raw JSON value into a form-friendly value (numbers to strings). */
export function jsonToFormValue(value: unknown): unknown {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value)) return value.map(jsonToFormValue)
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = jsonToFormValue(v)
    }
    return out
  }
  if (typeof value === 'number') return String(value)
  return value
}

/** Drops empty strings / empty objects and converts numeric strings back. */
export function formToJsonValue(value: unknown): unknown {
  if (value === null || value === undefined) return undefined
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed === '') return undefined
    return trimmed
  }
  if (Array.isArray(value)) {
    // keep arrays as-is (empty arrays like skills/chracters are meaningful)
    return value.map(formToJsonValue)
  }
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      const cleaned = formToJsonValue(v)
      if (cleaned !== undefined) out[k] = cleaned
    }
    return Object.keys(out).length > 0 ? out : undefined
  }
  return value
}

/** Converts numeric-string fields back to numbers. */
export function coerceNumbers(value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(value)) {
    if (typeof v === 'string' && NUMERIC_FIELDS.has(k)) {
      const n = Number(v)
      out[k] = Number.isNaN(n) ? v : n
    } else if (Array.isArray(v)) {
      out[k] = v.map((item) =>
        item !== null && typeof item === 'object' && !Array.isArray(item)
          ? coerceNumbers(item as Record<string, unknown>)
          : item
      )
    } else if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      out[k] = coerceNumbers(v as Record<string, unknown>)
    } else {
      out[k] = v
    }
  }
  return out
}
