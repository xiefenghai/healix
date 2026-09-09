/** 管理报告（周/月/季）展示文案与分档模板 */

export function formatHealthReportPeriodType(code?: string) {
  switch (code) {
    case 'WEEK':
      return '周报'
    case 'MONTH':
      return '月报'
    case 'QUARTER':
      return '三个月报告'
    default:
      return code || '-'
  }
}

export function formatHealthReportStatus(code?: string) {
  switch (code) {
    case 'DRAFT':
      return '待审阅'
    case 'PUBLISHED':
      return '已发布'
    case 'SKIPPED':
      return '已跳过'
    default:
      return code || '-'
  }
}

export function healthReportStatusTagType(code?: string): 'warning' | 'success' | 'info' {
  if (code === 'PUBLISHED') return 'success'
  if (code === 'DRAFT') return 'warning'
  return 'info'
}

export type TemplateTier = 'GOOD' | 'FAIR' | 'POOR'

/** 有方案 due 用方案 rate，否则用药 rate */
export function resolveTemplateTier(content?: Record<string, unknown> | null): TemplateTier | null {
  if (!content) return null
  const adherence = content.adherence as
    | { plan?: { dueCount?: number; rate?: number | null }; med?: { dueDayCount?: number; rate?: number | null } }
    | undefined
  if (!adherence) return null
  const planDue = Number(adherence.plan?.dueCount || 0)
  const medDue = Number(adherence.med?.dueDayCount || 0)
  let rate: number | null | undefined
  if (planDue > 0) rate = adherence.plan?.rate
  else if (medDue > 0) rate = adherence.med?.rate
  else return null
  if (rate == null || Number.isNaN(Number(rate))) return null
  const r = Number(rate)
  if (r >= 0.8) return 'GOOD'
  if (r >= 0.5) return 'FAIR'
  return 'POOR'
}

export function templateCommentForTier(tier: TemplateTier): string {
  switch (tier) {
    case 'GOOD':
      return '本周期执行情况良好，请继续按方案与用药计划坚持。'
    case 'FAIR':
      return '本周期部分任务未完成，建议按方案补齐打卡并监测关键指标。'
    case 'POOR':
      return '本周期执行偏弱，请尽快与健管师沟通，调整节奏或方案。'
  }
}

/** C 端展示寄语：人工优先，否则分档模板 */
export function resolveDisplayComment(
  staffComment?: string | null,
  content?: Record<string, unknown> | null,
): string {
  if (staffComment && staffComment.trim()) return staffComment.trim()
  const tier = resolveTemplateTier(content)
  if (!tier) return '本周期管理数据如下，请按方案继续执行；有疑问联系健管师。'
  return templateCommentForTier(tier)
}

export function formatReportDate(v?: string | null) {
  if (!v) return '-'
  return String(v).slice(0, 10)
}

export function formatReportDateTime(v?: string | null) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}
