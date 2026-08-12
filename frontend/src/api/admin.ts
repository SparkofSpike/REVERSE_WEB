import http from './http'
import type { AdminUser } from '@/types'

export function listUsers(): Promise<AdminUser[]> {
  return http.get('/admin/users').then((r) => r.data)
}

export function setUserRole(id: number, role: 'USER' | 'ADMIN'): Promise<AdminUser> {
  return http.patch(`/admin/users/${id}/role`, { role }).then((r) => r.data)
}

export function setUserEnabled(id: number, enabled: boolean): Promise<AdminUser> {
  return http.patch(`/admin/users/${id}/enabled`, { enabled }).then((r) => r.data)
}
