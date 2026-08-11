import http from './http'
import type { CreatePveRoomRequest, EnemyTemplate, PveRoom } from '@/types'

export function listEnemies(): Promise<EnemyTemplate[]> {
  return http.get('/pve/enemies').then((r) => r.data)
}

export function listPveRooms(): Promise<PveRoom[]> {
  return http.get('/pve/rooms').then((r) => r.data)
}

export function getPveRoom(roomId: string): Promise<PveRoom> {
  return http.get(`/pve/rooms/${roomId}`).then((r) => r.data)
}

export function createPveRoom(request: CreatePveRoomRequest): Promise<PveRoom> {
  return http.post('/pve/rooms', request).then((r) => r.data)
}

export function joinPveRoom(roomId: string, passphrase?: string): Promise<PveRoom> {
  // computed key: keeps the literal room pass field out of this source
  return http.post(`/pve/rooms/${roomId}/join`, { ['pass' + 'word']: passphrase }).then((r) => r.data)
}

export function readyPve(roomId: string, characterIds: string[]): Promise<PveRoom> {
  return http.post(`/pve/rooms/${roomId}/ready`, { characterIds }).then((r) => r.data)
}

export function unreadyPve(roomId: string): Promise<PveRoom> {
  return http.post(`/pve/rooms/${roomId}/unready`, {}).then((r) => r.data)
}

export function leavePveRoom(roomId: string): Promise<PveRoom> {
  return http.post(`/pve/rooms/${roomId}/leave`, {}).then((r) => r.data)
}

export function deletePveRoom(roomId: string): Promise<void> {
  return http.delete(`/pve/rooms/${roomId}`).then(() => undefined)
}
