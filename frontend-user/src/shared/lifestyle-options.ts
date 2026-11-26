/** 生活方式结构化字段（与 B 端 contentJson 对齐） */

export type SmokingStructured = {
  status: string
  cigarettesPerDay: number | null
  years: number | null
  quitYear: string
  note: string
}

export type DrinkingStructured = {
  status: string
  frequency: string
  type: string
  amountPerDay: string
  note: string
}

export type DietStructured = {
  appetite: string
  habit: string
  type: string
  preference: string
  note: string
  /** C 端口味多选（兼容 B 端 type/habit） */
  tastes: string[]
}

export type ExerciseStructured = {
  frequency: string
  intensity: string
  durationMin: number | null
  type: string
  note: string
}

export type SleepStructured = {
  quality: string
  hours: number | null
  disorder: string
  note: string
  /** C 端睡眠时长档位 */
  hoursBand: string
  /** C 端熬夜频率 */
  lateNight: string
}

export type LifestyleStructured = {
  smoking: SmokingStructured
  drinking: DrinkingStructured
  note: string
}

/** C 端整合心理与情绪的轻量字段（写入 basic.psych） */
export type PsychStructured = {
  mood: string
  stressLevel: string
}

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function asStr(v: unknown): string {
  return v == null ? '' : String(v)
}

function asNum(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function asStrList(v: unknown): string[] {
  if (Array.isArray(v)) return v.map((x) => asStr(x)).filter(Boolean)
  if (typeof v === 'string' && v.trim()) {
    return v
      .split(/[,，、]/)
      .map((s) => s.trim())
      .filter(Boolean)
  }
  return []
}

export function emptySmoking(): SmokingStructured {
  return { status: '', cigarettesPerDay: null, years: null, quitYear: '', note: '' }
}

export function emptyDrinking(): DrinkingStructured {
  return { status: '', frequency: '', type: '', amountPerDay: '', note: '' }
}

export function emptyDiet(): DietStructured {
  return { appetite: '', habit: '', type: '', preference: '', note: '', tastes: [] }
}

export function emptyExercise(): ExerciseStructured {
  return { frequency: '', intensity: '', durationMin: null, type: '', note: '' }
}

export function emptySleep(): SleepStructured {
  return { quality: '', hours: null, disorder: '', note: '', hoursBand: '', lateNight: '' }
}

export function emptyLifestyle(): LifestyleStructured {
  return { smoking: emptySmoking(), drinking: emptyDrinking(), note: '' }
}

export function emptyPsych(): PsychStructured {
  return { mood: '', stressLevel: '' }
}

export function smokingShowsDetail(status: string) {
  return status === 'CURRENT' || status === 'OCCASIONAL'
}

export function smokingShowsQuitYear(status: string) {
  return status === 'FORMER'
}

export function drinkingShowsDetail(status: string) {
  return status === 'CURRENT' || status === 'OCCASIONAL' || status === 'FORMER'
}

export function normalizeSmoking(raw: unknown): SmokingStructured {
  if (typeof raw === 'string') return { ...emptySmoking(), status: raw }
  const o = asRecord(raw)
  return {
    status: asStr(o.status),
    cigarettesPerDay: asNum(o.cigarettesPerDay),
    years: asNum(o.years),
    quitYear: asStr(o.quitYear),
    note: asStr(o.note),
  }
}

export function normalizeDrinking(raw: unknown): DrinkingStructured {
  if (typeof raw === 'string') return { ...emptyDrinking(), status: raw }
  const o = asRecord(raw)
  return {
    status: asStr(o.status),
    frequency: asStr(o.frequency),
    type: asStr(o.type),
    amountPerDay: asStr(o.amountPerDay),
    note: asStr(o.note),
  }
}

/** 从旧 type/habit 推导口味多选，便于回显 */
export function deriveDietTastes(diet: Pick<DietStructured, 'tastes' | 'type' | 'habit' | 'preference'>): string[] {
  if (diet.tastes?.length) return [...diet.tastes]
  const out: string[] = []
  const type = diet.type
  if (type === 'LIGHT' || type === 'SALTY' || type === 'OILY' || type === 'SWEET') out.push(type)
  if (type === 'MIXED') {
    // 杂食无法精确还原，尽量从 preference 文本猜测
    const pref = diet.preference || ''
    if (/清淡|少盐/.test(pref)) out.push('LIGHT')
    if (/咸/.test(pref)) out.push('SALTY')
    if (/油/.test(pref)) out.push('OILY')
    if (/甜/.test(pref)) out.push('SWEET')
    if (/辣/.test(pref)) out.push('SPICY')
  }
  if (/辣/.test(diet.preference || '')) out.push('SPICY')
  if (diet.habit === 'IRREGULAR') out.push('IRREGULAR')
  return [...new Set(out)]
}

export function applyDietTastes(tastes: string[]): Pick<DietStructured, 'tastes' | 'type' | 'habit'> {
  const unique = [...new Set(tastes.filter(Boolean))]
  const typeCodes = unique.filter((t) => t === 'LIGHT' || t === 'SALTY' || t === 'OILY' || t === 'SWEET')
  let type = ''
  if (typeCodes.length === 1) type = typeCodes[0]
  else if (typeCodes.length > 1 || unique.includes('SPICY')) type = typeCodes.length ? 'MIXED' : unique.includes('SPICY') ? 'MIXED' : ''
  const habit = unique.includes('IRREGULAR') ? 'IRREGULAR' : unique.length ? 'REGULAR' : ''
  return { tastes: unique, type, habit }
}

export function normalizeDiet(raw: unknown): DietStructured {
  const o = asRecord(raw)
  const base: DietStructured = {
    appetite: asStr(o.appetite),
    habit: asStr(o.habit),
    type: asStr(o.type),
    preference: asStr(o.preference),
    note: asStr(o.note),
    tastes: asStrList(o.tastes),
  }
  if (!base.tastes.length) base.tastes = deriveDietTastes(base)
  return base
}

export function normalizeExercise(raw: unknown): ExerciseStructured {
  const o = asRecord(raw)
  return {
    frequency: asStr(o.frequency),
    intensity: asStr(o.intensity),
    durationMin: asNum(o.durationMin),
    type: asStr(o.type),
    note: asStr(o.note),
  }
}

/** 由小时数回推时长档位 */
export function deriveHoursBand(hours: number | null, hoursBand?: string): string {
  if (hoursBand) return hoursBand
  if (hours == null) return ''
  if (hours < 6) return 'LT6'
  if (hours < 7) return 'H6_7'
  if (hours <= 8) return 'H7_8'
  return 'GT8'
}

export function hoursFromBand(band: string): number | null {
  switch (band) {
    case 'LT6':
      return 5
    case 'H6_7':
      return 6.5
    case 'H7_8':
      return 7.5
    case 'GT8':
      return 9
    default:
      return null
  }
}

export function qualityFromBand(band: string): string {
  if (band === 'LT6') return 'POOR'
  if (band === 'H6_7') return 'FAIR'
  if (band === 'H7_8' || band === 'GT8') return 'GOOD'
  return ''
}

export function normalizeSleep(raw: unknown): SleepStructured {
  const o = asRecord(raw)
  const hours = asNum(o.hours)
  const hoursBand = asStr(o.hoursBand) || deriveHoursBand(hours)
  return {
    quality: asStr(o.quality),
    hours,
    disorder: asStr(o.disorder),
    note: asStr(o.note),
    hoursBand,
    lateNight: asStr(o.lateNight),
  }
}

export function normalizeLifestyle(raw: unknown): LifestyleStructured {
  const o = asRecord(raw)
  return {
    smoking: normalizeSmoking(o.smoking),
    drinking: normalizeDrinking(o.drinking),
    note: asStr(o.note),
  }
}

export function normalizePsych(raw: unknown): PsychStructured {
  const o = asRecord(raw)
  return {
    mood: asStr(o.mood),
    stressLevel: asStr(o.stressLevel),
  }
}

export function buildLifestylePatch(input: {
  diet: DietStructured
  exercise: ExerciseStructured
  sleep: SleepStructured
  lifestyle: LifestyleStructured
  psych?: PsychStructured
}): Record<string, unknown> {
  const smokingStatus = input.lifestyle.smoking.status || undefined
  const drinkingStatus = input.lifestyle.drinking.status || undefined
  const smokingDetail = smokingShowsDetail(input.lifestyle.smoking.status)
  const smokingFormer = smokingShowsQuitYear(input.lifestyle.smoking.status)
  const drinkingDetail = drinkingShowsDetail(input.lifestyle.drinking.status)

  const tastes = input.diet.tastes?.length ? input.diet.tastes : deriveDietTastes(input.diet)
  const synced = applyDietTastes(tastes)
  const hoursBand = input.sleep.hoursBand || deriveHoursBand(input.sleep.hours)
  const hours = hoursBand ? hoursFromBand(hoursBand) : input.sleep.hours
  const quality = input.sleep.quality || qualityFromBand(hoursBand)

  const patch: Record<string, unknown> = {
    diet: {
      appetite: input.diet.appetite || undefined,
      habit: synced.habit || input.diet.habit || undefined,
      type: synced.type || input.diet.type || undefined,
      preference: input.diet.preference.trim() || undefined,
      note: input.diet.note.trim() || undefined,
      tastes: synced.tastes.length ? synced.tastes : undefined,
    },
    exercise: {
      frequency: input.exercise.frequency || undefined,
      intensity: input.exercise.intensity || undefined,
      durationMin: input.exercise.durationMin ?? undefined,
      type: input.exercise.type.trim() || undefined,
      note: input.exercise.note.trim() || undefined,
    },
    sleep: {
      quality: quality || undefined,
      hours: hours ?? undefined,
      disorder: input.sleep.disorder || undefined,
      note: input.sleep.note.trim() || undefined,
      hoursBand: hoursBand || undefined,
      lateNight: input.sleep.lateNight || undefined,
    },
    lifestyle: {
      smoking:
        smokingStatus || input.lifestyle.smoking.note.trim()
          ? {
              status: smokingStatus,
              cigarettesPerDay: smokingDetail
                ? input.lifestyle.smoking.cigarettesPerDay ?? undefined
                : undefined,
              years:
                smokingDetail || smokingFormer
                  ? input.lifestyle.smoking.years ?? undefined
                  : undefined,
              quitYear: smokingFormer ? input.lifestyle.smoking.quitYear || undefined : undefined,
              note: input.lifestyle.smoking.note.trim() || undefined,
            }
          : undefined,
      drinking:
        drinkingStatus || input.lifestyle.drinking.note.trim()
          ? {
              status: drinkingStatus,
              frequency: drinkingDetail ? input.lifestyle.drinking.frequency || undefined : undefined,
              type: drinkingDetail ? input.lifestyle.drinking.type.trim() || undefined : undefined,
              amountPerDay: drinkingDetail
                ? input.lifestyle.drinking.amountPerDay.trim() || undefined
                : undefined,
              note: input.lifestyle.drinking.note.trim() || undefined,
            }
          : undefined,
      note: input.lifestyle.note.trim() || undefined,
    },
  }

  if (input.psych) {
    const mood = input.psych.mood || undefined
    const stressLevel = input.psych.stressLevel || undefined
    if (mood || stressLevel) {
      patch.psych = {
        mood,
        stressLevel,
      }
    }
  }

  return patch
}
