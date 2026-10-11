/** 健康数据（检验 / 检查）展示文案 */

import { formatHealthDataSource } from './health-data-source'

export const SPECIMEN_TYPE_LABELS: Record<string, string> = {
  BLOOD: '血液',
  URINE: '尿液',
  OTHER: '其他',
}

export const EXAM_TYPE_LABELS: Record<string, string> = {
  ECG: '心电图',
  UCG: '心脏彩超',
  CAROTID_US: '颈动脉彩超',
  ABDOMINAL_US: '腹部超声',
  THYROID_US: '甲状腺超声',
  CHEST_IMAGING: '胸部影像',
  ABDOMINAL_CT: '腹部CT',
  PFT: '肺功能',
  BMD: '骨密度',
  FUNDUS: '眼底检查',
}

export const ABNORMAL_FLAG_LABELS: Record<string, string> = {
  H: '偏高',
  L: '偏低',
  HH: '危急高',
  LL: '危急低',
  A: '异常',
  N: '正常',
}

const EXAM_OPTION_LABELS: Record<string, Record<string, string>> = {
  rhythm: { SINUS: '窦性', AF: '房颤', OTHER: '其他' },
  hasIschemiaHint: { YES: '有', NO: '无', NA: '未评估' },
  drGrade: {
    NONE: '无',
    MILD: '轻度',
    MOD: '中度',
    SEVERE: '重度',
    PDR: '增殖期',
    UNGRADED: '未分级',
  },
  fattyLiver: {
    NORMAL: '正常',
    MILD: '轻度',
    MODERATE: '中度',
    SEVERE: '重度',
    UNGRADED: '未评估',
  },
  tiRads: {
    '1': '1类',
    '2': '2类',
    '3': '3类',
    '4': '4类',
    '5': '5类',
    UNGRADED: '未分级',
  },
}

const EXAM_FINDING_FIELDS: Record<string, Array<{ key: string; label: string; unit?: string; type: string }>> = {
  ECG: [
    { key: 'rhythm', label: '心律', type: 'select' },
    { key: 'hasIschemiaHint', label: '缺血提示', type: 'select' },
  ],
  UCG: [{ key: 'efPercent', label: 'EF', type: 'number', unit: '%' }],
  CAROTID_US: [
    { key: 'cimtMm', label: 'CIMT', type: 'number', unit: 'mm' },
    { key: 'hasPlaque', label: '斑块', type: 'bool' },
  ],
  ABDOMINAL_US: [
    { key: 'fattyLiver', label: '脂肪肝', type: 'select' },
    { key: 'kidneyNormal', label: '肾脏形态正常', type: 'bool' },
  ],
  THYROID_US: [
    { key: 'nodulePresent', label: '结节', type: 'bool' },
    { key: 'tiRads', label: 'TI-RADS', type: 'select' },
  ],
  FUNDUS: [{ key: 'drGrade', label: 'DR分级', type: 'select' }],
  PFT: [
    { key: 'fev1Fvc', label: 'FEV1/FVC', type: 'number' },
    { key: 'fev1PredPercent', label: 'FEV1占预计值', type: 'number', unit: '%' },
  ],
  BMD: [
    { key: 'tScore', label: 'T值', type: 'number' },
    { key: 'zScore', label: 'Z值', type: 'number' },
  ],
  CHEST_IMAGING: [{ key: 'nodulePresent', label: '结节', type: 'bool' }],
  ABDOMINAL_CT: [{ key: 'lesionPresent', label: '占位', type: 'bool' }],
}

const BOOL_KEYS = new Set(['hasPlaque', 'nodulePresent', 'kidneyNormal', 'lesionPresent'])

export function formatSpecimenType(code?: string | null): string {
  if (!code) return '检验报告'
  return SPECIMEN_TYPE_LABELS[code] ?? code
}

export function formatLabSource(source?: string | null): string {
  return formatHealthDataSource(source)
}

export function formatExamSource(source?: string | null): string {
  return formatHealthDataSource(source)
}

export function formatExamType(code?: string | null): string {
  if (!code) return '检查报告'
  return EXAM_TYPE_LABELS[code] ?? code
}

export function formatAbnormalFlag(flag?: string | null): string {
  if (!flag || flag === 'N') return ''
  return ABNORMAL_FLAG_LABELS[flag] ?? flag
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 19)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatLabItemValue(item: {
  valueNum?: number | string | null
  valueText?: string | null
  unit?: string | null
}): string {
  const text = item.valueText?.trim()
  if (text) return item.unit ? `${text} ${item.unit}` : text
  if (item.valueNum != null && item.valueNum !== '') {
    return item.unit ? `${item.valueNum} ${item.unit}` : String(item.valueNum)
  }
  return '—'
}

export function formatLabRefRange(item: {
  refLow?: number | string | null
  refHigh?: number | string | null
}): string {
  if (item.refLow == null && item.refHigh == null) return ''
  if (item.refLow != null && item.refHigh != null) return `${item.refLow} ~ ${item.refHigh}`
  if (item.refLow != null) return `≥ ${item.refLow}`
  return `≤ ${item.refHigh}`
}

export function formatExamFindingValue(fieldKey: string, value: unknown, unit?: string): string {
  if (value == null || value === '') return '—'
  if (BOOL_KEYS.has(fieldKey) || typeof value === 'boolean') {
    return value ? '有' : '无'
  }
  if (typeof value === 'string' && EXAM_OPTION_LABELS[fieldKey]) {
    return EXAM_OPTION_LABELS[fieldKey][value] ?? value
  }
  const s = String(value)
  return unit ? `${s} ${unit}` : s
}

export type KvRow = { label: string; value: string }

export function formatExamFindingRows(
  examType: string,
  findings?: Record<string, unknown> | null,
): KvRow[] {
  const f = findings || {}
  const fields = EXAM_FINDING_FIELDS[examType] || []
  const rows: KvRow[] = []
  if (fields.length) {
    for (const field of fields) {
      const v = f[field.key]
      if (v == null || v === '' || v === false) continue
      rows.push({
        label: field.label,
        value: formatExamFindingValue(field.key, v, field.unit),
      })
    }
  } else {
    for (const [k, v] of Object.entries(f)) {
      if (v == null || v === '') continue
      rows.push({ label: k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) })
    }
  }
  return rows
}

export function findingsSummaryText(examType?: string | null, findings?: Record<string, unknown> | null): string {
  if (!examType) return ''
  const rows = formatExamFindingRows(examType, findings)
  if (!rows.length) return ''
  return rows
    .slice(0, 3)
    .map((r) => `${r.label} ${r.value}`)
    .join(' · ')
}
