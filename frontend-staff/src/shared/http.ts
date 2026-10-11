import { ref } from 'vue'
import { showToast } from 'vant'

const TOKEN_KEY = 'healix_staff_token'
const ROLES_KEY = 'healix_staff_roles'
const ORG_ID_KEY = 'healix_staff_org_id'
const ORG_NAME_KEY = 'healix_staff_org_name'

/** 机构上下文变更时递增，供布局响应式刷新 */
export const orgSessionTick = ref(0)

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getRoles(): string[] {
  try {
    return JSON.parse(localStorage.getItem(ROLES_KEY) || '[]') as string[]
  } catch {
    return []
  }
}

export function getCurrentOrgId(): string | null {
  return localStorage.getItem(ORG_ID_KEY)
}

export function getCurrentOrgName(): string | null {
  return localStorage.getItem(ORG_NAME_KEY)
}

/** 从 JWT 解析当前员工 staffId */
export function getStaffId(): string | null {
  const token = getToken()
  if (!token) return null
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'))
    const payload = JSON.parse(json) as { staffId?: unknown }
    return typeof payload.staffId === 'string' && payload.staffId ? payload.staffId : null
  } catch {
    return null
  }
}

export function setSession(opts: {
  token: string
  roles?: string[]
  currentOrgId?: string | number | null
  currentOrgName?: string | null
}) {
  localStorage.setItem(TOKEN_KEY, opts.token)
  localStorage.setItem(ROLES_KEY, JSON.stringify(opts.roles ?? []))
  if (opts.currentOrgId != null && opts.currentOrgId !== '') {
    localStorage.setItem(ORG_ID_KEY, String(opts.currentOrgId))
  } else {
    localStorage.removeItem(ORG_ID_KEY)
  }
  if (opts.currentOrgName) {
    localStorage.setItem(ORG_NAME_KEY, opts.currentOrgName)
  } else {
    localStorage.removeItem(ORG_NAME_KEY)
  }
  orgSessionTick.value++
}

export function updateOrgSession(token: string, orgId: string | number, orgName: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(ORG_ID_KEY, String(orgId))
  localStorage.setItem(ORG_NAME_KEY, orgName)
  orgSessionTick.value++
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLES_KEY)
  localStorage.removeItem(ORG_ID_KEY)
  localStorage.removeItem(ORG_NAME_KEY)
  orgSessionTick.value++
}

let authRedirecting = false

export function redirectToLogin() {
  if (authRedirecting) return
  authRedirecting = true
  clearSession()
  void import('../shared/care-chat-realtime').then((m) => m.stopCareChatRealtime())
  void import('../router').then(({ default: router }) => {
    void router.replace('/login').finally(() => {
      window.setTimeout(() => {
        authRedirecting = false
      }, 1500)
    })
  })
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (!headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(path, { ...options, headers })
  const data = await res.json().catch(() => ({}))
  if (res.status === 401) {
    showToast('登录信息已过期')
    redirectToLogin()
    throw new Error((data as { message?: string }).message || '登录信息已过期')
  }
  if (!res.ok || (typeof (data as { code?: number }).code === 'number' && (data as { code: number }).code !== 0)) {
    throw new Error((data as { message?: string }).message || `HTTP ${res.status}`)
  }
  return data as T
}
