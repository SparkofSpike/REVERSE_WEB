import http from './http'
import type { AuthResponse } from '@/types'

export function register(username: string, password: string): Promise<AuthResponse> {
  return http.post('/auth/register', { username, password }).then((r) => r.data)
}

export function login(username: string, password: string): Promise<AuthResponse> {
  return http.post('/auth/login', { username, password }).then((r) => r.data)
}

export function me(): Promise<{ id: number; username: string; createdAt: string }> {
  return http.get('/auth/me').then((r) => r.data)
}
