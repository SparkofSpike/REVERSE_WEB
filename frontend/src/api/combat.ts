import http from './http'
import type { ActionDecision, CombatView } from '@/types'

export function createDummyBattle(packId: string, characterIds: string[]): Promise<CombatView> {
  return http.post('/combat/dummy', { packId, characterIds }).then((r) => r.data)
}

export function getBattle(battleId: string): Promise<CombatView> {
  return http.get(`/combat/${battleId}`).then((r) => r.data)
}

export function selectInitialPerk(battleId: string, perkId: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/initial-perk`, { perkId }).then((r) => r.data)
}

export function decide(battleId: string, decisions: ActionDecision[]): Promise<CombatView> {
  return http.post(`/combat/${battleId}/decide`, decisions).then((r) => r.data)
}

export function decideExtraActions(battleId: string, decisions: ActionDecision[]): Promise<CombatView> {
  return http.post(`/combat/${battleId}/extra-decide`, decisions).then((r) => r.data)
}

export function skipExtraActions(battleId: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/skip-extra`, {}).then((r) => r.data)
}

export function playCard(battleId: string, skillId: string, targetId?: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/card`, { skillId, targetId }).then((r) => r.data)
}

export function selectSpecialPerk(battleId: string, perkId: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/special-perk`, { perkId }).then((r) => r.data)
}

export function skipSpecialPerk(battleId: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/skip-perk`, {}).then((r) => r.data)
}

/** Surrender a battle: PVP ends the match with a loss; PVE pulls the
 *  caller's characters out of the fight while teammates continue. */
export function surrender(battleId: string): Promise<CombatView> {
  return http.post(`/combat/${battleId}/surrender`, {}).then((r) => r.data)
}

/** Report a selected-but-not-submitted decision draft: on timeout the
 *  backend auto-submits the draft (no AI stand-in). */
export function saveDraft(battleId: string, decisions: ActionDecision[]): Promise<CombatView> {
  return http.post(`/combat/${battleId}/draft`, decisions).then((r) => r.data)
}
