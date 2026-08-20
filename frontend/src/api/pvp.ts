import http from './http'
import type { CreateRoomRequest, JoinRoomRequest, PvpRoom } from '@/types'

export function listRooms(): Promise<PvpRoom[]> {
  return http.get('/pvp/rooms').then((r) => r.data)
}

export function getRoom(roomId: string): Promise<PvpRoom> {
  return http.get(`/pvp/rooms/${roomId}`).then((r) => r.data)
}

export function createRoom(request: CreateRoomRequest): Promise<PvpRoom> {
  return http.post('/pvp/rooms', request).then((r) => r.data)
}

export function joinRoom(roomId: string, request: JoinRoomRequest): Promise<PvpRoom> {
  return http.post(`/pvp/rooms/${roomId}/join`, request).then((r) => r.data)
}

export function startRoom(roomId: string): Promise<{ battleId: string }> {
  return http.post(`/pvp/rooms/${roomId}/start`, {}).then((r) => r.data)
}

export function deleteRoom(roomId: string): Promise<void> {
  return http.delete(`/pvp/rooms/${roomId}`).then(() => undefined)
}

/** Leave a waiting room as its guest. */
export function leaveRoom(roomId: string): Promise<PvpRoom> {
  return http.post(`/pvp/rooms/${roomId}/leave`, {}).then((r) => r.data)
}

/** Return the battle refresh event URL. */
export function battleEventsUrl(battleId: string): string {
  return `/api/pvp/events/${battleId}`
}
