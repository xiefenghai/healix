/** 近 N 日方案任务完成率色阶（与风险口径：<60% 偏中风险对齐）。 */
export type PlanRateBand = 'good' | 'fair' | 'concern' | 'poor' | 'empty'

export function planRateBand(rate?: number | null): PlanRateBand {
  if (rate == null || Number.isNaN(rate)) return 'empty'
  if (rate >= 0.8) return 'good'
  if (rate >= 0.6) return 'fair'
  if (rate >= 0.5) return 'concern'
  return 'poor'
}

export function formatPlanRatePct(rate?: number | null): string {
  if (rate == null || Number.isNaN(rate)) return '-'
  return `${Math.round(rate * 100)}%`
}

/** 进度条宽度 0–100；无数据为 0 */
export function planRateBarWidth(rate?: number | null): number {
  if (rate == null || Number.isNaN(rate)) return 0
  return Math.max(0, Math.min(100, Math.round(rate * 100)))
}

export const PLAN_RATE_COLUMN_HINT =
  '近7日方案任务完成率（应打任务：已完成/应打）。色阶：≥80% 绿 · ≥60% 蓝 · ≥50% 橙 · <50% 红'
