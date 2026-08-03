import http from './http'
import type { Build, BuildRequest } from '@/types'

export function listBuilds(): Promise<Build[]> {
  return http.get('/builds').then((r) => r.data)
}

export function getBuild(id: number): Promise<Build> {
  return http.get(`/builds/${id}`).then((r) => r.data)
}

export function createBuild(request: BuildRequest): Promise<Build> {
  return http.post('/builds', request).then((r) => r.data)
}

export function updateBuild(id: number, request: BuildRequest): Promise<Build> {
  return http.put(`/builds/${id}`, request).then((r) => r.data)
}

export function deleteBuild(id: number): Promise<void> {
  return http.delete(`/builds/${id}`)
}
