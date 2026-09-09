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
}

export type LifestyleStructured = {
  smoking: SmokingStructured
  drinking: DrinkingStructured
  note: string
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

export function buildLifestylePatch(input: {
  diet: DietStructured
  exercise: ExerciseStructured
  sleep: SleepStructured
  lifestyle: LifestyleStructured
}): Record<string, unknown> {
  const smokingStatus = input.lifestyle.smoking.status || undefined
  const drinkingStatus = input.lifestyle.drinking.status || undefined
  const smokingDetail = smokingShowsDetail(input.lifestyle.smoking.status)
  const smokingFormer = smokingShowsQuitYear(input.lifestyle.smoking.status)
  const drinkingDetail = drinkingShowsDetail(input.lifestyle.drinking.status)

  return {
    diet: {
      appetite: input.diet.appetite || undefined,
      habit: input.diet.habit || undefined,
      type: input.diet.type || undefined,
      preference: input.diet.preference.trim() || undefined,
      note: input.diet.note.trim() || undefined,
    },
    exercise: {
      frequency: input.exercise.frequency || undefined,
      intensity: input.exercise.intensity || undefined,
      durationMin: input.exercise.durationMin ?? undefined,
      type: input.exercise.type.trim() || undefined,
      note: input.exercise.note.trim() || undefined,
    },
    sleep: {
      quality: input.sleep.quality || undefined,
      hours: input.sleep.hours ?? undefined,
      disorder: input.sleep.disorder || undefined,
      note: input.sleep.note.trim() || undefined,
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
}
