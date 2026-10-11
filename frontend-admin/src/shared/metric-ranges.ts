/** 指标参考范围与异常判定（字典 content 优先，缺省用成人默认） */

export type AbnormalFlag = 'H' | 'L' | 'N'

export interface MetricRefRange {
  refLow?: number | null
  refHigh?: number | null
  refHighMale?: number | null
  refHighFemale?: number | null
  refByMeal?: Record<string, { refLow?: number | null; refHigh?: number | null }>
}

export interface MetricDictMeta extends MetricRefRange {
  unit?: string
}

/** 新环境字典种子对齐；已有库无 ref 字段时作兜底 */
export const DEFAULT_METRIC_REFS: Record<string, MetricDictMeta> = {
  BLOOD_PRESSURE_SYS: { refLow: 90, refHigh: 139 },
  BLOOD_PRESSURE_DIA: { refLow: 60, refHigh: 89 },
  BLOOD_GLUCOSE: {
    refByMeal: {
      FASTING: { refLow: 3.9, refHigh: 6.1 },
      POSTPRANDIAL: { refHigh: 7.8 },
      RANDOM: { refHigh: 11.1 },
    },
  },
  HEART_RATE: { refLow: 60, refHigh: 100 },
  WAIST: { refHighMale: 90, refHighFemale: 85 },
  BMI: { refLow: 18.5, refHigh: 23.9 },
}

export function parseMetricDictContent(raw?: string): MetricDictMeta {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as MetricDictMeta
  } catch {
    return {}
  }
}

export function mergeMetricMeta(
  metricType: string,
  dictContent?: string,
): MetricDictMeta {
  const parsed = parseMetricDictContent(dictContent)
  const fallback = DEFAULT_METRIC_REFS[metricType] ?? {}
  return {
    ...fallback,
    ...parsed,
    refByMeal: { ...fallback.refByMeal, ...parsed.refByMeal },
  }
}

export function refRangeText(range: {
  refLow?: number | null
  refHigh?: number | null
}): string {
  const { refLow, refHigh } = range
  if (refLow != null && refHigh != null) return `${refLow} ~ ${refHigh}`
  if (refLow != null) return `≥ ${refLow}`
  if (refHigh != null) return `≤ ${refHigh}`
  return ''
}

export function evaluateAbnormal(
  value: number | null | undefined,
  range: { refLow?: number | null; refHigh?: number | null },
): AbnormalFlag | null {
  if (value == null || Number.isNaN(value)) return null
  const { refLow, refHigh } = range
  if (refLow == null && refHigh == null) return null
  if (refLow != null && value < refLow) return 'L'
  if (refHigh != null && value > refHigh) return 'H'
  return 'N'
}

export function abnormalLabel(flag: AbnormalFlag | null | undefined): string {
  if (flag === 'H') return '偏高'
  if (flag === 'L') return '偏低'
  return ''
}

export function resolveGlucoseRange(
  meta: MetricDictMeta,
  mealContext?: string,
): { refLow?: number | null; refHigh?: number | null } {
  const meal = mealContext || 'FASTING'
  const byMeal = meta.refByMeal?.[meal]
  if (byMeal) return byMeal
  if (meal === 'FASTING') return { refLow: meta.refLow ?? 3.9, refHigh: meta.refHigh ?? 6.1 }
  return { refLow: meta.refLow, refHigh: meta.refHigh }
}

export function resolveWaistRange(
  meta: MetricDictMeta,
  gender?: string,
): { refLow?: number | null; refHigh?: number | null } {
  if (gender === 'MALE' && meta.refHighMale != null) {
    return { refHigh: meta.refHighMale }
  }
  if (gender === 'FEMALE' && meta.refHighFemale != null) {
    return { refHigh: meta.refHighFemale }
  }
  const fallback = DEFAULT_METRIC_REFS.WAIST
  if (gender === 'MALE') return { refHigh: fallback.refHighMale ?? 90 }
  if (gender === 'FEMALE') return { refHigh: fallback.refHighFemale ?? 85 }
  return { refHigh: fallback.refHighMale ?? 90 }
}

/** 收缩压 / 舒张压分别判定（N 视为正常，不标红） */
export function evaluateBloodPressureFlags(
  sys: number | null | undefined,
  dia: number | null | undefined,
  sysMeta: MetricDictMeta,
  diaMeta: MetricDictMeta,
): { sys: AbnormalFlag | null; dia: AbnormalFlag | null } {
  const sysRaw = evaluateAbnormal(sys, sysMeta)
  const diaRaw = evaluateAbnormal(dia, diaMeta)
  return {
    sys: sysRaw === 'N' ? null : sysRaw,
    dia: diaRaw === 'N' ? null : diaRaw,
  }
}

export function bloodPressureAbnormalLabel(
  sysFlag: AbnormalFlag | null,
  diaFlag: AbnormalFlag | null,
): string {
  const parts: string[] = []
  if (sysFlag === 'H') parts.push('收缩压偏高')
  else if (sysFlag === 'L') parts.push('收缩压偏低')
  if (diaFlag === 'H') parts.push('舒张压偏高')
  else if (diaFlag === 'L') parts.push('舒张压偏低')
  return parts.join('、')
}
