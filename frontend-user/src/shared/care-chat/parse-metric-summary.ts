import {
  abnormalLabel,
  evaluateBloodPressure,
  evaluateGlucose,
  isAbnormal,
} from '../metric-ranges'
import type { ChatHealthMetricPayload, ChatMetricItem } from './types'

function pickLine(lines: string[], label: string): string {
  const line = lines.find((l) => l.startsWith(`${label}：`) || l.startsWith(`${label}:`))
  if (!line) return '—'
  return line.replace(/^[^：:]+[：:]/, '').trim() || '—'
}

function parseFirstNumber(raw: string): number | null {
  const m = String(raw).match(/-?\d+(?:\.\d+)?/)
  if (!m) return null
  const n = Number(m[0])
  return Number.isFinite(n) ? n : null
}

function parseBloodPressure(raw: string): { sys: number | null; dia: number | null } {
  const m = String(raw).match(/(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)/)
  if (!m) return { sys: parseFirstNumber(raw), dia: null }
  const sys = Number(m[1])
  const dia = Number(m[2])
  return {
    sys: Number.isFinite(sys) ? sys : null,
    dia: Number.isFinite(dia) ? dia : null,
  }
}

function detectMealContext(raw: string): string | null {
  if (/餐后|餐后2小时|PPG/i.test(raw)) return 'POSTPRANDIAL'
  if (/随机/.test(raw)) return 'RANDOM'
  if (/空腹|FBG/i.test(raw)) return 'FASTING'
  return null
}

/** 识别「今日健康数据摘要」类文本并带上业务异常判定 */
export function parseMetricSummary(content?: string): ChatHealthMetricPayload | null {
  const raw = (content || '').trim()
  if (!raw.includes('今日健康数据摘要') && !raw.includes('健康数据摘要')) return null
  const lines = raw.split(/\n+/).map((l) => l.trim()).filter(Boolean)

  const weightRaw = pickLine(lines, '体重')
  const bpRaw = pickLine(lines, '血压')
  const glucoseRaw = pickLine(lines, '血糖')
  const mealFromText = detectMealContext(raw) || detectMealContext(glucoseRaw)

  const weightNum = weightRaw === '—' ? null : parseFirstNumber(weightRaw)
  const { sys, dia } = bpRaw === '—' ? { sys: null, dia: null } : parseBloodPressure(bpRaw)
  const glucoseNum = glucoseRaw === '—' ? null : parseFirstNumber(glucoseRaw)

  const bpFlag = evaluateBloodPressure(sys, dia)
  const glucoseFlag = evaluateGlucose(glucoseNum, mealFromText || 'FASTING')

  const items: ChatMetricItem[] = [
    {
      key: 'weight',
      label: '体重',
      value: weightRaw,
      numeric: weightNum,
      unit: 'kg',
    },
    {
      key: 'bp',
      label: '血压',
      value: bpRaw,
      numeric: sys,
      numericAlt: dia,
      unit: 'mmHg',
      status: bpRaw !== '—' ? abnormalLabel(bpFlag) : '',
      abnormal: bpRaw !== '—' && isAbnormal(bpFlag),
    },
    {
      key: 'glucose',
      label: '血糖',
      value: glucoseRaw,
      numeric: glucoseNum,
      unit: 'mmol/L',
      mealContext: mealFromText || 'FASTING',
      status: glucoseRaw !== '—' ? abnormalLabel(glucoseFlag) : '',
      abnormal: glucoseRaw !== '—' && isAbnormal(glucoseFlag),
    },
  ]

  return {
    title: '今日健康数据摘要',
    items,
  }
}

export function isMetricSummaryContent(content?: string): boolean {
  return !!parseMetricSummary(content)
}
