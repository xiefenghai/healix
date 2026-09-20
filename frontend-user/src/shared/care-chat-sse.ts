import { getToken } from '../api/http'

export interface CareChatSseEvent {
  type: string
  peopleId?: string
  threadId?: string
  staffUnreadTotal?: number
  patientUnreadCount?: number
  message?: {
    id: string
    threadId?: string
    senderType?: string
    senderName?: string
    content?: string
    gmtCreated?: string
  }
}

export type CareChatSseHandler = (event: CareChatSseEvent) => void

function sseUrl(path: string): string {
  if (import.meta.env.DEV) {
    return `http://127.0.0.1:8080${path}`
  }
  return path
}

/** C 端 CareChat SSE；断开自动重连。返回 stop。 */
export function subscribeCareChatSse(
  path: string,
  onEvent: CareChatSseHandler,
  opts?: { onStatus?: (connected: boolean) => void },
): () => void {
  let stopped = false
  let abort: AbortController | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let attempt = 0

  const clearRetry = () => {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  const scheduleRetry = () => {
    if (stopped) return
    const delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 4))
    attempt += 1
    clearRetry()
    retryTimer = setTimeout(() => void connect(), delay)
  }

  const connect = async () => {
    if (stopped) return
    abort?.abort()
    abort = new AbortController()
    const headers = new Headers({
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
    })
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)

    try {
      const res = await fetch(sseUrl(path), { method: 'GET', headers, signal: abort.signal })
      if (!res.ok || !res.body) {
        opts?.onStatus?.(false)
        scheduleRetry()
        return
      }
      attempt = 0
      opts?.onStatus?.(true)
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (!stopped) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const block = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const parsed = parseSseBlock(block)
          if (parsed && parsed.type !== 'ready') onEvent(parsed)
          boundary = buffer.indexOf('\n\n')
        }
      }
      opts?.onStatus?.(false)
      if (!stopped) scheduleRetry()
    } catch (e) {
      opts?.onStatus?.(false)
      if (stopped) return
      if (e instanceof DOMException && e.name === 'AbortError') return
      scheduleRetry()
    }
  }

  void connect()
  return () => {
    stopped = true
    clearRetry()
    abort?.abort()
    opts?.onStatus?.(false)
  }
}

function parseSseBlock(block: string): CareChatSseEvent | null {
  const lines = block.split('\n')
  let eventName = ''
  let dataLine = ''
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith(':')) continue
    if (trimmed.startsWith('event:')) {
      eventName = trimmed.slice(6).trim()
      continue
    }
    if (trimmed.startsWith('data:')) dataLine += trimmed.slice(5).trim()
  }
  if (!dataLine) return null
  try {
    const obj = JSON.parse(dataLine) as CareChatSseEvent
    if (!obj.type && eventName) obj.type = eventName
    return obj
  } catch {
    return null
  }
}
