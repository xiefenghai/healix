import { api } from '../api/http'

export interface RewardLevel {
  level: number
  title: string
  nextTitle?: string | null
  nextThreshold?: number | null
  lifetime: number
  progress: number
}

export interface RewardBadge {
  code: string
  name: string
  icon: string
  kind: 'BEHAVIOR' | 'OUTCOME'
  condition?: string | null
  earned: boolean
  earnedDate?: string | null
}

export interface RewardChallenge {
  id: string
  kind: string
  title: string
  target: number
  progress: number
  status: string
  periodStart: string
  periodEnd: string
  progressRatio: number
  pointsAwarded: number
}

export interface RewardPeer {
  available: boolean
  percentile: number | null
  poolSize: number
  optOut: boolean
  metricLabel: string
}

export interface RewardSummary {
  enabled: boolean
  points: number
  weekPoints: number
  todayPoints: number
  level: RewardLevel | null
  streak: number
  bestStreak: number
  freezeCards: number
  todayActive: boolean
  todayActions: number
  planDue: number
  planDone: number
  yesterdayPending: number
  yesterdayActive: boolean
  recentBadges: RewardBadge[]
  challenge?: RewardChallenge | null
  peer?: RewardPeer | null
}

export interface RewardLedgerItem {
  bizType: string
  title: string
  points: number
  createdAt?: string
}

export interface RewardLedgerDay {
  date: string
  total: number
  items: RewardLedgerItem[]
}

/** 报告 content_json.rewards */
export interface ReportRewards {
  points: number
  activeDays: number
  fullDays: number
  streak: number
  levelTitle?: string
  newBadges: { name: string; icon: string; kind: string }[]
}

export async function loadRewardSummary(): Promise<RewardSummary | null> {
  try {
    const res = await api<{ data: RewardSummary }>('/api/c/v1/rewards/summary')
    return res.data?.enabled ? res.data : null
  } catch {
    return null
  }
}

export const REWARD_TYPE_ICONS: Record<string, string> = {
  PLAN: '✅',
  MED: '💊',
  VITAL: '📈',
  DIET: '🥗',
  EXERCISE: '🏃',
  REPORT_READ: '📖',
  FULL_DAY: '🌟',
  BADGE: '🏅',
  CHALLENGE: '🎯',
}

export async function setPeerOptOut(optOut: boolean): Promise<RewardPeer | null> {
  try {
    const res = await api<{ data: RewardPeer }>('/api/c/v1/rewards/peer/opt-out', {
      method: 'PUT',
      body: JSON.stringify({ optOut }),
    })
    return res.data
  } catch {
    return null
  }
}

export function ledgerDayLabel(date: string) {
  const d = new Date(`${date}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((today.getTime() - d.getTime()) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

export function hasReportRewards(r?: ReportRewards | null): r is ReportRewards {
  return !!r && (r.points > 0 || r.activeDays > 0 || (r.newBadges?.length ?? 0) > 0)
}
