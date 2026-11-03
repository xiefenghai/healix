import { showToast } from 'vant'
import router from '../router'

const TOKEN_KEY = 'healix_c_token'
const AUTH_MODE_KEY = 'healix_c_auth_mode'

export type AuthMode = 'session' | 'intake_link'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getAuthMode(): AuthMode {
  return localStorage.getItem(AUTH_MODE_KEY) === 'intake_link' ? 'intake_link' : 'session'
}

export function setAuthMode(mode: AuthMode) {
  if (mode === 'intake_link') {
    localStorage.setItem(AUTH_MODE_KEY, 'intake_link')
  } else {
    localStorage.removeItem(AUTH_MODE_KEY)
  }
}

export function setToken(token: string, mode: AuthMode = 'session') {
  setAuthMode(mode)
  localStorage.setItem(TOKEN_KEY, token)
  // 首诊链接会话不启实时聊天
  if (mode === 'intake_link') return
  // 动态导入避免循环依赖（http ↔ care-chat-realtime）
  void import('../shared/care-chat-realtime').then((m) => m.startCareChatRealtime())
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(AUTH_MODE_KEY)
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
  const intakeLink = getAuthMode() === 'intake_link'
  clearToken()
  showToast(intakeLink ? '链接失效，请联系健管师重发' : '登录信息已过期')
  const current = router.currentRoute.value
  if (intakeLink) {
    void router.replace({ path: '/home' }).finally(() => {
      window.setTimeout(() => {
        authRedirecting = false
      }, 1500)
    })
    return
  }
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

export async function apiUpload<T>(
  path: string,
  file: File | File[],
  fieldName = 'file',
  fields?: Record<string, string>,
): Promise<T> {
  const form = new FormData()
  for (const f of Array.isArray(file) ? file : [file]) form.append(fieldName, f)
  for (const [k, v] of Object.entries(fields ?? {})) form.append(k, v)
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
