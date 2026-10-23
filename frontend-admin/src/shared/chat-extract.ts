export interface ChatExtractOption {
  value: string
  label: string
}

export interface ChatExtractRow {
  id: string
  target: 'VITAL' | 'ARCHIVE' | string
  code: string
  label: string
  value?: number | null
  systolic?: number | null
  diastolic?: number | null
  unit?: string | null
  sourceUnit?: string | null
  sourceValue?: number | null
  measuredAt?: string | null
  mealContext?: string | null
  bpContext?: string | null
  archiveValue?: string | null
  outcome?: 'WRITTEN' | 'DISCARDED' | null
  currentDisplay?: string | null
  textField?: boolean
  derived?: boolean
  quoteTruncated?: boolean
  quote: string
  editing?: boolean
  status?: string
  statusText?: string
  options?: ChatExtractOption[]
  removed?: boolean
}

export interface ChatExtractIgnored {
  quote: string
  reason?: string
}

export interface ChatExtractReview {
  messageId?: string
  peopleId?: string
  status?: string
  title?: string
  banner?: string
  confirmHint?: string
  labHint?: boolean
  overflow?: boolean
  restoreInput?: boolean
  items: ChatExtractRow[]
  ignored?: ChatExtractIgnored[]
}

const RANGES: Record<string, [number, number]> = {
  BLOOD_GLUCOSE: [1, 35],
  BLOOD_PRESSURE_SYS: [60, 260],
  BLOOD_PRESSURE_DIA: [30, 160],
  HEART_RATE: [30, 220],
  WEIGHT: [20, 300],
  WAIST: [40, 200],
}

export function blockPlainEnter(text: string) {
  const raw = text ?? ''
  return raw.includes('\n') || raw.trim().length > 200
}

export function chatExtractFromActions(
  actions?: Array<{ type?: string; payload?: unknown }> | null,
): ChatExtractReview | null {
  const action = (actions || []).find((item) => item?.type === 'chat_extract' && item.payload)
  if (!action?.payload || typeof action.payload !== 'object') return null
  const payload = action.payload as ChatExtractReview
  if (!Array.isArray(payload.items) && !Array.isArray(payload.ignored)) return null
  return {
    ...payload,
    items: (payload.items || []).map((row) => ({ ...row })),
    ignored: payload.ignored || [],
  }
}

export function asChatExtract(extracted: unknown): ChatExtractReview | null {
  if (!extracted || typeof extracted !== 'object') return null
  const card = extracted as ChatExtractReview
  if (card.restoreInput) return card
  if (!Array.isArray(card.items)) return null
  return {
    ...card,
    items: card.items.map((row) => ({ ...row })),
    ignored: card.ignored || [],
  }
}

export function refreshRow(row: ChatExtractRow, rows: ChatExtractRow[]) {
  if (row.removed) return
  const problem = problemOf(row, rows)
  row.status = problem ? problem.code : 'READY'
  row.statusText = problem ? problem.text : '可入库'
}

export function refreshAll(rows: ChatExtractRow[]) {
  for (const row of rows) refreshRow(row, rows)
}

export function canConfirm(review: ChatExtractReview) {
  if (
    !review.messageId ||
    review.status === 'CONFIRMED' ||
    review.status === 'SUPERSEDED' ||
    review.status === 'DISCARDED' ||
    review.status === 'saving'
  ) {
    return false
  }
  const alive = review.items.filter((row) => !row.removed && !row.outcome)
  if (!alive.length) return false
  refreshAll(review.items)
  return alive.every((row) => row.status === 'READY')
}

function problemOf(row: ChatExtractRow, rows: ChatExtractRow[]): { code: string; text: string } | null {
  if (row.outcome) return null
  if (row.target === 'ARCHIVE') {
    const same = rows.filter((item) => !item.removed && !item.outcome && item.target === 'ARCHIVE' && item.code === row.code)
    if (same.length > 1) return { code: 'ARCHIVE_DUP', text: '同一字段有多行，请只留一行' }
    if (!String(row.archiveValue ?? '').trim()) return { code: 'MISSING_SCENE', text: '请填写这一项' }
    if (row.textField && String(row.archiveValue).trim().length > 40) {
      return { code: 'TOO_LONG', text: '不超过 40 字' }
    }
    return null
  }
  const when = splitWhen(row.measuredAt)
  if (!when.date && !when.time) return { code: 'MISSING_DATE', text: '请补上日期和时刻' }
  if (!when.date) return { code: 'MISSING_DATE', text: '请补上日期' }
  if (!when.time) return { code: 'MISSING_TIME', text: '请补上时刻' }
  if (row.code === 'BLOOD_PRESSURE') {
    if (row.systolic == null || row.diastolic == null) return { code: 'BP_ORDER', text: '请填写收缩压和舒张压' }
    if (!inRange('BLOOD_PRESSURE_SYS', row.systolic) || !inRange('BLOOD_PRESSURE_DIA', row.diastolic)) {
      return { code: 'OUT_OF_RANGE', text: '超出合理范围' }
    }
    if (row.systolic <= row.diastolic) return { code: 'BP_ORDER', text: '收缩压不高于舒张压，请改数字' }
    return null
  }
  if (row.code === 'BLOOD_GLUCOSE' && !row.mealContext) {
    return { code: 'MISSING_SCENE', text: '请选择空腹、餐后或随机' }
  }
  if (row.code === 'WEIGHT' && row.unit !== 'kg') {
    return { code: 'MISSING_UNIT', text: '请选择体重单位' }
  }
  if (row.value == null || !inRange(row.code, row.value)) {
    return { code: 'OUT_OF_RANGE', text: '超出合理范围' }
  }
  if (row.status === 'DUPLICATE') return { code: 'DUPLICATE', text: '这个时刻已经有记录' }
  return null
}

function inRange(code: string, value: number) {
  const range = RANGES[code]
  if (!range) return true
  return value >= range[0] && value <= range[1]
}

export function splitWhen(measuredAt?: string | null) {
  if (!measuredAt) return { date: '', time: '' }
  const [date, time] = measuredAt.split('T')
  return { date: date || '', time: time ? time.slice(0, 5) : '' }
}

export function joinWhen(date: string, time: string) {
  if (date && time) return `${date}T${time}`
  if (date) return date
  if (time) return `T${time}`
  return ''
}

export function applyWeightUnit(row: ChatExtractRow, unit: 'kg' | '斤') {
  if (unit === '斤') {
    const base = row.sourceUnit === '斤' && row.sourceValue != null ? row.sourceValue : row.value
    if (base == null) return
    row.sourceValue = base
    row.sourceUnit = '斤'
    row.value = Math.round((Number(base) / 2) * 10) / 10
    row.unit = 'kg'
    return
  }
  row.sourceUnit = null
  row.sourceValue = null
  row.unit = 'kg'
}
