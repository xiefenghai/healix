/** 手机端报告摘要（无图表） */

export function formatPeriodType(code?: string) {
  switch (code) {
    case 'WEEK':
      return '周报'
    case 'MONTH':
      return '月报'
    case 'QUARTER':
      return '三个月报告'
    default:
      return code || '报告'
  }
}

export function formatReportStatus(code?: string) {
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

export function pct(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '—'
  return `${Math.round(Number(rate) * 100)}%`
}

export function metricLabel(family?: string) {
  switch (family) {
    case 'BP':
      return '血压'
    case 'GLUCOSE':
      return '血糖'
    case 'HR':
      return '心率'
    case 'BMI':
      return 'BMI'
    default:
      return family || '指标'
  }
}

export function sliceDate(v?: string | null) {
  if (!v) return '—'
  return String(v).slice(0, 10)
}
