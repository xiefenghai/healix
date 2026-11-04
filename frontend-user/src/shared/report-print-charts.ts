/**
 * 管理报告趋势图。
 *
 * 详情页的 echarts 画在 canvas 上，浏览器打印带不进 PDF。
 * 这里用同一套序列画成 SVG，页面和打印稿都能直接排进去。
 */

const TEAL = '#0e9f83'
const TEAL_SOFT = '#3fbfa3'
const ORANGE = '#e5a100'
const BLUE = '#5b8def'
const DANGER = '#e0435a'
const AXIS = '#6b7a87'
const INK = '#13222e'
const TRACK = '#eef2f4'
const BAND_TEAL = 'rgba(14,159,131,0.12)'
const BAND_BLUE = 'rgba(91,141,239,0.12)'

type Rec = Record<string, unknown>

interface Box {
  x: number
  y: number
  w: number
  h: number
}

interface Scale {
  min: number
  max: number
  ticks: number[]
}

function esc(v: unknown): string {
  return String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function rec(v: unknown): Rec {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : {}
}

function arr(v: unknown): Rec[] {
  return Array.isArray(v) ? (v.filter((x) => !!x && typeof x === 'object') as Rec[]) : []
}

function num(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function int(v: unknown): number {
  const n = num(v)
  return n == null ? 0 : Math.round(n)
}

function shortDate(v: unknown): string {
  return String(v || '')
    .replace('T', ' ')
    .slice(5, 10)
    .replace('-', '/')
}

function figure(title: string, body: string): string {
  if (!body) return ''
  const cap = title ? `<div class="chart-cap">${esc(title)}</div>` : ''
  return `<div class="chart-fig">${cap}${body}</div>`
}

function svgOpen(w: number, h: number): string {
  return `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" role="img" style="font-family:PingFang SC,Hiragino Sans GB,Microsoft YaHei,sans-serif">`
}

function niceStep(span: number): number {
  const raw = Math.abs(span) / 4 || 1
  const exp = Math.floor(Math.log10(raw))
  const base = 10 ** exp
  const f = raw / base
  const n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10
  return n * base
}

function axisOf(min: number, max: number, fromZero: boolean): Scale {
  let lo = fromZero ? Math.min(0, min) : min
  let hi = Math.max(max, lo)
  if (lo === hi) {
    const pad = Math.abs(lo) >= 1 ? Math.abs(lo) * 0.2 : 1
    if (!fromZero) lo -= pad
    hi += pad
  }
  const step = niceStep(hi - lo)
  const niceLo = fromZero && lo >= 0 ? 0 : Math.floor(lo / step) * step
  let niceHi = Math.ceil((hi - 1e-9) / step) * step
  if (niceHi <= niceLo) niceHi = niceLo + step
  const ticks: number[] = []
  for (let v = niceLo; v <= niceHi + step * 0.001 && ticks.length < 8; v += step) {
    ticks.push(Math.round(v * 1000) / 1000)
  }
  return { min: niceLo, max: ticks[ticks.length - 1] ?? niceHi, ticks }
}

function percentAxis(): Scale {
  return { min: 0, max: 100, ticks: [0, 25, 50, 75, 100] }
}

function fmt(v: number): string {
  if (Math.abs(v) >= 100 || Number.isInteger(v)) return String(Math.round(v))
  return String(Math.round(v * 10) / 10)
}

function yAt(v: number, scale: Scale, box: Box): number {
  const span = scale.max - scale.min || 1
  return box.y + ((scale.max - v) / span) * box.h
}

function labelIdx(n: number, max = 7): number[] {
  if (n <= max) return Array.from({ length: n }, (_, i) => i)
  const step = Math.ceil((n - 1) / (max - 1))
  const out: number[] = []
  for (let i = 0; i < n; i += step) out.push(i)
  if (out[out.length - 1] !== n - 1) out.push(n - 1)
  return out
}

function grid(scale: Scale, box: Box, unit = ''): string {
  return scale.ticks
    .map((t) => {
      const y = yAt(t, scale, box).toFixed(1)
      const text = unit === '%' ? `${fmt(t)}%` : fmt(t)
      return `<line x1="${box.x}" y1="${y}" x2="${(box.x + box.w).toFixed(1)}" y2="${y}" stroke="#e6eef2" stroke-dasharray="3 3"/>
        <text x="${box.x - 6}" y="${y}" text-anchor="end" dominant-baseline="middle" fill="${AXIS}" font-size="11">${esc(text)}</text>`
    })
    .join('')
}

function xLabels(labels: string[], box: Box, n: number): string {
  const bottom = box.y + box.h + 16
  return labelIdx(n)
    .map((i) => {
      const x = n <= 1 ? box.x + box.w / 2 : box.x + (i / (n - 1)) * box.w
      return `<text x="${x.toFixed(1)}" y="${bottom}" text-anchor="middle" fill="${AXIS}" font-size="11">${esc(labels[i] || '')}</text>`
    })
    .join('')
}

function hLine(y: number, box: Box, color: string, label: string): string {
  const yy = y.toFixed(1)
  return `<line x1="${box.x}" y1="${yy}" x2="${(box.x + box.w).toFixed(1)}" y2="${yy}" stroke="${color}" stroke-dasharray="4 3"/>
    <text x="${(box.x + box.w - 2).toFixed(1)}" y="${(y - 4).toFixed(1)}" text-anchor="end" fill="${color}" font-size="10">${esc(label)}</text>`
}

interface LineSeries {
  name: string
  color: string
  values: (number | null)[]
  bad: boolean[]
  band?: [number, number] | null
  bandFill?: string
}

function lineSvg(
  labels: string[],
  series: LineSeries[],
  opts: { upper?: number | null; target?: number | null; targetLabel?: string; fromZero?: boolean } = {},
): string {
  const n = labels.length
  if (!n || series.every((s) => s.values.every((v) => v == null))) return ''
  const legend = series.length > 1
  const w = 680
  const h = legend ? 214 : 196
  const box: Box = { x: 46, y: legend ? 28 : 14, w: w - 46 - 14, h: h - (legend ? 28 : 14) - 28 }
  const nums = series.flatMap((s) => s.values.filter((v): v is number => v != null))
  for (const s of series) {
    if (s.band) nums.push(s.band[0], s.band[1])
  }
  if (opts.upper != null) nums.push(opts.upper)
  if (opts.target != null) nums.push(opts.target)
  const scale = axisOf(Math.min(...nums), Math.max(...nums), opts.fromZero === true)

  const bands = series
    .filter((s) => s.band)
    .map((s) => {
      const [lo, hi] = s.band as [number, number]
      const y = yAt(Math.max(lo, hi), scale, box)
      const height = Math.abs(yAt(lo, scale, box) - yAt(hi, scale, box))
      return `<rect x="${box.x}" y="${y.toFixed(1)}" width="${box.w}" height="${Math.max(height, 0).toFixed(1)}" fill="${s.bandFill || BAND_TEAL}"/>`
    })
    .join('')

  const paths = series
    .map((s) => {
      let d = ''
      let started = false
      s.values.forEach((v, i) => {
        if (v == null) return
        const x = (n <= 1 ? box.x + box.w / 2 : box.x + (i / (n - 1)) * box.w).toFixed(1)
        const y = yAt(v, scale, box).toFixed(1)
        d += `${started ? 'L' : 'M'}${x},${y}`
        started = true
      })
      return d ? `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>` : ''
    })
    .join('')

  const dots = series
    .map((s) =>
      s.values
        .map((v, i) => {
          if (v == null) return ''
          const x = n <= 1 ? box.x + box.w / 2 : box.x + (i / (n - 1)) * box.w
          const y = yAt(v, scale, box)
          const bad = s.bad[i]
          const color = bad ? DANGER : s.color
          const r = bad ? 4.5 : 3.5
          return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${r}" fill="${color}" stroke="#fff" stroke-width="1.5"/>`
        })
        .join(''),
    )
    .join('')

  const marks = [
    opts.upper != null ? hLine(yAt(opts.upper, scale, box), box, '#b07400', `上限 ${fmt(opts.upper)}`) : '',
    opts.target != null ? hLine(yAt(opts.target, scale, box), box, '#b07400', opts.targetLabel || '目标') : '',
  ].join('')

  const legendHtml = legend
    ? series
        .map((s, i) => {
          const x = 46 + i * 78
          return `<circle cx="${x}" cy="12" r="4" fill="${s.color}"/><text x="${x + 8}" y="12" dominant-baseline="middle" fill="${INK}" font-size="11">${esc(s.name)}</text>`
        })
        .join('')
    : ''

  return `${svgOpen(w, h)}${legendHtml}${grid(scale, box)}${bands}${marks}${paths}${dots}${xLabels(labels, box, n)}</svg>`
}

function barSvg(
  labels: string[],
  values: (number | null)[],
  colors: string[],
  opts: { max?: number; unit?: string; target?: number | null; targetLabel?: string; captions?: string[] } = {},
): string {
  const n = labels.length
  if (!n || values.every((v) => v == null)) return ''
  const w = 680
  const h = 196
  const box: Box = { x: 46, y: 18, w: w - 46 - 14, h: h - 18 - 32 }
  const nums = values.filter((v): v is number => v != null)
  if (opts.target != null) nums.push(opts.target)
  if (opts.max != null) nums.push(opts.max)
  const scale = opts.unit === '%' ? percentAxis() : axisOf(0, Math.max(...nums, 0), true)
  const gap = box.w / n
  const bw = Math.max(6, Math.min(28, gap * 0.55))
  const showCap = (opts.captions?.length || 0) > 0 && n <= 12
  const bars = values
    .map((v, i) => {
      if (v == null) return ''
      const x = box.x + gap * i + (gap - bw) / 2
      const y = yAt(v, scale, box)
      const bh = Math.max(0, box.y + box.h - y)
      const cap = showCap ? opts.captions?.[i] || '' : ''
      return `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${bh.toFixed(1)}" rx="4" fill="${colors[i] || TEAL}"/>
        ${cap ? `<text x="${(x + bw / 2).toFixed(1)}" y="${(y - 6).toFixed(1)}" text-anchor="middle" fill="${INK}" font-size="11" font-weight="700">${esc(cap)}</text>` : ''}`
    })
    .join('')
  const target =
    opts.target != null ? hLine(yAt(opts.target, scale, box), box, '#b07400', opts.targetLabel || '目标') : ''
  return `${svgOpen(w, h)}${grid(scale, box, opts.unit || '')}${target}${bars}${xLabels(labels, box, n)}</svg>`
}

function rateColor(rate: number | null): string {
  if (rate == null) return TRACK
  if (rate >= 0.8) return TEAL
  if (rate >= 0.5) return ORANGE
  return DANGER
}

function parseRef(ref?: string | null) {
  const text = ref || ''
  const ranges = [...text.matchAll(/(\d+(?:\.\d+)?)\s*[~～-]\s*(\d+(?:\.\d+)?)/g)].map(
    (m) => [Number(m[1]), Number(m[2])] as [number, number],
  )
  const upper = text.match(/[≤<]\s*(\d+(?:\.\d+)?)/)
  return { ranges, upper: upper ? Number(upper[1]) : null }
}

function outOf(v: number | null, range?: [number, number]): boolean {
  if (v == null || !range) return false
  return v < range[0] || v > range[1]
}

/** 周报每日打卡柱，月报/季报每周完成率柱。无数据时返回空串。 */
export function adherenceChartHtml(content?: Rec | null): string {
  const plan = rec(rec(content?.adherence).plan)
  const weekly = arr(plan.weekly)
  if (weekly.length) {
    const labels = weekly.map((_, i) => `第${i + 1}周`)
    const rates = weekly.map((w) => num(w.rate))
    return figure(
      '每周方案完成率',
      barSvg(
        labels,
        rates.map((r) => (r == null ? null : Math.round(r * 100))),
        rates.map(rateColor),
        {
          unit: '%',
          max: 100,
          captions: rates.map((r) => (r == null ? '' : `${Math.round(r * 100)}%`)),
        },
      ),
    )
  }
  const days = arr(plan.daily).filter((d) => int(d.due) > 0 || int(d.done) > 0)
  if (!days.length) return ''
  const w = 680
  const h = 176
  const box: Box = { x: 12, y: 22, w: w - 24, h: h - 22 - 28 }
  const n = days.length
  const max = Math.max(1, ...days.map((d) => int(d.due)))
  const gap = box.w / n
  const bw = Math.max(8, Math.min(26, gap * 0.62))
  const bars = days
    .map((d, i) => {
      const due = int(d.due)
      const done = int(d.done)
      const x = box.x + gap * i + (gap - bw) / 2
      const dueH = (due / max) * box.h
      const doneH = (Math.min(done, due || done) / max) * box.h
      const full = due > 0 && done >= due
      const color = full ? TEAL : done > 0 ? TEAL_SOFT : TRACK
      const label = due ? `${done}/${due}` : '—'
      const labelColor = due && done < due ? DANGER : INK
      const yDue = box.y + box.h - dueH
      const yDone = box.y + box.h - doneH
      return `<rect x="${x.toFixed(1)}" y="${yDue.toFixed(1)}" width="${bw.toFixed(1)}" height="${Math.max(dueH, 2).toFixed(1)}" rx="6" fill="${TRACK}"/>
        <rect x="${x.toFixed(1)}" y="${yDone.toFixed(1)}" width="${bw.toFixed(1)}" height="${Math.max(doneH, done ? 2 : 0).toFixed(1)}" rx="6" fill="${color}"/>
        ${n <= 10 ? `<text x="${(x + bw / 2).toFixed(1)}" y="${(Math.min(yDue, yDone) - 6).toFixed(1)}" text-anchor="middle" fill="${labelColor}" font-size="11" font-weight="700">${esc(label)}</text>` : ''}`
    })
    .join('')
  const labels = xLabels(
    days.map((d) => shortDate(d.date)),
    box,
    n,
  )
  return figure('每日方案打卡', `${svgOpen(w, h)}${bars}${labels}</svg>`)
}

/** 指标折线：血压双线，其余单线；超出参考范围的点标红。 */
export function metricChartHtml(metric: { family: string; ref?: string | null; series?: Rec[] }): string {
  const series = metric.series || []
  if (!series.length) return ''
  const labels = series.map((p) => shortDate(p.t))
  const { ranges, upper } = parseRef(metric.ref)
  const flagged = (p: Rec) => p.abnormal === true || p.abnormal === 1
  if (metric.family === 'BP') {
    const [sysRange, diaRange] = ranges
    const sys = series.map((p) => num(p.sys))
    const dia = series.map((p) => num(p.dia))
    return figure(
      '',
      lineSvg(labels, [
        {
          name: '收缩压',
          color: TEAL,
          values: sys,
          bad: sys.map((v, i) => (sysRange ? outOf(v, sysRange) : flagged(series[i] || {}))),
          band: sysRange,
          bandFill: BAND_TEAL,
        },
        {
          name: '舒张压',
          color: BLUE,
          values: dia,
          bad: dia.map((v, i) => (diaRange ? outOf(v, diaRange) : flagged(series[i] || {}))),
          band: diaRange,
          bandFill: BAND_BLUE,
        },
      ]),
    )
  }
  const color = metric.family === 'GLUCOSE' ? ORANGE : TEAL
  const values = series.map((p) => num(p.value ?? p.bmi))
  const range = ranges[0]
  return figure(
    '',
    lineSvg(
      labels,
      [
        {
          name: '',
          color,
          values,
          bad: values.map((v, i) => (range ? outOf(v, range) : flagged(series[i] || {}))),
          band: range,
          bandFill: BAND_TEAL,
        },
      ],
      { upper },
    ),
  )
}

/** 有记录的日子画热量折线，并标出热量目标。 */
export function dietChartHtml(content?: Rec | null): string {
  const diet = rec(rec(content?.lifestyle).diet)
  const daily = arr(diet.daily)
  const values = daily.map((d) => num(d.kcal))
  if (!values.some((v) => v != null && v > 0)) return ''
  return figure(
    '每日热量',
    lineSvg(
      daily.map((d) => shortDate(d.date)),
      [{ name: '热量', color: TEAL, values, bad: daily.map(() => false) }],
      { target: num(diet.targetKcal), targetLabel: '目标' },
    ),
  )
}

/** 月报/季报画每周活动分钟；周报画每日活动分钟。 */
export function exerciseChartHtml(content?: Rec | null): string {
  const ex = rec(rec(content?.lifestyle).exercise)
  const weekly = arr(ex.weekly)
  if (weekly.length) {
    const target = num(ex.weeklyActiveMinTarget)
    const met =
      ex.targetMetWeeks != null && ex.weekCount != null ? `${int(ex.targetMetWeeks)}/${int(ex.weekCount)} 周达到目标` : ''
    const values = weekly.map((w) => num(w.activeMin) ?? 0)
    return figure(
      met ? `每周活动分钟 · ${met}` : '每周活动分钟',
      barSvg(
        weekly.map((_, i) => `第${i + 1}周`),
        values,
        weekly.map((w) => (w.met === false ? ORANGE : TEAL)),
        {
          target,
          targetLabel: '目标',
          captions: values.map((v) => String(Math.round(v))),
        },
      ),
    )
  }
  const daily = arr(ex.daily)
  const values = daily.map((d) => num(d.activeMin) ?? num(d.totalMin))
  if (!values.some((v) => v != null && v > 0)) return ''
  return figure(
    '每日活动分钟',
    barSvg(
      daily.map((d) => shortDate(d.date)),
      values,
      values.map(() => TEAL),
      { captions: values.map((v) => (v == null ? '' : String(Math.round(v)))) },
    ),
  )
}
