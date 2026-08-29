import { formatRole } from './enums'

const FIELD_PATH_LABELS: Record<string, string> = {
  presentIllness: '现有疾病',
  presentIllnessOther: '现有疾病 · 其他',
  familyHistory: '家族史',
  familyHistoryItems: '家族史',
  pastHistory: '既往史',
  earlyCvFamilyHistory: '早发心血管病家族史',
  diet: '饮食情况',
  'diet.appetite': '饮食 · 食欲',
  'diet.preference': '饮食 · 偏好',
  'diet.note': '饮食 · 备注',
  exercise: '运动情况',
  'exercise.frequency': '运动 · 频率',
  'exercise.durationMin': '运动 · 时长(分钟)',
  'exercise.type': '运动 · 类型',
  sleep: '睡眠情况',
  'sleep.quality': '睡眠 · 质量',
  'sleep.hours': '睡眠 · 时长(小时)',
  'sleep.note': '睡眠 · 备注',
  lifestyle: '生活习惯',
  'lifestyle.smoking': '生活习惯 · 吸烟',
  'lifestyle.drinking': '生活习惯 · 饮酒',
  'lifestyle.note': '生活习惯 · 备注',
  diagnosisDate: '确诊时间',
  diabetesType: '糖尿病类型',
  typicalSymptoms: '典型症状（三多一少）',
  atypicalSymptoms: '不典型症状与并发症征兆',
}

const OPERATOR_TYPE_LABELS: Record<string, string> = {
  STAFF: '员工',
  PEOPLE: '患者',
  SYSTEM: '系统',
}

const BIZ_TYPE_LABELS: Record<string, string> = {
  BASIC_ARCHIVE: '基础档案',
  DISEASE_ARCHIVE: '病种档案',
}

const DISEASE_CODE_LABELS: Record<string, string> = {
  diabetes: '糖尿病',
  hypertension: '高血压',
}

export type RevisionOptionMaps = Record<string, Map<string, string>>

export function formatFieldPathLabel(fieldPath: string): string {
  return FIELD_PATH_LABELS[fieldPath] ?? fieldPath
}

export function formatRevisionOperatorType(operatorType: string): string {
  return OPERATOR_TYPE_LABELS[operatorType] ?? operatorType
}

/** 修订操作者类型/角色：STAFF 展示岗位（健管师、医生等），非 STAFF 展示患者/系统 */
export function formatRevisionOperatorLabel(
  operatorType: string,
  operatorRoleCode?: string | null,
): string {
  if (operatorType === 'STAFF' && operatorRoleCode) {
    return formatRole(operatorRoleCode)
  }
  return formatRevisionOperatorType(operatorType)
}

export function formatRevisionBizLabel(bizType?: string, bizKey?: string | null): string {
  const typeLabel = BIZ_TYPE_LABELS[bizType ?? ''] ?? bizType ?? '档案'
  if (bizType === 'DISEASE_ARCHIVE' && bizKey) {
    const diseaseLabel = DISEASE_CODE_LABELS[bizKey] ?? bizKey
    return `${typeLabel} · ${diseaseLabel}`
  }
  return typeLabel
}

export function summarizeRevisionChanges(
  items?: Array<{ fieldPath: string; oldValue?: string; newValue?: string }>,
): string {
  const visible = (items ?? []).filter(isMeaningfulRevisionItem)
  if (!visible.length) return '档案更新'
  const labels = visible.map((item) => formatFieldPathLabel(item.fieldPath))
  if (labels.length === 1) return `更新 ${labels[0]}`
  if (labels.length === 2) return `更新 ${labels.join('、')}`
  return `更新 ${labels.slice(0, 2).join('、')} 等 ${labels.length} 项`
}

export function formatRevisionOperator(
  operatorType: string,
  operatorId: string,
  operatorName?: string | null,
): string {
  const typeLabel = OPERATOR_TYPE_LABELS[operatorType] ?? operatorType
  const name = operatorName?.trim()
  if (name) return `${typeLabel} · ${name}`
  if (operatorId) return typeLabel
  return typeLabel
}

function parseRevisionRaw(raw?: string | null): unknown {
  if (raw == null || raw === '') return null
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return raw
  }
}

function isEmptyRevisionValue(raw?: string | null): boolean {
  const v = parseRevisionRaw(raw)
  if (v == null) return true
  if (v === '') return true
  if (Array.isArray(v) && v.length === 0) return true
  return false
}

export function isMeaningfulRevisionItem(item: { oldValue?: string; newValue?: string }): boolean {
  return !(isEmptyRevisionValue(item.oldValue) && isEmptyRevisionValue(item.newValue))
}

function optionLabel(maps: RevisionOptionMaps, fieldPath: string, code: string): string {
  const map = maps[fieldPath] ?? maps[fieldPath.split('.')[0] ?? '']
  return map?.get(code) ?? code
}

function formatParsedValue(
  fieldPath: string,
  value: unknown,
  optionMaps: RevisionOptionMaps,
): string {
  if (value == null || value === '') return '空'

  if (fieldPath === 'presentIllness' && Array.isArray(value)) {
    const labels = value.map((c) => optionLabel(optionMaps, fieldPath, String(c)))
    return labels.length ? labels.join('、') : '空'
  }

  if (
    (fieldPath === 'typicalSymptoms' || fieldPath === 'atypicalSymptoms') &&
    Array.isArray(value)
  ) {
    const labels = value.map((c) => optionLabel(optionMaps, fieldPath, String(c)))
    return labels.length ? labels.join('、') : '空'
  }

  const optionFields = new Set([
    'diet.appetite',
    'exercise.frequency',
    'sleep.quality',
    'lifestyle.smoking',
    'lifestyle.drinking',
    'diabetesType',
  ])
  if (optionFields.has(fieldPath) && typeof value === 'string') {
    return value ? optionLabel(optionMaps, fieldPath, value) : '空'
  }

  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) {
    return value.length ? value.map(String).join('、') : '空'
  }
  return JSON.stringify(value)
}

/** 优先使用后端 oldDisplay/newDisplay，否则按字典与字段路径解析 */
export function formatRevisionValue(
  fieldPath: string,
  raw?: string | null,
  display?: string | null,
  optionMaps: RevisionOptionMaps = {},
): string {
  if (display?.trim()) return display.trim()
  return formatParsedValue(fieldPath, parseRevisionRaw(raw), optionMaps)
}

export function dictItemsToMap(items: Array<{ dictCode: string; dictCodeDesc: string }>): Map<string, string> {
  return new Map(items.map((o) => [o.dictCode, o.dictCodeDesc]))
}
