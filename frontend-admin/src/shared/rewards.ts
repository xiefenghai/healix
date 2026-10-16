export interface RewardBadge {
  code: string
  name: string
  icon: string
  kind: 'BEHAVIOR' | 'OUTCOME'
  condition?: string | null
  earned: boolean
  earnedDate?: string | null
}

export interface RewardSummary {
  enabled: boolean
  points: number
  weekPoints: number
  todayPoints: number
  level: { level: number; title: string; nextTitle?: string | null; lifetime: number; progress: number } | null
  streak: number
  bestStreak: number
  freezeCards: number
  todayActive: boolean
  todayActions: number
  yesterdayActive: boolean
}

export interface RewardLedgerDay {
  date: string
  total: number
  items: { bizType: string; title: string; points: number }[]
}

export interface RewardChallenge {
  id: string
  kind: string
  title: string
  target: number
  progress: number
  status: string
  progressRatio: number
  pointsAwarded: number
}

export interface RewardStaffView {
  tenantEnabled: boolean
  patientEnabled: boolean
  summary: RewardSummary
  badges: RewardBadge[]
  ledger: RewardLedgerDay[]
  kudos: { staffId: string; staffName?: string | null; message?: string | null; createdAt: string }[]
  kudosSentToday: boolean
  /** 近 7 天本人补录条数 */
  backfills7d: number
  challenge?: RewardChallenge | null
}

/** 近 7 天补录达到此数时提示健管师复核 */
export const BACKFILL_ALERT = 5

/** 报告 content_json.rewards */
export interface ReportRewards {
  points: number
  activeDays: number
  fullDays: number
  streak: number
  levelTitle?: string
  newBadges: { name: string; icon: string; kind: string }[]
}

export function reportRewards(content: Record<string, unknown> | null | undefined): ReportRewards | null {
  const r = content?.rewards as ReportRewards | undefined
  if (!r) return null
  return r.points > 0 || r.activeDays > 0 || (r.newBadges?.length ?? 0) > 0 ? r : null
}

export interface CheckinBrief {
  streakDays?: number | null
  todayActions?: number | null
  inactiveDays?: number | null
}

/** 患者列表打卡状态；未开启激励（streakDays 为空）时返回 null */
export function checkinStatus(row: CheckinBrief): { text: string; tone: 'ok' | 'idle' | 'warn' } | null {
  if (row.streakDays == null) return null
  if ((row.todayActions ?? 0) > 0) return { text: `今日已打卡 ${row.todayActions} 项`, tone: 'ok' }
  if (row.inactiveDays == null) return { text: '30 天以上未打卡', tone: 'warn' }
  if (row.inactiveDays >= 3) return { text: `${row.inactiveDays} 天未打卡`, tone: 'warn' }
  return { text: '今日未打卡', tone: 'idle' }
}

export const KUDOS_PRESETS = ['坚持得很好，继续保持！', '这周进步很明显 👏', '指标控制得很稳，继续加油']
