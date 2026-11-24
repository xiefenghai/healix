/** C 端白标/开关用的租户提示；登录账号本身不再按租户隔离。 */
const TENANT_KEY = 'healix_c_tenant_id'

export function getCachedTenantId(): string | null {
  return localStorage.getItem(TENANT_KEY)
}

export function setCachedTenantId(tenantId: string) {
  localStorage.setItem(TENANT_KEY, tenantId)
}

export function clearCachedTenantId() {
  localStorage.removeItem(TENANT_KEY)
}

/**
 * 仅用于品牌/功能开关。登录、注册、OTP 不必再调。
 * bootstrap 可能返回 null（无可用租户时）。
 */
export async function resolveTenantId(
  api: <T>(path: string, options?: RequestInit) => Promise<T>,
): Promise<string | null> {
  const fromEnv = (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_C_TENANT_ID
  if (fromEnv) {
    setCachedTenantId(fromEnv)
    return fromEnv
  }
  const cached = getCachedTenantId()
  if (cached) return cached
  const res = await api<{ data: { tenantId?: string | null } }>('/api/c/v1/auth/bootstrap')
  const tenantId = res.data?.tenantId || null
  if (tenantId) setCachedTenantId(tenantId)
  return tenantId
}
