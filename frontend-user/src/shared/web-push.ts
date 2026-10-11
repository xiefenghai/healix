import { api } from '../api/http'

export type PushSupport =
  | 'supported'
  /** 非 HTTPS（localhost 除外） */
  | 'insecure'
  /** iOS Safari 未添加到主屏幕 */
  | 'ios-needs-install'
  | 'unsupported'

export interface PushConfig {
  enabled: boolean
  publicKey?: string
  subscriptionCount?: number
}

export function isIos(): boolean {
  return /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

export function isStandalone(): boolean {
  return (
    window.matchMedia?.('(display-mode: standalone)').matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  )
}

export function pushSupport(): PushSupport {
  if (!window.isSecureContext) return 'insecure'
  if (isIos() && !isStandalone()) return 'ios-needs-install'
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
    return 'unsupported'
  }
  return 'supported'
}

export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!window.isSecureContext || !('serviceWorker' in navigator)) return null
  try {
    return await navigator.serviceWorker.register('/sw.js', { scope: '/' })
  } catch {
    return null
  }
}

async function registration(): Promise<ServiceWorkerRegistration> {
  const reg = (await navigator.serviceWorker.getRegistration('/')) ?? (await registerServiceWorker())
  if (!reg) throw new Error('当前浏览器无法注册推送服务')
  await navigator.serviceWorker.ready
  return reg
}

export async function fetchPushConfig(): Promise<PushConfig> {
  const res = await api<{ data: PushConfig }>('/api/c/v1/push/config')
  return res.data
}

/** 当前浏览器是否已订阅（且通知权限仍为允许）。 */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (pushSupport() !== 'supported' || Notification.permission !== 'granted') return null
  const reg = await navigator.serviceWorker.getRegistration('/')
  return (await reg?.pushManager.getSubscription()) ?? null
}

export async function enablePush(): Promise<void> {
  const support = pushSupport()
  if (support !== 'supported') throw new Error('当前浏览器不支持推送')
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new Error(permission === 'denied' ? '通知权限已被拒绝，请在浏览器设置中开启' : '未授予通知权限')
  }
  const cfg = await fetchPushConfig()
  if (!cfg.enabled || !cfg.publicKey) throw new Error('推送服务暂未开启')
  const reg = await registration()
  let sub = await reg.pushManager.getSubscription()
  if (sub && !sameKey(sub, cfg.publicKey)) {
    await sub.unsubscribe()
    sub = null
  }
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(cfg.publicKey),
    })
  }
  const json = sub.toJSON()
  await api('/api/c/v1/push/subscriptions', {
    method: 'POST',
    body: JSON.stringify({ endpoint: json.endpoint, p256dh: json.keys?.p256dh, auth: json.keys?.auth }),
  })
}

export async function disablePush(): Promise<void> {
  const reg = await navigator.serviceWorker.getRegistration('/')
  const sub = await reg?.pushManager.getSubscription()
  if (!sub) return
  await api('/api/c/v1/push/subscriptions', {
    method: 'DELETE',
    body: JSON.stringify({ endpoint: sub.endpoint }),
  }).catch(() => undefined)
  await sub.unsubscribe()
}

export async function sendTestPush(): Promise<void> {
  await api('/api/c/v1/push/test', { method: 'POST' })
}

function sameKey(sub: PushSubscription, publicKey: string): boolean {
  const key = sub.options?.applicationServerKey
  if (!key) return true
  return bytesToBase64Url(new Uint8Array(key)) === publicKey
}

function base64UrlToBytes(s: string): Uint8Array<ArrayBuffer> {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4)
  const bin = atob(b64)
  const out = new Uint8Array(new ArrayBuffer(bin.length))
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
