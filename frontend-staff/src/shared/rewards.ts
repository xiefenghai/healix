export interface CheckinBrief {
  streakDays?: number | null
  todayActions?: number | null
  inactiveDays?: number | null
  joinedAt?: string | null
}

/** 入组满这么多天、且窗口内仍无打卡，才写成「30 天以上未打卡」。 */
const CHECKIN_LOOKBACK_DAYS = 30

function daysSinceJoin(joinedAt?: string | null): number | null {
  if (!joinedAt) return null
  const joined = new Date(joinedAt)
  if (Number.isNaN(joined.getTime())) return null
  const start = new Date(joined.getFullYear(), joined.getMonth(), joined.getDate())
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.max(0, Math.round((today.getTime() - start.getTime()) / 86400000))
}

/** 患者列表打卡状态；未开启激励（streakDays 为空）时返回 null */
export function checkinStatus(row: CheckinBrief): { text: string; tone: 'ok' | 'idle' | 'warn' } | null {
  if (row.streakDays == null) return null
  if ((row.todayActions ?? 0) > 0) return { text: `今日打卡 ${row.todayActions} 项`, tone: 'ok' }
  if (row.inactiveDays == null) {
    const sinceJoin = daysSinceJoin(row.joinedAt)
    if (sinceJoin != null && sinceJoin < CHECKIN_LOOKBACK_DAYS) {
      if (sinceJoin < 3) return { text: '尚未打卡', tone: 'idle' }
      return { text: `${sinceJoin} 天未打卡`, tone: 'warn' }
    }
    return { text: '30 天+未打卡', tone: 'warn' }
  }
  if (row.inactiveDays >= 3) return { text: `${row.inactiveDays} 天未打卡`, tone: 'warn' }
  return { text: '今日未打卡', tone: 'idle' }
}
