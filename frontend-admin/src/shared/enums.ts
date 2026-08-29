/** 与后端 com.healix.core.*.enums 对齐的展示文案 */

export const OrgType = {
  GENERAL_HOSPITAL: 'GENERAL_HOSPITAL',
  SPECIALTY_HOSPITAL: 'SPECIALTY_HOSPITAL',
  TCM_GENERAL_HOSPITAL: 'TCM_GENERAL_HOSPITAL',
  COMMUNITY_HOSPITAL: 'COMMUNITY_HOSPITAL',
  OTHER: 'OTHER',
} as const

export const EnableStatus = {
  ACTIVE: 'ACTIVE',
  DISABLED: 'DISABLED',
} as const

export const TenantStatus = {
  ACTIVE: 'ACTIVE',
  SUSPENDED: 'SUSPENDED',
  CLOSED: 'CLOSED',
} as const

export const StaffRole = {
  TENANT_ADMIN: 'TENANT_ADMIN',
  CARE_MANAGER: 'CARE_MANAGER',
  DOCTOR: 'DOCTOR',
  TENANT_OPERATOR: 'TENANT_OPERATOR',
} as const

const ORG_TYPE_LABELS: Record<string, string> = {
  GENERAL_HOSPITAL: '综合性医院',
  SPECIALTY_HOSPITAL: '专科医院',
  TCM_GENERAL_HOSPITAL: '中医综合医院',
  COMMUNITY_HOSPITAL: '社区医院',
  OTHER: '其他',
}

const ENABLE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '正常',
  DISABLED: '已停用',
}

const TENANT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '正常',
  SUSPENDED: '已暂停',
  CLOSED: '已关闭',
}

const ROLE_LABELS: Record<string, string> = {
  TENANT_ADMIN: '租户管理员',
  CARE_MANAGER: '健管师',
  DOCTOR: '医生',
  TENANT_OPERATOR: '运营',
  ORG_ADMIN: '机构管理员（已废弃）',
}

export function formatOrgType(orgType: string) {
  return ORG_TYPE_LABELS[orgType] ?? orgType
}

export function formatEnableStatus(status: string) {
  return ENABLE_STATUS_LABELS[status] ?? status
}

export function formatTenantStatus(status: string) {
  return TENANT_STATUS_LABELS[status] ?? status
}

/** @deprecated 使用 formatEnableStatus */
export function formatStaffStatus(status: string) {
  return formatEnableStatus(status)
}

export function formatRole(roleCode: string) {
  return ROLE_LABELS[roleCode] ?? roleCode
}

export function tenantStatusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === TenantStatus.ACTIVE) return 'success'
  if (status === TenantStatus.SUSPENDED) return 'warning'
  if (status === TenantStatus.CLOSED) return 'info'
  return 'info'
}

export function enableStatusTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === EnableStatus.ACTIVE) return 'success'
  if (status === EnableStatus.DISABLED) return 'danger'
  return 'info'
}

/** @deprecated 使用 enableStatusTagType */
export function staffStatusTagType(status: string): 'success' | 'danger' | 'info' {
  return enableStatusTagType(status)
}

export const ORG_TYPE_OPTIONS = [
  { label: ORG_TYPE_LABELS.GENERAL_HOSPITAL, value: OrgType.GENERAL_HOSPITAL },
  { label: ORG_TYPE_LABELS.SPECIALTY_HOSPITAL, value: OrgType.SPECIALTY_HOSPITAL },
  { label: ORG_TYPE_LABELS.TCM_GENERAL_HOSPITAL, value: OrgType.TCM_GENERAL_HOSPITAL },
  { label: ORG_TYPE_LABELS.COMMUNITY_HOSPITAL, value: OrgType.COMMUNITY_HOSPITAL },
  { label: ORG_TYPE_LABELS.OTHER, value: OrgType.OTHER },
]

export const ENABLE_STATUS_OPTIONS = [
  { label: ENABLE_STATUS_LABELS.ACTIVE, value: EnableStatus.ACTIVE },
  { label: ENABLE_STATUS_LABELS.DISABLED, value: EnableStatus.DISABLED },
]

/** 机构工作台可新开的医护角色 */
export const ORG_CLINICAL_ROLE_OPTIONS = [
  { label: ROLE_LABELS.CARE_MANAGER, value: StaffRole.CARE_MANAGER },
  { label: ROLE_LABELS.DOCTOR, value: StaffRole.DOCTOR },
]

/** 租户管理员可分配的岗位角色 */
export const ASSIGNABLE_STAFF_ROLE_OPTIONS = [
  ...ORG_CLINICAL_ROLE_OPTIONS,
  { label: ROLE_LABELS.TENANT_OPERATOR, value: StaffRole.TENANT_OPERATOR },
]

const GENDER_LABELS: Record<string, string> = {
  MALE: '男',
  FEMALE: '女',
  UNKNOWN: '未知',
}

export function formatGender(gender: string | null | undefined) {
  if (!gender) return '-'
  return GENDER_LABELS[gender] ?? gender
}

/** 账号列表筛选（含管理员） */
export const STAFF_ROLE_FILTER_OPTIONS = [
  ...ASSIGNABLE_STAFF_ROLE_OPTIONS,
  { label: ROLE_LABELS.TENANT_ADMIN, value: StaffRole.TENANT_ADMIN },
]
