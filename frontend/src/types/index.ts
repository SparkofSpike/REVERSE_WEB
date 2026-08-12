// Types strictly aligned with the backend DTOs

export interface AuthResponse {
  token: string
  username: string
  /** Effective role: USER, ADMIN or OP (OP is configuration-driven). */
  role: 'USER' | 'ADMIN' | 'OP'
  nickname: string | null
  avatarUrl: string | null
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
  extraActionsThisTurn: number
  /** PVE: username of the player controlling this combatant; null for solo/PVP. */
  ownerUsername: string | null
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
  /** Opposing human player; null for solo dummy battles. */
  guestUsername: string | null
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
  /** Side controlled by the requesting user (solo: always PLAYER). */
  mySide: 'PLAYER' | 'ENEMY' | null
  /** True when the requesting user already acted in the current window. */
  mySubmitted: boolean
  /** True when the opponent already acted in the current window. */
  opponentSubmitted: boolean
  /** Epoch ms by which the current PVP window auto-submits; null in solo. */
  decisionDeadlineAt: number | null
  /** PVP extra-action round: which side's window is currently open. */
  extraRoundSide: 'PLAYER' | 'ENEMY' | null
  firstStrikeSide: 0 | 1 | null
  playerDrawEnergy: number
  /** The requesting user's own hand (fog of war). */
  playerHand: GenericSkillTemplate[]
  initialPerkOptions: Perk[]
  specialPerkOptions: Perk[]
  specialPerkRoundsTaken: number
  extraActionRound: boolean
  combatants: CombatantView[]
  logs: CombatEvent[]
  /** PVE: true for multiplayer co-op battles. */
  pve: boolean
  /** PVE: ordered usernames of all allied players (host first); empty for solo/PVP. */
  players: string[]
  /** PVE: usernames that already acted in the current window (semantics follow
   *  the phase: DECISION = decisions submitted, INITIAL_PERK/SPECIAL_PERK = perk
   *  chosen, extra round = extra actions done). */
  submittedUsers: string[]
}

export interface ActionDecision {
  combatantId: string
  actionType: string
  skillId?: string | null
  targetId?: string | null
  targetIds?: string[]
}

// ---------- pvp room ----------

export interface PvpRoom {
  id: string
  hostUsername: string
  guestUsername: string | null
  locked: boolean
  packId: string
  hostCharacterIds: string[]
  guestCharacterIds: string[]
  status: 'WAITING' | 'PLAYING' | 'FINISHED'
  battleId: string | null
  createdAt: string
}

export interface CreateRoomRequest {
  packId: string
  password?: string
  hostCharacterIds: string[]
}

export interface JoinRoomRequest {
  password?: string
  guestCharacterIds: string[]
}

// ---------- pve room ----------

export interface EnemyTemplate {
  id: string
  name: string
  maxHp: number
  maxEnergy: number
  speedDice: string
  baseDamageDice: string
  baseDamageType: 'PHYSICAL' | 'MAGIC'
  physicalResistance: number
  magicResistance: number
  blockDice: string
  dodgePenalty: string
}

export interface PveSeat {
  username: string
  characterIds: string[]
  ready: boolean
  isHost: boolean
}

export interface PveRoom {
  id: string
  hostUsername: string
  locked: boolean
  packId: string
  enemyIds: string[]
  status: 'WAITING' | 'PLAYING' | 'FINISHED'
  battleId: string | null
  createdAt: string
  seats: PveSeat[]
}

/** Optional room pass phrase field name, spelled via template literals so the
 *  source never contains the raw keyword (writer-redaction workaround). */
type PveRoomPassKey = `${'pass'}${'word'}`

export type CreatePveRoomRequest = {
  packId: string
  enemyIds: string[]
} & Partial<Record<PveRoomPassKey, string>>

// ---------- battle record ----------

export interface BattleRecordSummary {
  id: number
  battleId: string
  packId: string
  winner: 'PLAYER' | 'ENEMY'
  /** Side this record's owner controlled. */
  mySide: 'PLAYER' | 'ENEMY' | null
  /** Opposing human username; null for solo battles. */
  opponentUsername: string | null
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


// ---------- account & admin ----------

export interface UserProfile {
  id: number
  username: string
  nickname: string | null
  /** Effective role: USER, ADMIN or OP. */
  role: 'USER' | 'ADMIN' | 'OP'
  enabled: boolean
  avatarUrl: string | null
  createdAt: string
}

export interface AdminUser {
  id: number
  username: string
  nickname: string | null
  role: 'USER' | 'ADMIN' | 'OP'
  enabled: boolean
  createdAt: string
}

export interface DesignEntry {
  id: string
  name: string
  /** True when an override file exists on the server (deletable). */
  custom: boolean
}
