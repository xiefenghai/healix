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
  OTHER: '其他',
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
  let line = parts.join(' · ') || '按医嘱服用'
  if (row.timingNote?.trim()) line += `（${row.timingNote.trim()}）`
  return line
}
