/**
 * B 端首诊问卷工作台：类型、API 与显示条件 / 高亮判定辅助。
 */
import { api } from './http'
import {
  FAMILY_HISTORY_DISEASE_GROUPS,
  KINSHIP_MEMBERS,
  formatFamilyHistoryTag,
  type FamilyHistoryEntry,
  type KinshipLevel,
} from './family-history-options'

export type IntakeProgramCode = 'WEIGHT_LOSS' | 'DIABETES' | 'HYPERTENSION' | 'NONE'

export const CARE_TRACK_OPTIONS: { code: Exclude<IntakeProgramCode, 'NONE'>; label: string }[] = [
  { code: 'WEIGHT_LOSS', label: '减重管理' },
  { code: 'DIABETES', label: '糖尿病管理' },
  { code: 'HYPERTENSION', label: '高血压管理' },
]

/** 核心诉求（单选或多选）→ 建议纳入的管理方向 */
export function suggestedCareTracksFromAppeal(appeal: unknown): IntakeProgramCode[] {
  const codes = (Array.isArray(appeal) ? appeal : appeal != null && appeal !== '' ? [appeal] : []).map((x) =>
    String(x).toUpperCase(),
  )
  const out: IntakeProgramCode[] = []
  const add = (t: IntakeProgramCode) => {
    if (!out.includes(t)) out.push(t)
  }
  for (const code of codes) {
    if (code === 'FAT_LOSS' || code === 'BODY_SHAPE' || code === 'LIPID') add('WEIGHT_LOSS')
    else if (code === 'GLUCOSE') add('DIABETES')
    else if (code === 'BP') add('HYPERTENSION')
    else if (code === 'METABOLIC') {
      add('WEIGHT_LOSS')
      add('DIABETES')
      add('HYPERTENSION')
    }
  }
  return out
}

/** 病史现患 → 可提示一并纳入的慢病方向 */
export function suggestedCareTracksFromIllness(
  presentIllness: unknown,
): Exclude<IntakeProgramCode, 'NONE'>[] {
  const set = new Set(
    (Array.isArray(presentIllness) ? presentIllness : presentIllness != null ? [presentIllness] : []).map((x) =>
      String(x).toUpperCase(),
    ),
  )
  const out: Exclude<IntakeProgramCode, 'NONE'>[] = []
  if (set.has('DIABETES')) out.push('DIABETES')
  if (set.has('HYPERTENSION')) out.push('HYPERTENSION')
  return out
}

export function normalizeCareTracks(value: unknown): Exclude<IntakeProgramCode, 'NONE'>[] {
  const raw = Array.isArray(value) ? value : value != null && value !== '' ? [value] : []
  const allowed = new Set(['WEIGHT_LOSS', 'DIABETES', 'HYPERTENSION'])
  const out: Exclude<IntakeProgramCode, 'NONE'>[] = []
  for (const v of raw) {
    const c = String(v).toUpperCase()
    if (c === 'NONE') return []
    if (allowed.has(c) && !out.includes(c as Exclude<IntakeProgramCode, 'NONE'>)) {
      out.push(c as Exclude<IntakeProgramCode, 'NONE'>)
    }
  }
  return out
}

/** 写入答案：无项目时存 ["NONE"]，保证必收可过 */
export function careTracksAnswerValue(
  tracks: IntakeProgramCode[],
): Array<Exclude<IntakeProgramCode, 'NONE'> | 'NONE'> {
  const normalized = normalizeCareTracks(tracks)
  return normalized.length ? normalized : ['NONE']
}

export function primaryProgramFromTracks(
  tracks: IntakeProgramCode[],
  primaryAppeal?: unknown,
): IntakeProgramCode {
  const normalized = normalizeCareTracks(tracks)
  if (!normalized.length) return 'NONE'
  const suggested = suggestedCareTracksFromAppeal(primaryAppeal)
  if (suggested[0] && normalized.includes(suggested[0] as Exclude<IntakeProgramCode, 'NONE'>)) {
    return suggested[0]
  }
  return normalized[0]
}

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
  /** 出生日期换算的周岁下限；生日未知时不因此隐藏 */
  ageGte?: number
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
  /** 数值题下限（field-catalog） */
  min?: number | null
  /** 数值题上限（field-catalog） */
  max?: number | null
  /** 多选互斥码，如 NONE / UNKNOWN */
  exclusive?: string[] | null
  sensitive?: boolean
  showIf?: IntakeShowIf | null
  highlights?: IntakeHighlightRule[] | null
}

/** 多选：点互斥项则只保留该项；点其他项则清掉互斥项 */
export function toggleExclusiveMulti(
  prev: string[],
  code: string,
  exclusive: string[] | null | undefined,
  availableCodes?: string[],
): string[] {
  const exclusiveSet = new Set(
    (exclusive?.length
      ? exclusive
      : ['NONE', 'UNKNOWN'].filter((c) => !availableCodes || availableCodes.includes(c))
    ).map(String),
  )
  const cur = [...prev]
  const idx = cur.indexOf(code)
  if (idx >= 0) {
    cur.splice(idx, 1)
    return cur
  }
  if (exclusiveSet.has(code)) return [code]
  return [...cur.filter((c) => !exclusiveSet.has(c)), code]
}

export interface IntakeModuleView {
  code: string
  version?: number
  title: string
  script?: string | null
  showIf?: IntakeShowIf | null
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
  /** 服务端/本地聚合时带上的中文题名；缺省时用 resolveIntakeFieldLabel */
  label?: string | null
  severity?: string
  planEffect?: string | null
  followUp?: string | null
}

/**
 * field-catalog 常用中文名回落（模块未下发、补填过滤、预填键不在 modules 时避免露出英文 code）。
 * 不必穷尽；优先用 modules / hit.label。
 */
const INTAKE_FIELD_LABELS: Record<string, string> = {
  'allergy.status': '过敏史',
  'allergy.foodTags': '常见食物过敏',
  'allergy.drugTags': '常见药物过敏',
  'allergy.food': '食物过敏明细',
  'allergy.drug': '药物过敏明细',
  'medication.current': '在用药物明细',
  'basic.presentIllness': '现有疾病',
  'basic.presentIllnessOther': '其他疾病',
  'basic.medical.jointIssues': '关节问题与运动损伤',
  'basic.diet.taste': '口味',
  'basic.weightHistory.triedBefore': '减重经历',
  'basic.weightHistory.methods': '用过的减重方法',
  'goal.careTracks': '本次管理方向',
  'goal.primaryAppeal': '核心诉求',
  'goal.mode': '目标类型',
  'goal.targetWeightKg': '目标体重',
  'goal.targetDate': '希望多久达到体重目标',
  'goal.targetWaistCm': '腰围目标',
  'goal.targetHba1c': '糖化血红蛋白目标',
  'goal.targetFpg': '空腹血糖目标',
  'goal.targetBpSys': '收缩压目标',
  'goal.targetBpDia': '舒张压目标',
}

/** 解析首诊字段中文名：hit 自带 label → 模块题名 → catalog 回落 → 原始 field */
export function resolveIntakeFieldLabel(
  field: string,
  opts?: { hitLabel?: string | null; moduleLabel?: string | null },
): string {
  const hit = opts?.hitLabel?.trim()
  if (hit) return hit
  const mod = opts?.moduleLabel?.trim()
  if (mod && mod !== field) return mod
  return INTAKE_FIELD_LABELS[field] || field
}

/** 高亮严重程度码 → 界面中文（数据仍存 CAUTION / REFER / BLOCK） */
const SEVERITY_LABELS: Record<string, string> = {
  CAUTION: '谨慎',
  REFER: '转介',
  BLOCK: '阻断',
}

export function severityLabel(severity?: string | null): string {
  if (!severity) return '提示'
  return SEVERITY_LABELS[severity] || severity
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
  /** 提交快照：当时答案 / 高亮，只读详情优先用 */
  snapshot?: {
    questionnaire?: string
    answers?: Record<string, unknown>
    highlights?: IntakeHighlightHit[]
  } | null
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
  /** 最近一次已提交作答，供只读详情 */
  latestSubmittedId?: string | null
  submittedAt?: string | null
  questionnaireCode?: string
  submittedVersion?: number | null
  latestVersion: number
  pendingCount: number
  pendingFields?: IntakePendingField[]
  /** C 端提交档位 MINIMAL / QUICK / FULL */
  submittedDepth?: string | null
  submittedDepthLabel?: string | null
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

export async function submitIntake(
  id: string,
  conflictResolutions?: Record<string, unknown>,
  forceIncomplete = false,
) {
  const body: Record<string, unknown> = {}
  if (conflictResolutions) body.conflictResolutions = conflictResolutions
  if (forceIncomplete) body.forceIncomplete = true
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/questionnaire-responses/${id}/submit`,
    {
      method: 'POST',
      body: JSON.stringify(body),
    },
  )
  return res.data
}

/**
 * 主观诉求没采到时，不把推导出的管理方向写成「核心诉求」。
 * 只改展示/再生成前的条文，避免把血压、血糖误写成患者自己说的目标。
 */
export function clarifyUncollectedAppeal(raw: unknown, appealFilled: boolean): string {
  const text = String(raw ?? '')
  if (appealFilled || !text.trim() || !/核心诉求/.test(text)) return text
  return text
    .split('\n')
    .map((line) => {
      if (!/核心诉求/.test(line)) return line
      if (/主观诉求未采集/.test(line)) return line
      const dir = line.match(/管理方向[：:]\s*([^；;。\n]+)/)
      const direction = dir?.[1]?.replace(/（注：[^）]*）/g, '').trim()
      const bullet = /^\s*[·•\-*]/.test(line) ? '· ' : ''
      if (direction) return `${bullet}管理方向：${direction}（注：患者主观诉求未采集全）`
      return line.replace(/[；;，,]?\s*核心诉求[：:][^；;。\n]*/g, '（注：患者主观诉求未采集全）')
    })
    .join('\n')
}

export interface IntakeAiSummaryResult {
  summary: string
  keyPoints?: string[]
  risks?: string[]
  nextActions?: string[]
  fromLlm: boolean
  note?: string | null
}

/** 解析后的案头总结：概况 + 要点 / 风险 / 下一步 + 其它【】分节 */
export interface IntakeAiSummaryParsed {
  overview: string
  keyPoints: string[]
  risks: string[]
  nextActions: string[]
  otherSections: { title: string; items: string[] }[]
}

function emptyIntakeAiSummaryParsed(): IntakeAiSummaryParsed {
  return { overview: '', keyPoints: [], risks: [], nextActions: [], otherSections: [] }
}

function extractSummaryItems(lines: string[]): string[] {
  const items: string[] = []
  for (const line of lines) {
    const t = line.trim()
    if (!t) continue
    items.push(t.replace(/^[·•\-＊*]\s*/, '').trim())
  }
  return items.filter(Boolean)
}

/**
 * 将落库的案头总结文本拆成分节，供 UI 分要点展示。
 * 兼容 formatIntakeAiSummaryText 产出，以及模板兜底中的【基本情况】等标题。
 */
export function parseIntakeAiSummaryText(raw: unknown): IntakeAiSummaryParsed {
  const text = String(raw ?? '').trim()
  if (!text) return emptyIntakeAiSummaryParsed()

  const buckets: { title: string | null; lines: string[] }[] = [{ title: null, lines: [] }]
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^【([^】]+)】\s*$/)
    if (m) {
      buckets.push({ title: m[1].trim(), lines: [] })
      continue
    }
    buckets[buckets.length - 1].lines.push(line)
  }

  const out = emptyIntakeAiSummaryParsed()
  for (const b of buckets) {
    const items = extractSummaryItems(b.lines)
    const prose = b.lines.join('\n').trim()
    if (b.title == null) {
      out.overview = prose
      continue
    }
    const title = b.title
    if (/要点/.test(title)) {
      out.keyPoints.push(...items)
    } else if (/风险|禁忌/.test(title)) {
      out.risks.push(...items)
    } else if (/下一步/.test(title) || /^建议/.test(title)) {
      out.nextActions.push(...items)
    } else {
      out.otherSections.push({
        title,
        items: items.length ? items : prose ? [prose] : [],
      })
    }
  }

  // 无任何【】分节时整段作为概况，避免空白
  if (
    !out.overview &&
    !out.keyPoints.length &&
    !out.risks.length &&
    !out.nextActions.length &&
    !out.otherSections.length
  ) {
    out.overview = text
  }
  return out
}

export function hasParsedIntakeAiSummary(p: IntakeAiSummaryParsed): boolean {
  return Boolean(
    p.overview ||
      p.keyPoints.length ||
      p.risks.length ||
      p.nextActions.length ||
      p.otherSections.length,
  )
}

/** 将 AI 结构化结果拼成案头可编辑条文（重点条目清晰） */
export function formatIntakeAiSummaryText(draft: IntakeAiSummaryResult): string {
  const blocks: string[] = []
  const summary = (draft.summary || '').trim()
  if (summary) blocks.push(summary)

  const hasRiskSection = /【风险/.test(summary)
  const hasNextSection = /【建议下一步】|【下一步】/.test(summary)

  if (draft.keyPoints?.length && !/【要点】/.test(summary)) {
    blocks.push(['【要点】', ...draft.keyPoints.map((k) => `· ${k}`)].join('\n'))
  }
  if (draft.risks?.length && !hasRiskSection) {
    blocks.push(['【风险与禁忌】', ...draft.risks.map((k) => `· ${k}`)].join('\n'))
  }
  if (draft.nextActions?.length && !hasNextSection) {
    blocks.push(['【建议下一步】', ...draft.nextActions.map((k) => `· ${k}`)].join('\n'))
  }
  return blocks.filter(Boolean).join('\n\n')
}

/** 根据当前草稿答案生成 AI 案头总结（不落库）；可传本地 answers 避免未保存草稿过期 */
export async function generateIntakeAiSummary(id: string, answers?: Record<string, unknown>) {
  const res = await api<{ data: IntakeAiSummaryResult }>(
    `/api/b/v1/questionnaire-responses/${id}/ai-summary`,
    {
      method: 'POST',
      body: JSON.stringify(answers ? { answers } : {}),
    },
  )
  return res.data
}

export const FIELD_STAFF_AI_SUMMARY = 'staff.aiSummary'
export const FIELD_ALLERGY_STATUS = 'allergy.status'
export const FIELD_ALLERGY_FOOD = 'allergy.food'
export const FIELD_ALLERGY_DRUG = 'allergy.drug'

export function isAnswerEmpty(v: unknown): boolean {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  if (Array.isArray(v)) {
    if (v.length === 0) return true
    // 用药/过敏明细：全是空名称视为未填
    if (
      v.every(
        (item) =>
          item &&
          typeof item === 'object' &&
          ('drugName' in (item as object) || 'allergenName' in (item as object)),
      )
    ) {
      return v.every((item) => {
        const row = item as { drugName?: unknown; allergenName?: unknown }
        return !String(row.drugName || row.allergenName || '').trim()
      })
    }
    return false
  }
  if (typeof v === 'object') return Object.keys(v as object).length === 0
  return false
}

/**
 * 首诊题是否算已填。
 * 过敏选「有」后食物/药物两类都会出现：一类已有明细时，另一类空视为「该类无」，避免总挂 1 项未填。
 */
const FIELD_ALLERGY_FOOD_TAGS = 'allergy.foodTags'
const FIELD_ALLERGY_DRUG_TAGS = 'allergy.drugTags'

function allergySiblingField(field: string): string | null {
  if (field === FIELD_ALLERGY_FOOD) return FIELD_ALLERGY_DRUG
  if (field === FIELD_ALLERGY_DRUG) return FIELD_ALLERGY_FOOD
  if (field === FIELD_ALLERGY_FOOD_TAGS) return FIELD_ALLERGY_DRUG_TAGS
  if (field === FIELD_ALLERGY_DRUG_TAGS) return FIELD_ALLERGY_FOOD_TAGS
  return null
}

export function isIntakeAnswerFilled(field: string, answers: Record<string, unknown>): boolean {
  if (!isAnswerEmpty(answers[field])) return true
  const other = allergySiblingField(field)
  if (!other) return false
  const status = String(answers[FIELD_ALLERGY_STATUS] ?? '')
    .trim()
    .toUpperCase()
  if (status !== 'HAS') return false
  return !isAnswerEmpty(answers[other])
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
  if (typeof showIf.ageGte === 'number') {
    const age = ageYearsFromBirthday(answers['profile.birthday'])
    if (age != null && age < showIf.ageGte) return false
    if (!showIf.field) return true
  }
  if (!showIf.field) return true
  const actual = flattenAnswer(answers[showIf.field])
  if (showIf.eq != null) {
    return actual.length === 1 && actual[0].toLowerCase() === showIf.eq.toLowerCase()
  }
  if (showIf.in) {
    return actual.some((a) => showIf.in!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (showIf.notIn) {
    // 与后端 ShowIf 一致：未作答不算命中 notIn，避免追问题空挂
    if (!actual.length) return false
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

export function ageYearsFromBirthday(raw: unknown): number | null {
  if (raw == null || raw === '') return null
  const s = String(raw).trim().slice(0, 10)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null
  const b = new Date(`${s}T00:00:00`)
  if (Number.isNaN(b.getTime())) return null
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const md = now.getMonth() - b.getMonth()
  if (md < 0 || (md === 0 && now.getDate() < b.getDate())) age -= 1
  return age >= 0 && age < 150 ? age : null
}

/** 可前端即时算出的 computed；缺身高/生日等依赖时返回 null → 不高亮 */
function resolveHighlightComputed(
  name: string,
  answer: unknown,
  answers?: Record<string, unknown>,
): number | null {
  const n = String(name || '').trim()
  if (n === 'BMI') {
    return calcBmi(answers?.['metric.HEIGHT'], answers?.['metric.WEIGHT'] ?? answer)
  }
  if (n === '目标BMI') {
    return calcBmi(answers?.['metric.HEIGHT'], answer)
  }
  if (n === '年龄') {
    return ageYearsFromBirthday(answers?.['profile.birthday'] ?? answer)
  }
  return null
}

export function matchHighlightWhen(
  when: IntakeHighlightWhen | null | undefined,
  answer: unknown,
  answers?: Record<string, unknown>,
): boolean {
  if (!when) return false
  if (when.always) return true
  if (when.text != null) return false
  if (when.computed != null) {
    const num = resolveHighlightComputed(when.computed, answer, answers)
    if (num == null || when.op == null || when.value == null) return false
    return compareNum(num, when.op, Number(when.value))
  }
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

function highlightSeverityRank(severity?: string | null): number {
  switch (String(severity || '').toUpperCase()) {
    case 'BLOCK':
      return 3
    case 'REFER':
      return 2
    case 'CAUTION':
      return 1
    default:
      return 0
  }
}

/** 同一题多条规则命中时合并为一张卡：取最高级别，方案影响去重拼接 */
export function collapseHighlightsByField(hits: IntakeHighlightHit[]): IntakeHighlightHit[] {
  const map = new Map<string, IntakeHighlightHit>()
  for (const h of hits) {
    if (!h?.field) continue
    const prev = map.get(h.field)
    if (!prev) {
      map.set(h.field, { ...h })
      continue
    }
    const prevRank = highlightSeverityRank(prev.severity)
    const nextRank = highlightSeverityRank(h.severity)
    const severity = nextRank > prevRank ? h.severity : prev.severity
    const effects = [prev.planEffect, h.planEffect]
      .map((x) => (x || '').trim())
      .filter(Boolean)
    const planEffect = [...new Set(effects)].join('；') || null
    const follows = [prev.followUp, h.followUp]
      .map((x) => (x || '').trim())
      .filter(Boolean)
    const followUp = [...new Set(follows)].join(' ') || null
    map.set(h.field, {
      field: h.field,
      label: h.label || prev.label,
      severity,
      planEffect,
      followUp,
    })
  }
  return [...map.values()]
}

const LOCAL_COMPUTED = new Set(['BMI', '目标BMI', '年龄'])

/** 规则均可在前端即时判定（含 BMI / 目标BMI / 年龄；无 text）→ 侧栏以本地结果为准 */
export function isLocallyOwnedHighlightField(item: IntakeItemView): boolean {
  const rules = item.highlights || []
  if (!rules.length) return false
  return rules.every((r) => {
    if (r.when?.text != null) return false
    if (r.when?.computed != null) return LOCAL_COMPUTED.has(String(r.when.computed))
    return true
  })
}

export function collectLocalHighlights(
  modules: IntakeModuleView[],
  answers: Record<string, unknown>,
): IntakeHighlightHit[] {
  const out: IntakeHighlightHit[] = []
  for (const m of modules) {
    if (!matchShowIf(m.showIf, answers)) continue
    for (const item of m.items) {
      if (!matchShowIf(item.showIf, answers)) continue
      for (const rule of item.highlights || []) {
        if (matchHighlightWhen(rule.when, answers[item.field], answers)) {
          out.push({
            field: item.field,
            label: item.label,
            severity: rule.severity,
            planEffect: rule.planEffect,
            followUp: rule.followUp,
          })
        }
      }
    }
  }
  return collapseHighlightsByField(out)
}

/**
 * 侧栏即时高亮：本地可判定的题完全听 answers；
 * 仅保留服务端对 computed/text 等前端无法判的题的命中。
 */
export function mergeLiveHighlights(
  modules: IntakeModuleView[],
  answers: Record<string, unknown>,
  serverHits: IntakeHighlightHit[] | null | undefined,
): IntakeHighlightHit[] {
  const local = collectLocalHighlights(modules, answers)
  const localOwned = new Set<string>()
  for (const m of modules) {
    for (const item of m.items || []) {
      if (isLocallyOwnedHighlightField(item)) localOwned.add(item.field)
    }
  }
  const serverKept = (serverHits || []).filter((h) => h?.field && !localOwned.has(h.field))
  return collapseHighlightsByField([...serverKept, ...local])
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
    ARCHIVE_PAGE: '档案页',
    METADATA: '元数据',
    METRIC: '指标',
    ASSESSMENT: '评估问卷',
    ASSESSMENT_QUESTIONNAIRE: '评估问卷',
    INTAKE: '既往首诊',
    INTAKE_QUESTIONNAIRE: '既往首诊',
    MEDICATION: '用药清单',
    ALLERGY: '过敏史',
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

export interface DictOption {
  code: string
  label: string
}

/** parentCode → 选项列表；详情/变更对照解析 ENUM·MULTI 用 */
export type IntakeOptionMaps = Record<string, DictOption[]>

const optionCache = new Map<string, DictOption[]>()
/** 缓存版本：改兜底文案后递增，避免 HMR 仍用旧的 label===code 脏缓存 */
const OPTION_CACHE_VER = 'v5'
const verCacheKey = (parentCode: string) => `${OPTION_CACHE_VER}::${parentCode}`

/**
 * field-catalog optionSets 本地兜底（字典未同步时仍能显示中文）。
 * 与 backend/.../field-catalog.json 的 optionSets 保持同码同文。
 */
const OPTION_SET_FALLBACKS: Record<string, string[]> = {
  yesNoUnknown: ['YES=是', 'NO=否', 'UNKNOWN=不清楚'],
  yesNo: ['YES=是', 'NO=否'],
  recentHospitalization: [
    'NONE=没有',
    'SURGERY=有手术',
    'HOSPITAL=有住院',
    'BOTH=都有',
    'UNKNOWN=不清楚',
  ],
  gender: ['MALE=男', 'FEMALE=女'],
  maritalStatus: ['UNMARRIED=未婚', 'MARRIED=已婚', 'DIVORCED=离异', 'WIDOWED=丧偶', 'OTHER=其他'],
  workSchedule: ['DAY=规律白班', 'SHIFT=倒班', 'NIGHT=夜班为主', 'IRREGULAR=不固定'],
  historyStatus: ['none=没有', 'has=有'],
  reproductiveStatus: [
    'NONE=都没有',
    'PREPARING=备孕中',
    'PREGNANT=怀孕中',
    'LACTATING=哺乳期',
    'UNSURE=不确定',
  ],
  menstrualStatus: [
    'REGULAR=规律',
    'IRREGULAR=不规律',
    'AMENORRHEA=停经（非绝经）',
    'PERIMENOPAUSE=围绝经期',
    'POSTMENOPAUSE=已绝经',
  ],
  medicationFlags: [
    'NONE=都没有',
    'HYPOGLYCEMIC=降糖药或胰岛素',
    'ANTIHYPERTENSIVE=降压药',
    'STATIN=降脂药',
    'PSYCH=抗抑郁 / 抗精神病药',
    'STEROID=长期激素',
    'THYROID=甲状腺药',
    'WEIGHT_LOSS_DRUG=减重药（如司美格鲁肽）',
    'OTHER=其他',
  ],
  supplements: [
    'NONE=都没有',
    'MEAL_REPLACEMENT=代餐',
    'PROTEIN=蛋白粉',
    'DIET_TEA=减肥茶 / 非处方减肥产品',
    'FAT_BURNER=燃脂类',
    'VITAMIN=维生素 / 矿物质',
    'OTHER=其他',
  ],
  exerciseRestriction: [
    'NONE=没有',
    'DOCTOR_LIMIT=医生明确限制',
    'CARDIAC=运动时胸痛 / 心悸 / 晕厥',
    'BP_UNCONTROLLED=血压控制不好',
    'RECENT_SURGERY=近期手术未恢复',
    'OTHER=其他',
  ],
  jointIssues: [
    'NONE=没有',
    'KNEE=膝',
    'ANKLE=踝',
    'HIP=髋',
    'LOWER_BACK=腰',
    'SHOULDER=肩颈',
    'OTHER=其他',
  ],
  allergyStatus: ['NKA=没有已知过敏', 'HAS=有'],
  foodAllergyTags: [
    'SEAFOOD=海鲜（统称）',
    'FISH=鱼类',
    'SHELLFISH=虾蟹贝类',
    'MILK=牛奶/乳制品',
    'EGG=鸡蛋',
    'PEANUT=花生',
    'TREE_NUT=坚果',
    'SOY=大豆',
    'WHEAT=小麦/麸质',
    'SESAME=芝麻',
    'MANGO=芒果',
    'LACTOSE=乳糖不耐受',
    'OTHER=其他（请健管师补明细）',
  ],
  drugAllergyTags: [
    'PENICILLIN=青霉素类',
    'CEPHALOSPORIN=头孢类',
    'SULFONAMIDE=磺胺类',
    'NSAID=阿司匹林/解热镇痛药',
    'QUINOLONE=喹诺酮类',
    'MACROLIDE=大环内酯类',
    'IODINE_CONTRAST=碘造影剂',
    'INSULIN=胰岛素制剂',
    'OTHER=其他（请健管师补明细）',
  ],
  dietRestriction: [
    'NONE=没有',
    'VEGETARIAN=蛋奶素',
    'VEGAN=纯素',
    'HALAL=清真',
    'NO_SPICY=不吃辣',
    'NO_BEEF_LAMB=不吃牛羊肉',
    'NO_SEAFOOD=不吃海鲜',
    'OTHER=其他',
  ],
  mealRegularity: ['REGULAR=基本规律', 'SOMETIMES=偶尔不规律', 'IRREGULAR=经常不规律'],
  breakfast: ['DAILY=每天吃', 'SOMETIMES=有时吃', 'RARELY=基本不吃'],
  takeoutFrequency: [
    'RARELY=很少',
    'WEEKLY_1_3=每周 1–3 次',
    'WEEKLY_4_7=每周 4–7 次',
    'DAILY_MULTI=每天不止一次',
  ],
  weeklyFrequency3: ['RARELY=很少', 'WEEKLY_1_3=每周 1–3 次', 'MOST_DAYS=大多数天'],
  sugaryDrinks: [
    'NONE=基本不喝',
    'WEEKLY_1_3=每周 1–3 杯',
    'DAILY_1=每天 1 杯',
    'DAILY_2PLUS=每天 2 杯以上',
  ],
  staple: ['REFINED=以白米白面为主', 'MIXED=粗细搭配', 'LOW=主食吃得很少'],
  vegFruit: ['ENOUGH=每天都有不少', 'SOME=有但不多', 'RARE=很少吃'],
  cooking: ['STEAM_BOIL=蒸煮', 'STIR_FRY=炒', 'DEEP_FRY=油炸', 'BRAISE=红烧 / 卤'],
  eatingSpeed: ['FAST=10 分钟以内', 'NORMAL=10–20 分钟', 'SLOW=20 分钟以上'],
  taste: ['LIGHT=清淡', 'SALTY=偏咸 / 常吃咸菜腌制品', 'SWEET=偏甜', 'OILY=偏油', 'SPICY=偏辣'],
  dietTracked: ['NEVER=没有', 'TRIED=试过没坚持', 'ONGOING=一直在记'],
  cook: ['SELF=自己做', 'FAMILY=家人做', 'CANTEEN=单位食堂', 'EAT_OUT=外卖 / 外食为主'],
  exerciseTypes: [
    'WALK=快走',
    'RUN=跑步',
    'CYCLE=骑行',
    'SWIM=游泳',
    'STRENGTH=力量训练',
    'BALL=球类',
    'YOGA=瑜伽 / 普拉提',
    'DANCE=跳操 / 广场舞',
    'OTHER=其他',
  ],
  strengthExperience: ['NONE=没练过', 'BEGINNER=偶尔练', 'REGULAR=规律练'],
  dailySteps: [
    'LT3000=3000 步以下',
    'S3000_6000=3000–6000 步',
    'S6000_10000=6000–10000 步',
    'GT10000=1 万步以上',
    'UNKNOWN=不清楚',
  ],
  exerciseSlots: ['MORNING=早上', 'NOON=中午', 'EVENING=傍晚', 'NIGHT=晚上', 'WEEKEND=只有周末'],
  exerciseVenue: ['HOME=家里', 'OUTDOOR=户外', 'GYM=健身房', 'OFFICE=单位'],
  activityLevel: [
    'SEDENTARY=久坐为主',
    'LIGHT=轻体力（站立走动较多）',
    'MODERATE=中等体力',
    'HEAVY=重体力',
  ],
  stressLevel: ['LOW=不大', 'MEDIUM=有一些', 'HIGH=长期压力很大'],
  stressEating: ['NO=没变化', 'EAT_MORE=吃得更多', 'EAT_LESS=吃得更少'],
  emotionalEating: ['NEVER=很少', 'SOMETIMES=有时', 'OFTEN=经常'],
  readiness: [
    'NOT_READY=还没想好',
    'THINKING=想改但还没开始',
    'READY=准备好了',
    'ACTING=已经在做了',
  ],
  familySupport: ['SUPPORTIVE=支持', 'NEUTRAL=无所谓', 'UNSUPPORTIVE=不太支持'],
  willingToRecord: ['YES=愿意', 'MAYBE=可以试试', 'NO=不太愿意'],
  weightLossTried: ['NEVER=没有', 'ONCE=1 次', 'MULTIPLE=多次'],
  weightLossMethods: [
    'DIET_CONTROL=控制饮食',
    'LOW_CARB=低碳 / 生酮',
    'MEAL_REPLACEMENT=代餐',
    'FASTING=断食 / 轻断食',
    'EXERCISE=运动',
    'DRUG=药物',
    'SURGERY=减重手术',
    'OTHER=其他',
  ],
  weightLossFailReasons: [
    'REBOUND=反弹了',
    'HUNGRY=太饿坚持不了',
    'NO_TIME=没时间',
    'NO_RESULT=没效果',
    'SOCIAL=应酬多',
    'INJURY=受伤',
    'OTHER=其他',
  ],
  weightTrend: [
    'STABLE=基本稳定',
    'UP=涨了',
    'DOWN_INTENDED=有意减下来了',
    'DOWN_UNINTENDED=没刻意减却瘦了',
  ],
  eatingDisorderHistory: ['NO=没有', 'YES=有过', 'UNSURE=说不清', 'DECLINE=不想回答'],
  soughtHelp: ['YES=找过', 'NO=没有'],
  bodySatisfaction: [
    'SATISFIED=满意',
    'NEUTRAL=一般',
    'DISSATISFIED=不太满意',
    'VERY_DISSATISFIED=很不满意',
  ],
  bodyAnxiety: ['NONE=不会', 'MILD=偶尔', 'SEVERE=经常，比较严重'],
  expectedPace: ['STEADY=稳一点就好', 'MODERATE=适中', 'ASAP=越快越好'],
  weightTargetHorizon: [
    'M1=1 个月',
    'M3=3 个月',
    'M6=6 个月',
    'M12=约 1 年',
    'FLEXIBLE=不着急，先稳住',
  ],
  primaryAppeal: [
    'GLUCOSE=控糖（糖尿病）',
    'BP=控压（高血压）',
    'LIPID=控血脂',
    'FAT_LOSS=减脂减重',
    'BODY_SHAPE=塑形增肌',
    'METABOLIC=三高 / 代谢综合',
    'SLEEP=改善睡眠',
    'GUT=改善肠胃',
    'CONSULT=先了解看看',
    'OTHER=其他',
  ],
  careTracks: [
    'WEIGHT_LOSS=减重管理',
    'DIABETES=糖尿病管理',
    'HYPERTENSION=高血压管理',
    'NONE=暂不加入项目',
  ],
  goalMode: [
    'LOSS=减重',
    'BODY_COMPOSITION=改善体成分',
    'MAINTAIN=维持',
    'GLUCOSE_CONTROL=控糖',
    'BP_CONTROL=控压',
  ],
  dmType: ['T2=2 型糖尿病', 'T1=1 型糖尿病', 'OTHER=其他 / 未分型', 'UNKNOWN=不清楚'],
  dmHypoglycemia: ['NONE=没有', 'MILD=偶尔轻微', 'SEVERE=有过较重或需他人帮助', 'UNKNOWN=不清楚'],
  dmMonitorFreq: ['NONE=基本不测', 'OCCASIONAL=偶尔', 'WEEKLY=每周几次', 'DAILY=几乎每天', 'UNKNOWN=不清楚'],
  medAdherence: [
    'ALWAYS=基本按时用药',
    'MOSTLY=大多按时偶有漏服',
    'IRREGULAR=经常漏服或自行加减',
    'STOPPED=已自行停药',
    'UNKNOWN=不清楚',
  ],
  htnHomeMonitor: ['YES=有在家测', 'NO=基本不测', 'OCCASIONAL=偶尔测', 'UNKNOWN=不清楚'],
  htnMorningPeak: ['YES=有', 'NO=没有', 'UNKNOWN=不清楚'],
  yesNoUnsure: ['YES=有', 'NO=没有', 'UNSURE=不清楚'],
  eyeCheckRecency: [
    'WITHIN_1Y=近 1 年内查过',
    '1_TO_2Y=1–2 年前',
    'OVER_2Y=超过 2 年 / 没查过',
    'UNKNOWN=不清楚',
  ],
  dietEffort: ['COOK=愿意自己做', 'ADJUST_ORDER=只能调整点餐', 'MINIMAL=尽量少改变'],
  followupChannel: ['WECHAT=微信', 'PHONE=电话', 'ONSITE=到店'],
  followupFrequency: ['DAILY=每天', 'WEEKLY_2_3=每周 2–3 次', 'WEEKLY_1=每周 1 次'],
  consentGiven: ['YES=已同意'],
  consentMethod: ['VERBAL=口头', 'WRITTEN=书面', 'ELECTRONIC=电子'],
  referral: [
    'NONE=无',
    'NUTRITION_CLINIC=营养科',
    'ENDOCRINE=内分泌科',
    'CARDIOLOGY=心内科',
    'PSYCH=心理科',
    'OBSTETRICS=产科',
    'OTHER=其他',
  ],
  measureMethod: ['ONSITE=现场测量', 'DEVICE=家用设备', 'SELF_REPORT=客户自述'],
  familyHistoryFirstDegree: [
    'FH_DIABETES=糖尿病',
    'FH_HYPERTENSION=高血压',
    'FH_CHD=冠心病',
    'FH_STROKE=脑卒中',
    'FH_EARLY_MI=早发心梗（男 55 岁 / 女 65 岁前发病）',
    'NONE=都没有',
    'UNKNOWN=不清楚',
  ],
  dmChronicComplications: [
    'NEPHROPATHY=糖尿病肾病',
    'RETINOPATHY=糖尿病视网膜病变',
    'NEUROPATHY=周围神经病变',
    'DIABETIC_FOOT=糖尿病足',
    'NONE=都没有',
  ],
}

function parseOptionPairs(pairs: string[]): DictOption[] {
  return pairs
    .map((p) => {
      const i = p.indexOf('=')
      if (i <= 0) return null
      return { code: p.slice(0, i), label: p.slice(i + 1) }
    })
    .filter((x): x is DictOption => !!x)
}

function fallbackOptions(parentCode: string): DictOption[] {
  const pairs = OPTION_SET_FALLBACKS[parentCode] || DICT_OPTION_FALLBACKS[parentCode]
  return pairs ? parseOptionPairs(pairs) : []
}

/** 按 options parentCode 拉字典；失败或 desc 等于 code 时用本地中文兜底 */
export async function loadDictOptions(parentCode: string): Promise<DictOption[]> {
  if (!parentCode) return []
  const key = verCacheKey(parentCode)
  const cached = optionCache.get(key)
  if (cached) return cached
  const fb = fallbackOptions(parentCode)
  const fbByCode = new Map(fb.map((o) => [o.code, o.label]))
  try {
    const res = await api<{ data: Array<{ dictCode: string; dictCodeDesc: string }> }>(
      `/api/b/v1/dict?dictType=OPTION&parentCode=${encodeURIComponent(parentCode)}`,
    )
    const byCode = new Map<string, DictOption>()
    for (const d of res.data || []) {
      const code = d.dictCode
      const desc = (d.dictCodeDesc || '').trim()
      const label = isUsefulLabel(code, desc) ? desc : fbByCode.get(code) || desc || code
      byCode.set(code, { code, label })
    }
    // 兜底补缺，并优先按本地兜底顺序排（与 field-catalog 一致）
    for (const o of fb) {
      if (!byCode.has(o.code)) byCode.set(o.code, o)
    }
    const ordered: DictOption[] = []
    for (const o of fb) {
      const hit = byCode.get(o.code)
      if (hit) ordered.push(hit)
      byCode.delete(o.code)
    }
    for (const rest of byCode.values()) ordered.push(rest)
    const final = ordered.length ? ordered : fb
    optionCache.set(key, final)
    return final
  } catch {
    optionCache.set(key, fb)
    return fb
  }
}

/** 收集模块里带 options 的 parentCode（ENUM/MULTI） */
export function collectOptionParentCodes(modules: IntakeModuleView[] | null | undefined): string[] {
  const set = new Set<string>()
  for (const m of modules || []) {
    for (const it of m.items || []) {
      if (it.options) set.add(it.options)
      const byField = it.field ? FIELD_OPTION_PARENT[it.field] : null
      if (byField) set.add(byField)
    }
  }
  return [...set]
}

/** 批量加载选项字典 */
export async function loadIntakeOptionMaps(
  modules: IntakeModuleView[] | null | undefined,
): Promise<IntakeOptionMaps> {
  const codes = collectOptionParentCodes(modules)
  const maps: IntakeOptionMaps = {}
  await Promise.all(
    codes.map(async (code) => {
      maps[code] = await loadDictOptions(code)
    }),
  )
  return maps
}

export function responseStatusLabel(status?: string | null): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '已提交',
    VOID: '已作废',
    CANCELLED: '已取消',
  }
  if (!status) return '—'
  return map[status] || status
}

/** 常见字典父码（走 DB，不在 optionSets 里）的本地兜底，避免详情页短暂/失败时露出英文码 */
const DICT_OPTION_FALLBACKS: Record<string, string[]> = {
  presentIllness: [
    'NONE=无',
    'HYPERTENSION=高血压',
    'CHD=冠心病',
    'STROKE=脑卒中',
    'HEART_FAILURE=心力衰竭',
    'ATRIAL_FIBRILLATION=心房颤动',
    'DIABETES=2型糖尿病',
    'HYPERLIPIDEMIA=高脂血症',
    'GOUT=高尿酸血症/痛风',
    'COPD=慢性阻塞性肺疾病',
    'ASTHMA=支气管哮喘',
    'CKD=慢性肾脏病',
    'CHRONIC_HEPATITIS=慢性肝炎',
    'CIRRHOSIS=肝硬化',
    'NAFLD=脂肪肝',
    'CHRONIC_GI_DISEASE=慢性胃炎/消化性溃疡',
    'OSTEOPOROSIS=骨质疏松症',
    'RHEUMATOID_ARTHRITIS=类风湿关节炎',
    'OSTEOARTHRITIS=骨关节炎',
    'ALZHEIMERS=阿尔茨海默病',
    'PARKINSONS=帕金森病',
    'DEPRESSION=抑郁症',
    'ANXIETY=焦虑症',
    'MALIGNANT_TUMOR=恶性肿瘤',
    'BPH=良性前列腺增生',
    'THYROID_DISORDER=甲状腺功能减退/亢进',
    'OBESITY=肥胖症',
    'PREDIABETES=糖尿病前期（血糖偏高）',
    'PCOS=多囊卵巢综合征',
    'OSA=睡眠呼吸暂停',
  ],
  exerciseFrequency: [
    'NONE=基本不运动',
    'WEEKLY_1=每周1-2次',
    'WEEKLY_3=每周≥3次',
    'DAILY=每天',
  ],
  exerciseIntensity: ['LIGHT=轻度', 'MODERATE=中度', 'VIGOROUS=重度'],
  appetiteLevel: ['POOR=差', 'NORMAL=一般', 'GOOD=好', 'EXCESSIVE=旺盛'],
  dietHabit: ['REGULAR=规律', 'IRREGULAR=不规律'],
  dietType: ['LIGHT=清淡', 'OILY=油腻', 'SALTY=偏咸', 'SWEET=偏甜', 'MIXED=杂食'],
  sleepQuality: ['GOOD=好', 'FAIR=一般', 'POOR=差'],
  sleepDisorder: ['NONE=没有', 'INSOMNIA=失眠', 'SNORING=打鼾', 'OTHER=其他'],
  smoking: ['NEVER=从不', 'FORMER=已戒', 'CURRENT=仍吸', 'OCCASIONAL=偶尔'],
  drinking: ['NEVER=从不', 'FORMER=已戒', 'CURRENT=仍饮', 'OCCASIONAL=偶尔'],
  drinkingFrequency: ['NONE=基本不喝', 'OCCASIONAL=偶尔', 'WEEKLY=每周', 'DAILY=每天'],
}

/** 与 AllergyReactionEnum 对齐 */
const ALLERGY_REACTION_LABELS: Record<string, string> = {
  RASH: '皮疹/荨麻疹',
  ITCH: '瘙痒',
  ANGIOEDEMA: '口唇/眼睑肿胀',
  DYSPNEA: '喘息/呼吸困难',
  ANAPHYLAXIS: '过敏性休克',
  NAUSEA_VOMIT: '恶心呕吐',
  ABDOMINAL: '腹痛腹泻',
  RHINITIS: '鼻塞流涕/喷嚏',
  EYE: '眼痒流泪',
  OTHER: '其他',
}

const ALLERGY_TYPE_LABELS: Record<string, string> = {
  ALLERGY: '过敏',
  INTOLERANCE: '不耐受',
  SENSITIVITY: '敏感',
  UNKNOWN: '不清楚',
}

/** 字段 → options parentCode（item.options 缺失时兜底） */
const FIELD_OPTION_PARENT: Record<string, string> = {
  'profile.gender': 'gender',
  'profile.maritalStatus': 'maritalStatus',
  'profile.workSchedule': 'workSchedule',
  'measure.method': 'measureMethod',
  'basic.presentIllness': 'presentIllness',
  'basic.familyHistoryStatus': 'historyStatus',
  'basic.familyHistory.firstDegree': 'familyHistoryFirstDegree',
  'basic.pastHistoryStatus': 'historyStatus',
  'basic.diet.appetite': 'appetiteLevel',
  'basic.diet.habit': 'dietHabit',
  'basic.diet.type': 'dietType',
  'basic.diet.restriction': 'dietRestriction',
  'basic.diet.mealRegularity': 'mealRegularity',
  'basic.diet.breakfast': 'breakfast',
  'basic.diet.takeoutFrequency': 'takeoutFrequency',
  'basic.diet.snackNight': 'weeklyFrequency3',
  'basic.diet.sugaryDrinks': 'sugaryDrinks',
  'basic.diet.staple': 'staple',
  'basic.diet.vegFruit': 'vegFruit',
  'basic.diet.cooking': 'cooking',
  'basic.diet.eatingSpeed': 'eatingSpeed',
  'basic.diet.taste': 'taste',
  'basic.diet.trackedBefore': 'dietTracked',
  'basic.diet.cook': 'cook',
  'basic.diet.livingAlone': 'yesNo',
  'basic.exercise.frequency': 'exerciseFrequency',
  'basic.exercise.intensity': 'exerciseIntensity',
  'basic.exercise.types': 'exerciseTypes',
  'basic.exercise.strengthExperience': 'strengthExperience',
  'basic.exercise.dailySteps': 'dailySteps',
  'basic.exercise.availableSlots': 'exerciseSlots',
  'basic.exercise.venue': 'exerciseVenue',
  'basic.sleep.quality': 'sleepQuality',
  'basic.sleep.disorder': 'sleepDisorder',
  'basic.sleep.lateNight': 'weeklyFrequency3',
  'basic.lifestyle.smoking.status': 'smoking',
  'basic.lifestyle.smoking.passive': 'yesNoUnknown',
  'basic.lifestyle.drinking.status': 'drinking',
  'basic.lifestyle.drinking.frequency': 'drinkingFrequency',
  'basic.lifestyle.activityLevel': 'activityLevel',
  'basic.women.reproductiveStatus': 'reproductiveStatus',
  'basic.women.menstrualStatus': 'menstrualStatus',
  'basic.women.earlyMenopause': 'yesNoUnknown',
  'basic.women.gdmHistory': 'yesNoUnknown',
  'basic.medicationFlags': 'medicationFlags',
  'basic.supplements': 'supplements',
  'basic.medical.exerciseRestriction': 'exerciseRestriction',
  'basic.medical.jointIssues': 'jointIssues',
  'basic.medical.recentHospitalization': 'recentHospitalization',
  'basic.medical.steroidHyperglycemia': 'yesNoUnknown',
  'basic.medical.acanthosis': 'yesNoUnknown',
  'allergy.status': 'allergyStatus',
  'allergy.foodTags': 'foodAllergyTags',
  'allergy.drugTags': 'drugAllergyTags',
  'basic.psych.stressLevel': 'stressLevel',
  'basic.psych.stressEating': 'stressEating',
  'basic.psych.emotionalEating': 'emotionalEating',
  'basic.psych.readiness': 'readiness',
  'basic.psych.familySupport': 'familySupport',
  'basic.psych.willingToRecord': 'willingToRecord',
  'basic.psych.eatingDisorderHistory': 'eatingDisorderHistory',
  'basic.psych.soughtHelp': 'soughtHelp',
  'basic.psych.bodySatisfaction': 'bodySatisfaction',
  'basic.psych.bodyAnxiety': 'bodyAnxiety',
  'basic.weightHistory.triedBefore': 'weightLossTried',
  'basic.weightHistory.methods': 'weightLossMethods',
  'basic.weightHistory.failReasons': 'weightLossFailReasons',
  'basic.weightHistory.recentTrend': 'weightTrend',
  'basic.weightHistory.expectedPace': 'expectedPace',
  'goal.careTracks': 'careTracks',
  'goal.primaryAppeal': 'primaryAppeal',
  'goal.doctorAdvised': 'yesNo',
  'goal.mode': 'goalMode',
  'goal.targetDate': 'weightTargetHorizon',
  'disease.diabetes.type': 'dmType',
  'disease.diabetes.hypoglycemia': 'dmHypoglycemia',
  'disease.diabetes.monitorFreq': 'dmMonitorFreq',
  'disease.diabetes.chronicComplications': 'dmChronicComplications',
  'disease.diabetes.medAdherence': 'medAdherence',
  'disease.diabetes.eyeCheck': 'eyeCheckRecency',
  'disease.hypertension.homeMonitor': 'htnHomeMonitor',
  'disease.hypertension.morningPeak': 'htnMorningPeak',
  'disease.hypertension.medAdherence': 'medAdherence',
  'disease.hypertension.orthostatic': 'yesNoUnsure',
  'goal.dietEffort': 'dietEffort',
  'goal.followupChannel': 'followupChannel',
  'goal.followupFrequency': 'followupFrequency',
  'consent.sensitiveHealth': 'consentGiven',
  'consent.method': 'consentMethod',
  'staff.referral': 'referral',
}

/** 非 optionSets：亲属 / 用药频次 / 过敏临床状态等 */
const EXTRA_CODE_LABELS: Record<string, string> = (() => {
  const out: Record<string, string> = {
    ...ALLERGY_REACTION_LABELS,
    ...ALLERGY_TYPE_LABELS,
    ACTIVE: '仍过敏',
    RESOLVED: '已消失',
    INACTIVE: '不活动',
    CONFIRMED: '已确认',
    UNCONFIRMED: '未确认',
    REFUTED: '已排除',
    FOOD: '食物',
    DRUG: '药物',
    OTHER_CATEGORY: '其他',
    ORAL: '口服',
    INJECTION: '注射',
    TOPICAL: '外用',
    INHALATION: '吸入',
    QD: '每日1次',
    BID: '每日2次',
    TID: '每日3次',
    QID: '每日4次',
    QN: '每晚1次',
    QOD: '隔日1次',
    PRN: '必要时',
    MILD: '轻度',
    MODERATE: '中度',
    SEVERE: '严重',
  }
  for (const level of Object.keys(KINSHIP_MEMBERS) as KinshipLevel[]) {
    for (const m of KINSHIP_MEMBERS[level]) out[m.value] = m.label
  }
  for (const g of FAMILY_HISTORY_DISEASE_GROUPS) {
    for (const d of g.options) out[d.value] = d.label
  }
  return out
})()

function isUsefulLabel(code: string, label?: string | null) {
  if (!label) return false
  const t = label.trim()
  if (!t) return false
  // 字典偶发把 code 原样塞进 desc，不能当中文用
  if (t === code) return false
  return true
}

function looksLikeOptionCode(raw: string): boolean {
  if (!raw || raw === '已采集') return false
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return false
  if (raw.length > 48) return false
  // 含中文则已是可读文案
  if (/[\u4e00-\u9fff]/.test(raw)) return false
  return /^[A-Za-z][A-Za-z0-9_]*$/.test(raw)
}

function lookupFallbackLabel(code: string, parentCode?: string | null): string | null {
  if (EXTRA_CODE_LABELS[code]) return EXTRA_CODE_LABELS[code]
  if (parentCode) {
    const fb = fallbackOptions(parentCode).find((o) => o.code === code)
    if (isUsefulLabel(code, fb?.label)) return fb!.label
  }
  for (const pairs of Object.values(OPTION_SET_FALLBACKS)) {
    const hit = parseOptionPairs(pairs).find((o) => o.code === code)
    if (isUsefulLabel(code, hit?.label)) return hit!.label
  }
  for (const pairs of Object.values(DICT_OPTION_FALLBACKS)) {
    const hit = parseOptionPairs(pairs).find((o) => o.code === code)
    if (isUsefulLabel(code, hit?.label)) return hit!.label
  }
  return null
}

function optionLabelOf(parentCode: string | null | undefined, code: string, maps?: IntakeOptionMaps) {
  if (!code) return ''
  if (parentCode && maps?.[parentCode]) {
    const hit = maps[parentCode].find((o) => o.code === code)
    if (isUsefulLabel(code, hit?.label)) return hit!.label
  }
  const fb = lookupFallbackLabel(code, parentCode)
  if (fb) return fb
  // 扫已加载 maps（跳过 label===code）
  if (maps) {
    for (const list of Object.values(maps)) {
      const hit = list.find((o) => o.code === code)
      if (isUsefulLabel(code, hit?.label)) return hit!.label
    }
  }
  return code
}

function resolveOptionsParent(
  item?: Pick<IntakeItemView, 'options' | 'field'> | null,
): string | null {
  if (item?.options) return item.options
  if (item?.field && FIELD_OPTION_PARENT[item.field]) return FIELD_OPTION_PARENT[item.field]
  return null
}

function formatStructuredItem(item: unknown, maps?: IntakeOptionMaps): string | null {
  if (item == null) return null
  // 原始码字符串留给 optionLabelOf，这里只处理结构化对象
  if (typeof item !== 'object' || Array.isArray(item)) return null
  const o = item as Record<string, unknown>

  if ('drugName' in o || 'usageMethod' in o) {
    const name = String(o.drugName ?? '').trim()
    if (!name) return null
    const parts = [name]
    const dose = [o.doseAmount, o.doseUnit].filter((x) => x != null && String(x).trim()).join('')
    if (dose) parts.push(dose)
    const usage = o.usageMethod != null ? optionLabelOf(null, String(o.usageMethod), maps) : ''
    if (usage && usage !== String(o.usageMethod)) parts.push(usage)
    else if (o.usageMethod) {
      const raw = String(o.usageMethod)
      const usageMap: Record<string, string> = {
        ORAL: '口服',
        INJECTION: '注射',
        TOPICAL: '外用',
        INHALATION: '吸入',
        OTHER: '其他',
      }
      if (usageMap[raw]) parts.push(usageMap[raw])
    }
    if (o.timingNote) parts.push(`（${String(o.timingNote).trim()}）`)
    return parts.join(' ')
  }

  if ('allergenName' in o || 'allergenCode' in o || 'reactions' in o) {
    const name = String(o.allergenName || o.allergenCode || '').trim() || '未命名过敏原'
    const severityMap: Record<string, string> = {
      MILD: '轻度',
      MODERATE: '中度',
      SEVERE: '严重',
      UNKNOWN: '不清楚',
    }
    const bits = [name]
    const typeLabel = o.allergyType != null ? ALLERGY_TYPE_LABELS[String(o.allergyType)] : ''
    if (typeLabel) bits.push(typeLabel)
    const sev = o.severity != null ? severityMap[String(o.severity)] || '' : ''
    if (sev && sev !== '不清楚') bits.push(sev)
    if (Array.isArray(o.reactions) && o.reactions.length) {
      bits.push(
        o.reactions
          .map((r) => optionLabelOf(null, String(r), maps) || String(r))
          .join('/'),
      )
    }
    return bits.join(' · ')
  }

  if ('member' in o && 'diseases' in o) {
    const member = String(o.member || '').trim()
    const diseases = Array.isArray(o.diseases) ? o.diseases.map(String).filter(Boolean) : []
    if (!member && !diseases.length) return null
    const entry: FamilyHistoryEntry = {
      kinshipLevel: (String(o.kinshipLevel || '1') as KinshipLevel) || '1',
      member,
      diseases,
      note: o.note ? String(o.note) : undefined,
    }
    return formatFamilyHistoryTag(entry)
  }

  return null
}

function formatObjectAnswer(v: Record<string, unknown>): string {
  // 既往史结构化
  if (
    'diseases' in v ||
    'surgeries' in v ||
    'allergies' in v ||
    'transfusions' in v ||
    'vaccinations' in v ||
    'medicationNote' in v
  ) {
    const parts: string[] = []
    const pushList = (label: string, key: string, nameKey: string) => {
      const arr = v[key]
      if (!Array.isArray(arr) || !arr.length) return
      const names = arr
        .map((x) => {
          if (!x || typeof x !== 'object') return null
          const n = String((x as Record<string, unknown>)[nameKey] || '').trim()
          return n || null
        })
        .filter(Boolean)
      if (names.length) parts.push(`${label}${names.join('、')}`)
    }
    pushList('疾病：', 'diseases', 'name')
    pushList('手术：', 'surgeries', 'name')
    pushList('过敏：', 'allergies', 'name')
    pushList('输血：', 'transfusions', 'name')
    pushList('接种：', 'vaccinations', 'vaccine')
    if (typeof v.medicationNote === 'string' && v.medicationNote.trim()) {
      parts.push(`用药：${v.medicationNote.trim()}`)
    }
    if (parts.length) return parts.join('；')
  }
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/**
 * 答案展示：ENUM/MULTI 转中文；LIST/OBJECT 转可读摘要。
 * @param item 题目元数据（含 type / options）
 * @param maps loadIntakeOptionMaps 结果
 */
export function formatAnswerBrief(
  v: unknown,
  item?: Pick<IntakeItemView, 'type' | 'options' | 'unit' | 'field'> | null,
  maps?: IntakeOptionMaps,
): string {
  if (isAnswerEmpty(v)) return '—'

  const parent = resolveOptionsParent(item)
  const type = (item?.type || '').toUpperCase()

  if (Array.isArray(v)) {
    const parts = v
      .map((itemVal) => {
        if (itemVal && typeof itemVal === 'object' && !Array.isArray(itemVal)) {
          const structured = formatStructuredItem(itemVal, maps)
          if (structured != null) return structured
        }
        if (typeof itemVal === 'string' || typeof itemVal === 'number') {
          const raw = String(itemVal)
          if (type === 'ENUM' || type === 'MULTI' || parent || looksLikeOptionCode(raw)) {
            return optionLabelOf(parent, raw, maps)
          }
          return raw
        }
        return null
      })
      .filter((x): x is string => !!x && !!String(x).trim())
    return parts.join('、') || '—'
  }

  if (v && typeof v === 'object') {
    const structured = formatStructuredItem(v, maps)
    if (structured != null) return structured
    return formatObjectAnswer(v as Record<string, unknown>)
  }

  const raw = String(v)
  if (type === 'NUMBER') {
    return item?.unit ? `${raw} ${item.unit}` : raw
  }
  // 所有像选项码的字符串（含 has/none 小写）一律尝试中文
  if (type === 'ENUM' || type === 'MULTI' || parent || looksLikeOptionCode(raw)) {
    return optionLabelOf(parent, raw, maps)
  }
  return raw
}
