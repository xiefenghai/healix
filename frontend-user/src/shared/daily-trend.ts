import { ymd } from './diet'

/** on=达标/完成，soft=有记录未达标，warn=需要留意，future=还没到的日子 */
export type TrendTone = 'on' | 'soft' | 'warn' | 'future' | ''

export interface TrendBar {
  date: string
  value: number
  tone: TrendTone
  /** 点选时展示的数值文案，如「1800 ml」；为空表示当天没记录 */
  display?: string
}

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

/** 截止 end（含）往前 n 天，升序 */
export function lastNDays(n: number, end = new Date()): string[] {
  const out: string[] = []
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(end)
    d.setDate(end.getDate() - i)
    out.push(ymd(d))
  }
  return out
}

export function parseYmd(date: string): Date {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function weekdayOf(date: string): string {
  return WEEKDAYS[parseYmd(date).getDay()]
}

/** 7 天以内显示星期，更长显示几号；今天统一显示「今」 */
export function axisLabel(date: string, total: number, today = ymd(new Date())): string {
  if (date === today) return '今'
  return total <= 7 ? weekdayOf(date) : String(parseYmd(date).getDate())
}

export function captionDate(date: string): string {
  return `${date.slice(5).replace('-', '/')} 周${weekdayOf(date)}`
}
