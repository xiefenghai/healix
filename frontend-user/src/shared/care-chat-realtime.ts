/**
 * C 端 CareChat SSE 单例：全 App 共用一条连接，页面只订阅事件总线。
 */
import { getToken } from '../api/http'
import { subscribeCareChatSse, type CareChatSseEvent, type CareChatSseHandler } from './care-chat-sse'

const listeners = new Set<CareChatSseHandler>()
let stopSse: (() => void) | null = null
let connected = false

export function isCareChatRealtimeConnected() {
  return connected
}

/** 订阅事件；返回取消订阅。 */
export function onCareChatEvent(handler: CareChatSseHandler): () => void {
  listeners.add(handler)
  startCareChatRealtime()
  return () => {
    listeners.delete(handler)
  }
}

export function startCareChatRealtime() {
  if (stopSse || !getToken()) return
  stopSse = subscribeCareChatSse(
    '/api/c/v1/care-chat/events',
    (ev) => {
      listeners.forEach((h) => {
        try {
          h(ev)
        } catch {
          /* ignore */
        }
      })
    },
    {
      onStatus: (ok) => {
        connected = ok
      },
    },
  )
}

export function stopCareChatRealtime() {
  stopSse?.()
  stopSse = null
  connected = false
}

export type { CareChatSseEvent, CareChatSseHandler }
