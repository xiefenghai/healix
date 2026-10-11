/** C 端开发期租户：优先环境变量，否则走 bootstrap 接口。 */
const TENANT_KEY = 'healix_c_tenant_id'

export function getCachedTenantId(): string | null {
  return localStorage.getItem(TENANT_KEY)
}

export function setCachedTenantId(tenantId: string) {
  localStorage.setItem(TENANT_KEY, tenantId)
}

export async function resolveTenantId(
  api: <T>(path: string, options?: RequestInit) => Promise<T>,
): Promise<string> {
  const fromEnv = (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_C_TENANT_ID
  if (fromEnv) {
    setCachedTenantId(fromEnv)
    return fromEnv
  }
  const cached = getCachedTenantId()
  if (cached) return cached
  const res = await api<{ data: { tenantId: string } }>('/api/c/v1/auth/bootstrap')
  setCachedTenantId(res.data.tenantId)
  return res.data.tenantId
}
