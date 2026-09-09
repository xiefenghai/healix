/**
 * 健康观测数据来源（指标 / 检验 / 检查）。
 * 与后端 HealthDataSourceEnum 对齐；B/C 端共用同一套文案。
 */
export type HealthDataSource =
  | 'PATIENT'
  | 'PATIENT_OCR'
  | 'STAFF'
  | 'STAFF_OCR'
  | 'DEVICE'
  /** @deprecated 历史检验手工，展示同 STAFF */
  | 'MANUAL'
  /** @deprecated 历史裸 OCR，需结合是否员工录入推断 */
  | 'OCR'
  /** @deprecated 历史指标用户录入，展示同 PATIENT */
  | 'SELF'
  | 'LIS'

const SOURCE_LABELS: Record<string, string> = {
  PATIENT: '用户录入',
  PATIENT_OCR: '用户录入-OCR识别',
  STAFF: '医护代录',
  STAFF_OCR: '医护代录-OCR识别',
  DEVICE: '设备同步',
  // 历史兼容
  SELF: '用户录入',
  MANUAL: '医护代录',
  OCR: '医护代录-OCR识别',
  LIS: '医护代录',
}

/** 统一展示文案。裸 OCR 默认按医护代录-OCR（历史 B 端为主）；有明确 PATIENT_OCR 则优先。 */
export function formatHealthDataSource(source?: string | null): string {
  if (!source) return '医护代录'
  const s = String(source).trim().toUpperCase()
  return SOURCE_LABELS[s] || source
}

export function isOcrSource(source?: string | null): boolean {
  const s = (source || '').toUpperCase()
  return s === 'STAFF_OCR' || s === 'PATIENT_OCR' || s === 'OCR'
}

export function isPatientSource(source?: string | null): boolean {
  const s = (source || '').toUpperCase()
  return s === 'PATIENT' || s === 'PATIENT_OCR' || s === 'SELF'
}
