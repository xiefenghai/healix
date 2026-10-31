import { api } from './http'

export interface GuidelineSourceView {
  code: string
  label: string
  name: string
  edition: string
  publisher: string | null
  status: string
  recCount: number
}

export interface GuidelineRecView {
  code: string
  rev: number
  sourceCode: string
  sourceLabel: string
  kind: 'SAFETY' | 'TARGET'
  block: 'exercise' | 'diet' | 'execution'
  audience: string
  staffText: string
  constraint: string | null
  evidence: string | null
  status: 'DRAFT' | 'REVIEWED' | 'RETIRED'
}

export interface GuidelineLibraryView {
  enabled: boolean
  includesDraft: boolean
  version: string
  sources: GuidelineSourceView[]
  recs: GuidelineRecView[]
  stats: { total: number; safety: number; target: number; byAudience: Record<string, number> }
  reviewers: Array<{ name: string; title: string | null; lastReviewedAt: string | null; recCount: number }>
}

let libraryCache: Promise<GuidelineLibraryView> | null = null

export function loadGuidelineLibrary(): Promise<GuidelineLibraryView> {
  if (!libraryCache) {
    libraryCache = api<{ data: GuidelineLibraryView }>('/api/b/v1/guidelines')
      .then((r) => r.data)
      .catch((e) => {
        libraryCache = null
        throw e
      })
  }
  return libraryCache
}

export function guidelineIndex(lib: GuidelineLibraryView | null): Map<string, GuidelineRecView> {
  return new Map((lib?.recs ?? []).map((r) => [r.code, r]))
}

/** 方案块或任务上的 refs（服务端已清洗未知编码；C 端不下发）。 */
export function refsOf(node: unknown): string[] {
  const refs = (node as { refs?: unknown } | null | undefined)?.refs
  return Array.isArray(refs) ? refs.filter((r): r is string => typeof r === 'string' && r.length > 0) : []
}

/** 依据库已启用、但方案快照里没有匹配记录 → 生成于依据库上线前（不回填）。 */
export function generatedBeforeLibrary(lib: GuidelineLibraryView | null, contextSnapshot: unknown): boolean {
  if (!lib?.enabled) return false
  const snap = contextSnapshot as { guideline?: unknown } | null | undefined
  return !snap?.guideline
}
