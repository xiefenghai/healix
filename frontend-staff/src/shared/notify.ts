import { isChatNotifyEnabled } from './prefs'

let lastNotifiedAt = 0

export async function ensureChatNotifyPermission(): Promise<boolean> {
  if (typeof Notification === 'undefined') return false
  if (Notification.permission === 'granted') return true
  if (Notification.permission === 'denied') return false
  const result = await Notification.requestPermission()
  return result === 'granted'
}

/** 页面在后台且用户开启通知时，提示新未读 */
export function maybeNotifyChatUnread(count: number, preview?: string) {
  if (!isChatNotifyEnabled() || count <= 0) return
  if (typeof document === 'undefined' || document.visibilityState === 'visible') return
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  const now = Date.now()
  if (now - lastNotifiedAt < 8_000) return
  lastNotifiedAt = now
  try {
    const n = new Notification('Healix 患者消息', {
      body: preview?.trim() || `有 ${count} 条未读沟通`,
      tag: 'healix-staff-chat',
    })
    n.onclick = () => {
      window.focus()
      n.close()
    }
  } catch {
    /* ignore */
  }
}
