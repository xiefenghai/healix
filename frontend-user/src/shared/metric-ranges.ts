/** C 端首页指标参考范围与异常判定（成人默认） */

export type AbnormalFlag = 'H' | 'L' | 'N'

const REFS = {
  BLOOD_PRESSURE_SYS: { refLow: 90, refHigh: 139 },
  BLOOD_PRESSURE_DIA: { refLow: 60, refHigh: 89 },
  BLOOD_GLUCOSE: {
    FASTING: { refLow: 3.9, refHigh: 6.1 },
    POSTPRANDIAL: { refHigh: 7.8 },
    RANDOM: { refHigh: 11.1 },
  },
  HEART_RATE: { refLow: 60, refHigh: 100 },
  BMI: { refLow: 18.5, refHigh: 23.9 },
} as const

function formatRange(range: { refLow?: number | null; refHigh?: number | null }): string {
  const { refLow, refHigh } = range
  if (refLow != null && refHigh != null) return `${refLow}~${refHigh}`
  if (refLow != null) return `≥${refLow}`
  if (refHigh != null) return `≤${refHigh}`
  return ''
}

export function bmiRefText(): string {
  return formatRange(REFS.BMI)
}

/** 收缩/舒张分行展示，避免窄卡片截断 */
export function bloodPressureRefLines(): string[] {
  return [formatRange(REFS.BLOOD_PRESSURE_SYS), formatRange(REFS.BLOOD_PRESSURE_DIA)].filter(
    Boolean,
  )
}

export function glucoseMealLabel(mealContext?: string | null): string {
  const meal = (mealContext || 'FASTING').toUpperCase()
  if (meal === 'POSTPRANDIAL') return '餐后'
  if (meal === 'RANDOM') return '随机'
  return '空腹'
}

export function glucoseRefText(mealContext?: string | null): string {
  const meal = (mealContext || 'FASTING').toUpperCase()
  const range =
    meal === 'POSTPRANDIAL'
      ? REFS.BLOOD_GLUCOSE.POSTPRANDIAL
      : meal === 'RANDOM'
        ? REFS.BLOOD_GLUCOSE.RANDOM
        : REFS.BLOOD_GLUCOSE.FASTING
  return formatRange(range)
}

export function heartRateRefText(): string {
  return formatRange(REFS.HEART_RATE)
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
  if (flag === 'N') return '正常'
  return ''
}

export function isAbnormal(flag: AbnormalFlag | null | undefined): boolean {
  return flag === 'H' || flag === 'L'
}

export function calcBmi(heightCm?: number | null, weightKg?: number | null): number | null {
  if (heightCm == null || weightKg == null) return null
  const h = Number(heightCm)
  const w = Number(weightKg)
  if (!Number.isFinite(h) || !Number.isFinite(w) || h <= 0 || w <= 0) return null
  const m = h / 100
  return Math.round((w / (m * m)) * 10) / 10
}

export function evaluateBmi(value: number | null | undefined): AbnormalFlag | null {
  return evaluateAbnormal(value, REFS.BMI)
}

export function evaluateBloodPressure(
  sys: number | null | undefined,
  dia: number | null | undefined,
): AbnormalFlag | null {
  const sysFlag = evaluateAbnormal(sys, REFS.BLOOD_PRESSURE_SYS)
  const diaFlag = evaluateAbnormal(dia, REFS.BLOOD_PRESSURE_DIA)
  if (sysFlag === 'H' || diaFlag === 'H') return 'H'
  if (sysFlag === 'L' || diaFlag === 'L') return 'L'
  if (sysFlag === 'N' && diaFlag === 'N') return 'N'
  if (sysFlag === 'N' || diaFlag === 'N') return 'N'
  return null
}

export function evaluateGlucose(
  value: number | null | undefined,
  mealContext?: string | null,
): AbnormalFlag | null {
  const meal = (mealContext || 'FASTING').toUpperCase()
  const range =
    meal === 'POSTPRANDIAL'
      ? REFS.BLOOD_GLUCOSE.POSTPRANDIAL
      : meal === 'RANDOM'
        ? REFS.BLOOD_GLUCOSE.RANDOM
        : REFS.BLOOD_GLUCOSE.FASTING
  return evaluateAbnormal(value, range)
}

export function evaluateHeartRate(value: number | null | undefined): AbnormalFlag | null {
  return evaluateAbnormal(value, REFS.HEART_RATE)
}
