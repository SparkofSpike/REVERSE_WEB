// 与后端 DTO 严格对齐的类型契约

export interface AuthResponse {
  token: string
  username: string
}

export interface ApiError {
  timestamp: string
  status: number
  error: string
  message: string
}

// ---------- card pack ----------

export interface EffectSpec {
  type?: string
  dice?: string
  amount?: number
  ratio?: number
  duration?: number
  count?: number
  max?: number
  damageType?: string
  target?: string
  interval?: number
}

export interface SkillTemplate {
  id: string
  name: string
  energyCost: number
  cooldown: number
  targetType: string
  effects: EffectSpec[]
  description: string
  upgraded?: SkillTemplate | null
}

export interface PassiveSpec {
  type: string
  amount: number
  ratio: number
  dice?: string
  duration: number
  description: string
}

export interface PerformanceSpec {
  triggerType: string
  threshold: number
  description: string
  rewardDescription?: string
  effects: EffectSpec[]
}

export interface CharacterTemplate {
  id: string
  name: string
  description: string
  maxHp: number
  maxEnergy: number
  speedDice: string
  physicalResistance: number
  magicResistance: number
  baseDamageDice: string
  baseDamageType: string
  blockDice: string
  dodgePenalty: string
  baseActions: string[]
  corePassive?: PassiveSpec | null
  performance?: PerformanceSpec | null
  skills: SkillTemplate[]
}

export interface Perk {
  id: string
  name: string
  description: string
  effect: EffectSpec
  roundRequirement: number
}

export interface GenericSkillTemplate {
  id: string
  name: string
  consumed: boolean
  effects: EffectSpec[]
  description: string
}

export interface Core {
  id: string
  name: string
}

export interface CardPack {
  id: string
  name: string
  core: Core
  initialPerks: Perk[]
  specialPerks: Perk[]
  genericSkills: GenericSkillTemplate[]
  characters: CharacterTemplate[]
}

export interface PuppetTemplate {
  id: string
  name: string
  maxHp: number
  maxEnergy: number
  speedDice: string
  physicalResistance: number
  magicResistance: number
  baseDamageDice: string
  baseDamageType: string
  blockDice: string
  dodgePenalty: string
  baseActions: string[]
}

// ---------- build ----------

export interface Build {
  id: number
  name: string
  packId: string
  characterIds: string[]
  initialPerkId: string | null
  createdAt: string
  updatedAt: string
}

export interface BuildRequest {
  name: string
  packId: string
  characterIds: string[]
  initialPerkId?: string | null
}

// ---------- combat ----------

export interface CombatEvent {
  round: number
  type: string
  message: string
  data: Record<string, unknown>
}

export interface CombatantView {
  id: string
  templateId: string
  name: string
  side: 'PLAYER' | 'ENEMY'
  hp: number
  maxHp: number
  energy: number
  maxEnergy: number
  shield: number
  shieldRemainingRounds: number
  dead: boolean
  performing: boolean
  skillsUpgraded: boolean
  dodging: boolean
  guardSuccessCount: number
  totalHealGiven: number
  guardTargetId: string | null
  permanentExtraAction: boolean
  undyingUsed: boolean
  undyingRounds: number
  speedDice: string
  permanentSpeedBonus: number
  physicalResistance: number
  magicResistance: number
  baseDamageDice: string
  baseDamageType: string
  blockDice: string
  dodgePenalty: string
  baseActions: string[]
  skills: SkillView[]
  corePassiveName: string | null
  corePassiveDescription: string | null
  performance: PerformanceSpec | null
  statusEffects: StatusEffect[]
  cooldowns: Record<string, number>
  bonusDamage: number
}

export interface SkillView {
  id: string
  name: string
  energyCost: number
  cooldown: number
  targetType: string
  description: string
  upgraded: boolean
  effects: EffectSpec[]
}

export interface StatusEffect {
  type: string
  remainingRounds: number
  duration: number
  ratio: number
  dice?: string
  amount: number
  count: number
  max: number
  ownerId?: string
}

export interface CombatView {
  id: string
  ownerUsername: string
  phase:
    | 'SETUP'
    | 'INITIAL_PERK'
    | 'ROUND_START'
    | 'DECISION'
    | 'SPEED'
    | 'EXECUTION'
    | 'SPECIAL_PERK'
    | 'ROUND_END'
    | 'FINISHED'
  round: number
  winner: 'PLAYER' | 'ENEMY' | null
  firstStrikeSide: 0 | 1 | null
  playerDrawEnergy: number
  playerHand: GenericSkillTemplate[]
  initialPerkOptions: Perk[]
  specialPerkOptions: Perk[]
  specialPerkRoundsTaken: number
  combatants: CombatantView[]
  logs: CombatEvent[]
}

export interface ActionDecision {
  combatantId: string
  actionType: string
  skillId?: string | null
  targetId?: string | null
}

// ---------- battle record ----------

export interface BattleRecordSummary {
  id: number
  battleId: string
  packId: string
  winner: 'PLAYER' | 'ENEMY'
  rounds: number
  playerCharacterIds: string[]
  totalDamageDealt: number
  maxSingleHit: number
  avgDamagePerRound: number
  createdAt: string
}

export interface BattleRecordDetail extends BattleRecordSummary {
  logJson: string
  logs: CombatEvent[]
}
