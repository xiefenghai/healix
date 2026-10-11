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
