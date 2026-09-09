export type ExamFieldType = 'text' | 'number' | 'select' | 'bool'

export interface ExamFindingField {
  key: string
  label: string
  type: ExamFieldType
  options?: string[]
  unit?: string
}

export interface ExamPanel {
  key: string
  label: string
  examTypes: string[]
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

export const EXAM_PANELS: ExamPanel[] = [
  { key: 'ECG', label: '心电检查', examTypes: ['ECG'] },
  { key: 'ECHO', label: '超声检查', examTypes: ['UCG', 'CAROTID_US', 'ABDOMINAL_US', 'THYROID_US'] },
  { key: 'IMAGING', label: '影像检查', examTypes: ['CHEST_IMAGING', 'ABDOMINAL_CT'] },
  { key: 'FUNCTION', label: '功能检查', examTypes: ['PFT', 'BMD'] },
  { key: 'FUNDUS', label: '眼底检查', examTypes: ['FUNDUS'] },
]

const RHYTHM_LABELS: Record<string, string> = {
  SINUS: '窦性',
  AF: '房颤',
  OTHER: '其他',
}

const ISCHEMIA_LABELS: Record<string, string> = {
  YES: '有',
  NO: '无',
  NA: '未评估',
}

const DR_GRADE_LABELS: Record<string, string> = {
  NONE: '无',
  MILD: '轻度',
  MOD: '中度',
  SEVERE: '重度',
  PDR: '增殖期',
  UNGRADED: '未分级',
}

const FATTY_LIVER_LABELS: Record<string, string> = {
  NORMAL: '正常',
  MILD: '轻度',
  MODERATE: '中度',
  SEVERE: '重度',
  UNGRADED: '未评估',
}

const TIRADS_LABELS: Record<string, string> = {
  '1': '1类',
  '2': '2类',
  '3': '3类',
  '4': '4类',
  '5': '5类',
  UNGRADED: '未分级',
}

export const EXAM_OPTION_LABELS: Record<string, Record<string, string>> = {
  rhythm: RHYTHM_LABELS,
  hasIschemiaHint: ISCHEMIA_LABELS,
  drGrade: DR_GRADE_LABELS,
  fattyLiver: FATTY_LIVER_LABELS,
  tiRads: TIRADS_LABELS,
}

export const EXAM_FINDING_FIELDS: Record<string, ExamFindingField[]> = {
  ECG: [
    { key: 'rhythm', label: '心律', type: 'select', options: ['SINUS', 'AF', 'OTHER'] },
    { key: 'hasIschemiaHint', label: '缺血提示', type: 'select', options: ['YES', 'NO', 'NA'] },
  ],
  UCG: [{ key: 'efPercent', label: 'EF', type: 'number', unit: '%' }],
  CAROTID_US: [
    { key: 'cimtMm', label: 'CIMT', type: 'number', unit: 'mm' },
    { key: 'hasPlaque', label: '斑块', type: 'bool' },
  ],
  ABDOMINAL_US: [
    { key: 'fattyLiver', label: '脂肪肝', type: 'select', options: ['NORMAL', 'MILD', 'MODERATE', 'SEVERE', 'UNGRADED'] },
    { key: 'kidneyNormal', label: '肾脏形态正常', type: 'bool' },
  ],
  THYROID_US: [
    { key: 'nodulePresent', label: '结节', type: 'bool' },
    { key: 'tiRads', label: 'TI-RADS', type: 'select', options: ['1', '2', '3', '4', '5', 'UNGRADED'] },
  ],
  FUNDUS: [
    {
      key: 'drGrade',
      label: 'DR分级',
      type: 'select',
      options: ['NONE', 'MILD', 'MOD', 'SEVERE', 'PDR', 'UNGRADED'],
    },
  ],
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

export const ALL_EXAM_TYPES = [...new Set(EXAM_PANELS.flatMap((p) => p.examTypes))]

export function examTypeLabel(code: string, dictDesc?: string) {
  return dictDesc || EXAM_TYPE_LABELS[code] || code
}

export function optionLabel(fieldKey: string, code: string) {
  return EXAM_OPTION_LABELS[fieldKey]?.[code] ?? code
}

export function blankFindings(examType: string): Record<string, unknown> {
  const next: Record<string, unknown> = {}
  for (const field of EXAM_FINDING_FIELDS[examType] || []) {
    if (field.type === 'bool') next[field.key] = false
    else if (field.type === 'number') next[field.key] = null
    else next[field.key] = ''
  }
  return next
}

export function formatExamFindingValue(fieldKey: string, value: unknown) {
  if (value == null || value === '') return '-'
  if (fieldKey === 'hasPlaque' || fieldKey === 'nodulePresent' || fieldKey === 'kidneyNormal' || fieldKey === 'lesionPresent') {
    return value ? '有' : '无'
  }
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string' && EXAM_OPTION_LABELS[fieldKey]) {
    return optionLabel(fieldKey, value)
  }
  return String(value)
}

export function isFindingFilled(field: ExamFindingField, value: unknown) {
  if (field.type === 'bool') return value === true
  if (field.type === 'number') return value != null && value !== ''
  return value != null && String(value).trim() !== ''
}

export function buildFindingsPayload(
  examType: string,
  raw: Record<string, unknown>,
): Record<string, unknown> {
  const findings: Record<string, unknown> = {}
  for (const field of EXAM_FINDING_FIELDS[examType] || []) {
    const v = raw[field.key]
    if (v === '' || v == null) continue
    if (field.type === 'number') findings[field.key] = Number(v)
    else if (field.type === 'bool') findings[field.key] = !!v
    else findings[field.key] = v
  }
  return findings
}

export function hasExamDraft(
  examType: string,
  findings: Record<string, unknown>,
  conclusion?: string,
) {
  if (conclusion?.trim()) return true
  return (EXAM_FINDING_FIELDS[examType] || []).some((field) => isFindingFilled(field, findings[field.key]))
}

export function findingsSummaryText(examType: string, findings?: Record<string, unknown>) {
  const f = findings || {}
  const fields = EXAM_FINDING_FIELDS[examType] || []
  const parts = fields
    .map((field) => {
      const v = f[field.key]
      if (v == null || v === '' || v === false) return null
      return `${field.label} ${formatExamFindingValue(field.key, v)}`
    })
    .filter(Boolean)
  return parts.length ? parts.slice(0, 3).join(' · ') : '-'
}
