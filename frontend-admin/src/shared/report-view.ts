/**
 * 管理报告展示模型：把 content_json（schemaVersion 1/2）整理成各端可直接渲染的结构。
 *
 * 三端各拷一份（与 report-lifestyle.ts 相同做法），改动时请同步：
 * frontend-user / frontend-admin / frontend-staff 的 src/shared/report-view.ts
 */

type Rec = Record<string, unknown>
type Num = number | null

export type Trend = 'BETTER' | 'WORSE' | 'FLAT' | null
export type Band = 'good' | 'fair' | 'poor' | 'empty'
export type HighlightType = 'GOOD' | 'WATCH' | 'FOCUS'

export interface ReportKpi {
  key: string
  label: string
  display: string
  unit: string
  sub?: string
  deltaText?: string
  trend: Trend
}

export interface ReportHighlight {
  type: HighlightType
  text: string
}

export interface ReportGoal {
  key: string
  label: string
  targetText: string
  actualText: string
  progress: number
  met: boolean
}

export interface TaskRow {
  title: string
  categoryLabel: string
  done: number
  due: number
  rate: Num
  rateText: string
  band: Band
}

export interface DrugRow {
  name: string
  takenDoses: number
  dueDoses: number
  rateText: string
  band: Band
}

export interface WeekBar {
  label: string
  range: string
  value: Num
  display: string
  met?: boolean | null
}

export interface StatCell {
  label: string
  value: string
}

export interface SlotCell {
  label: string
  value: string
  sub: string
  warn: boolean
}

export interface MetricView {
  family: string
  label: string
  unit: string
  count: number
  abnormalCount: number
  inRangeText: string | null
  inRangeBand: Band
  ref: string | null
  stats: StatCell[]
  prevText: string | null
  trend: Trend
  slots: SlotCell[]
  abnormalItems: Array<{ time: string; text: string }>
  series: Rec[]
}

export interface LabItemView {
  name: string
  value: string
  ref: string
  flag: string | null
  flagText: string
  prevText: string
}

export interface LabView {
  id: string
  title: string
  time: string
  abnormalCount: number
  itemCount: number
  items: LabItemView[]
}

export interface ExamView {
  id: string
  title: string
  time: string
  conclusion: string
}

export interface MacroRow {
  label: string
  actual: Num
  target: Num
  grams: Num
}

export interface ChipItem {
  label: string
  count: number
  warn: boolean
}

export const HIGHLIGHT_LABELS: Record<HighlightType, string> = {
  GOOD: '做得好',
  WATCH: '需注意',
  FOCUS: '下期重点',
}

export const INSIGHT_LABELS: Record<string, string> = {
  plan: '方案执行',
  med: '用药',
  metrics: '指标',
  diet: '饮食',
  exercise: '运动',
  observations: '检验检查',
}

function rec(v: unknown): Rec {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : {}
}

function arr(v: unknown): Rec[] {
  return Array.isArray(v) ? (v.filter((x) => x && typeof x === 'object') as Rec[]) : []
}

function num(v: unknown): Num {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function int(v: unknown): number {
  return Math.round(num(v) ?? 0)
}

function str(v: unknown): string {
  return v == null ? '' : String(v)
}

function fmt(v: unknown, digits = 1): string {
  const n = num(v)
  if (n == null) return '—'
  return Number.isInteger(n) ? String(n) : n.toFixed(digits).replace(/\.0+$/, '')
}

function signed(n: number, digits = 1): string {
  return `${n > 0 ? '+' : n < 0 ? '-' : ''}${fmt(Math.abs(n), digits)}`
}

export function pctText(rate: unknown): string {
  const n = num(rate)
  return n == null ? '—' : `${Math.round(n * 100)}%`
}

export function rateBand(rate: unknown): Band {
  const r = num(rate)
  if (r == null) return 'empty'
  if (r >= 0.8) return 'good'
  if (r >= 0.5) return 'fair'
  return 'poor'
}

export function shortTime(v: unknown): string {
  return str(v).replace('T', ' ').slice(5, 16)
}

function shortDate(v: unknown): string {
  return str(v).slice(5, 10).replace('-', '/')
}

export function isV2(content?: Rec | null): boolean {
  return int(content?.schemaVersion) >= 2
}

// ---------------------------------------------------------------- 概览

export function reportKpis(content?: Rec | null): ReportKpi[] {
  return arr(rec(content?.overview).kpis).map((k) => ({
    key: str(k.key),
    label: str(k.label),
    display: str(k.display) || '—',
    unit: str(k.unit),
    sub: str(k.sub) || undefined,
    deltaText: str(k.deltaText) || undefined,
    trend: (str(k.trend) || null) as Trend,
  }))
}

/** 优先用健管师审阅过的 narrative.highlights，否则用规则要点。 */
export function reportHighlights(content?: Rec | null): ReportHighlight[] {
  const narrative = arr(rec(content?.narrative).highlights)
  const source = narrative.length ? narrative : arr(rec(content?.overview).highlights)
  return source
    .map((h) => ({ type: str(h.type) as HighlightType, text: str(h.text) }))
    .filter((h) => h.text && h.type in HIGHLIGHT_LABELS)
}

export function groupHighlights(list: ReportHighlight[]) {
  return (['GOOD', 'WATCH', 'FOCUS'] as HighlightType[])
    .map((type) => ({ type, label: HIGHLIGHT_LABELS[type], items: list.filter((h) => h.type === type) }))
    .filter((g) => g.items.length)
}

export function ruleHighlights(content?: Rec | null): ReportHighlight[] {
  return arr(rec(content?.overview).highlights)
    .map((h) => ({ type: str(h.type) as HighlightType, text: str(h.text) }))
    .filter((h) => h.text && h.type in HIGHLIGHT_LABELS)
}

export function reportGoals(content?: Rec | null): ReportGoal[] {
  return arr(content?.goals).map((g) => ({
    key: str(g.key),
    label: str(g.label),
    targetText: str(g.targetText),
    actualText: str(g.actualText),
    progress: Math.max(0, Math.min(1, num(g.progress) ?? 0)),
    met: g.met === true,
  }))
}

export function reportMilestones(content?: Rec | null): string[] {
  return arr(content?.milestones)
    .map((m) => str(m.text))
    .filter(Boolean)
}

export function reportInsights(content?: Rec | null): Record<string, string> {
  const raw = rec(rec(content?.narrative).insights)
  const out: Record<string, string> = {}
  for (const key of Object.keys(INSIGHT_LABELS)) {
    const v = str(raw[key]).trim()
    if (v) out[key] = v
  }
  return out
}

// ---------------------------------------------------------------- 执行

export function planDetail(content?: Rec | null) {
  const plan = rec(rec(content?.adherence).plan)
  const tasks: TaskRow[] = arr(plan.byTask).map((t) => ({
    title: str(t.title) || '未命名任务',
    categoryLabel: str(t.categoryLabel),
    done: int(t.done),
    due: int(t.due),
    rate: num(t.rate),
    rateText: pctText(t.rate),
    band: rateBand(t.rate),
  }))
  const weekly: WeekBar[] = arr(plan.weekly).map((w, i) => ({
    label: `第${i + 1}周`,
    range: `${shortDate(w.weekStart)}–${shortDate(w.weekEnd)}`,
    value: num(w.rate),
    display: pctText(w.rate),
  }))
  return {
    tasks,
    weekly,
    longestStreak: int(plan.longestStreak),
    fullDays: int(plan.fullDays),
    prevRate: num(rec(plan.prev).rate),
  }
}

export function medDetail(content?: Rec | null) {
  const med = rec(rec(content?.adherence).med)
  const drugs: DrugRow[] = arr(med.byDrug).map((d) => ({
    name: str(d.name) || '药物',
    takenDoses: int(d.takenDoses),
    dueDoses: int(d.dueDoses),
    rateText: pctText(d.rate),
    band: rateBand(d.rate),
  }))
  const missed = arr(med.missedBySlot)
    .filter((m) => int(m.count) > 0)
    .map((m) => `${str(m.label) === '每日' ? '' : str(m.label)}漏服 ${int(m.count)} 次`)
    .join(' · ')
  return { drugs, missed, prevRate: num(rec(med.prev).rate) }
}

/** 本期与上期完成率的比较文案。 */
export function compareRate(rate: unknown, prev: unknown): { text: string; trend: Trend } | null {
  const r = num(rate)
  const p = num(prev)
  if (r == null || p == null) return null
  const delta = Math.round((r - p) * 100)
  if (Math.abs(delta) < 3) return { text: '与上期持平', trend: 'FLAT' }
  return { text: `较上期 ${delta > 0 ? '+' : ''}${delta}%`, trend: delta > 0 ? 'BETTER' : 'WORSE' }
}

// ---------------------------------------------------------------- 指标

const METRIC_UNITS: Record<string, string> = {
  BP: 'mmHg',
  GLUCOSE: 'mmol/L',
  HR: 'bpm',
  BMI: '',
  WEIGHT: 'kg',
}

const METRIC_LABELS: Record<string, string> = {
  BP: '血压',
  GLUCOSE: '血糖',
  HR: '心率',
  BMI: 'BMI',
  WEIGHT: '体重',
}

export function metricLabel(family?: string) {
  return (family && METRIC_LABELS[family]) || family || '指标'
}

export function metricViews(content?: Rec | null): MetricView[] {
  return arr(content?.metrics).map((m) => {
    const family = str(m.family)
    const unit = METRIC_UNITS[family] ?? ''
    const stats = rec(m.stats)
    const prev = m.prev ? rec(m.prev) : null
    const series = arr(m.series)
    const count = int(m.count) || series.length
    const abnormalCount = int(m.abnormalCount)
    const cells: StatCell[] = []
    let prevText: string | null = null
    let trend: Trend = null

    if (family === 'BP') {
      if (stats.sysAvg != null) {
        cells.push({ label: '平均', value: `${fmt(stats.sysAvg)}/${fmt(stats.diaAvg)}` })
        cells.push({ label: '最高', value: `${fmt(stats.sysMax)}/${fmt(stats.diaMax)}` })
        cells.push({ label: '最低', value: `${fmt(stats.sysMin)}/${fmt(stats.diaMin)}` })
      }
      if (prev && prev.sysAvg != null && stats.sysAvg != null) {
        const d = int(stats.sysAvg) - int(prev.sysAvg)
        prevText = `上期平均 ${fmt(prev.sysAvg)}/${fmt(prev.diaAvg)}，收缩压${d === 0 ? '持平' : `${d > 0 ? '升高' : '下降'} ${Math.abs(d)}`}`
        trend = Math.abs(d) < 3 ? 'FLAT' : d < 0 ? 'BETTER' : 'WORSE'
      }
    } else if (family === 'WEIGHT') {
      if (stats.first != null) {
        cells.push({ label: '期初', value: fmt(stats.first) })
        cells.push({ label: '期末', value: fmt(stats.last) })
        cells.push({ label: '变化', value: signed(num(stats.delta) ?? 0) })
      }
    } else if (stats.avg != null) {
      cells.push({ label: '平均', value: fmt(stats.avg) })
      cells.push({ label: '最高', value: fmt(stats.max) })
      cells.push({ label: '最低', value: fmt(stats.min) })
      if (prev && prev.avg != null) {
        const d = (num(stats.avg) ?? 0) - (num(prev.avg) ?? 0)
        prevText = `上期平均 ${fmt(prev.avg)}，${Math.abs(d) < 0.05 ? '持平' : `${d > 0 ? '升高' : '下降'} ${fmt(Math.abs(d))}`}`
      }
    }
    if (!prevText && prev && family !== 'WEIGHT' && prev.inRangeRate != null) {
      prevText = `上期达标 ${pctText(prev.inRangeRate)}`
    }

    const slots: SlotCell[] = []
    for (const s of arr(m.byTime)) {
      slots.push({
        label: str(s.label),
        value: `${fmt(s.sysAvg)}/${fmt(s.diaAvg)}`,
        sub: `${int(s.count)} 次${int(s.abnormalCount) ? ` · 异常 ${int(s.abnormalCount)}` : ''}`,
        warn: int(s.abnormalCount) > 0,
      })
    }
    for (const s of arr(m.bySlot)) {
      slots.push({
        label: str(s.label),
        value: fmt(s.avg),
        sub: `${int(s.count)} 次 · 达标 ${pctText(s.inRangeRate)}`,
        warn: int(s.abnormalCount) > 0,
      })
    }

    const inRange = family === 'WEIGHT' ? null : num(m.inRangeRate)
    return {
      family,
      label: str(m.label) || metricLabel(family),
      unit,
      count,
      abnormalCount,
      inRangeText: inRange == null ? null : pctText(inRange),
      inRangeBand: rateBand(inRange),
      ref: str(m.ref) || null,
      stats: cells,
      prevText,
      trend,
      slots,
      abnormalItems: arr(m.abnormalItems).map((a) => ({ time: shortTime(a.t), text: str(a.text) })),
      series,
    }
  })
}

// ---------------------------------------------------------------- 饮食 / 运动

export function dietExtras(content?: Rec | null) {
  const diet = rec(rec(content?.lifestyle).diet)
  const macro = rec(diet.macro)
  const macros: MacroRow[] = diet.macro
    ? [
        { label: '碳水', actual: num(macro.carbPct), target: num(macro.targetCarbPct), grams: num(macro.avgCarbG) },
        { label: '蛋白质', actual: num(macro.proteinPct), target: num(macro.targetProteinPct), grams: num(macro.avgProteinG) },
        { label: '脂肪', actual: num(macro.fatPct), target: num(macro.targetFatPct), grams: num(macro.avgFatG) },
      ]
    : []
  const flags: ChipItem[] = arr(diet.flagTop).map((f) => ({
    label: str(f.label),
    count: int(f.count),
    warn: str(f.level) === 'RED' || str(f.level) === 'YELLOW',
  }))
  const foods: ChipItem[] = arr(diet.topFoods).map((f) => ({
    label: str(f.name),
    count: int(f.count),
    warn: int(f.flaggedCount) > 0,
  }))
  const recordDays = int(diet.recordDays)
  const breakfast =
    diet.breakfastDays != null && recordDays > 0 ? `吃早餐 ${int(diet.breakfastDays)}/${recordDays} 天` : ''
  const sodium =
    diet.highSodiumDays != null && int(diet.highSodiumDays) > 0 ? `钠超标 ${int(diet.highSodiumDays)} 天` : ''
  const comments = int(diet.staffCommentCount)
  return {
    macros,
    flags,
    foods,
    notes: [breakfast, sodium, comments ? `健管师点评 ${comments} 餐` : ''].filter(Boolean),
  }
}

export function exerciseExtras(content?: Rec | null) {
  const ex = rec(rec(content?.lifestyle).exercise)
  const types: ChipItem[] = arr(ex.topTypes).map((t) => ({
    label: `${str(t.name)} ${int(t.min)} 分钟`,
    count: int(t.count),
    warn: false,
  }))
  const weekly: WeekBar[] = arr(ex.weekly).map((w, i) => ({
    label: `第${i + 1}周`,
    range: `${shortDate(w.weekStart)}–${shortDate(w.weekEnd)}`,
    value: num(w.activeMin),
    display: `${int(w.activeMin)} 分钟`,
    met: w.met == null ? null : w.met === true,
  }))
  const target = num(ex.weeklyActiveMinTarget)
  return {
    types,
    weekly,
    target,
    metText:
      ex.targetMetWeeks != null ? `${int(ex.targetMetWeeks)}/${int(ex.weekCount)} 周达到目标` : '',
    avgSessionMin: num(ex.avgSessionMin),
  }
}

// ---------------------------------------------------------------- 检验 / 检查

const FLAG_TEXT: Record<string, string> = { H: '偏高', L: '偏低', HH: '明显偏高', LL: '明显偏低' }

function labItem(it: Rec): LabItemView {
  const flag = str(it.flag).toUpperCase() || null
  const abnormal = !!flag && flag !== 'N' && flag !== 'NORMAL'
  const unit = str(it.unit)
  const prev = it.prevValue != null && it.prevValue !== '' ? `${fmt(it.prevValue, 2)}${unit ? ` ${unit}` : ''}` : ''
  return {
    name: str(it.name) || str(it.code),
    value: `${fmt(it.value, 2)}${unit ? ` ${unit}` : ''}`,
    ref: str(it.ref),
    flag: abnormal ? flag : null,
    flagText: abnormal ? FLAG_TEXT[flag!] || '异常' : '',
    prevText: prev ? `上次 ${prev}${it.prevDate ? `（${shortDate(it.prevDate)}）` : ''}` : '',
  }
}

export function observationViews(content?: Rec | null) {
  const obs = content?.observations ? rec(content.observations) : null
  if (!obs) return null
  const labs: LabView[] = arr(obs.labs).map((l) => ({
    id: str(l.id),
    title: str(l.specimenType) || '检验',
    time: shortTime(l.reportedAt || l.sampledAt),
    abnormalCount: int(l.abnormalCount),
    itemCount: int(l.itemCount),
    items: arr(l.items).map(labItem),
  }))
  const exams: ExamView[] = arr(obs.exams).map((e) => ({
    id: str(e.id),
    title: str(e.examType) || '检查',
    time: shortTime(e.examinedAt),
    conclusion: str(e.conclusion),
  }))
  if (!labs.length && !exams.length) return null
  return { labs, exams, abnormal: arr(obs.abnormalItems).map(labItem) }
}
