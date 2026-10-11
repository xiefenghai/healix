import { ref } from 'vue'

const TOKEN_KEY = 'healix_admin_token'
const AUD_KEY = 'healix_admin_aud'
const ROLES_KEY = 'healix_admin_roles'
const OPS_ROLE_KEY = 'healix_ops_role'
const ORG_ID_KEY = 'healix_admin_org_id'
const ORG_NAME_KEY = 'healix_admin_org_name'
const ENTRY_KEY = 'healix_admin_entry'

/** 机构上下文变更时递增，供布局菜单响应式刷新 */
export const orgSessionTick = ref(0)

export type AdminAud = 'b' | 'ops'
export type AdminEntry = 'ops' | 'tenant' | 'workspace'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getAud(): AdminAud | null {
  const v = localStorage.getItem(AUD_KEY)
  return v === 'b' || v === 'ops' ? v : null
}

export function getEntry(): AdminEntry | null {
  const v = localStorage.getItem(ENTRY_KEY)
  return v === 'ops' || v === 'tenant' || v === 'workspace' ? v : null
}

export function getRoles(): string[] {
  try {
    return JSON.parse(localStorage.getItem(ROLES_KEY) || '[]') as string[]
  } catch {
    return []
  }
}

export function hasRole(role: string): boolean {
  return getRoles().includes(role)
}

/** 从 JWT 解析当前员工 staffId（B 端机构工作台）。 */
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

export function getOpsRole(): string | null {
  return localStorage.getItem(OPS_ROLE_KEY)
}

export function isOpsSuperAdmin(): boolean {
  return getOpsRole() === 'SUPER_ADMIN'
}

export function getCurrentOrgId(): string | null {
  const v = localStorage.getItem(ORG_ID_KEY)
  return v || null
}

export function getCurrentOrgName(): string | null {
  return localStorage.getItem(ORG_NAME_KEY)
}

export function setSession(opts: {
  token: string
  aud: AdminAud
  entry: AdminEntry
  roles?: string[]
  opsRoleCode?: string | null
  currentOrgId?: string | number | null
  currentOrgName?: string | null
}) {
  localStorage.setItem(TOKEN_KEY, opts.token)
  localStorage.setItem(AUD_KEY, opts.aud)
  localStorage.setItem(ENTRY_KEY, opts.entry)
  localStorage.setItem(ROLES_KEY, JSON.stringify(opts.roles ?? []))
  if (opts.opsRoleCode) {
    localStorage.setItem(OPS_ROLE_KEY, opts.opsRoleCode)
  } else {
    localStorage.removeItem(OPS_ROLE_KEY)
  }
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
  localStorage.removeItem(AUD_KEY)
  localStorage.removeItem(ROLES_KEY)
  localStorage.removeItem(OPS_ROLE_KEY)
  localStorage.removeItem(ORG_ID_KEY)
  localStorage.removeItem(ORG_NAME_KEY)
  localStorage.removeItem(ENTRY_KEY)
  orgSessionTick.value++
}

let authRedirecting = false

/** Token 失效：清会话并跳转登录（并发请求只处理一次） */
export function redirectToLogin() {
  if (authRedirecting) return
  authRedirecting = true
  const entry = getEntry()
  clearSession()
  // 动态导入避免 router ↔ http 循环依赖
  void import('../router').then(({ default: router }) => {
    const path =
      entry === 'ops' || entry === 'tenant' || entry === 'workspace'
        ? `/login/${entry}`
        : '/entry'
    void router.replace(path).finally(() => {
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
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(path, { ...options, headers })
  const data = await res.json().catch(() => ({}))
  if (res.status === 401) {
    redirectToLogin()
    throw new Error(data.message || '登录信息已过期')
  }
  if (!res.ok || (typeof data.code === 'number' && data.code !== 0)) {
    throw new Error(data.message || `HTTP ${res.status}`)
  }
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
  const data = await res.json().catch(() => ({}))
  if (res.status === 401) {
    redirectToLogin()
    throw new Error(data.message || '登录信息已过期')
  }
  if (!res.ok || (typeof data.code === 'number' && data.code !== 0)) {
    throw new Error(data.message || `HTTP ${res.status}`)
  }
  return data as T
}
