/** 管理报告文案（C 端） */

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

export type TemplateTier = 'GOOD' | 'FAIR' | 'POOR'

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
      return '您好，本周期执行情况不错，请您继续按方案与用药计划坚持；有不适随时联系我。'
    case 'FAIR':
      return '您好，本周期有部分任务未完成，请您按方案尽量补齐打卡，并留意关键指标；有困难可以告诉我。'
    case 'POOR':
      return '您好，本周期节奏有些吃力也正常，请您尽快和我沟通，我们一起调整节奏或方案。'
  }
}

export function resolveDisplayComment(
  staffComment?: string | null,
  content?: Record<string, unknown> | null,
): string {
  if (staffComment && staffComment.trim()) return staffComment.trim()
  const tier = resolveTemplateTier(content)
  if (!tier) return '您好，本周期管理数据如下，请您按方案继续执行；有疑问随时联系我。'
  return templateCommentForTier(tier)
}

export function formatReportDate(v?: string | null) {
  if (!v) return '-'
  return String(v).slice(0, 10)
}
