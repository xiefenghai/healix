import { api } from '../api/http'

export type DictItem = {
  dictCode: string
  dictCodeDesc: string
  content?: string
  sortOrder?: number
}

const cache = new Map<string, DictItem[]>()

export async function loadDictOptions(parentCode: string): Promise<DictItem[]> {
  const key = `OPTION:${parentCode}`
  if (cache.has(key)) return cache.get(key)!
  const res = await api<{ data: DictItem[] }>(
    `/api/c/v1/dict?dictType=OPTION&parentCode=${encodeURIComponent(parentCode)}`,
  )
  const items = (res.data ?? []).slice().sort((a, b) => (b.sortOrder ?? 0) - (a.sortOrder ?? 0))
  cache.set(key, items)
  return items
}

export async function loadDiseaseDict(): Promise<DictItem[]> {
  const key = 'DISEASE:0'
  if (cache.has(key)) return cache.get(key)!
  const res = await api<{ data: DictItem[] }>('/api/c/v1/dict?dictType=DISEASE&parentCode=0')
  const items = (res.data ?? []).slice().sort((a, b) => (b.sortOrder ?? 0) - (a.sortOrder ?? 0))
  cache.set(key, items)
  return items
}

export function dictLabel(items: DictItem[], code?: string | null, fallback = '—'): string {
  if (!code) return fallback
  return items.find((i) => i.dictCode === code)?.dictCodeDesc ?? code
}

export function dictLabels(items: DictItem[], codes?: string[] | null): string {
  if (!codes?.length) return '—'
  return codes.map((c) => dictLabel(items, c, c)).join('、')
}

export function toLabelMap(items: DictItem[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const i of items) out[i.dictCode] = i.dictCodeDesc
  return out
}
