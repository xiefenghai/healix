/**
 * 管理报告导出：渲染独立打印文档，交由浏览器打印引擎输出 PDF。
 *
 * 走浏览器打印而非服务端渲染，是为了让中文字体、分页与图表样式直接复用系统能力，
 * 无需在后端内嵌 CJK 字体。
 */
import {
  formatHealthReportPeriodType,
  formatReportDate,
  formatReportDateTime,
  resolveDisplayComment,
} from './health-report-labels'

export interface PrintableReport {
  id: string
  title?: string
  peopleName?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  status?: string
  staffComment?: string | null
  publishedAt?: string
  publishedByName?: string
  content?: Record<string, unknown> | null
}

function esc(v: unknown): string {
  return String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function asArray(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? v.filter((x) => !!x && typeof x === 'object') : []
}

function pct(rate: unknown): string {
  if (rate == null || Number.isNaN(Number(rate))) return '-'
  return `${Math.round(Number(rate) * 100)}%`
}

function num(v: unknown): string {
  if (v == null || v === '') return '-'
  return String(v)
}

function kvTable(rows: [string, string][]): string {
  const body = rows.map(([k, v]) => `<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join('')
  return `<table class="kv">${body}</table>`
}

function section(title: string, inner: string): string {
  if (!inner.trim()) return ''
  return `<section><h2>${esc(title)}</h2>${inner}</section>`
}

function buildAdherence(content: Record<string, unknown>): string {
  const adherence = asRecord(content.adherence)
  const plan = asRecord(adherence.plan)
  const med = asRecord(adherence.med)
  const rows: [string, string][] = []
  if (Number(plan.dueCount || 0) > 0 || plan.rate != null) {
    rows.push([
      '方案打卡',
      `应打 ${num(plan.dueCount)} 次 · 已打 ${num(plan.doneCount)} 次 · 完成率 ${pct(plan.rate)}`,
    ])
  }
  if (Number(med.dueDayCount || 0) > 0 || med.rate != null) {
    rows.push([
      '用药打卡',
      `应打 ${num(med.dueDayCount)} 天 · 达标 ${num(med.okDayCount)} 天 · 达标率 ${pct(med.rate)}`,
    ])
  }
  return rows.length ? kvTable(rows) : '<p class="empty">本周期无打卡记录</p>'
}

function buildMetrics(content: Record<string, unknown>): string {
  const metrics = asArray(content.metrics)
  if (!metrics.length) return ''
  const rows = metrics
    .map((m) => {
      const latest = asRecord(m.latest)
      const latestText =
        latest.text != null
          ? String(latest.text)
          : latest.value != null
            ? String(latest.value)
            : '-'
      const at = latest.t ? formatReportDateTime(String(latest.t)) : '-'
      const abnormal = Number(m.abnormalCount || 0)
      return `<tr>
        <td>${esc(m.label ?? m.family)}</td>
        <td>${esc(latestText)}</td>
        <td>${esc(at)}</td>
        <td class="${abnormal > 0 ? 'warn' : ''}">${abnormal > 0 ? `${abnormal} 次` : '无'}</td>
      </tr>`
    })
    .join('')
  return `<table class="grid">
    <thead><tr><th>指标</th><th>最近值</th><th>记录时间</th><th>异常次数</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>`
}

function buildFollowups(content: Record<string, unknown>): string {
  const followups = asArray(content.followups)
  if (!followups.length) return ''
  const rows = followups
    .map(
      (f) => `<tr>
        <td>${esc(f.title ?? '-')}</td>
        <td>${esc(f.summary ?? '-')}</td>
        <td>${esc(f.completedAt ? formatReportDateTime(String(f.completedAt)) : '-')}</td>
      </tr>`,
    )
    .join('')
  return `<table class="grid">
    <thead><tr><th>随访</th><th>摘要</th><th>办结时间</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>`
}

function buildObservations(content: Record<string, unknown>): string {
  const obs = asRecord(content.observations)
  const labs = asArray(obs.labs)
  const exams = asArray(obs.exams)
  if (!labs.length && !exams.length) return ''
  const rows = [
    ...labs.map(
      (l) => `<tr>
        <td>检验</td>
        <td>${esc(l.specimenType ?? '-')}</td>
        <td>${esc(l.reportedAt || l.sampledAt ? formatReportDate(String(l.reportedAt || l.sampledAt)) : '-')}</td>
        <td>${esc(l.note ?? '-')}</td>
      </tr>`,
    ),
    ...exams.map(
      (e) => `<tr>
        <td>检查</td>
        <td>${esc(e.examType ?? '-')}</td>
        <td>${esc(e.examinedAt ? formatReportDate(String(e.examinedAt)) : '-')}</td>
        <td>${esc(e.conclusion ?? e.note ?? '-')}</td>
      </tr>`,
    ),
  ].join('')
  return `<table class="grid">
    <thead><tr><th>类型</th><th>项目</th><th>日期</th><th>说明</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>`
}

function buildNarrative(report: PrintableReport, content: Record<string, unknown>): string {
  const narrative = asRecord(content.narrative)
  const comment = resolveDisplayComment(report.staffComment, content)
  const rows: [string, string][] = [['健管师点评', comment]]
  if (narrative.nextFocus) rows.push(['下期关注', String(narrative.nextFocus)])
  if (narrative.quarterAdvice) rows.push(['阶段建议', String(narrative.quarterAdvice)])
  return kvTable(rows)
}

const PRINT_CSS = `
  @page { size: A4; margin: 16mm 14mm; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Source Han Sans SC", sans-serif;
    font-size: 12px;
    line-height: 1.6;
    color: #1f2329;
  }
  header { border-bottom: 2px solid #1f2329; padding-bottom: 10px; margin-bottom: 16px; }
  h1 { font-size: 19px; margin: 0 0 6px; }
  .meta { font-size: 11px; color: #646a73; }
  section { margin-bottom: 16px; page-break-inside: avoid; }
  h2 {
    font-size: 13px;
    margin: 0 0 8px;
    padding-left: 7px;
    border-left: 3px solid #1f6feb;
  }
  table { width: 100%; border-collapse: collapse; }
  table.kv th {
    width: 96px;
    text-align: left;
    vertical-align: top;
    font-weight: 600;
    color: #646a73;
    padding: 5px 8px 5px 0;
  }
  table.kv td { padding: 5px 0; vertical-align: top; }
  table.grid th, table.grid td {
    border: 1px solid #dee0e3;
    padding: 5px 7px;
    text-align: left;
  }
  table.grid thead th { background: #f5f6f7; font-weight: 600; }
  .warn { color: #d83931; font-weight: 600; }
  .empty { color: #8f959e; margin: 0; }
  footer { margin-top: 20px; padding-top: 8px; border-top: 1px solid #dee0e3; font-size: 10px; color: #8f959e; }
  @media print { .no-print { display: none; } }
`

export function buildReportPrintHtml(report: PrintableReport): string {
  const content = asRecord(report.content)
  const periodLabel = formatHealthReportPeriodType(report.periodType)
  const title = report.title || `${report.peopleName || ''}${periodLabel}`.trim() || '管理报告'
  const range = `${formatReportDate(report.periodStart)} ~ ${formatReportDate(report.periodEnd)}`

  const head: [string, string][] = [
    ['患者', report.peopleName || '-'],
    ['报告周期', `${periodLabel}（${range}）`],
  ]
  if (report.publishedAt) head.push(['发布时间', formatReportDateTime(report.publishedAt)])
  if (report.publishedByName) head.push(['发布人', report.publishedByName])

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${esc(title)}</title>
  <style>${PRINT_CSS}</style>
</head>
<body>
  <header>
    <h1>${esc(title)}</h1>
    <div class="meta">${esc(periodLabel)} · ${esc(range)}</div>
  </header>
  ${section('基本信息', kvTable(head))}
  ${section('依从性', buildAdherence(content))}
  ${section('指标概览', buildMetrics(content))}
  ${section('本周期随访', buildFollowups(content))}
  ${section('检验与检查', buildObservations(content))}
  ${section('管理小结', buildNarrative(report, content))}
  <footer>本报告由 Healix 健康管理平台生成，仅用于健康管理参考，不作为诊断依据。</footer>
</body>
</html>`
}

/**
 * 在隐藏 iframe 中打印，避免弹窗拦截与当前页面样式污染。
 *
 * @returns 打印对话框是否成功唤起
 */
export function printReport(report: PrintableReport): Promise<boolean> {
  return new Promise((resolve) => {
    const iframe = document.createElement('iframe')
    iframe.setAttribute('aria-hidden', 'true')
    iframe.style.position = 'fixed'
    iframe.style.right = '0'
    iframe.style.bottom = '0'
    iframe.style.width = '0'
    iframe.style.height = '0'
    iframe.style.border = '0'
    document.body.appendChild(iframe)

    const cleanup = () => {
      window.setTimeout(() => iframe.remove(), 1000)
    }

    iframe.onload = () => {
      try {
        const win = iframe.contentWindow
        if (!win) {
          cleanup()
          resolve(false)
          return
        }
        win.focus()
        win.print()
        cleanup()
        resolve(true)
      } catch {
        cleanup()
        resolve(false)
      }
    }

    const doc = iframe.contentDocument
    if (!doc) {
      cleanup()
      resolve(false)
      return
    }
    doc.open()
    doc.write(buildReportPrintHtml(report))
    doc.close()
  })
}
