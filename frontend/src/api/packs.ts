import http from './http'
import type { CardPack, PuppetTemplate } from '@/types'

export function listPacks(): Promise<CardPack[]> {
  return http.get('/packs').then((r) => r.data)
}

export function getPuppet(): Promise<PuppetTemplate> {
  return http.get('/packs/puppet').then((r) => r.data)
}
