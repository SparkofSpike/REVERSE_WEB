import http from './http'
import type { DesignEntry } from '@/types'

// Definitions are edited as raw JSON text; the server validates the
// structure before persisting to the data dir.

export function listPacks(): Promise<DesignEntry[]> {
  return http.get('/design/packs').then((r) => r.data)
}

export function getPack(id: string): Promise<unknown> {
  return http.get(`/design/packs/${id}`).then((r) => r.data)
}

export function createPack(json: string): Promise<unknown> {
  return http.post('/design/packs', JSON.parse(json)).then((r) => r.data)
}

export function updatePack(id: string, json: string): Promise<unknown> {
  return http.put(`/design/packs/${id}`, JSON.parse(json)).then((r) => r.data)
}

export function deletePack(id: string): Promise<{ ok: boolean }> {
  return http.delete(`/design/packs/${id}`).then((r) => r.data)
}

export function listEnemies(): Promise<DesignEntry[]> {
  return http.get('/design/enemies').then((r) => r.data)
}

export function getEnemy(id: string): Promise<unknown> {
  return http.get(`/design/enemies/${id}`).then((r) => r.data)
}

export function createEnemy(json: string): Promise<unknown> {
  return http.post('/design/enemies', JSON.parse(json)).then((r) => r.data)
}

export function updateEnemy(id: string, json: string): Promise<unknown> {
  return http.put(`/design/enemies/${id}`, JSON.parse(json)).then((r) => r.data)
}

export function deleteEnemy(id: string): Promise<{ ok: boolean }> {
  return http.delete(`/design/enemies/${id}`).then((r) => r.data)
}

export function listCharacters(packId: string): Promise<DesignEntry[]> {
  return http.get(`/design/packs/${packId}/characters`).then((r) => r.data)
}

export function addCharacter(packId: string, json: string): Promise<unknown> {
  return http.post(`/design/packs/${packId}/characters`, JSON.parse(json)).then((r) => r.data)
}

export function updateCharacter(
  packId: string,
  characterId: string,
  json: string
): Promise<unknown> {
  return http
    .put(`/design/packs/${packId}/characters/${characterId}`, JSON.parse(json))
    .then((r) => r.data)
}

export function deleteCharacter(packId: string, characterId: string): Promise<{ ok: boolean }> {
  return http.delete(`/design/packs/${packId}/characters/${characterId}`).then((r) => r.data)
}
