import http from './http'
import type { BattleRecordDetail, BattleRecordSummary } from '@/types'

export function listRecords(): Promise<BattleRecordSummary[]> {
  return http.get('/records').then((r) => r.data)
}

export function getRecord(id: number): Promise<BattleRecordDetail> {
  return http.get(`/records/${id}`).then((r) => r.data)
}
