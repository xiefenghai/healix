/**
 * 方案解读大白话话术：从当前草稿字段拼装，供健管师照稿宣教与一键复制。
 * 首版不做 LLM，保证可离线、可预览、与方案页数据同源。
 */
import { energyGoalText, type EnergyTarget } from './energy-target'
import { patientDietNoteLines } from './care-plan-diet-notes'
import {
  carePlanExerciseKind,
  carePlanModerateEquivMin,
  formatCarePlanExerciseType,
} from './care-plan-labels'

export type BriefingWeeklyItem = {
  type?: string | null
  durationMin?: number | null
  intensity?: string | null
  note?: string | null
}

export type BriefingWeeklyDay = {
  day?: string | null
  items?: BriefingWeeklyItem[] | null
}

export type BriefingScriptInput = {
  displayName?: string | null
  /** M/F/男/女 等，用于称呼 */
  gender?: string | null
  goalSummary?: string | null
  energyTarget?: EnergyTarget | null
  allergenLabels?: string[]
  exerciseGoal?: string | null
  weeklyPlan?: BriefingWeeklyDay[] | null
  dietNotes?: string | null
}

/** 是否像 peopleId / ULID，不能当患者姓名展示 */
export function looksLikePeopleId(value?: string | null): boolean {
  const s = String(value || '').trim()
  if (!s) return false
  if (/[\u4e00-\u9fff]/.test(s)) return false
  // 长纯字母数字（含 ULID）一律视为 ID
  if (/^[0-9A-Za-z_-]{12,}$/.test(s)) return true
  return false
}

/** 解析可宣读的真实姓名；ID / 空串视为无效 */
export function resolvePatientDisplayName(displayName?: string | null): string {
  const raw = String(displayName || '').trim()
  if (!raw || looksLikePeopleId(raw)) return ''
  return raw.replace(/(先生|女士|同学|老师)$/u, '').trim() || raw
}

function honorific(gender?: string | null): string {
  const g = String(gender || '')
    .trim()
    .toUpperCase()
  if (g === 'M' || g === 'MALE' || g === '男' || g === 'MAN') return '先生'
  if (g === 'F' || g === 'FEMALE' || g === '女' || g === 'WOMAN') return '女士'
  return ''
}

function politeName(displayName?: string | null, gender?: string | null): string {
  const base = resolvePatientDisplayName(displayName)
  if (!base) return '您好'
  const title = honorific(gender)
  return title ? `${base}${title}您好` : `${base}您好`
}

function pickDietPoints(notes?: string | null, allergens?: string[]): string[] {
  const out: string[] = []
  const allergen = (allergens || []).map((x) => String(x || '').trim()).filter(Boolean)
  if (allergen.length) {
    out.push(`${allergen.slice(0, 3).join('、')}过敏，千万别碰`)
  }
  const lines = patientDietNoteLines(notes || '')
  for (const line of lines) {
    if (out.length >= 3) break
    if (/睡眠呼吸|多导睡眠|打鼾/.test(line)) {
      if (!out.some((x) => /睡眠|打鼾/.test(x))) {
        out.push('打鼾和晨起疲惫要尽快去做睡眠呼吸监测，别拖')
      }
      continue
    }
    if (/软烂|粥/.test(line)) {
      if (!out.some((x) => /粥|主食/.test(x))) out.push('主食换成杂粮固态，少喝软烂粥')
      continue
    }
    if (/进餐顺序|先吃/.test(line)) {
      if (!out.some((x) => /先吃|进餐/.test(x))) out.push('吃饭先菜和蛋白质，主食放最后')
      continue
    }
    if (/少盐|少油|外食|含糖/.test(line)) {
      if (!out.some((x) => /少盐|少油|外食/.test(x))) out.push('少盐少油，少喝含糖饮料')
      continue
    }
  }
  return out
}

/**
 * 从周计划结构化字段汇总运动要点，避免对自由文本 goal 做正则截取造成数字幻觉。
 * 例：每周累计中等强度有氧约150分钟（含4次步行、2次骑行）
 */
export function summarizeExerciseFromWeekly(
  weeklyPlan?: BriefingWeeklyDay[] | null,
): string {
  const days = Array.isArray(weeklyPlan) ? weeklyPlan : []
  const typeCount = new Map<string, number>()
  const durations: number[] = []
  let moderateEquiv = 0
  let aerobicSessions = 0

  for (const day of days) {
    for (const item of day?.items || []) {
      const min = Number(item?.durationMin) || 0
      if (min <= 0) continue
      if (carePlanExerciseKind(item?.type) !== 'aerobic') continue
      aerobicSessions += 1
      moderateEquiv += carePlanModerateEquivMin(item)
      durations.push(min)
      const label = formatCarePlanExerciseType(item?.type) || '有氧'
      typeCount.set(label, (typeCount.get(label) || 0) + 1)
    }
  }

  if (aerobicSessions <= 0) return ''

  const typeBits = [...typeCount.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([label, n]) => `${n}次${label}`)
    .join('、')

  if (moderateEquiv > 0) {
    const head = `每周累计中等强度有氧约${moderateEquiv}分钟`
    return typeBits ? `${head}（含${typeBits}）` : head
  }

  const typical =
    durations.sort((a, b) => a - b)[Math.floor(durations.length / 2)] || 30
  return typeBits
    ? `每周约${aerobicSessions}次运动（含${typeBits}，单次约${typical}分钟）`
    : `每周约${aerobicSessions}次中等强度运动，单次约${typical}分钟`
}

const SAFE_EXERCISE_FALLBACK = '每天餐后30分钟中等强度运动'

/**
 * 运动宣教句：优先周计划变量渲染；无周计划时用安全兜底，不再对 goal 自由截取。
 */
function pickExercisePoint(
  goal?: string | null,
  weeklyPlan?: BriefingWeeklyDay[] | null,
): string {
  const fromWeekly = summarizeExerciseFromWeekly(weeklyPlan)
  if (fromWeekly) return fromWeekly
  const g = String(goal || '').trim()
  // 仅当 goal 本身已是「每周累计…」类规范句时原样采用，避免截出「5天」碎片
  if (/^每周累计中等强度有氧约\d+分钟/.test(g)) {
    const first = g.split(/[。；;\n]/)[0]?.trim()
    if (first && first.length <= 48) return first
  }
  return SAFE_EXERCISE_FALLBACK
}

/** 生成患者版解读话术（纯文本，可复制） */
export function buildCarePlanBriefingScript(input: BriefingScriptInput): string {
  const hello = politeName(input.displayName, input.gender)
  const goal = String(input.goalSummary || '').trim()
  const goalLead = goal
    ? goal.length > 36
      ? `${goal.slice(0, 36)}…`
      : goal
    : '按您的健康目标来管'
  const energy = input.energyTarget
  const energyBit = energy
    ? `饮食大约每天 ${energy.energyKcal} 千卡（${energyGoalText(energy)}）`
    : ''
  const dietBits = pickDietPoints(input.dietNotes, input.allergenLabels)
  const exerciseBit = pickExercisePoint(input.exerciseGoal, input.weeklyPlan)

  const points: string[] = []
  if (energyBit || dietBits[0]) {
    points.push(
      [energyBit, dietBits[0]].filter(Boolean).join('；') || '饮食按方案少盐少油、主食控量',
    )
  }
  if (exerciseBit) points.push(exerciseBit)
  const rest = dietBits.slice(energyBit || dietBits[0] ? 1 : 0)
  for (const r of rest) {
    if (points.length >= 3) break
    points.push(r)
  }
  while (points.length < 3) {
    const fallback = ['少盐少油，规律三餐', '按方案完成打卡', '有不适及时联系健管师']
    const next = fallback.find((x) => !points.includes(x))
    if (!next) break
    points.push(next)
  }

  const numbered = points
    .slice(0, 3)
    .map((p, i) => `第${['一', '二', '三'][i]}，${p.replace(/^[一二三]、\s*/, '')}`)
    .join('；')

  return `${hello}，您的专属健康管理方案出来了。咱们主要是${goalLead}。重点是：${numbered}。您看这几点能坚持吗？`
}

export type BriefingHighlightCards = {
  weight: string
  diet: string
  exercise: string
}

export function buildBriefingHighlightCards(input: BriefingScriptInput): BriefingHighlightCards {
  const energy = input.energyTarget
  const weight = energy ? energyGoalText(energy) : '按方案目标执行'
  const allergen = (input.allergenLabels || []).filter(Boolean).slice(0, 2).join('、')
  const diet = energy
    ? `${energy.energyKcal} 千卡${allergen ? `（${allergen}忌口）` : ''}`
    : allergen
      ? `${allergen}忌口`
      : '按饮食方案执行'
  const exercise = pickExercisePoint(input.exerciseGoal, input.weeklyPlan)
  return { weight, diet, exercise }
}
