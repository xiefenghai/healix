/** 生活方式 / 社会史结构化字段（对齐临床社会史核心 5 类，向后兼容旧扁平存档） */

export interface SmokingStructured {
  status: string
  cigarettesPerDay: number | null
  years: number | null
  quitYear: string
  note: string
}

export interface DrinkingStructured {
  status: string
  frequency: string
  type: string
  amountPerDay: string
  note: string
}

export interface DietStructured {
  appetite: string
  habit: string
  type: string
  preference: string
  note: string
}

export interface ExerciseStructured {
  frequency: string
  intensity: string
  durationMin: number | null
  type: string
  note: string
}

export interface SleepStructured {
  quality: string
  hours: number | null
  disorder: string
  note: string
}

export interface LifestyleStructured {
  smoking: SmokingStructured
  drinking: DrinkingStructured
  note: string
}

export function emptySmoking(): SmokingStructured {
  return { status: '', cigarettesPerDay: null, years: null, quitYear: '', note: '' }
}

export function emptyDrinking(): DrinkingStructured {
  return { status: '', frequency: '', type: '', amountPerDay: '', note: '' }
}

export function emptyDiet(): DietStructured {
  return { appetite: '', habit: '', type: '', preference: '', note: '' }
}

export function emptyExercise(): ExerciseStructured {
  return { frequency: '', intensity: '', durationMin: null, type: '', note: '' }
}

export function emptySleep(): SleepStructured {
  return { quality: '', hours: null, disorder: '', note: '' }
}

export function emptyLifestyle(): LifestyleStructured {
  return { smoking: emptySmoking(), drinking: emptyDrinking(), note: '' }
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

/** 旧版吸烟状态「偶尔」按当前吸烟处理明细展示 */
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
  if (typeof raw === 'string') {
    return { ...emptySmoking(), status: raw }
  }
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
  if (typeof raw === 'string') {
    return { ...emptyDrinking(), status: raw }
  }
  const o = asRecord(raw)
  return {
    status: asStr(o.status),
    frequency: asStr(o.frequency),
    type: asStr(o.type),
    amountPerDay: asStr(o.amountPerDay),
    note: asStr(o.note),
  }
}

export function normalizeDiet(raw: unknown): DietStructured {
  const o = asRecord(raw)
  return {
    appetite: asStr(o.appetite),
    habit: asStr(o.habit),
    type: asStr(o.type),
    preference: asStr(o.preference),
    note: asStr(o.note),
  }
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

export function normalizeSleep(raw: unknown): SleepStructured {
  const o = asRecord(raw)
  return {
    quality: asStr(o.quality),
    hours: asNum(o.hours),
    disorder: asStr(o.disorder),
    note: asStr(o.note),
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

/** 保存时按状态裁剪无关明细（内存草稿可保留） */
export function serializeDiet(diet: DietStructured): Record<string, unknown> {
  return {
    appetite: diet.appetite || undefined,
    habit: diet.habit || undefined,
    type: diet.type || undefined,
    preference: diet.preference.trim() || undefined,
    note: diet.note.trim() || undefined,
  }
}

export function serializeExercise(exercise: ExerciseStructured): Record<string, unknown> {
  return {
    frequency: exercise.frequency || undefined,
    intensity: exercise.intensity || undefined,
    durationMin: exercise.durationMin ?? undefined,
    type: exercise.type.trim() || undefined,
    note: exercise.note.trim() || undefined,
  }
}

export function serializeSleep(sleep: SleepStructured): Record<string, unknown> {
  return {
    quality: sleep.quality || undefined,
    hours: sleep.hours ?? undefined,
    disorder: sleep.disorder || undefined,
    note: sleep.note.trim() || undefined,
  }
}

export function serializeLifestyle(lifestyle: LifestyleStructured): Record<string, unknown> {
  const smokingStatus = lifestyle.smoking.status || undefined
  const drinkingStatus = lifestyle.drinking.status || undefined
  const smokingNote = lifestyle.smoking.note.trim() || undefined
  const drinkingNote = lifestyle.drinking.note.trim() || undefined
  const smokingDetail = smokingShowsDetail(lifestyle.smoking.status)
  const smokingFormer = smokingShowsQuitYear(lifestyle.smoking.status)
  const drinkingDetail = drinkingShowsDetail(lifestyle.drinking.status)

  return {
    smoking:
      smokingStatus || smokingNote
        ? {
            status: smokingStatus,
            cigarettesPerDay: smokingDetail ? lifestyle.smoking.cigarettesPerDay ?? undefined : undefined,
            years: smokingDetail || smokingFormer ? lifestyle.smoking.years ?? undefined : undefined,
            quitYear: smokingFormer ? lifestyle.smoking.quitYear || undefined : undefined,
            note: smokingNote,
          }
        : undefined,
    drinking:
      drinkingStatus || drinkingNote
        ? {
            status: drinkingStatus,
            frequency: drinkingDetail ? lifestyle.drinking.frequency || undefined : undefined,
            type: drinkingDetail ? lifestyle.drinking.type.trim() || undefined : undefined,
            amountPerDay: drinkingDetail ? lifestyle.drinking.amountPerDay.trim() || undefined : undefined,
            note: drinkingNote,
          }
        : undefined,
    note: lifestyle.note.trim() || undefined,
  }
}
