export interface TenantListItem {
  tenantId: string
  code: string
  name: string
  status: string
  hasTenantAdmin: boolean
  createdAt: string
}

export interface StaffAccountListItem {
  staffId: string
  accountId: string
  username: string
  displayName: string
  mobile?: string | null
  title?: string | null
  status: string
  roles: string[]
  defaultOrgId?: string | null
  orgIds?: string[]
  createdAt: string
}

export interface PageResult<T> {
  total: number
  items: T[]
}
