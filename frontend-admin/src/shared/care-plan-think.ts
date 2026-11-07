import type { ActivityItem } from './agent-activity'

export interface CarePlanThinkLine {
  id: string
  text: string
  status: 'running' | 'done'
}

const MODEL_NAME_RE =
  /\b(DeepSeek|OpenAI|GPT-?4|Claude|Qwen|通义|文心|Moonshot|Kimi)\b/gi

/** 底层工具名 / 技术文案 → 健管师可读短句 */
const TOOL_LABEL: Record<string, string> = {
  DeepSeek: '正在综合患者数据与指南起草方案…',
  临床方案推理: '正在综合患者数据与指南起草方案…',
  resolveTemplateKey: '正在匹配适宜的管理方向…',
  匹配管理方向: '正在匹配适宜的管理方向…',
  CarePlanTemplateRegistry: '正在按标准模板生成方案…',
  标准模板方案: '正在按标准模板生成方案…',
  safetyCheck: '正在做发布前安全校验…',
  安全校验: '正在做发布前安全校验…',
  allergenGuard: '正在核对饮食与过敏史…',
  过敏规避核对: '正在核对饮食与过敏史…',
  persistGenerated: '正在写入方案草稿…',
  updateDraft: '正在写入方案草稿…',
  保存草稿: '正在写入方案草稿…',
  parseCarePlan: '正在核对方案结构…',
}

function scrubModelNames(text: string): string {
  return text
    .replace(MODEL_NAME_RE, '智能推理')
    .replace(/正在调用\s*/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

export function humanizeCarePlanActivityTitle(title?: string, detail?: string): string {
  const rawTitle = scrubModelNames(String(title || '').trim())
  const rawDetail = scrubModelNames(String(detail || '').trim())
  if (TOOL_LABEL[rawTitle]) return TOOL_LABEL[rawTitle]
  if (/^DeepSeek$/i.test(String(title || '').trim())) {
    return TOOL_LABEL.DeepSeek
  }
  if (rawDetail && !/^正在调用/.test(rawDetail) && rawDetail.length <= 48) {
    if (/生成管理方案|修订管理方案/.test(rawDetail)) {
      return '正在综合患者数据与指南起草方案…'
    }
    return rawDetail.endsWith('…') || rawDetail.endsWith('...') ? rawDetail : `${rawDetail}…`
  }
  if (!rawTitle) return '正在为患者定制管理方案…'
  if (/思考/.test(rawTitle)) return '正在梳理运动、饮食与执行结构…'
  if (rawTitle.length <= 36) {
    return /[…\.．]$/.test(rawTitle) ? rawTitle : `${rawTitle}…`
  }
  return `${rawTitle.slice(0, 34)}…`
}

/** 从活动轨迹提炼拟人化步骤（去重，保留顺序） */
export function carePlanThinkLines(items: ActivityItem[], streaming: boolean): CarePlanThinkLine[] {
  const out: CarePlanThinkLine[] = []
  const seen = new Set<string>()
  for (const item of items) {
    if (item.kind === 'thinking' && item.status === 'running') {
      const text = '正在梳理运动、饮食与执行结构…'
      if (!seen.has(text)) {
        seen.add(text)
        out.push({ id: item.id, text, status: 'running' })
      }
      continue
    }
    if (item.kind === 'thinking') continue
    const text = humanizeCarePlanActivityTitle(item.title, item.detail)
    if (!text || seen.has(text)) continue
    seen.add(text)
    out.push({
      id: item.id,
      text,
      status: item.status === 'done' ? 'done' : 'running',
    })
  }
  if (!out.length && streaming) {
    out.push({
      id: 'boot',
      text: '正在为患者定制管理方案…',
      status: 'running',
    })
  }
  // 只保留最近若干步，避免刷屏
  return out.slice(-5)
}
