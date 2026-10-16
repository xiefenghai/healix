/** 运动记录：类型、文案与接口模型（与后端 exercise 模块对齐）。 */

export type Intensity = 'LIGHT' | 'MODERATE' | 'VIGOROUS'
export type PlanState = 'ACTIVE' | 'EXPIRED' | 'NONE'
export type ExerciseLevel = 'GREEN' | 'YELLOW' | 'RED'

export interface ExerciseType {
  code: string
  name: string
  aliases: string[]
  category: string
  defaultIntensity: Intensity
  met: Record<string, number>
  tags: string[]
}

export interface ExerciseFlag {
  code: string
  level: 'RED' | 'YELLOW' | 'INFO'
  message: string
}

export interface ExerciseSession {
  id: string
  exerciseDate: string
  startedAt: string
  durationMin: number
  typeCode: string
  typeName: string
  category: string
  intensity: Intensity
  avgHr?: number | null
  distanceM?: number | null
  metValue: number
  activeMin: number
  energyKcal: number
  weightEstimated: boolean
  symptoms: string[]
  symptomLevel: 'NONE' | 'YELLOW' | 'RED'
  flags: ExerciseFlag[]
  level: ExerciseLevel
  carePlanTaskId?: string | null
  source: string
  note?: string | null
  recordedByStaff: boolean
  editable: boolean
}

export interface ExerciseTaskItem {
  taskId: string
  taskCode: string
  title: string
  typeCode?: string | null
  typeName?: string | null
  category: string
  targetMin: number
  intensity: Intensity
  doneMin: number
  checkinStatus?: string | null
}

export interface ExerciseToday {
  date: string
  planState: PlanState
  tasks: ExerciseTaskItem[]
  contraindications: string[]
  precautions: string[]
  sessions: ExerciseSession[]
}

export interface ExerciseWeekDay {
  date: string
  dueCount: number
  doneCount: number
  activeMin: number
  totalMin: number
  steps?: number | null
  level?: ExerciseLevel | null
}

export interface ExerciseWeek {
  weekStart: string
  weekEnd: string
  planState: PlanState
  dueCount: number
  doneCount: number
  activeMin: number
  weeklyActiveMinTarget: number
  totalMin: number
  sessionCount: number
  hasSteps: boolean
  days: ExerciseWeekDay[]
}

/** 谈话测试三档 */
export const INTENSITY_OPTIONS: { value: Intensity; label: string; hint: string }[] = [
  { value: 'LIGHT', label: '轻松', hint: '还能轻松唱歌' },
  { value: 'MODERATE', label: '中等', hint: '能说话，但唱不了歌' },
  { value: 'VIGOROUS', label: '较累', hint: '说不完整句子' },
]

export const INTENSITY_LABELS: Record<string, string> = {
  LIGHT: '轻松',
  MODERATE: '中等',
  VIGOROUS: '较累',
}

/**
 * 大类任务（抗阻、有氧、拉伸等）补一句患者能照做的动作。
 * 标题里已经点了具体运动（步行、太极、弹力带等）时不再追加。
 */
export function exerciseHowTo(title?: string | null): string | null {
  const t = (title || '').replace(/\s/g, '')
  if (!t) return null
  if (/步行|散步|快走|慢跑|跑步|骑车|骑行|游泳|太极|广场舞|瑜伽|八段锦|靠墙|深蹲|弹力带|哑铃|平板支撑|抬腿|划船/.test(t)) {
    return null
  }
  if (/抗阻|力量训练|力量练习|阻力训练|负重训练/.test(t)) {
    return '可以这样做：靠墙静蹲、坐着抬腿、弹力带往后拉，各 8～12 次，做 2～3 组。自然呼吸，不要憋气。'
  }
  if (/有氧/.test(t)) {
    return '可以这样做：快走、骑车或游泳，保持能正常说话。'
  }
  if (/柔韧|拉伸/.test(t)) {
    return '可以这样做：肩、大腿后侧、小腿各停 15～20 秒，拉到有感觉即可，不要弹振。'
  }
  if (/平衡/.test(t)) {
    return '可以这样做：扶着椅背站稳，再轻轻抬起一只脚，每次 10～20 秒，左右轮换。'
  }
  if (/核心/.test(t)) {
    return '可以这样做：靠墙静蹲或仰卧抬腿，动作放慢，不要憋气。'
  }
  if (/呼吸训练|呼吸练习/.test(t)) {
    return '可以这样做：坐直，鼻子吸气 4 秒，嘴巴慢慢呼气 6 秒，重复 8～10 次。'
  }
  if (/床旁|卧床/.test(t)) {
    return '可以这样做：躺着勾脚尖再绷直，坐着把腿抬平，每边 10 次，量力而行。'
  }
  return null
}

export const SYMPTOM_OPTIONS: { value: string; label: string; red?: boolean }[] = [
  { value: 'DIZZY', label: '头晕' },
  { value: 'PALPITATION', label: '心慌' },
  { value: 'JOINT_PAIN', label: '关节疼痛' },
  { value: 'HYPO', label: '低血糖症状（出汗、手抖）' },
  { value: 'CHEST_PAIN', label: '胸痛胸闷', red: true },
  { value: 'SYNCOPE', label: '晕厥或快晕倒', red: true },
  { value: 'SEVERE_DYSPNEA', label: '严重气短', red: true },
]

export const SYMPTOM_LABELS: Record<string, string> = Object.fromEntries(
  SYMPTOM_OPTIONS.map((o) => [o.value, o.label]),
)

export const CATEGORY_LABELS: Record<string, string> = {
  AEROBIC: '有氧',
  RESISTANCE: '力量',
  FLEXIBILITY: '柔韧',
  BALANCE: '平衡',
  DAILY: '日常活动',
}

export const BACKFILL_DAYS = 7
export const LONG_DURATION_MIN = 240
export const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']

export function hhmm(iso: string): string {
  const t = iso.split('T')[1] || ''
  return t.slice(0, 5)
}

/** 本地时间 → 后端 LocalDateTime（无时区） */
export function toLocalDateTime(date: string, time: string): string {
  return `${date}T${time.length === 5 ? `${time}:00` : time}`
}
