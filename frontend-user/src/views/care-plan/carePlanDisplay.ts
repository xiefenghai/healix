import {
  formatCarePlanLabel,
  formatWeeklyPlanDay,
  CARE_PLAN_EXERCISE_TYPE_LABELS,
  CARE_PLAN_INTENSITY_LABELS,
  parseSampleMeal,
} from '../../shared/care-plan-labels'
import { exerciseHowTo } from '../../shared/exercise'

export const WEEK_DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const

export const WEEK_DAY_SHORT: Record<string, string> = {
  MON: '一',
  TUE: '二',
  WED: '三',
  THU: '四',
  FRI: '五',
  SAT: '六',
  SUN: '日',
}

export const DAY_LABELS: Record<string, string> = {
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  SUN: '周日',
}

export const SAMPLE_MEAL_META: Array<{ key: string; label: string; tone: string; aliases?: string[] }> = [
  { key: 'breakfast', label: '早餐', tone: 'sunrise' },
  { key: 'lunch', label: '午餐', tone: 'noon' },
  { key: 'dinner', label: '晚餐', tone: 'dusk' },
  { key: 'snacks', label: '加餐', tone: 'snack', aliases: ['snack'] },
]

export type SampleMealRow = {
  key: string
  label: string
  tone: string
  value: string
  swapHint?: string
  items: string[]
}

export type WeekCalCell = {
  code: string
  label: string
  text: string
  /** 周历三行展示：时长如 30′ */
  duration?: string
  /** 周历三行展示：活动简称如 快走 */
  activity?: string
  tone: 'off' | 'on' | 'hot'
  today?: boolean
}

export type WeeklyPlanRow = {
  key: string
  day: string
  dayCode: string
  showDay: boolean
  type: string
  intensity: string
  duration: string
  note: string
  rest: boolean
}

export function parseSampleDayMeals(raw: unknown): SampleMealRow[] {
  if (!raw || typeof raw !== 'object') return []
  const o = raw as Record<string, unknown>
  const rows: SampleMealRow[] = []
  for (const meal of SAMPLE_MEAL_META) {
    let parsed = parseSampleMeal(o[meal.key])
    if (!parsed.text && meal.aliases) {
      for (const alias of meal.aliases) {
        parsed = parseSampleMeal(o[alias])
        if (parsed.text) break
      }
    }
    if (!parsed.text) continue
    const items = parsed.text
      .split(/[，,;；、]/)
      .map((s) => s.trim())
      .filter(Boolean)
    rows.push({
      key: meal.key,
      label: meal.label,
      tone: meal.tone,
      value: parsed.text,
      swapHint: parsed.swapHint,
      items: items.length ? items : [parsed.text],
    })
  }
  return rows
}

export function buildSampleWeek(
  raw: unknown,
): Array<{ day: string; label: string; meals: SampleMealRow[] }> {
  if (!raw || typeof raw !== 'object') return []
  const o = raw as Record<string, unknown>
  const rows: Array<{ day: string; label: string; meals: SampleMealRow[] }> = []
  for (const day of Object.keys(DAY_LABELS)) {
    const meals = parseSampleDayMeals(o[day])
    if (!meals.length) continue
    rows.push({ day, label: DAY_LABELS[day], meals })
  }
  return rows
}

export function buildWeekCalCells(weeklyPlan: unknown[]): WeekCalCell[] {
  const byDay = new Map<string, any>()
  for (const day of weeklyPlan as any[]) {
    if (day?.day) byDay.set(String(day.day).toUpperCase(), day)
  }
  const jsDay = new Date().getDay() // 0 Sun
  const todayCode = WEEK_DAY_ORDER[jsDay === 0 ? 6 : jsDay - 1]
  return WEEK_DAY_ORDER.map((code) => {
    const day = byDay.get(code)
    const items = Array.isArray(day?.items) ? day.items : []
    if (!items.length) {
      return {
        code,
        label: WEEK_DAY_SHORT[code],
        text: '休息',
        duration: '—',
        activity: '休息',
        tone: 'off' as const,
        today: code === todayCode,
      }
    }
    const first = items[0] && typeof items[0] === 'object' ? (items[0] as Record<string, unknown>) : {}
    const typeCode = first.type != null ? String(first.type).toUpperCase() : ''
    const typeLabel =
      formatCarePlanLabel(CARE_PLAN_EXERCISE_TYPE_LABELS, typeCode) ||
      (first.title != null ? String(first.title) : '运动')
    const min = first.durationMin != null && first.durationMin !== '' ? `${first.durationMin}′` : ''
    const shortType =
      typeCode === 'WALKING'
        ? '快走'
        : typeCode === 'STRETCHING'
          ? '拉伸'
          : typeCode === 'STRENGTH' || typeCode === 'RESISTANCE'
            ? '力量'
            : typeCode === 'CYCLING'
              ? '骑行'
              : typeLabel.length > 4
                ? typeLabel.slice(0, 4)
                : typeLabel
    const tone =
      typeCode === 'STRETCHING' || typeCode === 'YOGA' || typeCode === 'TAICHI'
        ? ('hot' as const)
        : ('on' as const)
    return {
      code,
      label: WEEK_DAY_SHORT[code],
      text: min ? `${shortType}${min}` : shortType,
      duration: min || '—',
      activity: shortType,
      tone,
      today: code === todayCode,
    }
  })
}

export function buildWeeklyPlanRows(weeklyPlan: unknown[]): WeeklyPlanRow[] {
  const rows: WeeklyPlanRow[] = []
  weeklyPlan.forEach((day: any, di: number) => {
    const dayCode = String(day?.day || '').toUpperCase()
    const dayLabel = formatWeeklyPlanDay(day?.day) || '—'
    const items = Array.isArray(day?.items) ? day.items : []
    if (!items.length) {
      rows.push({
        key: `${di}-rest`,
        day: dayLabel,
        dayCode,
        showDay: true,
        type: '休息',
        intensity: '—',
        duration: '—',
        note: '',
        rest: true,
      })
      return
    }
    items.forEach((item: unknown, ii: number) => {
      const o = item && typeof item === 'object' ? (item as Record<string, unknown>) : {}
      const type =
        formatCarePlanLabel(CARE_PLAN_EXERCISE_TYPE_LABELS, o.type != null ? String(o.type) : '') ||
        (o.title != null ? String(o.title) : '') ||
        '—'
      const intensity =
        formatCarePlanLabel(CARE_PLAN_INTENSITY_LABELS, o.intensity != null ? String(o.intensity) : '') ||
        '—'
      const duration = o.durationMin != null && o.durationMin !== '' ? `${o.durationMin} 分钟` : '—'
      const written = o.note != null ? String(o.note).trim() : ''
      const note = written || exerciseHowTo(type) || ''
      rows.push({
        key: `${di}-${ii}`,
        day: dayLabel,
        dayCode,
        showDay: ii === 0,
        type,
        intensity,
        duration,
        note,
        rest: false,
      })
    })
  })
  return rows
}

export function validityLabel(publishedAt?: string | null, horizonDays?: number | null): string {
  const pub = publishedAt ? String(publishedAt).slice(0, 10) : ''
  const days = typeof horizonDays === 'number' && horizonDays > 0 ? horizonDays : 0
  if (pub && days) {
    const start = new Date(`${pub}T00:00:00`)
    if (!Number.isNaN(start.getTime())) {
      const end = new Date(start)
      end.setDate(end.getDate() + days - 1)
      const pad = (n: number) => String(n).padStart(2, '0')
      const endStr = `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`
      return `${pub} ~ ${endStr}（${days} 天）`
    }
  }
  if (days) return `发布后 ${days} 天`
  if (pub) return `发布于 ${pub}`
  return ''
}

export function statusLabel(status?: string | null): string {
  if (status === 'ACTIVE') return '当前生效'
  if (status === 'ARCHIVED') return '历史'
  if (status === 'DRAFT') return '草稿'
  return status || ''
}

export function reviewLabel(signStatus?: string | null, aiAssisted?: boolean): string {
  const s = String(signStatus || '').toUpperCase()
  if (s === 'SIGNED' || s === 'REVIEWED') return '健管师已审核'
  if (aiAssisted) return '健管师已审核发布'
  if (s) return '待确认审核状态'
  return '已发布'
}
