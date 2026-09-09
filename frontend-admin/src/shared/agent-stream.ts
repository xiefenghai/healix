import { getToken } from './http'

export interface StreamEnvelope {
  type: string
  data: Record<string, unknown>
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

export interface StreamHandlers {
  onProgress?: (message: string, index: number) => void
  onTool?: (event: StreamToolEvent) => void
  onSkill?: (event: StreamSkillEvent) => void
  onToken?: (text: string) => void
  onResult?: (payload: unknown) => void
  onError?: (message: string) => void
  onDone?: () => void
}

/** 开发环境直连后端，避免 Vite 代理缓冲 SSE */
function sseUrl(path: string): string {
  if (import.meta.env.DEV) {
    return `http://127.0.0.1:8080${path}`
  }
  return path
}

export async function postSse(
  path: string,
  body: unknown,
  handlers: StreamHandlers,
): Promise<void> {
  const headers = new Headers({
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    'Cache-Control': 'no-cache',
  })
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(sseUrl(path), { method: 'POST', headers, body: JSON.stringify(body) })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.message || `HTTP ${res.status}`)
  }
  if (!res.body) {
    throw new Error('无流式响应')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let progressIndex = 0

  const handleEvent = (event: StreamEnvelope) => {
    dispatch(event, handlers, () => {
      progressIndex += 1
      return progressIndex
    })
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseSseBlock(block)
      if (event) handleEvent(event)
      boundary = buffer.indexOf('\n\n')
    }
  }

  if (buffer.trim()) {
    const event = parseSseBlock(buffer)
    if (event) handleEvent(event)
  }
}

function parseSseBlock(block: string): StreamEnvelope | null {
  const lines = block.split('\n')
  let dataLine = ''
  for (const line of lines) {
    const trimmed = line.trim()
    if (trimmed.startsWith('data:')) {
      dataLine += trimmed.slice(5).trim()
    }
  }
  if (!dataLine) return null
  try {
    return JSON.parse(dataLine) as StreamEnvelope
  } catch {
    return null
  }
}

function dispatch(
  event: StreamEnvelope,
  handlers: StreamHandlers,
  nextProgressIndex: () => number,
) {
  const data = event.data || {}
  switch (event.type) {
    case 'progress':
      handlers.onProgress?.(String(data.message || ''), nextProgressIndex())
      break
    case 'tool':
      handlers.onTool?.({
        name: String(data.name || ''),
        status: String(data.status || ''),
        detail: String(data.detail || ''),
      })
      break
    case 'skill':
      handlers.onSkill?.({
        name: String(data.name || ''),
        detail: String(data.detail || ''),
      })
      break
    case 'token':
      handlers.onToken?.(String(data.text || ''))
      break
    case 'result':
      handlers.onResult?.(data.payload ?? data)
      break
    case 'error':
      handlers.onError?.(String(data.message || '未知错误'))
      break
    case 'done':
      handlers.onDone?.()
      break
    default:
      break
  }
}
