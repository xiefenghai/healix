/**
 * B 端首诊问卷工作台：类型、API 与显示条件 / 高亮判定辅助。
 */
import { api } from './http'

export type IntakeProgramCode = 'WEIGHT_LOSS' | 'NONE'

export type IntakeFieldType =
  | 'TEXT'
  | 'NUMBER'
  | 'ENUM'
  | 'MULTI'
  | 'DATE'
  | 'TIME'
  | 'LIST'
  | 'OBJECT'
  | string

export type IntakeItemLevel = 'REQUIRED' | 'FIRST_VISIT' | 'LATER' | 'STAFF' | string

export interface IntakeShowIf {
  field?: string
  eq?: string
  in?: string[]
  notIn?: string[]
  intersects?: string[]
  all?: IntakeShowIf[]
  any?: IntakeShowIf[]
}

export interface IntakeHighlightWhen {
  always?: boolean
  eq?: string
  in?: string[]
  notIn?: string[]
  intersects?: string[]
  op?: string
  value?: number
  text?: string
  computed?: string
}

export interface IntakeHighlightRule {
  when?: IntakeHighlightWhen
  severity?: 'CAUTION' | 'REFER' | 'BLOCK' | string
  followUp?: string | null
  planEffect?: string | null
}

export interface IntakeItemView {
  field: string
  label: string
  level: IntakeItemLevel
  type?: IntakeFieldType | null
  unit?: string | null
  /** 选项字典 parentCode */
  options?: string | null
  sensitive?: boolean
  showIf?: IntakeShowIf | null
  highlights?: IntakeHighlightRule[] | null
}

export interface IntakeModuleView {
  code: string
  version?: number
  title: string
  script?: string | null
  items: IntakeItemView[]
}

export interface IntakePrefillCell {
  value: unknown
  sourceBizCode?: string | null
  gmtModified?: string | null
}

export interface IntakeCompleteness {
  requiredDone?: number
  requiredTotal?: number
  firstVisitDone?: number
  firstVisitTotal?: number
  allDone?: number
  allTotal?: number
  requiredMissing?: string[]
  firstVisitMissing?: string[]
}

export interface IntakeHighlightHit {
  field: string
  severity?: string
  planEffect?: string | null
  followUp?: string | null
}

export interface QuestionnaireResponseView {
  id: string
  peopleId: string
  questionnaireCode?: string
  questionnaireVersion?: number
  purpose?: string
  status?: string
  channel?: string | null
  programCode?: string | null
  enrollmentId?: string | null
  followupRecordId?: string | null
  title?: string | null
  prefill?: Record<string, IntakePrefillCell> | null
  answers?: Record<string, unknown> | null
  highlights?: IntakeHighlightHit[] | null
  completeness?: IntakeCompleteness | null
  changes?: Record<string, { from?: unknown; to?: unknown }> | null
  modules?: IntakeModuleView[] | null
  submittedAt?: string | null
}

export interface OpenIntakeBody {
  followupRecordId?: string | null
  programCode?: string | null
  channel?: string | null
}

export interface IntakePendingField {
  field: string
  label?: string
  level?: string
  moduleCode?: string
  moduleTitle?: string
}

export interface IntakeStatusView {
  hasSubmitted: boolean
  questionnaireCode?: string
  submittedVersion?: number | null
  latestVersion: number
  pendingCount: number
  pendingFields?: IntakePendingField[]
}

export interface SaveDraftBody {
  answers: Record<string, unknown>
  programCode?: string | null
  channel?: string | null
}

export async function openIntake(peopleId: string, body: OpenIntakeBody = {}) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/patients/${peopleId}/intake/open`,
    { method: 'POST', body: JSON.stringify(body) },
  )
  return res.data
}

export async function getIntakeStatus(peopleId: string) {
  const res = await api<{ data: IntakeStatusView }>(`/api/b/v1/patients/${peopleId}/intake/status`)
  return res.data
}

export async function openIntakeSupplement(peopleId: string, body: OpenIntakeBody = {}) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/patients/${peopleId}/intake/supplement/open`,
    { method: 'POST', body: JSON.stringify(body) },
  )
  return res.data
}

export async function getQuestionnaireResponse(id: string) {
  const res = await api<{ data: QuestionnaireResponseView }>(`/api/b/v1/questionnaire-responses/${id}`)
  return res.data
}

export async function saveIntakeDraft(id: string, body: SaveDraftBody) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/questionnaire-responses/${id}/draft`,
    { method: 'PUT', body: JSON.stringify(body) },
  )
  return res.data
}

export async function submitIntake(id: string, conflictResolutions?: Record<string, unknown>) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/questionnaire-responses/${id}/submit`,
    {
      method: 'POST',
      body: JSON.stringify(conflictResolutions ? { conflictResolutions } : {}),
    },
  )
  return res.data
}

export function isAnswerEmpty(v: unknown): boolean {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  if (Array.isArray(v)) return v.length === 0
  if (typeof v === 'object') return Object.keys(v as object).length === 0
  return false
}

function flattenAnswer(v: unknown): string[] {
  if (v == null) return []
  if (Array.isArray(v)) {
    return v.map((x) => String(x).trim()).filter(Boolean)
  }
  if (typeof v === 'object') return []
  const s = String(v).trim()
  return s ? [s] : []
}

export function matchShowIf(
  showIf: IntakeShowIf | null | undefined,
  answers: Record<string, unknown>,
): boolean {
  if (!showIf) return true
  if (showIf.all?.length) return showIf.all.every((s) => matchShowIf(s, answers))
  if (showIf.any?.length) return showIf.any.some((s) => matchShowIf(s, answers))
  if (!showIf.field) return true
  const actual = flattenAnswer(answers[showIf.field])
  if (showIf.eq != null) {
    return actual.length === 1 && actual[0].toLowerCase() === showIf.eq.toLowerCase()
  }
  if (showIf.in) {
    return actual.some((a) => showIf.in!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (showIf.notIn) {
    if (!actual.length) return true
    return actual.every((a) => showIf.notIn!.every((w) => w.toLowerCase() !== a.toLowerCase()))
  }
  if (showIf.intersects) {
    return actual.some((a) => showIf.intersects!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  return true
}

function compareNum(num: number, op: string, thr: number): boolean {
  switch (op) {
    case '<':
      return num < thr
    case '<=':
    case '≤':
      return num <= thr
    case '>':
      return num > thr
    case '>=':
    case '≥':
      return num >= thr
    default:
      return false
  }
}

export function matchHighlightWhen(when: IntakeHighlightWhen | null | undefined, answer: unknown): boolean {
  if (!when) return false
  if (when.always) return true
  if (when.text != null || when.computed != null) return false
  const actual = flattenAnswer(answer)
  if (when.eq != null) {
    return actual.length === 1 && actual[0].toLowerCase() === when.eq.toLowerCase()
  }
  if (when.in) {
    return actual.some((a) => when.in!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (when.notIn) {
    if (!actual.length) return false
    return actual.every((a) => when.notIn!.every((w) => w.toLowerCase() !== a.toLowerCase()))
  }
  if (when.intersects) {
    return actual.some((a) => when.intersects!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (when.op != null && when.value != null) {
    if (!actual.length) return false
    const num = Number(actual[0])
    if (Number.isNaN(num)) return false
    return compareNum(num, when.op, Number(when.value))
  }
  return false
}

export function collectLocalHighlights(
  modules: IntakeModuleView[],
  answers: Record<string, unknown>,
): IntakeHighlightHit[] {
  const out: IntakeHighlightHit[] = []
  for (const m of modules) {
    for (const item of m.items) {
      if (!matchShowIf(item.showIf, answers)) continue
      for (const rule of item.highlights || []) {
        if (matchHighlightWhen(rule.when, answers[item.field])) {
          out.push({
            field: item.field,
            severity: rule.severity,
            planEffect: rule.planEffect,
            followUp: rule.followUp,
          })
        }
      }
    }
  }
  return out
}

/** 预填写入 answers：仅空位 */
export function mergePrefillIntoAnswers(
  answers: Record<string, unknown> | null | undefined,
  prefill: Record<string, IntakePrefillCell> | null | undefined,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...(answers || {}) }
  if (!prefill) return out
  for (const [field, cell] of Object.entries(prefill)) {
    if (!isAnswerEmpty(out[field]) || cell == null || isAnswerEmpty(cell.value)) continue
    out[field] = cell.value
  }
  return out
}

export function calcBmi(heightCm: unknown, weightKg: unknown): number | null {
  const h = Number(heightCm)
  const w = Number(weightKg)
  if (!Number.isFinite(h) || !Number.isFinite(w) || h <= 0 || w <= 0) return null
  const m = h / 100
  const bmi = w / (m * m)
  return Number.isFinite(bmi) ? Math.round(bmi * 10) / 10 : null
}

export function formatPrefillSource(sourceBizCode?: string | null): string {
  const code = (sourceBizCode || '').toUpperCase()
  const map: Record<string, string> = {
    PROFILE: '档案资料',
    ARCHIVE: '档案页',
    METADATA: '元数据',
    METRIC: '指标',
    ASSESSMENT: '评估问卷',
    INTAKE: '既往首诊',
    INTAKE_QUESTIONNAIRE: '既往首诊',
  }
  return map[code] || sourceBizCode || '预填'
}

export function valuesEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (isAnswerEmpty(a) && isAnswerEmpty(b)) return true
  try {
    return JSON.stringify(a) === JSON.stringify(b)
  } catch {
    return false
  }
}

export function formatAnswerBrief(v: unknown): string {
  if (isAnswerEmpty(v)) return '—'
  if (Array.isArray(v)) return v.map(String).join('、') || '—'
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v)
    } catch {
      return String(v)
    }
  }
  return String(v)
}

export interface DictOption {
  code: string
  label: string
}

const optionCache = new Map<string, DictOption[]>()

/** 按 options parentCode 拉字典；失败时返回空，调用方用编码占位 */
export async function loadDictOptions(parentCode: string): Promise<DictOption[]> {
  if (!parentCode) return []
  const cached = optionCache.get(parentCode)
  if (cached) return cached
  try {
    const res = await api<{ data: Array<{ dictCode: string; dictCodeDesc: string }> }>(
      `/api/b/v1/dict?dictType=OPTION&parentCode=${encodeURIComponent(parentCode)}`,
    )
    const list = (res.data || []).map((d) => ({
      code: d.dictCode,
      label: d.dictCodeDesc || d.dictCode,
    }))
    optionCache.set(parentCode, list)
    return list
  } catch {
    optionCache.set(parentCode, [])
    return []
  }
}
