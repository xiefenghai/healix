/**
 * 管理报告导出：按详情页的卡片版式渲染打印文档，交由浏览器输出 PDF。
 *
 * 走浏览器打印而非服务端渲染，是为了让中文字体与分页直接复用系统能力，
 * 无需在后端内嵌 CJK 字体。图表在打印稿里用进度条代替。
 */
import {
  formatHealthReportPeriodType,
  formatReportDate,
  resolveDisplayComment,
  resolveTemplateTier,
  templateTierLabel,
} from './management-report-labels'
import { lifestyleSections } from './report-lifestyle'
import {
  compareRate,
  dietExtras,
  exerciseExtras,
  groupHighlights,
  medDetail,
  metricViews,
  observationViews,
  pctText,
  planDetail,
  rateBand,
  reportGoals,
  reportHighlights,
  reportInsights,
  reportKpis,
  reportMilestones,
} from './report-view'

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

function formatReportDateTime(v?: string | null) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
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

/** rate 为 0–1。 */
function widthOf(rate: unknown): string {
  if (rate == null || Number.isNaN(Number(rate))) return '0%'
  return `${Math.min(100, Math.max(0, Math.round(Number(rate) * 100)))}%`
}

function widthOfPercent(pct: unknown): string {
  if (pct == null || Number.isNaN(Number(pct))) return '0%'
  return `${Math.min(100, Math.max(0, Math.round(Number(pct))))}%`
}

function bandOf(rate: unknown, band?: string): string {
  const b = band && band !== 'empty' ? band : rateBand(rate)
  return b === 'good' || b === 'fair' || b === 'poor' ? b : 'empty'
}

function track(rate: unknown, band?: string): string {
  return `<div class="track"><i class="fill fill--${bandOf(rate, band)}" style="width:${widthOf(rate)}"></i></div>`
}

function trendIcon(trend?: string | null): string {
  if (trend === 'BETTER') return '↑'
  if (trend === 'WORSE') return '↓'
  return '→'
}

function delta(text?: string | null, trend?: string | null): string {
  if (!text) return ''
  const t = (trend || 'FLAT').toLowerCase()
  return `<span class="delta trend--${t}">${trendIcon(trend)} ${esc(text)}</span>`
}

function card(title: string, inner: string): string {
  if (!inner.trim()) return ''
  return `<section class="card"><h2><span class="mark">${esc(title)}</span></h2>${inner}</section>`
}

function insightLine(text?: string): string {
  if (!text) return ''
  return `<p class="insight">${esc(text)}</p>`
}

function buildKpis(content: Record<string, unknown>): string {
  const kpis = reportKpis(content)
  if (!kpis.length) return ''
  const cells = kpis
    .map((k) => {
      const wide = kpis.length % 2 === 1 && k === kpis[kpis.length - 1] ? ' kpi--wide' : ''
      const bad = k.trend === 'WORSE' ? ' bad' : ''
      const unit = k.display !== '—' && k.unit ? `<small>${esc(k.unit)}</small>` : ''
      return `<div class="kpi${wide}">
        <div class="kpi-label">${esc(k.label)}</div>
        <div class="kpi-value${bad}">${esc(k.display)}${unit}</div>
        ${k.sub ? `<div class="kpi-sub">${esc(k.sub)}</div>` : ''}
        ${delta(k.deltaText, k.trend)}
      </div>`
    })
    .join('')
  return `<div class="kpi-grid">${cells}</div>`
}

function buildNote(report: PrintableReport, content: Record<string, unknown>): string {
  const narrative = asRecord(content.narrative)
  const comment = resolveDisplayComment(report.staffComment, content)
  const extras = [
    narrative.nextFocus
      ? `<div class="note-item"><span class="tag">下期关注</span><p>${esc(narrative.nextFocus)}</p></div>`
      : '',
    narrative.quarterAdvice
      ? `<div class="note-item"><span class="tag tag--blue">阶段建议</span><p>${esc(narrative.quarterAdvice)}</p></div>`
      : '',
  ].join('')
  return `<section class="card note">
    <div class="note-head"><span class="avatar">健</span><div class="note-title">健管师寄语</div></div>
    <p class="quote">${esc(comment)}</p>
    ${extras ? `<div class="note-extra">${extras}</div>` : ''}
  </section>`
}

function buildHighlights(content: Record<string, unknown>): string {
  const groups = groupHighlights(reportHighlights(content))
  if (!groups.length) return ''
  const rows = groups
    .flatMap((g) => g.items.map((h) => ({ type: g.type, label: g.label, text: h.text })))
    .map(
      (h) =>
        `<div class="hl hl--${h.type.toLowerCase()}"><i class="hl-tag">${esc(h.label)}</i><span>${esc(h.text)}</span></div>`,
    )
    .join('')
  return card('本期要点', `<div class="hl-list">${rows}</div>`)
}

function buildGoals(content: Record<string, unknown>): string {
  const goals = reportGoals(content)
  const milestones = reportMilestones(content)
  if (!goals.length && !milestones.length) return ''
  const goalHtml = goals
    .map(
      (g) => `<div class="goal">
        <div class="goal-top"><span>${esc(g.label)}</span><span class="${g.met ? 'met' : ''}">${esc(g.actualText)}</span></div>
        ${track(g.progress, g.met ? 'good' : 'fair')}
        <div class="goal-target">目标 ${esc(g.targetText)}${g.met ? '<span class="met">已达成</span>' : ''}</div>
      </div>`,
    )
    .join('')
  const ms = milestones.length
    ? `<div class="ms-title">本期里程碑</div>${milestones.map((m) => `<div class="ms">${esc(m)}</div>`).join('')}`
    : ''
  return card('目标进度', goalHtml + ms)
}

function buildAdherence(content: Record<string, unknown>): string {
  const adherence = asRecord(content.adherence)
  const planRaw = asRecord(adherence.plan)
  const medRaw = asRecord(adherence.med)
  const plan = planDetail(content)
  const med = medDetail(content)
  const insights = reportInsights(content)
  const hasPlan = Number(planRaw.dueCount || 0) > 0 || planRaw.rate != null
  const hasMed = Number(medRaw.dueDayCount || 0) > 0 || medRaw.rate != null
  if (!hasPlan && !hasMed && !plan.tasks.length && !med.drugs.length) return ''

  const planCompare = compareRate(planRaw.rate, plan.prevRate)
  const medCompare = compareRate(medRaw.rate, med.prevRate)
  const rates = `<div class="rate-grid">
    <div class="rate-box rate--${bandOf(planRaw.rate)}">
      <div class="rate-label">方案完成率</div>
      <div class="rate-value">${esc(pctText(planRaw.rate))}</div>
      ${track(planRaw.rate)}
      <div class="rate-sub">已完成 ${esc(planRaw.doneCount || 0)} / 应打 ${esc(planRaw.dueCount || 0)}${Number(planRaw.skippedCount) ? ` · 跳过 ${esc(planRaw.skippedCount)}` : ''}</div>
      ${planCompare ? `<div class="rate-delta">${delta(planCompare.text, planCompare.trend)}</div>` : ''}
    </div>
    <div class="rate-box rate--${bandOf(medRaw.rate)}">
      <div class="rate-label">用药达标率</div>
      <div class="rate-value">${esc(pctText(medRaw.rate))}</div>
      ${track(medRaw.rate)}
      <div class="rate-sub">达标 ${esc(medRaw.okDayCount || 0)} / 应服 ${esc(medRaw.dueDayCount || 0)} 日</div>
      ${medCompare ? `<div class="rate-delta">${delta(medCompare.text, medCompare.trend)}</div>` : ''}
    </div>
  </div>`

  const streak =
    plan.longestStreak >= 2
      ? `<div class="streak">最长连续全勤 <strong>${plan.longestStreak}</strong> 天 · 全勤 ${plan.fullDays} 天</div>`
      : ''

  const tasks = plan.tasks.length
    ? `<div class="sub-title">各项任务完成情况</div>${plan.tasks
        .map(
          (t) => `<div class="task">
            <div class="task-top">
              <span>${t.categoryLabel ? `<em class="cat">${esc(t.categoryLabel)}</em>` : ''}${esc(t.title)}</span>
              <span class="task-rate rate--${t.band}">${t.done}/${t.due}</span>
            </div>
            ${track(t.rate, t.band)}
          </div>`,
        )
        .join('')}`
    : ''

  const days = asArray(medRaw.daily)
  const medDays = days.length
    ? `<div class="sub-title">每日服药</div><div class="med-days">${days
        .map((d) => {
          const ok = d.ok === true
          const text = ok ? '✓' : `${d.takenDoses ?? 0}/${d.dueDoses ?? 0}`
          const date = String(d.date || '').slice(5).replace('-', '/')
          return `<div class="med-day"><div class="med-dot ${ok ? 'ok' : 'miss'}">${esc(text)}</div><small>${esc(date)}</small></div>`
        })
        .join('')}</div>`
    : ''

  const drugs = med.drugs.length
    ? `<div class="sub-title">用药明细${med.missed ? `<span class="hint">${esc(med.missed)}</span>` : ''}</div>${med.drugs
        .map(
          (d) =>
            `<div class="drug"><span>${esc(d.name)}</span><span class="task-rate rate--${d.band}">${d.takenDoses}/${d.dueDoses} 次 · ${esc(d.rateText)}</span></div>`,
        )
        .join('')}`
    : ''

  const weekly = plan.weekly.length ? buildWeekRates(plan.weekly) : ''

  return card(
    '执行情况',
    insightLine(insights.plan || insights.med) + rates + streak + tasks + medDays + drugs + weekly,
  )
}

function buildWeekRates(weekly: { label: string; display: string; value: number | null }[]): string {
  const bars = weekly
    .map((w) => {
      const h = widthOf(w.value)
      return `<div class="week">
        <div class="week-col"><i class="fill fill--${bandOf(w.value)}" style="height:${h}"></i></div>
        <div class="week-val">${esc(w.display)}</div>
        <div class="week-lab">${esc(w.label)}</div>
      </div>`
    })
    .join('')
  return `<div class="sub-title">每周方案完成率</div><div class="weeks">${bars}</div>`
}

function buildMetrics(content: Record<string, unknown>): string {
  const metrics = metricViews(content)
  if (!metrics.length) return ''
  const blocks = metrics
    .map((m) => {
      const stats = m.stats
        .map(
          (s) =>
            `<div class="stat"><div class="stat-val">${esc(s.value)}</div><div class="stat-lab">${esc(s.label)}${m.unit ? ` · ${esc(m.unit)}` : ''}</div></div>`,
        )
        .join('')
      const slots = m.slots
        .map(
          (s) =>
            `<div class="slot${s.warn ? ' slot--warn' : ''}"><div class="slot-lab">${esc(s.label)}</div><div class="slot-val">${esc(s.value)}</div><div class="slot-sub">${esc(s.sub)}</div></div>`,
        )
        .join('')
      const abn = m.abnormalItems
        .map((a) => `<li><span class="abn-time">${esc(a.time)}</span>${esc(a.text)}</li>`)
        .join('')
      return `<div class="metric">
        <div class="metric-head">
          <strong>${esc(m.label)}</strong>
          <span class="muted">测量 ${m.count} 次</span>
          ${m.inRangeText ? `<span class="pill pill--${m.inRangeBand}">达标 ${esc(m.inRangeText)}</span>` : ''}
          ${m.abnormalCount > 0 ? `<span class="pill pill--poor">异常 ${m.abnormalCount}</span>` : ''}
        </div>
        ${m.ref ? `<div class="metric-ref">参考范围 ${esc(m.ref)}</div>` : ''}
        ${stats ? `<div class="stat-row">${stats}</div>` : ''}
        ${m.prevText ? `<div class="metric-prev">${delta(m.prevText, m.trend)}</div>` : ''}
        ${slots ? `<div class="slot-row">${slots}</div>` : ''}
        ${abn ? `<ul class="abn">${abn}</ul>` : ''}
      </div>`
    })
    .join('')
  return card('指标变化', insightLine(reportInsights(content).metrics) + blocks)
}

function chips(items: { label: string; count?: number; warn?: boolean }[], withCount: boolean): string {
  if (!items.length) return ''
  return `<div class="chips">${items
    .map((f) => {
      const n = withCount && f.count != null ? ` ${f.count}` : ''
      return `<span class="chip${f.warn ? ' chip--warn' : ''}">${esc(f.label)}${esc(n)}</span>`
    })
    .join('')}</div>`
}

function buildLifestyle(content: Record<string, unknown>): string {
  const sections = lifestyleSections(content)
  if (!sections.length) return ''
  const insights = reportInsights(content)
  const diet = dietExtras(content)
  const exercise = exerciseExtras(content)
  return sections
    .map((sec) => {
      if (sec.empty) return card(sec.title, insightLine(insights[sec.key]) + `<p class="muted">${esc(sec.empty)}</p>`)
      const boxes = `<div class="ls-grid">${sec.stats
        .map(
          (s) => `<div class="ls">
            <div class="ls-label">${esc(s.label)}</div>
            <div class="ls-value${s.warn ? ' warn' : ''}">${esc(s.value)}</div>
            ${s.sub ? `<div class="ls-sub">${esc(s.sub)}</div>` : ''}
            ${s.delta ? `<div class="ls-delta">${delta(s.delta, s.trend)}</div>` : ''}
          </div>`,
        )
        .join('')}</div>`
      let extra = ''
      if (sec.key === 'diet') {
        const macros = diet.macros.length
          ? `<div class="sub-title">三大营养素供能比</div>${diet.macros
              .map(
                (r) => `<div class="macro">
                  <span>${esc(r.label)}</span>
                  <div class="track"><i class="fill fill--good" style="width:${widthOfPercent(r.actual)}"></i></div>
                  <span class="macro-val">${r.actual ?? '—'}%${r.target != null ? ` / ${r.target}%` : ''}</span>
                </div>`,
              )
              .join('')}`
          : ''
        const flags = diet.flags.length ? `<div class="sub-title">需注意的原因</div>${chips(diet.flags, true)}` : ''
        const foods = diet.foods.length ? `<div class="sub-title">常吃的食物</div>${chips(diet.foods.map((f) => ({ ...f, label: `${f.label} ×${f.count}` })), false)}` : ''
        const notes = diet.notes.length ? `<p class="muted">${esc(diet.notes.join(' · '))}</p>` : ''
        extra = macros + flags + foods + notes
      }
      if (sec.key === 'exercise') {
        const types = exercise.types.length ? `<div class="sub-title">主要运动</div>${chips(exercise.types, false)}` : ''
        const max = Math.max(exercise.target || 0, ...exercise.weekly.map((w) => w.value || 0), 1)
        const bars = exercise.weekly.length
          ? `<div class="sub-title">每周活动分钟${exercise.metText ? `<span class="hint">${esc(exercise.metText)}</span>` : ''}</div><div class="weeks">${exercise.weekly
              .map((w) => {
                const h = `${Math.max(8, Math.round(((w.value || 0) / max) * 100))}%`
                const cls = w.met === false ? 'fair' : 'good'
                return `<div class="week"><div class="week-col"><i class="fill fill--${cls}" style="height:${h}"></i></div><div class="week-val">${w.value ?? 0}</div><div class="week-lab">${esc(w.label)}</div></div>`
              })
              .join('')}</div>`
          : ''
        extra = types + bars
      }
      return card(sec.title, insightLine(insights[sec.key]) + boxes + extra)
    })
    .join('')
}

function buildFollowups(content: Record<string, unknown>): string {
  const followups = asArray(content.followups)
  if (!followups.length) return ''
  const rows = followups
    .map((f) => {
      const time = f.completedAt ? formatReportDateTime(String(f.completedAt)) : ''
      return `<div class="fu">
        <div class="fu-top"><span class="fu-type">${esc(f.title || f.recordType || '随访')}</span>${time ? `<span class="muted">${esc(time)}</span>` : ''}</div>
        <p>${esc(f.summary || '—')}</p>
      </div>`
    })
    .join('')
  return card('随访与干预', rows)
}

function buildObservations(content: Record<string, unknown>): string {
  const obs = observationViews(content)
  if (!obs) return ''
  const labs = obs.labs
    .map((lab) => {
      const items = lab.items
        .map(
          (it) => `<div class="lab${it.flag ? ' lab--warn' : ''}">
            <div class="lab-main"><span>${esc(it.name)}</span><span class="lab-val">${esc(it.value)}${it.flagText ? `<em>${esc(it.flagText)}</em>` : ''}</span></div>
            <div class="lab-sub">${it.ref ? `<span>参考 ${esc(it.ref)}</span>` : ''}${it.prevText ? `<span>${esc(it.prevText)}</span>` : ''}</div>
          </div>`,
        )
        .join('')
      return `<div class="obs">
        <div class="obs-head"><strong>${esc(lab.title)}</strong><span class="muted">${esc(lab.time)}</span>${lab.abnormalCount ? `<span class="pill pill--poor">异常 ${lab.abnormalCount} 项</span>` : ''}</div>
        ${items || '<p class="muted">暂无结构化结果</p>'}
      </div>`
    })
    .join('')
  const exams = obs.exams
    .map(
      (exam) => `<div class="obs">
        <div class="obs-head"><strong>${esc(exam.title)}</strong><span class="muted">${esc(exam.time)}</span></div>
        ${exam.conclusion ? `<p>${esc(exam.conclusion)}</p>` : ''}
      </div>`,
    )
    .join('')
  return card('检验 / 检查', insightLine(reportInsights(content).observations) + labs + exams)
}

const PRINT_CSS = `
  /* margin 为 0 时，Chrome 不再把网址、日期印在页眉页脚。留白改由 body 内边距承担，并在分页时重复。 */
  @page { size: A4; margin: 0; }
  * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  body {
    margin: 0;
    padding: 12mm 12mm 14mm;
    -webkit-box-decoration-break: clone;
    box-decoration-break: clone;
    font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    font-size: 12px;
    line-height: 1.5;
    color: #13222e;
    background: #f7fbf9;
  }
  .mark { background: linear-gradient(transparent 62%, #ffd84d 62%); padding: 0 2px; }
  .hero { margin-bottom: 12px; }
  .pills { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
  .pill {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 700;
    background: #e8eef2;
    color: #3d4b58;
  }
  .pill--ink { background: #13222e; color: #fff; }
  .tier--good, .pill--good, .fill--good { background: #e3f6ef; color: #0b7a66; }
  .tier--fair, .pill--fair { background: #fff3da; color: #8a5c00; }
  .tier--poor, .pill--poor { background: #fde7ea; color: #a8243a; }
  .fill--good { background: #12a389; color: inherit; }
  .fill--fair { background: #e5a100; }
  .fill--poor { background: #e0435a; }
  .fill--empty { background: #c5d0d8; }
  h1 { margin: 0; font-size: 22px; font-weight: 900; line-height: 1.35; }
  .meta { margin: 6px 0 0; font-size: 12px; font-weight: 600; color: #5b6a77; }
  .kpi-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
  .kpi, .card, .rate-box, .ls {
    background: #fff;
    border: 1px solid #e6eef2;
    border-radius: 14px;
  }
  .kpi { padding: 10px 12px; break-inside: avoid; }
  .kpi--wide { grid-column: span 2; }
  .kpi-label { font-size: 11px; font-weight: 700; color: #5b6a77; }
  .kpi-value { margin-top: 2px; font-size: 22px; font-weight: 900; line-height: 1.15; }
  .kpi-value small { margin-left: 2px; font-size: 12px; }
  .kpi-value.bad { color: #a8243a; }
  .kpi-sub, .muted, .hint, .rate-sub, .goal-target, .ls-sub, .metric-ref, .lab-sub { color: #7b8b98; font-size: 11px; }
  .delta { display: inline-block; margin-top: 4px; padding: 1px 7px; border-radius: 6px; font-size: 11px; font-weight: 800; background: #eef2f4; }
  .trend--better { background: #e3f6ef; color: #0b7a66; }
  .trend--worse { background: #fde7ea; color: #a8243a; }
  .card { margin-top: 10px; padding: 14px 14px 12px; }
  h2 { margin: 0 0 10px; font-size: 16px; font-weight: 900; }
  .note { background: linear-gradient(180deg, #fff 0%, #f3fbf8 100%); border-color: #d7f3ea; }
  .note-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
  .avatar {
    width: 28px; height: 28px; border-radius: 50%;
    display: grid; place-items: center;
    background: #13222e; color: #fff; font-size: 13px; font-weight: 800;
  }
  .note-title { font-size: 15px; font-weight: 900; }
  .quote { margin: 0; font-size: 13px; line-height: 1.65; }
  .note-extra { margin-top: 10px; display: grid; gap: 8px; }
  .note-item p { margin: 4px 0 0; }
  .tag { display: inline-block; padding: 1px 8px; border-radius: 6px; background: #e3f6ef; color: #0b7a66; font-size: 11px; font-weight: 800; }
  .tag--blue { background: #e9f0fd; color: #2c4f8f; }
  .insight { margin: 0 0 10px; padding: 8px 10px; border-radius: 10px; background: #f3fbf8; color: #0b7a66; }
  .hl { display: flex; gap: 8px; align-items: flex-start; margin: 0 0 8px; font-weight: 600; break-inside: avoid; }
  .hl-tag { flex: none; padding: 1px 8px; border-radius: 6px; font-style: normal; font-size: 11px; font-weight: 800; background: #e3f6ef; color: #0b7a66; }
  .hl--watch .hl-tag { background: #fff3da; color: #8a5c00; }
  .hl--focus .hl-tag { background: #e9f0fd; color: #2c4f8f; }
  .goal, .task, .drug, .metric, .obs, .fu { break-inside: avoid; margin-bottom: 8px; }
  .goal-top, .task-top, .drug, .metric-head, .obs-head, .fu-top, .lab-main {
    display: flex; justify-content: space-between; align-items: baseline; gap: 8px;
  }
  .goal-top { font-weight: 800; }
  .met { color: #0b7a66; }
  .track { height: 6px; margin: 4px 0; border-radius: 99px; background: #eef2f4; overflow: hidden; }
  .fill { display: block; height: 100%; border-radius: 99px; }
  .rate-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .rate-box { padding: 10px 12px; break-inside: avoid; }
  .rate-label { font-size: 11px; font-weight: 700; color: #5b6a77; }
  .rate-value { font-size: 22px; font-weight: 900; line-height: 1.15; }
  .rate--poor .rate-value, .task-rate.rate--poor { color: #a8243a; }
  .rate--fair .rate-value, .task-rate.rate--fair { color: #8a5c00; }
  .rate--good .rate-value, .task-rate.rate--good { color: #0b7a66; }
  .streak { margin: 8px 0; padding: 6px 10px; border-radius: 10px; background: #fff8e8; font-weight: 700; }
  .sub-title { margin: 12px 0 6px; font-size: 13px; font-weight: 800; }
  .sub-title .hint { margin-left: 8px; font-weight: 600; }
  .cat { margin-right: 6px; padding: 0 6px; border-radius: 6px; background: #eef6f4; color: #0b7a66; font-style: normal; font-size: 11px; font-weight: 800; }
  .task-rate { font-weight: 800; }
  .med-days { display: flex; flex-wrap: wrap; gap: 6px; }
  .med-day { width: 42px; text-align: center; }
  .med-day small { color: #7b8b98; font-size: 10px; }
  .med-dot { height: 28px; border-radius: 8px; display: grid; place-items: center; font-size: 11px; font-weight: 800; }
  .med-dot.ok { background: #e3f6ef; color: #0b7a66; }
  .med-dot.miss { background: #fde7ea; color: #a8243a; }
  .weeks { display: flex; gap: 8px; align-items: flex-end; }
  .week { flex: 1; text-align: center; }
  .week-col { height: 72px; display: flex; align-items: flex-end; justify-content: center; }
  .week-col .fill { width: 18px; border-radius: 6px 6px 2px 2px; }
  .week-val, .week-lab { font-size: 10px; color: #5b6a77; }
  .stat-row, .slot-row, .ls-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .stat, .slot, .ls { padding: 8px; border-radius: 10px; background: #f7fafb; break-inside: avoid; }
  .stat-val, .slot-val, .ls-value { font-size: 16px; font-weight: 900; }
  .ls-value.warn, .slot--warn .slot-val { color: #a8243a; }
  .metric-head, .obs-head { margin-bottom: 4px; }
  .lab { padding: 6px 0; border-top: 1px solid #eef2f4; }
  .lab-val em { margin-left: 6px; font-style: normal; color: #a8243a; font-weight: 800; }
  .lab--warn .lab-val { color: #a8243a; }
  .lab-sub { display: flex; gap: 10px; }
  .abn { margin: 6px 0 0; padding: 0; list-style: none; }
  .abn li { padding: 3px 0; }
  .abn-time { margin-right: 8px; color: #7b8b98; }
  .fu { padding-left: 12px; border-left: 2px solid #d7f3ea; }
  .fu-type { font-weight: 800; }
  .fu p { margin: 2px 0 0; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .chip { padding: 2px 8px; border-radius: 999px; background: #eef6f4; font-size: 11px; font-weight: 700; }
  .chip--warn { background: #fff3da; color: #8a5c00; }
  .macro { display: grid; grid-template-columns: 52px 1fr 88px; gap: 8px; align-items: center; margin-bottom: 4px; }
  .macro-val { text-align: right; font-size: 11px; font-weight: 700; }
  footer { margin-top: 16px; font-size: 11px; color: #7b8b98; }
`

/** 打印对话框用文档标题当文件名；斜杠会被当成路径，日期里的 / 先换成 -。 */
function printFileName(report: PrintableReport): string {
  const raw = (report.title || '').trim() || '管理报告'
  const safe = raw
    .replace(/(\d{1,2})\/(\d{1,2})/g, '$1-$2')
    .replace(/[–—]/g, '至')
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
  const name = (report.peopleName || '').trim()
  const titled = name && !safe.includes(name) ? `${name}-${safe}` : safe
  return titled || '管理报告'
}

export function buildReportPrintHtml(report: PrintableReport): string {
  const content = asRecord(report.content)
  const periodLabel = formatHealthReportPeriodType(report.periodType)
  const title = report.title || `${report.peopleName || ''}${periodLabel}`.trim() || '管理报告'
  const fileName = printFileName(report)
  const range = `${formatReportDate(report.periodStart)} ~ ${formatReportDate(report.periodEnd)}`
  const tier = resolveTemplateTier(content)
  const tierHtml = tier ? `<span class="pill tier--${tier.toLowerCase()}">${esc(templateTierLabel(tier))}</span>` : ''
  const meta = [
    report.peopleName,
    range,
    report.publishedAt ? `${formatReportDate(report.publishedAt)} 发布` : '',
    report.publishedByName ? `发布人 ${report.publishedByName}` : '',
  ]
    .filter(Boolean)
    .join(' · ')

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${esc(fileName)}</title>
  <style>${PRINT_CSS}</style>
</head>
<body>
  <header class="hero">
    <div class="pills"><span class="pill pill--ink">${esc(periodLabel)}</span>${tierHtml}</div>
    <h1><span class="mark">${esc(title)}</span></h1>
    <p class="meta">${esc(meta)}</p>
    ${buildKpis(content)}
  </header>
  ${buildNote(report, content)}
  ${buildHighlights(content)}
  ${buildGoals(content)}
  ${buildAdherence(content)}
  ${buildMetrics(content)}
  ${buildLifestyle(content)}
  ${buildFollowups(content)}
  ${buildObservations(content)}
  <footer>本报告仅用于健康管理参考，不作为诊断依据；如有不适请及时就医。</footer>
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

    const fileName = printFileName(report)
    const previousTitle = document.title
    const restoreTitle = () => {
      document.title = previousTitle
    }

    iframe.onload = () => {
      try {
        const win = iframe.contentWindow
        if (!win) {
          cleanup()
          resolve(false)
          return
        }
        document.title = fileName
        const finish = () => {
          restoreTitle()
          cleanup()
        }
        win.addEventListener('afterprint', finish, { once: true })
        window.addEventListener('afterprint', finish, { once: true })
        win.focus()
        win.print()
        resolve(true)
      } catch {
        restoreTitle()
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
