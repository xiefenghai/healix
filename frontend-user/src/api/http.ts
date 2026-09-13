import { showToast } from 'vant'
import router from '../router'

const TOKEN_KEY = 'healix_c_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
  // 动态导入避免循环依赖（http ↔ care-chat-realtime）
  void import('../shared/care-chat-realtime').then((m) => m.startCareChatRealtime())
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  void import('../shared/care-chat-realtime').then((m) => m.stopCareChatRealtime())
}

let authRedirecting = false

function isAuthFailure(status: number, code?: number): boolean {
  return status === 401 || status === 403 || code === 401 || code === 403
}

/** Token 失效：提示并跳转登录（并发请求只处理一次） */
function handleAuthExpired() {
  if (authRedirecting) return
  authRedirecting = true
  clearToken()
  showToast('登录信息已过期')
  const current = router.currentRoute.value
  const redirect = current.path === '/login' ? undefined : current.fullPath
  void router
    .replace({
      path: '/login',
      query: redirect ? { redirect } : undefined,
    })
    .finally(() => {
      window.setTimeout(() => {
        authRedirecting = false
      }, 1500)
    })
}

async function parseJson(res: Response): Promise<any> {
  return res.json().catch(() => ({}))
}

function assertOk(res: Response, data: any) {
  const code = typeof data.code === 'number' ? data.code : undefined
  if (isAuthFailure(res.status, code)) {
    handleAuthExpired()
    throw new Error(data.message || '登录信息已过期')
  }
  if (!res.ok || (code != null && code !== 0)) {
    throw new Error(data.message || `HTTP ${res.status}`)
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (!headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(path, { ...options, headers })
  const data = await parseJson(res)
  assertOk(res, data)
  return data as T
}

export async function apiUpload<T>(path: string, file: File, fieldName = 'file'): Promise<T> {
  const form = new FormData()
  form.append(fieldName, file)
  const headers = new Headers()
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const res = await fetch(path, { method: 'POST', body: form, headers })
  const data = await parseJson(res)
  assertOk(res, data)
  return data as T
}
