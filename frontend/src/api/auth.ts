import http from './http'
import type { AuthResponse, UserProfile } from '@/types'

export function register(username: string, password: string): Promise<AuthResponse> {
  return http.post('/auth/register', { username, password }).then((r) => r.data)
}

export function login(username: string, password: string): Promise<AuthResponse> {
  return http.post('/auth/login', { username, password }).then((r) => r.data)
}

export function me(): Promise<UserProfile> {
  return http.get('/auth/me').then((r) => r.data)
}

export function updateProfile(nickname: string): Promise<{ nickname: string | null }> {
  return http.put('/auth/profile', { nickname }).then((r) => r.data)
}

export function changePassword(oldPassword: string, newPassword: string): Promise<{ ok: boolean }> {
  return http.put('/auth/password', { oldPassword, newPassword }).then((r) => r.data)
}

export function uploadAvatar(file: File): Promise<{ avatarUrl: string }> {
  const form = new FormData()
  form.append('file', file)
  return http.put('/auth/avatar', form).then((r) => r.data)
}
