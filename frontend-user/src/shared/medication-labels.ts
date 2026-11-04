/** 用药展示文案（与 B 端字典对齐，本地兜底） */

export const MED_USAGE_LABELS: Record<string, string> = {
  ORAL: '口服',
  SUBLINGUAL: '舌下含服',
  INHALATION: '吸入',
  TOPICAL: '外用',
  INJECTION: '注射',
  OTHER: '其他',
}

export const MED_FREQUENCY_LABELS: Record<string, string> = {
  QD: '每日1次',
  BID: '每日2次',
  TID: '每日3次',
  QID: '每日4次',
  QN: '每晚1次',
  QOD: '隔日1次',
  QW: '每周1次',
  PRN: '必要时',
  OTHER: '其他',
}

export const MED_DOSE_UNIT_LABELS: Record<string, string> = {
  G: 'g',
  MG: 'mg',
  ML: 'ml',
  TABLET: '片',
  CAPSULE: '粒',
  BAG: '袋',
  BOTTLE: '支',
  SPRAY: '喷',
  OTHER: '其他',
}

export const MED_INTAKE_STATUS_LABELS: Record<string, string> = {
  TAKEN: '已服',
  MISSED: '漏服',
  SKIPPED: '跳过',
}

export const MED_TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '早',
  NOON: '午',
  EVENING: '晚',
  BEDTIME: '睡前',
  PRN: '需要时',
  OTHER: '其他',
}

/** 时间线分组标题 */
export const MED_SLOT_TITLES: Record<string, string> = {
  MORNING: '早上',
  NOON: '中午',
  EVENING: '晚上',
  BEDTIME: '睡前',
  PRN: '需要时服用',
}

export const MED_TIMED_SLOTS = ['MORNING', 'NOON', 'EVENING', 'BEDTIME'] as const

export const MED_FEEDBACK_KIND_LABELS: Record<string, string> = {
  ADVERSE: '不适上报',
  ADJUST: '申请调整',
  STOPPED: '我已停药',
  ALLERGY_CONFLICT: '过敏冲突',
}

export const MED_SYMPTOMS: { code: string; label: string }[] = [
  { code: 'RASH', label: '皮疹或瘙痒' },
  { code: 'DIZZY', label: '头晕' },
  { code: 'GI', label: '胃肠不适' },
  { code: 'PALPITATION', label: '心慌' },
  { code: 'FATIGUE', label: '乏力' },
  { code: 'COUGH', label: '咳嗽' },
  { code: 'EDEMA', label: '水肿' },
  { code: 'OTHER', label: '其他' },
]

export const MED_SEVERITY_LABELS: Record<string, string> = {
  MILD: '轻度',
  MODERATE: '中度',
  SEVERE: '重度',
}

export const MED_STOP_REASONS: { code: string; label: string }[] = [
  { code: 'IMPROVED', label: '自觉好转' },
  { code: 'SIDE_EFFECT', label: '副作用' },
  { code: 'DOCTOR', label: '医生让停' },
  { code: 'OTHER', label: '其他' },
]

export type FeedbackMode = 'ADVERSE' | 'ADJUST' | 'STOP'

/** 药箱来源标签 */
export function medSourceLabel(m: { source?: string | null; verified?: boolean | null }): string {
  if (m.source !== 'PATIENT') return '医护开立'
  return m.verified ? '自己添加·已核对' : '自己添加·待核对'
}

export interface MedicationView {
  id: string
  drugCode?: string | null
  drugName: string
  usageMethod?: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  timingNote?: string | null
  courseDays?: number | null
  startDate?: string | null
  stopDate?: string | null
  remark?: string | null
  status?: string
  source?: string
  hasAdverseReaction?: boolean | null
  dueDoseCount?: number
  prn?: boolean
  suggestedSlots?: string[]
  slotsCustom?: boolean
  patientEditable?: boolean
  verified?: boolean | null
  allergyConflict?: string | null
  stopReason?: string | null
  openFeedbackCount?: number
}

export interface MedicationFeedback {
  id: string
  medicationId: string
  drugName?: string
  kind: string
  kindLabel?: string
  symptoms?: string[]
  symptomLabels?: string[]
  severity?: string | null
  content?: string | null
  status: 'OPEN' | 'HANDLED'
  reply?: string | null
  handledByName?: string | null
  handledAt?: string | null
  gmtCreated?: string
}

export interface ScheduleItem {
  medicationId: string
  drugName: string
  doseAmount?: string | null
  doseUnit?: string | null
  usageMethod?: string | null
  timingNote?: string | null
  source?: string
  intakeId?: string | null
  status?: 'TAKEN' | 'MISSED' | 'SKIPPED' | null
  takenAt?: string | null
  backfill: boolean
}

export interface ScheduleSlot {
  slot: string
  label: string
  time: string
  past: boolean
  pendingCount: number
  items: ScheduleItem[]
}

export interface SchedulePrn {
  medicationId: string
  drugName: string
  doseAmount?: string | null
  doseUnit?: string | null
  timingNote?: string | null
  count: number
  max: number
  doses: { intakeId: string; seq: number; takenAt?: string | null }[]
}

export interface MedicationSchedule {
  date: string
  today: boolean
  editable: boolean
  slots: ScheduleSlot[]
  prn: SchedulePrn[]
  summary: { due: number; taken: number; missed: number; skipped: number; unrecorded: number }
  nextSlot?: string | null
}

export interface MedicationReminder {
  enabled: boolean
  morningAt: string
  noonAt: string
  eveningAt: string
  bedtimeAt: string
  followUpMin: number
}

export interface AdherenceCell {
  date: string
  slot: string
  status: string
  takenAt?: string | null
  backfill: boolean
  count: number
}

export interface MedicationAdherence {
  from: string
  to: string
  stats: {
    due: number
    taken: number
    missed: number
    skipped: number
    unrecorded: number
    ratePercent: number | null
    onTimePercent: number | null
    prnCount: number
  }
  days: { date: string; due: number; taken: number; missed: number; skipped: number; unrecorded: number; backfill: number }[]
  meds: { medicationId: string; drugName: string; prn: boolean; slots: string[]; cells: AdherenceCell[] }[]
}

/** "2026-10-14T08:12:00" → "08:12" */
export function hhmm(dt?: string | null): string {
  if (!dt) return ''
  const m = /T(\d{2}:\d{2})/.exec(dt) || /\s(\d{2}:\d{2})/.exec(dt)
  return m ? m[1] : ''
}

export function medLabel(map: Record<string, string>, code?: string | null): string {
  if (!code) return ''
  const raw = map[code] ?? code
  return raw.replace(/\s*\([^)]*\)\s*$/, '').trim() || raw
}

export function formatDose(amount?: string | null, unit?: string | null, unitMap?: Record<string, string>): string {
  if (!amount && !unit) return ''
  const u = (unitMap && unit ? unitMap[unit] : undefined) || medLabel(MED_DOSE_UNIT_LABELS, unit) || unit || ''
  const a = amount?.trim() ?? ''
  const tight = ['g', 'mg', 'ml', 'G', 'MG', 'ML'].includes(u)
  return tight ? `${a}${u}` : `${a}${a && u ? ' ' : ''}${u}`.trim()
}

export function formatMedicationLine(row: {
  doseAmount?: string | null
  doseUnit?: string | null
  usageMethod?: string | null
  frequency?: string | null
  timingNote?: string | null
  courseDays?: number | null
  stopDate?: string | null
}, maps?: {
  usage?: Record<string, string>
  frequency?: Record<string, string>
  doseUnit?: Record<string, string>
}): string {
  const parts: string[] = []
  const dose = formatDose(row.doseAmount, row.doseUnit, maps?.doseUnit)
  if (dose) parts.push(dose)
  const usage = maps?.usage
    ? medLabel(maps.usage, row.usageMethod) || row.usageMethod || ''
    : medLabel(MED_USAGE_LABELS, row.usageMethod)
  if (usage) parts.push(usage)
  const freq = maps?.frequency
    ? medLabel(maps.frequency, row.frequency) || row.frequency || ''
    : medLabel(MED_FREQUENCY_LABELS, row.frequency)
  if (freq) parts.push(freq)
  if (row.courseDays != null && row.courseDays > 0) parts.push(`×${row.courseDays}天`)
  else if (!row.stopDate) parts.push('长期')
  let line = parts.join(' · ') || '按说明服用'
  if (row.timingNote?.trim()) line += `（${row.timingNote.trim()}）`
  return line
}
