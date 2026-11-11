/** 疾病评估（患病风险评估 / 严重等级分层）前端类型与展示辅助 */

import { ref } from 'vue'

/** 评估结果变更后递增，供患者详情头图等旁路刷新 */
export const assessmentTick = ref(0)

export function notifyAssessmentChanged() {
  assessmentTick.value++
}

export type AssessmentTone = 'danger' | 'warning' | 'success' | 'info' | 'muted'

export interface AssessmentDimension {
  code: string
  label: string
  value?: string
  tier?: string | null
  hint?: string | null
}

export interface AssessmentItem {
  code: string
  label: string
  value?: string
  points?: number | null
  rationale?: string | null
}

export interface AssessmentHit {
  code: string
  label: string
  detail?: string | null
  source?: string | null
}

export interface AssessmentUnchecked {
  field: string
  label: string
  questionnaire: boolean
}

export interface AssessmentFactorGroup {
  code: string
  title: string
  hits: AssessmentHit[]
  unchecked: AssessmentUnchecked[]
}

export interface AssessmentMissing {
  field: string
  label: string
  fillHint?: string | null
  questionnaire: boolean
}

export interface AssessmentComparison {
  label: string
  value?: string
  computed?: string
  consistent: boolean
  hint?: string | null
}

export interface AssessmentSnapshot {
  id: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  engineCode: string
  engineLabel: string
  diseaseCode: string
  diseaseLabel: string
  rulePackVersion?: string
  provisional: boolean
  status: 'COMPLETE' | 'INCOMPLETE' | string
  level?: string | null
  levelLabel?: string | null
  tier?: string | null
  tierLabel?: string | null
  tone?: AssessmentTone
  score?: number | null
  advice?: string | null
  dimensions: AssessmentDimension[]
  items: AssessmentItem[]
  factorGroups: AssessmentFactorGroup[]
  missing: AssessmentMissing[]
  comparisons: AssessmentComparison[]
  notes: string[]
  suspicion?: { diseaseCode: string; diseaseLabel: string; reason: string } | null
  guideline?: { name?: string; version?: string; publishedYear?: number } | null
  triggerSource?: string
  triggerLabel?: string
  assessedAt?: string
  legacy: boolean
}

export interface AssessmentGroup {
  diseaseCode: string
  diseaseLabel: string
  diagnosed: boolean
  diagnosedReason?: string | null
  hasDiseaseArchive: boolean
  engineCode: string
  engineLabel: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  applicable: boolean
  notApplicableReason?: string | null
  latest?: AssessmentSnapshot | null
}

export interface QuestionnaireRequest {
  id: string
  status: 'PENDING' | 'SUBMITTED' | 'EXPIRED' | 'CANCELLED'
  createdAt?: string
  expiresAt?: string
  submittedAt?: string | null
}

export interface AssessmentOverview {
  groups: AssessmentGroup[]
  lastQuestionnaire?: { id: string; portal: 'B' | 'C'; submittedAt: string } | null
  pendingRequest?: QuestionnaireRequest | null
  evaluatedAt?: string
  disclaimer?: string
}

export interface QuestionOption {
  code: string
  label: string
}

export interface Question {
  field: string
  label: string
  type: 'NUMBER' | 'BOOL' | 'ENUM' | 'MULTI'
  text: string
  unit?: string | null
  min?: number | null
  max?: number | null
  options: QuestionOption[]
  audience: 'PATIENT' | 'STAFF'
  required: boolean
  missing: boolean
  currentValue?: unknown
  currentText?: string | null
  engines: string[]
}

export interface QuestionnaireView {
  sections: Array<{ code: string; title: string; questions: Question[] }>
  hints: Array<{ field: string; label: string; hint: string; engines: string[] }>
  missingCount: number
  totalCount: number
  pendingRequest?: QuestionnaireRequest | null
  lastSubmittedAt?: string | null
}

export interface QuestionnaireConflict {
  field: string
  label: string
  currentText?: string | null
  newText?: string | null
}

export interface QuestionnaireSubmitResult {
  status: 'OK' | 'CONFLICT'
  conflicts: QuestionnaireConflict[]
  submissionId?: string | null
  overview?: AssessmentOverview | null
}

export interface RuleSheetSection {
  title: string
  headers?: string[] | null
  rows?: string[][] | null
  bullets?: string[] | null
}

export interface AssessmentRuleSheet {
  engineCode: string
  engineLabel: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  diseaseCode: string
  diseaseLabel: string
  guidelineName?: string
  guidelineVersion?: string
  rulePackVersion: string
  provisional: boolean
  levels: Array<{ code: string; label: string; tier?: string | null; tierLabel?: string | null; tone?: AssessmentTone }>
  sheet: { summary?: string; sections: RuleSheetSection[]; notes?: string[] }
}

export const NONE_CODE = 'NONE'
export const UNKNOWN_CODE = 'UNKNOWN'

const SOURCE_LABELS: Record<string, string> = {
  ARCHIVE: '档案',
  METRIC: '指标',
  LAB: '检验',
  MEDICATION: '用药',
  QUESTIONNAIRE: '问卷',
  DERIVED: '推算',
}

export function sourceLabel(source?: string | null): string {
  return source ? SOURCE_LABELS[source] || source : ''
}

export function toneTagType(tone?: string | null): 'danger' | 'warning' | 'success' | 'info' | undefined {
  if (tone === 'danger' || tone === 'warning' || tone === 'success' || tone === 'info') return tone
  return 'info'
}

export function tierTone(tier?: string | null): AssessmentTone {
  if (tier === 'HIGH' || tier === 'VERY_HIGH') return 'danger'
  if (tier === 'MID') return 'warning'
  if (tier === 'LOW') return 'success'
  return 'muted'
}

export function formatDateTime(v?: string | null): string {
  const n = normalizeAssessedAt(v)
  return n ? n.slice(0, 16) : '—'
}

/** 把评估时间规范成可比较的字符串（兼容 string / Date / Jackson 数组） */
export function normalizeAssessedAt(v: unknown): string {
  if (v == null || v === '') return ''
  if (Array.isArray(v) && v.length >= 5) {
    const [y, m, d, h, min, sec = 0] = v as number[]
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(Number(sec) || 0)}`
  }
  if (v instanceof Date && !Number.isNaN(v.getTime())) {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${v.getFullYear()}-${pad(v.getMonth() + 1)}-${pad(v.getDate())} ${pad(v.getHours())}:${pad(v.getMinutes())}:${pad(v.getSeconds())}`
  }
  const s = String(v).trim().replace('T', ' ').replace(/\.\d+/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, '')
  // YYYY-MM-DD HH:mm:ss 或至少到分钟
  return s.slice(0, 19)
}

/** 评估时间截到分钟，用于展示去重 */
export function assessedAtMinute(v?: string | null): string {
  const n = normalizeAssessedAt(v)
  return n ? n.slice(0, 16) : ''
}

/** 一次评估动作的时间桶：精确到秒（同一次 recompute 共享同一 assessedAt） */
export function assessedAtSecond(v?: string | null): string {
  const n = normalizeAssessedAt(v)
  if (!n) return ''
  return n.length >= 19 ? n.slice(0, 19) : n.slice(0, 16)
}

export interface AssessmentHistoryCard {
  key: string
  assessedAt: string
  triggerSource?: string
  triggerLabel?: string
  items: AssessmentSnapshot[]
}

function snapshotRank(row: AssessmentSnapshot): number {
  let rank = 0
  if (row.status === 'COMPLETE') rank += 100
  if (row.levelLabel) rank += 40
  if (row.score != null) rank += 10
  if (row.advice) rank += 2
  return rank
}

function diseaseKey(row: AssessmentSnapshot): string {
  return `${row.diseaseCode || ''}|${row.engineCode || ''}`
}

function resultFingerprint(row: AssessmentSnapshot): string {
  return [row.level || '', row.levelLabel || '', row.score ?? '', row.status || ''].join('|')
}

function cardResultSignature(card: AssessmentHistoryCard): string {
  return card.items
    .map((i) => `${diseaseKey(i)}=${resultFingerprint(i)}`)
    .sort()
    .join(';')
}

function parseAssessedMs(v?: string | null): number {
  const n = normalizeAssessedAt(v)
  if (!n) return 0
  const t = Date.parse(n.replace(' ', 'T'))
  return Number.isNaN(t) ? 0 : t
}

/** 同一评估时刻 + 同一病种（引擎）只保留一条，优先完整结果 */
export function dedupeAssessmentSnapshots(items: AssessmentSnapshot[]): AssessmentSnapshot[] {
  const best = new Map<string, AssessmentSnapshot>()
  const order: string[] = []
  for (const row of items) {
    const key = [assessedAtSecond(row.assessedAt) || assessedAtMinute(row.assessedAt), diseaseKey(row)].join('||')
    const prev = best.get(key)
    if (!prev) {
      best.set(key, row)
      order.push(key)
      continue
    }
    if (snapshotRank(row) > snapshotRank(prev)) {
      best.set(key, row)
    }
  }
  return order.map((k) => best.get(k)!)
}

/**
 * 一次评估动作 = 一张卡片：按 assessedAt（到秒）GroupBy。
 * 同秒写入的高血压 / 糖尿病 / 肥胖会进同一张卡；若仅一个病种则卡片内只有一行。
 * 额外合并：来源相同、结果签名相同、间隔 ≤ 90 秒的幽灵连点批次。
 */
export function groupAssessmentHistoryCards(items: AssessmentSnapshot[]): AssessmentHistoryCard[] {
  const list = Array.isArray(items) ? items : []
  const deduped = dedupeAssessmentSnapshots(list)
  const map = new Map<string, AssessmentHistoryCard>()
  const order: string[] = []

  for (const row of deduped) {
    const bucket = assessedAtSecond(row.assessedAt) || assessedAtMinute(row.assessedAt) || `id:${row.id}`
    let card = map.get(bucket)
    if (!card) {
      card = {
        key: bucket,
        assessedAt: normalizeAssessedAt(row.assessedAt) || bucket,
        triggerSource: row.triggerSource,
        triggerLabel: row.triggerLabel,
        items: [],
      }
      map.set(bucket, card)
      order.push(bucket)
    } else if (!card.triggerLabel && row.triggerLabel) {
      card.triggerSource = row.triggerSource
      card.triggerLabel = row.triggerLabel
    }
    if (!card.items.some((i) => diseaseKey(i) === diseaseKey(row))) {
      card.items.push(row)
    }
  }

  // 稳定病种顺序：按 diseaseCode / engineCode
  for (const card of map.values()) {
    card.items.sort((a, b) => diseaseKey(a).localeCompare(diseaseKey(b)))
  }

  const rawCards = order.map((k) => map.get(k)!)
  // 合并幽灵连点：同来源、同结果、90 秒内只留最新一张
  const merged: AssessmentHistoryCard[] = []
  for (const card of rawCards) {
    const prev = merged[merged.length - 1]
    if (!prev) {
      merged.push(card)
      continue
    }
    const dt = Math.abs(parseAssessedMs(prev.assessedAt) - parseAssessedMs(card.assessedAt))
    const sameSource = (prev.triggerSource || '') === (card.triggerSource || '')
    const sameResult = cardResultSignature(prev) === cardResultSignature(card)
    if (sameSource && sameResult && dt <= 90_000) {
      // rawCards 按时间倒序（接口 DESC）；保留先出现的更新批次
      continue
    }
    merged.push(card)
  }
  return merged
}

/** 顶部得分计算说明（兼容旧调用） */
export function scoreBreakdownText(snap: AssessmentSnapshot): string {
  return patientScoreSummary(snap)
}

function cleanItemValue(raw?: string | null): string {
  if (!raw) return ''
  return raw.replace(/\s+/g, ' ').trim()
}

/** 患者得分摘要：患者当前年龄 34 岁（得 4 分），BMI 28.7（得 3 分），合计积分 XX 分，触发高危线。 */
export function patientScoreSummary(
  snap: AssessmentSnapshot,
  opts?: { /** 顶部已展示结论时，明细不再重复「触发红标」 */ omitLevel?: boolean },
): string {
  const scored = (snap.items || []).filter((i) => i.points != null)
  const total =
    snap.score != null
      ? snap.score
      : scored.reduce((sum, i) => sum + (Number(i.points) || 0), 0)
  const omitLevel = !!opts?.omitLevel

  if (!scored.length) {
    if (snap.score == null && (omitLevel || !snap.levelLabel)) {
      return snap.score != null ? `合计积分 ${snap.score} 分。` : ''
    }
    const bits: string[] = []
    if (snap.score != null) bits.push(`合计积分 ${snap.score} 分`)
    if (!omitLevel && snap.levelLabel) bits.push(`触发${snap.levelLabel}`)
    return bits.length ? `${bits.join('，')}。` : ''
  }

  const parts = scored.map((i) => {
    const label = i.label || ''
    const val = cleanItemValue(i.value)
    if (/年龄/.test(label)) {
      return `年龄 ${val || '—'}（得 ${i.points} 分）`
    }
    if (/BMI/i.test(label)) {
      const num = val.replace(/\s*kg\/m²/i, '').trim()
      return `BMI ${num || '—'}（得 ${i.points} 分）`
    }
    return `${label}${val ? ` ${val}` : ''}（得 ${i.points} 分）`
  })

  let text = `患者当前${parts.join('，')}`
  if (total != null && !Number.isNaN(Number(total))) {
    text += `，合计积分 ${total} 分`
  }
  if (!omitLevel && snap.levelLabel) {
    text += `，触发${snap.levelLabel}`
  }
  return `${text}。`
}

function normalizeBandText(s: string): string {
  return s
    .replace(/BMI\s*/gi, '')
    .replace(/[～~—－]/g, '–')
    .replace(/\s+/g, '')
    .trim()
}

function extractLeadingNumber(text?: string | null): number | null {
  if (!text) return null
  const m = String(text).replace(/,/g, '').match(/-?\d+(?:\.\d+)?/)
  return m ? Number(m[0]) : null
}

/** 解析规则表区间单元格：25–34、<22.0、≥30.0、110–119 */
function parseRangeCell(cell: string): { min: number; max: number; minInc: boolean; maxInc: boolean } | null {
  const t = normalizeBandText(cell)
  if (!t) return null
  let m = t.match(/^(\d+(?:\.\d+)?)[–\-](\d+(?:\.\d+)?)$/)
  if (m) {
    return { min: Number(m[1]), max: Number(m[2]), minInc: true, maxInc: true }
  }
  m = t.match(/^(≤|<=)(\d+(?:\.\d+)?)$/)
  if (m) return { min: -Infinity, max: Number(m[2]), minInc: true, maxInc: true }
  m = t.match(/^(<)(\d+(?:\.\d+)?)$/)
  if (m) return { min: -Infinity, max: Number(m[2]), minInc: true, maxInc: false }
  m = t.match(/^(≥|>=)(\d+(?:\.\d+)?)$/)
  if (m) return { min: Number(m[2]), max: Infinity, minInc: true, maxInc: true }
  m = t.match(/^(>)(\d+(?:\.\d+)?)$/)
  if (m) return { min: Number(m[2]), max: Infinity, minInc: false, maxInc: true }
  return null
}

function inRange(
  n: number,
  range: { min: number; max: number; minInc: boolean; maxInc: boolean },
): boolean {
  if (range.minInc ? n < range.min : n <= range.min) return false
  if (range.maxInc ? n > range.max : n >= range.max) return false
  return true
}

function scoreColIndex(headers?: string[] | null): number {
  if (!headers?.length) return -1
  return headers.findIndex((h) => /分值|得分|分数|积分/.test(h))
}

function itemMatchesSection(item: AssessmentItem, sectionTitle: string): boolean {
  const title = sectionTitle || ''
  const label = item.label || ''
  const code = (item.code || '').toUpperCase()
  if (/年龄/.test(title) && (/年龄/.test(label) || code === 'AGE')) return true
  if (/BMI/i.test(title) && (/BMI/i.test(label) || code === 'BMI')) return true
  if (/腰围/.test(title) && (/腰围/.test(label) || code === 'WAIST')) return true
  if (
    /收缩压|血压/.test(title) &&
    (/收缩压|血压|SBP/i.test(label) || code.includes('SBP') || code.includes('BP'))
  ) {
    return true
  }
  if (title && label && (title.includes(label) || label.includes(title.replace(/（.*）/g, '')))) {
    return true
  }
  return false
}

/**
 * 判断规则表某一行是否为患者命中项（浅蓝色高亮）。
 * 优先用评分项的区间说明 / 分值 / 数值落入区间匹配。
 */
export function isRuleRowHit(
  headers: string[] | null | undefined,
  row: string[],
  sectionTitle: string,
  snap?: AssessmentSnapshot | null,
): boolean {
  if (!snap || !row?.length) return false
  const items = snap.items || []
  const scored = items.filter((i) => i.points != null)
  const candidates = scored.filter((i) => itemMatchesSection(i, sectionTitle))
  const pool = candidates.length ? candidates : scored

  const sIdx = scoreColIndex(headers)
  for (const item of pool) {
    const rationale = normalizeBandText(item.rationale || '')
    for (const cell of row) {
      const c = normalizeBandText(cell)
      if (!c) continue
      if (rationale && (rationale === c || rationale.endsWith(c) || c.includes(rationale))) {
        if (sIdx < 0) return true
        const rowPts = extractLeadingNumber(row[sIdx])
        if (rowPts == null || Number(item.points) === rowPts) return true
      }
    }
    if (sIdx >= 0) {
      const rowPts = extractLeadingNumber(row[sIdx])
      if (rowPts != null && Number(item.points) === rowPts) {
        const val = extractLeadingNumber(item.value)
        if (val == null) return !!candidates.length
        const rangeCells = row.filter((_, idx) => idx !== sIdx)
        if (
          rangeCells.some((cell) => {
            const r = parseRangeCell(cell)
            return r ? inRange(val, r) : false
          })
        ) {
          return true
        }
        if (candidates.length) return true
      }
    }
  }

  const needles = [snap.levelLabel, snap.level, ...(snap.dimensions || []).map((d) => d.value)]
    .filter(Boolean)
    .map((x) => normalizeBandText(String(x)))
  if (needles.length) {
    const rowText = normalizeBandText(row.join('|'))
    if (needles.some((n) => n && n.length >= 2 && (rowText.includes(n) || n.includes(rowText)))) {
      return true
    }
  }
  return false
}

export function isScoreHeader(header: string): boolean {
  return /分值|得分|分数|积分/.test(header || '')
}

/** 多病种卡片打开依据时，优先选风险更高的引擎 */
export function pickPrimarySnapshot(items: AssessmentSnapshot[]): AssessmentSnapshot | null {
  if (!items?.length) return null
  const rank = (t?: string | null) => {
    if (t === 'danger') return 4
    if (t === 'warning') return 3
    if (t === 'info') return 2
    if (t === 'success') return 1
    return 0
  }
  return [...items].sort((a, b) => rank(b.tone) - rank(a.tone))[0] || items[0]
}

function dimOf(snap: AssessmentSnapshot, code: string): AssessmentDimension | undefined {
  return (snap.dimensions || []).find((d) => d.code === code)
}

/** 是否为心血管风险分层交叉表 */
export function isRiskMatrixSection(title: string, headers?: string[] | null): boolean {
  if (/风险分层表/.test(title || '')) return true
  const hs = headers || []
  return hs.some((h) => /^1\s*级/.test(h)) && hs.some((h) => /^2\s*级/.test(h))
}

function parseBpPair(text?: string | null): string | null {
  if (!text) return null
  const m = String(text).match(/(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)/)
  return m ? `${m[1]}/${m[2]}` : null
}

function parseBpGradeToken(text?: string | null): string | null {
  if (!text) return null
  const head = String(text).split('·')[0]?.trim() || ''
  if (/正常高值/.test(head)) return '正常高值'
  if (/3\s*级/.test(head)) return '3级'
  if (/2\s*级/.test(head)) return '2级'
  if (/1\s*级/.test(head)) return '1级'
  return null
}

/**
 * 心血管风险分层表命中交叉格（行=危险因素档，列=血压分级）。
 * 返回表格坐标：row 为数据行下标，col 为含表头列下标（0 为行标题列）。
 */
export function riskMatrixHitCell(
  headers: string[] | null | undefined,
  rows: string[][] | null | undefined,
  snap?: AssessmentSnapshot | null,
): { row: number; col: number } | null {
  if (!snap || !headers?.length || !rows?.length) return null
  const bpDim = dimOf(snap, 'BP_GRADE')
  const rowDim = dimOf(snap, 'ROW')
  const rowLabel = (rowDim?.value || '').trim()
  if (!rowLabel) return null

  const rowIdx = rows.findIndex((r) => {
    const cell = (r[0] || '').trim()
    return cell === rowLabel || normalizeBandText(cell) === normalizeBandText(rowLabel)
  })
  if (rowIdx < 0) return null

  const grade = parseBpGradeToken(bpDim?.value)
  let colIdx = -1
  if (grade) {
    colIdx = headers.findIndex((h, i) => {
      if (i === 0) return false
      if (grade === '正常高值') return /正常高值/.test(h)
      return new RegExp(`^${grade.replace('级', '\\s*级')}`).test(h.trim()) || h.trim() === grade
    })
  }
  // 兜底：用分层结果文字匹配矩阵格
  if (colIdx < 0) {
    const stratum = (dimOf(snap, 'STRATUM')?.value || snap.levelLabel || '').trim()
    if (stratum) {
      const row = rows[rowIdx] || []
      for (let c = 1; c < row.length; c++) {
        const cell = row[c] || ''
        if (cell.includes(stratum) || stratum.includes(cell.replace(/[—→].*$/, '').trim())) {
          colIdx = c
          break
        }
      }
    }
  }
  if (colIdx < 1) return null
  return { row: rowIdx, col: colIdx }
}

/** 分层表上方推理文案：当前患者血压 145/89，无其他危险因素，命中【低危】分层。 */
export function riskMatrixSummary(snap?: AssessmentSnapshot | null): string {
  if (!snap) return ''
  const bpDim = dimOf(snap, 'BP_GRADE')
  const rowDim = dimOf(snap, 'ROW')
  const stratum = dimOf(snap, 'STRATUM')?.value || snap.levelLabel || ''
  const bp = parseBpPair(bpDim?.value) || parseBpPair(bpDim?.hint) || '—'
  const rowText = (rowDim?.value || '').trim() || '危险因素情况未知'
  const level = (stratum || '—').replace(/^【|】$/g, '')
  return `当前患者血压 ${bp}，${rowText}，命中【${level}】分层。`
}

export type BulletHitStatus = 'hit' | 'miss'

function factorGroupForSection(snap: AssessmentSnapshot, sectionTitle: string) {
  const groups = snap.factorGroups || []
  const title = sectionTitle || ''
  if (/高危人群|高危因素/.test(title)) {
    return (
      groups.find((g) => g.code === 'DM_HIGH_RISK' || /高危/.test(g.title)) ||
      groups.find((g) => /危险因素|高危/.test(g.title)) ||
      null
    )
  }
  if (/危险因素/.test(title)) {
    return groups.find((g) => g.code === 'HTN_RF' || /危险因素/.test(g.title)) || null
  }
  if (/靶器官/.test(title)) {
    return groups.find((g) => g.code === 'HTN_TOD' || /靶器官/.test(g.title)) || null
  }
  if (/临床并发症|并发症/.test(title)) {
    return groups.find((g) => g.code === 'HTN_ACC' || /并发症/.test(g.title)) || null
  }
  return groups.find((g) => g.title && (title.includes(g.title) || g.title.includes(title))) || null
}

function labelsLooselyMatch(a: string, b: string): boolean {
  const na = normalizeBandText(a)
  const nb = normalizeBandText(b)
  if (!na || !nb) return false
  if (na === nb) return true
  if (na.includes(nb) || nb.includes(na)) return true
  // 关键关键词干后仍有较长公共子串
  const stem = (s: string) =>
    s
      .replace(/男>|女>|岁|或|及|的|与|和|≥|≤|<|>|\d+|mm|cm|mg|g|μmol|L|mmol/g, '')
      .slice(0, 12)
  const sa = stem(na)
  const sb = stem(nb)
  return !!(sa && sb && (sa.includes(sb) || sb.includes(sa)))
}

/**
 * 危险因素 / 靶器官损害 / 临床并发症条目是否命中。
 * 有对应 factorGroup 时返回 hit/miss；无患者数据时返回 null（不展示标签）。
 */
export function bulletHitStatus(
  bullet: string,
  sectionTitle: string,
  snap?: AssessmentSnapshot | null,
): BulletHitStatus | null {
  if (!snap || !bullet) return null
  const group = factorGroupForSection(snap, sectionTitle)
  if (!group) return null
  const hit = (group.hits || []).some((h) => labelsLooselyMatch(h.label || '', bullet))
  return hit ? 'hit' : 'miss'
}

/** 该 bullet 分区是否具备患者命中数据（用于决定是否渲染标签列） */
export function sectionHasBulletStatus(sectionTitle: string, snap?: AssessmentSnapshot | null): boolean {
  if (!snap) return false
  return !!factorGroupForSection(snap, sectionTitle)
}

/** 命中行的得分（用于「当前得分：X 分」） */
export function hitRowPoints(
  headers: string[] | null | undefined,
  row: string[],
  sectionTitle: string,
  snap?: AssessmentSnapshot | null,
): number | null {
  if (!snap || !isRuleRowHit(headers, row, sectionTitle, snap)) return null
  const sIdx = scoreColIndex(headers)
  if (sIdx >= 0) {
    const fromCell = extractLeadingNumber(row[sIdx])
    if (fromCell != null) return fromCell
  }
  const scored = (snap.items || []).filter((i) => i.points != null && itemMatchesSection(i, sectionTitle))
  if (scored.length === 1) return Number(scored[0].points)
  // 多候选时：分值列已处理；否则取与区间文案匹配的那条
  for (const item of scored) {
    const rationale = normalizeBandText(item.rationale || '')
    if (rationale && row.some((c) => {
      const cell = normalizeBandText(c)
      return cell && (rationale === cell || rationale.endsWith(cell) || cell.includes(rationale))
    })) {
      return Number(item.points)
    }
  }
  return scored[0] != null ? Number(scored[0].points) : null
}

/** 顶部结果总结：患者当前累计得分：22分（属于：高风险）。 */
export function patientResultHeadline(snap?: AssessmentSnapshot | null): {
  score: number | null
  levelLabel: string
  text: string
} | null {
  if (!snap) return null
  const score =
    snap.score != null
      ? Number(snap.score)
      : (snap.items || []).some((i) => i.points != null)
        ? (snap.items || []).reduce((sum, i) => sum + (Number(i.points) || 0), 0)
        : null
  const levelLabel = (snap.levelLabel || '').trim()
  if (score == null && !levelLabel) return null
  if (score != null && levelLabel) {
    return {
      score,
      levelLabel,
      text: `患者当前累计得分：${score}分（属于：${levelLabel}）。`,
    }
  }
  if (score != null) {
    return { score, levelLabel: '', text: `患者当前累计得分：${score}分。` }
  }
  return { score: null, levelLabel, text: `患者当前评估结果：${levelLabel}。` }
}

export function requestStatusText(req?: QuestionnaireRequest | null): string {
  if (!req) return ''
  switch (req.status) {
    case 'PENDING':
      return `已发送评估问卷，等待患者填写（有效至 ${formatDateTime(req.expiresAt)}）`
    case 'SUBMITTED':
      return `患者已于 ${formatDateTime(req.submittedAt)} 提交评估问卷`
    case 'EXPIRED':
      return `评估问卷已过期未填写（发送于 ${formatDateTime(req.createdAt)}）`
    default:
      return ''
  }
}
