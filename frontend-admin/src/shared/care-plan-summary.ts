/** 方案总结阅读态：把整段 summary 拆成运动 / 饮食 / 监测 */

export type PlanSummarySectionKey = 'exercise' | 'diet' | 'monitor'

export type PlanSummarySection = {
  key: PlanSummarySectionKey
  label: string
  tag: string
  text: string
}

export const PLAN_SUMMARY_SECTION_META: Record<
  PlanSummarySectionKey,
  { label: string; tag: string }
> = {
  exercise: { label: '运动', tag: '运' },
  diet: { label: '饮食', tag: '食' },
  monitor: { label: '监测与随访', tag: '监' },
}

const EXERCISE_RE =
  /^(?:运动|锻炼)|骑行|步行|有氧|力量|训练|不憋气|直立性|低冲击|活动量|心率|每周累计/
const DIET_RE =
  /^(?:饮食)|千卡|kcal|热量|控糖|限盐|食盐|降压药|同服|碳水|蛋白|脂肪|三餐定时/
const MONITOR_RE =
  /^(?:监测|随访|执行)|记录|打卡|复评|补采|久坐|起身|血糖|体重|过敏史|家族史|依从/

function classifyChunk(text: string): PlanSummarySectionKey {
  const t = text.trim()
  if (EXERCISE_RE.test(t)) return 'exercise'
  if (DIET_RE.test(t)) return 'diet'
  if (MONITOR_RE.test(t)) return 'monitor'
  // 弱信号：句中关键词
  if (/运动|骑行|步行|有氧|力量/.test(t) && !/饮食|千卡|热量/.test(t)) return 'exercise'
  if (/饮食|千卡|热量|控糖|限盐|同服/.test(t)) return 'diet'
  if (/记录|复评|随访|监测|打卡|补采|久坐/.test(t)) return 'monitor'
  return 'monitor'
}

/** 按中文分号 / 句号拆条，保留可读短句 */
function splitSummaryChunks(raw: string): string[] {
  const s = String(raw || '').trim()
  if (!s) return []
  if (s.includes('\n')) {
    return s
      .split(/\n+/)
      .map((x) => x.trim())
      .filter(Boolean)
  }
  const parts: string[] = []
  let buf = ''
  let depth = 0
  for (const ch of s) {
    if (ch === '（' || ch === '(' || ch === '「') depth += 1
    else if (ch === '）' || ch === ')' || ch === '」') depth = Math.max(0, depth - 1)
    if ((ch === '；' || ch === '。') && depth === 0) {
      const t = buf.trim()
      if (t) parts.push(t)
      buf = ''
      continue
    }
    buf += ch
  }
  const last = buf.trim()
  if (last) parts.push(last)
  return parts
}

/**
 * 启发式三段。同一 key 多句合并；解析不出结构时返回单段 monitor（调用方应降级为全文编辑）。
 */
export function parsePlanSummarySections(raw: string): PlanSummarySection[] {
  const chunks = splitSummaryChunks(raw)
  if (!chunks.length) return []

  const bucket: Record<PlanSummarySectionKey, string[]> = {
    exercise: [],
    diet: [],
    monitor: [],
  }
  // 开场总述（「本阶段以…」）并入第一段有实质内容的块
  let lead = ''
  for (const chunk of chunks) {
    const key = classifyChunk(chunk)
    const looksLead =
      /^(?:本阶段|本期|总体|以.{1,12}并进)/.test(chunk) &&
      !EXERCISE_RE.test(chunk) &&
      !DIET_RE.test(chunk) &&
      !MONITOR_RE.test(chunk)
    if (looksLead && !bucket.exercise.length && !bucket.diet.length && !bucket.monitor.length) {
      lead = chunk
      continue
    }
    if (lead) {
      bucket[key].push(`${lead}${chunk.startsWith('：') || chunk.startsWith(':') ? '' : '：'}${chunk}`)
      lead = ''
    } else {
      bucket[key].push(chunk)
    }
  }
  if (lead) bucket.monitor.push(lead)

  const order: PlanSummarySectionKey[] = ['exercise', 'diet', 'monitor']
  const filled = order
    .filter((k) => bucket[k].length)
    .map((k) => ({
      key: k,
      label: PLAN_SUMMARY_SECTION_META[k].label,
      tag: PLAN_SUMMARY_SECTION_META[k].tag,
      text: bucket[k].join('；'),
    }))

  // 拆不出 ≥2 类时视为失败，交给全文阅读
  const kinds = new Set(filled.map((s) => s.key))
  if (kinds.size < 2) return []
  return filled
}

export function joinPlanSummarySections(sections: PlanSummarySection[]): string {
  return sections
    .map((s) => s.text.trim())
    .filter(Boolean)
    .join('；')
}

/** 总结数字高亮（与总览目标摘要同风格） */
export function highlightPlanSummaryHtml(text: string): string {
  const esc = String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return esc
    .replace(
      /(\d+(?:\.\d+)?)\s*(?:→|->|至|降至|降到|减至)\s*(?:约\s*)?(\d+(?:\.\d+)?)\s*(kg|cm|mmol\/L|mmHg|%|千卡|kcal)?/gi,
      (_m, a, b, u) => `<mark class="goal-metric">${a}→${b}${u || ''}</mark>`,
    )
    .replace(
      /(\d+(?:\.\d+)?)\s*(kg|cm|mmol\/L|mmHg|%|千卡|kcal|g\/日|分钟)?/gi,
      (m, n, u) => (u ? `<mark class="goal-metric">${n} ${u}</mark>` : m),
    )
    .replace(/\n/g, '<br>')
}
