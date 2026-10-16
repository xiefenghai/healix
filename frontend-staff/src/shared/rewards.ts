export interface CheckinBrief {
  streakDays?: number | null
  todayActions?: number | null
  inactiveDays?: number | null
}

/** 患者列表打卡状态；未开启激励（streakDays 为空）时返回 null */
export function checkinStatus(row: CheckinBrief): { text: string; tone: 'ok' | 'idle' | 'warn' } | null {
  if (row.streakDays == null) return null
  if ((row.todayActions ?? 0) > 0) return { text: `今日打卡 ${row.todayActions} 项`, tone: 'ok' }
  if (row.inactiveDays == null) return { text: '30 天+未打卡', tone: 'warn' }
  if (row.inactiveDays >= 3) return { text: `${row.inactiveDays} 天未打卡`, tone: 'warn' }
  return { text: '今日未打卡', tone: 'idle' }
}
