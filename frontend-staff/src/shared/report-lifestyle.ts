/** 管理报告「饮食 / 运动」章节（content.lifestyle）展示行 */

export interface LifestyleStat {
  label: string
  value: string
  sub?: string
  warn?: boolean
}

export interface LifestyleSection {
  key: 'diet' | 'exercise'
  title: string
  stats: LifestyleStat[]
  empty?: string
}

/** 中国居民膳食指南：食盐 <5 g/日 ≈ 钠 2000 mg */
const SODIUM_LIMIT_MG = 2000
const WHO_WEEKLY_ACTIVE_MIN = 150

type Num = number | null | undefined

interface LifestyleContent {
  dayCount?: number
  diet?: {
    recordDays?: number
    completeDays?: number
    targetKcal?: Num
    avgKcal?: Num
    onTargetDays?: Num
    avgSodiumMg?: Num
    redMeals?: number
    yellowMeals?: number
  }
  exercise?: {
    activeDays?: number
    sessionCount?: number
    totalMin?: number
    avgWeeklyActiveMin?: number
    weeklyActiveMinTarget?: Num
    redCount?: number
    yellowCount?: number
    categories?: Array<{ label?: string; min?: number }>
  }
}

export function lifestyleSections(content?: Record<string, unknown> | null): LifestyleSection[] {
  const ls = content?.lifestyle as LifestyleContent | undefined
  if (!ls) return []
  const days = Number(ls.dayCount || 0)
  return [dietSection(ls.diet, days), exerciseSection(ls.exercise, days)]
}

function dietSection(d: LifestyleContent['diet'], days: number): LifestyleSection {
  const section: LifestyleSection = { key: 'diet', title: '饮食记录', stats: [] }
  const recordDays = Number(d?.recordDays || 0)
  if (!d || !recordDays) {
    section.empty = '本周期暂无饮食记录'
    return section
  }
  const red = Number(d.redMeals || 0)
  const yellow = Number(d.yellowMeals || 0)
  section.stats.push({
    label: '记录天数',
    value: `${recordDays}/${days} 天`,
    sub: `三餐记全 ${d.completeDays || 0} 天`,
  })
  section.stats.push({
    label: '日均热量',
    value: d.avgKcal != null ? `${d.avgKcal} kcal` : '-',
    sub:
      d.targetKcal != null
        ? `目标 ${d.targetKcal} kcal · 达标 ${d.onTargetDays ?? 0} 天`
        : d.avgKcal != null
          ? '未设热量目标'
          : '三餐记全后统计',
  })
  section.stats.push({
    label: '日均钠',
    value: d.avgSodiumMg != null ? `${d.avgSodiumMg} mg` : '-',
    sub: `建议 <${SODIUM_LIMIT_MG} mg`,
    warn: d.avgSodiumMg != null && d.avgSodiumMg > SODIUM_LIMIT_MG,
  })
  section.stats.push({
    label: '需注意餐次',
    value: `${red + yellow} 餐`,
    sub: red ? `需调整 ${red} · 留意 ${yellow}` : yellow ? `留意 ${yellow}` : '无',
    warn: red > 0,
  })
  return section
}

function exerciseSection(e: LifestyleContent['exercise'], days: number): LifestyleSection {
  const section: LifestyleSection = { key: 'exercise', title: '运动记录', stats: [] }
  const activeDays = Number(e?.activeDays || 0)
  if (!e || !activeDays) {
    section.empty = '本周期暂无运动记录'
    return section
  }
  const target = e.weeklyActiveMinTarget ?? null
  const avg = Number(e.avgWeeklyActiveMin || 0)
  const red = Number(e.redCount || 0)
  const yellow = Number(e.yellowCount || 0)
  const top = (e.categories || [])
    .slice(0, 3)
    .map((c) => `${c.label || '其他'} ${c.min || 0}`)
    .join(' · ')
  section.stats.push({
    label: '运动天数',
    value: `${activeDays}/${days} 天`,
    sub: `共 ${e.sessionCount || 0} 次`,
  })
  section.stats.push({
    label: '总时长',
    value: `${e.totalMin || 0} 分钟`,
    sub: top || undefined,
  })
  section.stats.push({
    label: '周均活动分钟',
    value: `${avg} 分钟`,
    sub: target ? `计划 ${target} 分钟/周` : `参考 ${WHO_WEEKLY_ACTIVE_MIN} 分钟/周`,
    warn: target != null && target > 0 && avg < target,
  })
  section.stats.push({
    label: '运动中不适',
    value: `${red + yellow} 次`,
    sub: red ? `需关注 ${red} 次` : '无严重不适',
    warn: red > 0,
  })
  return section
}
