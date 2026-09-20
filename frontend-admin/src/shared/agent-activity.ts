export type ActivityKind = 'progress' | 'tool' | 'skill' | 'thinking'

export interface ActivityItem {
  id: string
  kind: ActivityKind
  title: string
  detail?: string
  status: 'running' | 'done'
  elapsedMs?: number
  /** thinking 正文（可折叠） */
  body?: string
  collapsed?: boolean
}

export interface StreamToolEvent {
  name: string
  status: string
  detail: string
}

export interface StreamSkillEvent {
  name: string
  detail: string
}

export interface StreamThinkingEvent {
  status: string
  text?: string
  elapsedMs?: number
}

let seq = 0
function nextId(prefix: string) {
  seq += 1
  return `${prefix}-${seq}`
}

export function ensureActivityList(list?: ActivityItem[] | null): ActivityItem[] {
  return list ?? []
}

export function markActivityDone(list: ActivityItem[], exceptTitle?: string) {
  for (const item of list) {
    if (item.status === 'running' && item.title !== exceptTitle) {
      item.status = 'done'
    }
  }
}

export function finishAllActivity(list?: ActivityItem[] | null) {
  if (!list) return
  for (const item of list) {
    item.status = 'done'
    if (item.kind === 'thinking') {
      item.collapsed = true
      if (!item.title.includes('思考过程')) {
        item.title =
          item.elapsedMs != null ? `思考过程 · ${formatDuration(item.elapsedMs)}` : '思考过程'
      }
    }
  }
}

export function appendProgress(list: ActivityItem[], msg: string) {
  const text = msg.trim()
  if (!text) return
  const last = list[list.length - 1]
  if (last?.kind === 'progress' && last.title === text) {
    last.status = 'running'
    return
  }
  markActivityDone(list)
  list.push({
    id: nextId('progress'),
    kind: 'progress',
    title: text,
    status: 'running',
  })
}

export function appendTool(list: ActivityItem[], event: StreamToolEvent) {
  const status = event.status === 'done' ? 'done' : 'running'
  const existing = [...list]
    .reverse()
    .find((item) => item.kind === 'tool' && item.title === event.name)
  if (existing) {
    existing.detail = event.detail
    existing.status = status
    return
  }
  markActivityDone(list, event.name)
  list.push({
    id: nextId('tool'),
    kind: 'tool',
    title: event.name,
    detail: event.detail,
    status,
  })
}

export function appendSkill(list: ActivityItem[], event: StreamSkillEvent) {
  markActivityDone(list)
  list.push({
    id: nextId('skill'),
    kind: 'skill',
    title: event.name?.trim() || '技能',
    detail: event.detail?.trim() || undefined,
    status: 'done',
  })
}

export function appendThinking(list: ActivityItem[], event: StreamThinkingEvent) {
  let item = [...list].reverse().find((x) => x.kind === 'thinking' && x.status === 'running')
  if (!item) {
    // 允许在 done 前复用最近一条未折叠完的 thinking
    const last = [...list].reverse().find((x) => x.kind === 'thinking')
    if (last && last.status !== 'done') {
      item = last
    }
  }
  if (!item) {
    markActivityDone(list)
    item = {
      id: nextId('thinking'),
      kind: 'thinking',
      title: '思考中',
      status: 'running',
      body: '',
      // 进行中默认折叠，不刷思考全文
      collapsed: true,
    }
    list.push(item)
  }

  const text = event.text?.trim() ? event.text : ''
  if (event.status === 'delta' && text) {
    item.body = (item.body || '') + text
    item.detail = item.body
    item.status = 'running'
    item.title = '思考中'
    item.collapsed = true
    return
  }
  if (event.status === 'start') {
    item.status = 'running'
    item.title = '思考中'
    item.collapsed = true
    if (text) {
      item.body = item.body ? `${item.body}\n${text}` : text
      item.detail = item.body
    }
    return
  }
  if (event.status === 'done') {
    item.status = 'done'
    item.elapsedMs = event.elapsedMs
    // 结束时不要覆盖已有思考正文；仅在完全没有正文时写入兜底说明
    if (text && !(item.body || '').trim()) {
      item.body = text
      item.detail = text
    }
    item.title =
      event.elapsedMs != null ? `思考过程 · ${formatDuration(event.elapsedMs)}` : '思考过程'
    item.collapsed = true
  }
}

export function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(sec < 10 ? 1 : 0)}s`
  const m = Math.floor(sec / 60)
  const s = Math.round(sec % 60)
  return `${m}m ${s}s`
}

export function activityIcon(item: ActivityItem): string {
  if (item.kind === 'tool') return '⚙'
  if (item.kind === 'skill') return '◆'
  if (item.kind === 'thinking') return item.status === 'running' ? '…' : '◈'
  return item.status === 'done' ? '✓' : '•'
}
