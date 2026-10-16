import {
  CARE_PLAN_EXERCISE_TYPE_OPTIONS,
  formatCarePlanTaskCategory,
  parseSampleMeal,
} from './care-plan-labels'
import { asEnergyTarget, type EnergyTarget } from './energy-target'

const DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const
const DAY_SHORT: Record<string, string> = {
  MON: '一',
  TUE: '二',
  WED: '三',
  THU: '四',
  FRI: '五',
  SAT: '六',
  SUN: '日',
}

const EXERCISE_TYPE_LABELS = Object.fromEntries(
  CARE_PLAN_EXERCISE_TYPE_OPTIONS.map((o) => [o.value, o.label]),
)

export interface CarePlanChatDay {
  day: string
  label: string
  text: string
  tone: 'hot' | 'off' | 'normal'
}

export interface CarePlanChatMeal {
  label: string
  text: string
  swapHint?: string
}

export interface CarePlanChatTask {
  title: string
  category?: string
  categoryLabel: string
  targetMl?: number | null
  tone: 'default' | 'water' | 'sleep' | 'mood'
}

export interface CarePlanChatCheck {
  level: 'OK' | 'YELLOW' | 'RED'
  label: string
  text: string
}

export interface CarePlanChatPreview {
  title: string
  status: 'DRAFT' | 'ACTIVE' | string
  statusLabel: string
  sourceLabel: string
  goalSummary?: string
  /** 整份生成依据（B 端审阅） */
  rationaleOverview?: string
  schemaVersion?: number | null
  horizonDays?: number | null
  energyKcal?: number | null
  weeklyDays: CarePlanChatDay[]
  sampleMeals: CarePlanChatMeal[]
  tasks: CarePlanChatTask[]
  checks: CarePlanChatCheck[]
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

function textOf(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v.trim()
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return ''
}

function exerciseTypeLabel(type?: string): string {
  if (!type) return '运动'
  return EXERCISE_TYPE_LABELS[type] || type
}

function formatWeeklyItem(item: unknown): string {
  const o = asRecord(item)
  if (!o) return ''
  const type = exerciseTypeLabel(textOf(o.type))
  const min = Number(o.durationMin)
  if (Number.isFinite(min) && min > 0) return `${type}${min}′`
  const note = textOf(o.note)
  return note || type
}

function weeklyTone(day: string, text: string): 'hot' | 'off' | 'normal' {
  if (!text || /休息|恢复|无安排/.test(text)) return 'off'
  if (day === 'WED' || /拉伸|柔韧/.test(text)) return 'hot'
  return 'normal'
}

function taskTone(category?: string): CarePlanChatTask['tone'] {
  if (category === 'WATER') return 'water'
  if (category === 'SLEEP') return 'sleep'
  if (category === 'MOOD') return 'mood'
  return 'default'
}

function foodLabels(list: unknown): string[] {
  return asArray(list)
    .map((it) => {
      const o = asRecord(it)
      if (!o) return textOf(it)
      return textOf(o.label) || textOf(o.code)
    })
    .filter(Boolean)
}

export function carePlanStatusLine(preview: CarePlanChatPreview, revised = false): string {
  const head = revised ? '已按你的要求修订管理方案草稿' : '已生成管理方案草稿'
  const title = preview.title ? `「${preview.title}」` : ''
  return `${head}${title}，请核对后发布。`
}

/** 从 Agent extracted（CarePlanBundleDto）构建聊天内结构化预览 */
export function buildCarePlanChatPreview(extracted: unknown): CarePlanChatPreview | null {
  const root = asRecord(extracted)
  if (!root) return null
  const plan = asRecord(root.plan)
  const draft = asRecord(root.draft) || asRecord(root.activeVersion)
  if (!plan && !draft) return null

  const exercise = asRecord(draft?.exercise)
  const diet = asRecord(draft?.diet)
  const execution = asRecord(draft?.execution)
  const contextSnapshot = asRecord(draft?.contextSnapshot)
  const rationaleNode = asRecord(contextSnapshot?.rationale)
  const rationaleOverview = textOf(rationaleNode?.overview) || undefined
  const energy = asEnergyTarget(diet?.energyTarget) as EnergyTarget | null

  const weeklyRaw = asArray(exercise?.weeklyPlan)
  const byDay = new Map<string, string>()
  for (const row of weeklyRaw) {
    const o = asRecord(row)
    if (!o) continue
    const day = textOf(o.day).toUpperCase()
    if (!DAY_ORDER.includes(day as (typeof DAY_ORDER)[number])) continue
    const items = asArray(o.items).map(formatWeeklyItem).filter(Boolean)
    byDay.set(day, items.join(' · ') || '安排')
  }
  const weeklyDays: CarePlanChatDay[] = DAY_ORDER.map((day) => {
    const text = byDay.get(day) || (byDay.size ? '休息' : '')
    return {
      day,
      label: DAY_SHORT[day] || day,
      text: text || '—',
      tone: weeklyTone(day, text),
    }
  }).filter((d) => byDay.size === 0 || !!byDay.get(d.day) || d.text === '休息')

  // 若只有部分天，仍展示完整一周更清晰
  const weeklyFull: CarePlanChatDay[] =
    byDay.size > 0
      ? DAY_ORDER.map((day) => {
          const text = byDay.get(day) || '休息'
          return {
            day,
            label: DAY_SHORT[day] || day,
            text,
            tone: weeklyTone(day, text),
          }
        })
      : []

  const mealKeys = [
    { key: 'breakfast', label: '早餐' },
    { key: 'lunch', label: '午餐' },
    { key: 'dinner', label: '晚餐' },
    { key: 'snacks', label: '加餐' },
  ]
  const sampleDay = asRecord(diet?.sampleDay)
  const sampleMeals: CarePlanChatMeal[] = mealKeys
    .map(({ key, label }) => {
      const meal = parseSampleMeal(sampleDay?.[key])
      return meal.text ? { label, text: meal.text, swapHint: meal.swapHint || undefined } : null
    })
    .filter((x): x is CarePlanChatMeal => !!x)

  const tasks: CarePlanChatTask[] = asArray(execution?.tasks)
    .map((t) => {
      const o = asRecord(t)
      if (!o) return null
      const title = textOf(o.title)
      if (!title || o.enabled === false) return null
      const category = textOf(o.category) || undefined
      const targetMl = o.targetMl == null ? null : Number(o.targetMl)
      return {
        title,
        category,
        categoryLabel: formatCarePlanTaskCategory(category) || category || '任务',
        targetMl: Number.isFinite(targetMl as number) ? (targetMl as number) : null,
        tone: taskTone(category),
      }
    })
    .filter((x): x is CarePlanChatTask => !!x)
    .slice(0, 6)

  const checks: CarePlanChatCheck[] = []
  const allergens = foodLabels(diet?.allergensAvoid)
  if (allergens.length) {
    checks.push({
      level: 'OK',
      label: '过敏',
      text: `已回避：${allergens.slice(0, 4).join('、')}`,
    })
  } else {
    checks.push({ level: 'OK', label: '通过', text: '推荐食物与过敏原无交集' })
  }
  const limited = foodLabels(diet?.limited)
  if (limited.length) {
    checks.push({
      level: 'YELLOW',
      label: '提醒',
      text: `限制食物：${limited.slice(0, 3).join('、')}`,
    })
  }
  if (!energy?.energyKcal && !textOf(diet?.calorieHint)) {
    checks.push({ level: 'YELLOW', label: '提醒', text: '热量目标未给出，请核对后发布' })
  }
  const safety = asRecord(draft?.safetyFlags)
  const warnings = asArray(safety?.warnings || safety?.flags || safety?.messages)
    .map((w) => textOf(w) || textOf(asRecord(w)?.message) || textOf(asRecord(w)?.text))
    .filter(Boolean)
  for (const w of warnings.slice(0, 2)) {
    checks.push({ level: 'RED', label: '已改', text: w })
  }

  const source = textOf(draft?.source)
  // 头表 status 在「已有发布版 + 新草稿」时仍为 ACTIVE；聊天卡应以本次 extracted 为准
  const hasDraft = !!asRecord(root.draft)
  const status = hasDraft
    ? 'DRAFT'
    : textOf(plan?.status) || (asRecord(root.activeVersion) ? 'ACTIVE' : 'DRAFT')
  const title =
    textOf(plan?.title) ||
    textOf(asRecord(root.activeVersion)?.title) ||
    '健康管理方案'

  return {
    title,
    status,
    statusLabel: status === 'ACTIVE' ? '已发布' : status === 'ARCHIVED' ? '历史' : '草稿',
    sourceLabel:
      source === 'LLM' || source === 'LLM_THEN_EDIT'
        ? 'AI 生成'
        : source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT'
          ? '模板'
          : source === 'MANUAL'
            ? '人工'
            : source || 'AI',
    goalSummary: textOf(plan?.goalSummary) || textOf(exercise?.goal) || undefined,
    rationaleOverview,
    schemaVersion: draft?.schemaVersion == null ? null : Number(draft.schemaVersion),
    horizonDays: execution?.horizonDays == null ? null : Number(execution.horizonDays),
    energyKcal: energy?.energyKcal ?? null,
    weeklyDays: weeklyFull.length ? weeklyFull : weeklyDays,
    sampleMeals,
    tasks,
    checks,
  }
}
