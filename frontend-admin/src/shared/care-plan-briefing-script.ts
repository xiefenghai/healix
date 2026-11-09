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
  /** 热量文案兜底：energyTarget 缺失时尝试从中解析千卡 */
  calorieHint?: string | null
  allergenLabels?: string[]
  exerciseGoal?: string | null
  weeklyPlan?: BriefingWeeklyDay[] | null
  dietNotes?: string | null
}

/** 从热量提示文案解析每日千卡 */
export function parseKcalFromHint(hint?: string | null): number | null {
  const s = String(hint || '')
  const m = s.match(/(\d{3,4})\s*(?:千卡|大卡|kcal)/i)
  if (!m) return null
  const n = Number(m[1])
  return n >= 800 && n <= 5000 ? n : null
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

function formatKgRate(n: number): string {
  const r = Math.round(n * 10) / 10
  return Number.isInteger(r) ? String(r) : r.toFixed(1)
}

export type PeriodWeightLoss = {
  months: number
  totalKg: number
  weeklyKg: number
  fromKg?: number
  toKg?: number
}

/**
 * 从目标句提取减重周期与总量，并推算每周速度（月×52/12≈周）。
 * 支持：
 * - 「6个月减重20kg」
 * - 「6个月内从80kg降至60kg」
 */
export function parsePeriodWeightLoss(goalSummary?: string | null): PeriodWeightLoss | null {
  const s = String(goalSummary || '')
  let months = 0
  let totalKg = 0
  let fromKg: number | undefined
  let toKg: number | undefined

  // 6个月内从80kg降至60kg / 6个月从80公斤减到60公斤
  const fromTo = s.match(
    /(\d+(?:\.\d+)?)\s*(?:个)?月(?:左右|内)?[^。；;\n]{0,24}从\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)\s*(?:降至|减至|降到|减到|到)\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)/i,
  )
  if (fromTo) {
    months = Number(fromTo[1])
    fromKg = Number(fromTo[2])
    toKg = Number(fromTo[3])
    if (fromKg > toKg) totalKg = fromKg - toKg
  }

  if (!(months > 0 && totalKg > 0)) {
    const a = s.match(
      /(\d+(?:\.\d+)?)\s*(?:个)?月(?:左右|内)?[^。；;\n\d]{0,16}减(?:重|脂)?\s*约?\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)/i,
    )
    if (a) {
      months = Number(a[1])
      totalKg = Number(a[2])
    } else {
      const b = s.match(
        /减(?:重|脂)?\s*约?\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)[^。；;\n]{0,16}(\d+(?:\.\d+)?)\s*(?:个)?月/i,
      )
      if (b) {
        totalKg = Number(b[1])
        months = Number(b[2])
      }
    }
  }

  // 无起止体重但月份写在别处：…从80kg降至60kg…（6个月）
  if (!(months > 0 && totalKg > 0)) {
    const ft = s.match(
      /从\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)\s*(?:降至|减至|降到|减到|到)\s*(\d+(?:\.\d+)?)\s*(?:kg|公斤)/i,
    )
    const mo = s.match(/(\d+(?:\.\d+)?)\s*(?:个)?月/)
    if (ft && mo) {
      fromKg = Number(ft[1])
      toKg = Number(ft[2])
      months = Number(mo[1])
      if (fromKg > toKg) totalKg = fromKg - toKg
    }
  }

  if (!(months > 0 && totalKg > 0)) return null
  const weeks = months * (52 / 12)
  const weeklyKg = Math.round((totalKg / weeks) * 10) / 10
  if (!(weeklyKg > 0)) return null
  return { months, totalKg, weeklyKg, fromKg, toKg }
}

/** 宣教用每周减重速度：优先目标句推算，避免与「N月减Mkg / 从A降至B」打架 */
export function resolveWeeklyLossKg(
  goalSummary?: string | null,
  energy?: EnergyTarget | null,
): number | null {
  const implied = parsePeriodWeightLoss(goalSummary)?.weeklyKg
  if (implied && implied > 0) return implied
  if (energy?.goalType === 'LOSE' && energy.rateKgPerWeek && energy.rateKgPerWeek > 0) {
    return energy.rateKgPerWeek
  }
  return null
}

const WEEKLY_RATE_RE =
  /(?:（|\()?每周(?:累计)?(?:约)?(?:减重|减|减脂)?\s*约?\s*\d+(?:\.\d+)?\s*(?:kg|公斤)(?:）|\))?/g

/** 修正目标句中自相矛盾的「每周减 X kg」数字；不截断正文 */
export function alignGoalSummaryMath(
  goalSummary?: string | null,
  energy?: EnergyTarget | null,
): string {
  const raw = String(goalSummary || '').trim()
  if (!raw) return ''
  const weekly = resolveWeeklyLossKg(raw, energy)
  if (!weekly) return collapseDuplicatePunctuation(raw)
  const rateText = `每周约减${formatKgRate(weekly)}kg`
  let out = raw
  if (WEEKLY_RATE_RE.test(out)) {
    WEEKLY_RATE_RE.lastIndex = 0
    out = out.replace(WEEKLY_RATE_RE, (m) => {
      const wrapped = m.startsWith('（') || m.startsWith('(')
      return wrapped ? `（${rateText}）` : rateText
    })
  }
  return collapseDuplicatePunctuation(out)
}

/** 清理连用标点：。。 / 。． → 。 */
export function collapseDuplicatePunctuation(raw: string): string {
  return String(raw || '')
    .replace(/[。．.]{2,}/g, '。')
    .replace(/！{2,}/g, '！')
    .replace(/？{2,}/g, '？')
    .replace(/，{2,}/g, '，')
    .replace(/；{2,}/g, '；')
    .replace(/。\s*。/g, '。')
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

export type ExerciseSummary = {
  sessions: number
  minutes: number
  /** 卡片短句：每周约 6 次（约 150 分钟） */
  card: string
  /** 话术稍详：可带运动类型 */
  script: string
}

/**
 * 从周计划结构化字段汇总运动要点，避免对自由文本 goal 做正则截取造成数字幻觉。
 */
export function summarizeExerciseStats(
  weeklyPlan?: BriefingWeeklyDay[] | null,
): ExerciseSummary | null {
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

  if (aerobicSessions <= 0) return null

  const minutes =
    moderateEquiv > 0
      ? moderateEquiv
      : durations.reduce((sum, n) => sum + n, 0) ||
        (durations.sort((a, b) => a - b)[Math.floor(durations.length / 2)] || 30) * aerobicSessions

  const typeBits = [...typeCount.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([label, n]) => `${n}次${label}`)
    .join('、')

  const card = `每周约 ${aerobicSessions} 次（约 ${minutes} 分钟）`
  const script = typeBits
    ? `每周约${aerobicSessions}次运动（约${minutes}分钟，含${typeBits}）`
    : `每周约${aerobicSessions}次中等强度运动（约${minutes}分钟）`

  return { sessions: aerobicSessions, minutes, card, script }
}

/** @deprecated 兼容旧调用；返回话术句 */
export function summarizeExerciseFromWeekly(
  weeklyPlan?: BriefingWeeklyDay[] | null,
): string {
  return summarizeExerciseStats(weeklyPlan)?.script || ''
}

const SAFE_EXERCISE_FALLBACK = '每天餐后30分钟中等强度运动'

/**
 * 运动宣教句：优先周计划变量渲染；无周计划时用安全兜底，不再对 goal 自由截取。
 */
function pickExercisePoint(
  goal?: string | null,
  weeklyPlan?: BriefingWeeklyDay[] | null,
): string {
  const stats = summarizeExerciseStats(weeklyPlan)
  if (stats) return stats.script
  const g = String(goal || '').trim()
  // 仅当 goal 本身已是「每周累计…」类规范句时原样采用，避免截出「5天」碎片
  if (/^每周累计中等强度有氧约\d+分钟/.test(g)) {
    const first = g.split(/[。；;\n]/)[0]?.trim()
    if (first && first.length <= 48) return first
  }
  return SAFE_EXERCISE_FALLBACK
}

function energyPaceClause(
  energy?: EnergyTarget | null,
  goalSummary?: string | null,
): string {
  if (!energy) return ''
  if (energy.goalType === 'LOSE') {
    const weekly = resolveWeeklyLossKg(goalSummary, energy)
    if (weekly) return `每周约减${formatKgRate(weekly)}kg`
    return '稳步减重'
  }
  if (energy.goalType === 'GAIN' && energy.rateKgPerWeek) {
    return `每周约增${formatKgRate(energy.rateKgPerWeek)}kg`
  }
  return energyGoalText(energy)
}

/** 生成患者版解读话术（纯文本，可复制；目标句不截断） */
export function buildCarePlanBriefingScript(input: BriefingScriptInput): string {
  const hello = politeName(input.displayName, input.gender)
  const goalAligned = alignGoalSummaryMath(input.goalSummary, input.energyTarget)
  const goalLead = goalAligned || '按您的健康目标来管'
  const energy = input.energyTarget
  const hintKcal = energy?.energyKcal || parseKcalFromHint(input.calorieHint)
  const pace = energyPaceClause(energy, input.goalSummary)
  const energyBit = hintKcal
    ? `饮食大约每天 ${hintKcal} 千卡${pace ? `（${pace}）` : ''}`
    : patientDietFallback(input.goalSummary, energy)
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
    .join('\n')

  // goalLead 可能已带句号，避免拼出「。。」
  const lead = goalLead.replace(/[。．.]+$/u, '')
  return collapseDuplicatePunctuation(
    `${hello}，您的专属健康管理方案出来了。咱们主要是${lead}。\n重点是：\n${numbered}\n您看这几点能坚持吗？`,
  )
}

/** 话术分段：确保「第一/第二/第三」各自换行（兼容旧稿用分号挤在一段） */
export function formatBriefingScriptParagraphs(raw: string): string {
  return collapseDuplicatePunctuation(
    String(raw || '')
      .replace(/\r\n/g, '\n')
      .replace(/([。！？；;])\s*(?=第[一二三])/g, '$1\n')
      .replace(/；\s*(?=第[二三])/g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim(),
  )
}

export type BriefingHighlightCards = {
  weight: string
  diet: string
  exercise: string
}

/** 从目标句提取「减重至 / 降至 X kg」 */
function extractTargetWeightLabel(goalSummary?: string | null): string | null {
  const s = String(goalSummary || '')
  const m = s.match(/(?:减重至|降至|目标体重[为是：:]?\s*|体重目标[为是：:]?\s*)(\d+(?:\.\d+)?)\s*kg/i)
  if (m) return `减重至 ${m[1]}kg`
  return null
}

/** 目标卡片短句：去掉运动括号，优先周期/目标体重，最长约 18 字 */
export function compactGoalForCard(
  goalSummary?: string | null,
  energy?: EnergyTarget | null,
): string {
  const goal = String(goalSummary || '').trim()
  const targetWeight = extractTargetWeightLabel(goal)
  if (targetWeight) return targetWeight

  const period = parsePeriodWeightLoss(goal)
  if (period) {
    if (period.months >= 3) return `${period.months}个月内平稳减重`
    return `${period.months}个月减重约${formatKgRate(period.totalKg)}kg`
  }

  const weekly = resolveWeeklyLossKg(goal, energy)
  if (energy?.goalType === 'LOSE' && weekly) {
    return `每周约减 ${formatKgRate(weekly)}kg`
  }
  if (energy?.goalType === 'LOSE') return '平稳减重'
  if (energy && energy.goalType !== 'GAIN') return energyGoalText(energy)
  if (energy?.goalType === 'GAIN') return energyGoalText(energy)

  if (!goal) return '按方案目标执行'

  // 去掉「（每周运动…）」类附属句，再取首句短截
  const cleaned = goal
    .replace(/[（(][^）)]*(?:运动|分钟|有氧|步数)[^）)]*[）)]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  const first = cleaned.split(/[。；;\n]/)[0]?.trim() || cleaned
  if (first.length <= 18) return first
  if (/减重|减脂|体重/.test(first)) return '平稳减重'
  return `${first.slice(0, 16)}…`
}

function patientDietFallback(
  goalSummary?: string | null,
  energy?: EnergyTarget | null,
): string {
  if (energy?.goalType === 'LOSE') return '按标准减重餐执行'
  if (energy?.goalType === 'GAIN') return '按标准增重餐执行'
  const g = String(goalSummary || '')
  if (/减重|减脂|肥胖|体脂/.test(g)) return '按标准减重餐执行'
  return '按标准饮食方案执行'
}

export function buildBriefingHighlightCards(input: BriefingScriptInput): BriefingHighlightCards {
  const energy = input.energyTarget
  const goal = String(input.goalSummary || '').trim()
  const weight = compactGoalForCard(goal, energy)

  const allergen = (input.allergenLabels || []).filter(Boolean).slice(0, 2).join('、')
  const kcal = energy?.energyKcal || parseKcalFromHint(input.calorieHint)
  const dietFallback = patientDietFallback(goal, energy)
  // 面向患者：禁止露出「待完善」等后台状态词
  const diet = kcal
    ? `${kcal}千卡${allergen ? `（${allergen}忌口）` : ''}`
    : allergen
      ? `${dietFallback}（${allergen}忌口）`
      : dietFallback

  const stats = summarizeExerciseStats(input.weeklyPlan)
  const exercise = stats?.card || pickExercisePoint(input.exerciseGoal, input.weeklyPlan)

  return { weight, diet, exercise }
}
