import { ref } from 'vue'
import { subscribeCareChatSse, type CareChatSseEvent, type CareChatSseHandler } from './care-chat-sse'
import { getCurrentOrgId, getToken } from './http'
import { maybeNotifyChatUnread } from './notify'

export type { CareChatSseEvent }

const listeners = new Set<CareChatSseHandler>()
export const chatUnreadTotal = ref(0)

let stopSse: (() => void) | null = null
let connected = false
let startedForOrg: string | null = null
let stopVisibility: (() => void) | null = null

export function onCareChatEvent(handler: CareChatSseHandler): () => void {
  listeners.add(handler)
  startCareChatRealtime()
  return () => listeners.delete(handler)
}

/** 已连接则复用，避免路由切换时反复断连丢事件 */
export function startCareChatRealtime() {
  if (!getToken() || !getCurrentOrgId()) {
    stopCareChatRealtime()
    return
  }
  const orgId = getCurrentOrgId()!
  if (stopSse && startedForOrg === orgId) return

  stopCareChatRealtime()
  startedForOrg = orgId
  stopSse = subscribeCareChatSse(
    '/api/b/v1/care-chat/events',
    (ev) => {
      if (typeof ev.staffUnreadTotal === 'number') {
        const next = Number(ev.staffUnreadTotal)
        if (next > chatUnreadTotal.value) {
          maybeNotifyChatUnread(next, ev.message?.content)
        }
        chatUnreadTotal.value = next
      } else if (ev.type === 'message' && ev.message?.senderType === 'PATIENT') {
        maybeNotifyChatUnread(Math.max(1, chatUnreadTotal.value), ev.message.content)
      }
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
  if (!stopVisibility) {
    const onVis = () => {
      if (document.visibilityState === 'visible') void refreshChatUnread()
    }
    document.addEventListener('visibilitychange', onVis)
    window.addEventListener('focus', onVis)
    stopVisibility = () => {
      document.removeEventListener('visibilitychange', onVis)
      window.removeEventListener('focus', onVis)
    }
  }
}

export function stopCareChatRealtime() {
  stopSse?.()
  stopSse = null
  startedForOrg = null
  connected = false
  stopVisibility?.()
  stopVisibility = null
}

export function isCareChatRealtimeConnected() {
  return connected
}

export async function refreshChatUnread() {
  if (!getToken() || !getCurrentOrgId()) {
    chatUnreadTotal.value = 0
    return
  }
  try {
    const { api } = await import('./http')
    const res = await api<{ data: { count: number } }>('/api/b/v1/care-chat/unread-count')
    chatUnreadTotal.value = Number(res.data?.count || 0)
  } catch {
    /* ignore */
  }
}
