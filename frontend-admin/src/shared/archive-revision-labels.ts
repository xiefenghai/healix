import { formatRole } from './enums'
import {
  formatAllergyEntry,
  formatDiseaseEntry,
  formatSurgeryEntry,
  formatTransfusionEntry,
  formatVaccinationEntry,
  normalizePastHistory,
  serializePastHistory,
} from './past-history-options'

const PAST_HISTORY_SUB_LABELS: Record<string, string> = {
  diseases: '既往史 · 疾病史',
  surgeries: '既往史 · 手术及外伤',
  allergies: '既往史 · 过敏史',
  transfusions: '既往史 · 输血史',
  vaccinations: '既往史 · 预防接种',
  medicationNote: '既往史 · 用药史',
  status: '既往史 · 状态',
}

const FIELD_PATH_LABELS: Record<string, string> = {
  presentIllness: '现有疾病',
  presentIllnessOther: '现有疾病 · 其他',
  familyHistory: '家族史',
  familyHistoryItems: '家族史',
  pastHistory: '既往史',
  pastHistoryItems: '既往史',
  earlyCvFamilyHistory: '早发心血管病家族史',
  diet: '饮食情况',
  'diet.appetite': '饮食 · 食欲',
  'diet.habit': '饮食 · 习惯',
  'diet.type': '饮食 · 类型',
  'diet.preference': '饮食 · 偏好',
  'diet.note': '饮食 · 备注',
  exercise: '运动情况',
  'exercise.frequency': '运动 · 频率',
  'exercise.intensity': '运动 · 强度',
  'exercise.durationMin': '运动 · 时长(分钟)',
  'exercise.type': '运动 · 类型',
  'exercise.note': '运动 · 备注',
  sleep: '睡眠情况',
  'sleep.quality': '睡眠 · 质量',
  'sleep.hours': '睡眠 · 时长(小时)',
  'sleep.disorder': '睡眠 · 障碍',
  'sleep.note': '睡眠 · 备注',
  lifestyle: '生活习惯',
  'lifestyle.smoking': '吸烟',
  'lifestyle.smoking.status': '吸烟 · 状态',
  'lifestyle.smoking.cigarettesPerDay': '吸烟 · 每日支数',
  'lifestyle.smoking.years': '吸烟 · 烟龄(年)',
  'lifestyle.smoking.quitYear': '吸烟 · 戒烟年份',
  'lifestyle.smoking.note': '吸烟 · 备注',
  'lifestyle.drinking': '饮酒',
  'lifestyle.drinking.status': '饮酒 · 状态',
  'lifestyle.drinking.frequency': '饮酒 · 频率',
  'lifestyle.drinking.type': '饮酒 · 酒类',
  'lifestyle.drinking.amountPerDay': '饮酒 · 日均量',
  'lifestyle.drinking.note': '饮酒 · 备注',
  'lifestyle.note': '生活习惯 · 备注',
  diagnosisDate: '确诊时间',
  diabetesType: '糖尿病类型',
  hypertensionType: '高血压类型',
  hypertensionGrade: '高血压分级',
  cvRiskStratification: '高血压心血管风险分层',
  highestSystolic: '既往最高收缩压',
  highestDiastolic: '既往最高舒张压',
  symptoms: '症状',
  symptomsOther: '其它症状',
  emergencyComplications: '紧急并发症',
  emergencyComplicationsOther: '其它紧急并发症',
  hypoglycemiaReaction: '低血糖反应（公卫）',
  hypoglycemiaCountLastMonth: '近一个月发生过低血糖',
  hypoglycemiaHandling: '发生低血糖时如何处理',
  remark: '备注',
  drugName: '药品名称',
  usageMethod: '用法',
  frequency: '用药频率',
  doseAmount: '单次剂量',
  doseUnit: '剂量单位',
  startDate: '开始服药时间',
  stopDate: '停药时间',
  timingNote: '用药时机',
  courseDays: '疗程天数',
  hasAdverseReaction: '不良反应',
  status: '用药状态',
  source: '来源',
  intakeDate: '服药日期',
  timeSlot: '服药时段',
  intakeStatus: '依从性',
  note: '备注',
  metricType: '指标类型',
  value: '数值',
  unit: '单位',
  recordedAt: '测量时间',
  groupId: '成组ID',
  bpContext: '血压情境',
  mealContext: '餐次',
  specimenType: '标本类型',
  sampledAt: '采样时间',
  reportedAt: '报告时间',
  items: '明细项目',
  examType: '检查类型',
  examinedAt: '检查时间',
  conclusion: '结论',
  findings: '关键测量',
  execution: '执行计划',
  action: '操作',
  currentVersionId: '生效版本',
  versionNo: '版本号',
  'carePlan.diet': '饮食方案',
  'carePlan.exercise': '运动方案',
  'carePlan.execution': '执行计划',
  _created: '创建',
  _deleted: '删除',
}

const OPERATOR_TYPE_LABELS: Record<string, string> = {
  STAFF: '员工',
  PEOPLE: '患者',
  SYSTEM: '系统',
}

const BIZ_TYPE_LABELS: Record<string, string> = {
  BASIC_ARCHIVE: '基础档案',
  DISEASE_ARCHIVE: '病种档案',
  MEDICATION: '用药管理',
  MEDICATION_INTAKE: '用药依从性',
  METRIC: '指标数据',
  LAB: '检验报告',
  EXAM: '检查报告',
  CARE_PLAN: '管理方案',
}

const DISEASE_CODE_LABELS: Record<string, string> = {
  diabetes: '糖尿病',
  hypertension: '高血压',
}

const MEDICATION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '在用',
  STOPPED: '已停用',
}

const CARE_PLAN_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已发布',
  ARCHIVED: '已归档',
}

const MEDICATION_SOURCE_LABELS: Record<string, string> = {
  MANUAL: '健管录入',
  PRESCRIPTION: '外部医嘱',
  PATIENT: '患者自添加',
}

const TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '早',
  NOON: '午',
  EVENING: '晚',
  BEDTIME: '睡前',
  OTHER: '其他',
}

const INTAKE_STATUS_LABELS: Record<string, string> = {
  TAKEN: '已服',
  MISSED: '漏服',
  SKIPPED: '跳过',
}

export type RevisionOptionMaps = Record<string, Map<string, string>>

export function formatFieldPathLabel(fieldPath: string, bizKey?: string | null): string {
  if (fieldPath === 'symptoms') {
    if (bizKey === 'diabetes') return '糖尿病症状'
    if (bizKey === 'hypertension') return '症状表现'
  }
  if (FIELD_PATH_LABELS[fieldPath]) return FIELD_PATH_LABELS[fieldPath]
  if (fieldPath.startsWith('pastHistoryItems.')) {
    const sub = fieldPath.slice('pastHistoryItems.'.length)
    return PAST_HISTORY_SUB_LABELS[sub] ?? `既往史 · ${sub}`
  }
  return fieldPath
}

function formatPastHistorySubValue(subKey: string, value: unknown): string {
  if (value == null || value === '') return '空'
  if (subKey === 'status') {
    if (value === 'has') return '有既往史'
    if (value === 'none') return '无既往史'
    return String(value)
  }
  if (subKey === 'medicationNote') {
    return typeof value === 'string' ? value.trim() || '空' : String(value)
  }
  if (Array.isArray(value)) {
    if (!value.length) return '空'
    const formatters: Record<string, (entry: never) => string> = {
      diseases: formatDiseaseEntry,
      surgeries: formatSurgeryEntry,
      allergies: formatAllergyEntry,
      transfusions: formatTransfusionEntry,
      vaccinations: formatVaccinationEntry,
    }
    const fmt = formatters[subKey]
    if (fmt) {
      return value.map((item) => fmt(item as never)).join('、')
    }
    return value.map(String).join('、')
  }
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? '是' : '否'
  return JSON.stringify(value)
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

function parseStoredScalar(raw?: string | null): string | null {
  if (raw == null || raw === '') return null
  try {
    const v = JSON.parse(raw) as unknown
    if (v == null) return null
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  } catch {
    return raw
  }
}

function revisionDrugName(
  items?: Array<{ fieldPath: string; oldValue?: string; newValue?: string }>,
): string | null {
  const item = (items ?? []).find((i) => i.fieldPath === 'drugName')
  if (!item) return null
  return parseStoredScalar(item.newValue) ?? parseStoredScalar(item.oldValue)
}

function revisionFieldScalar(
  items: Array<{ fieldPath: string; oldValue?: string; newValue?: string }> | undefined,
  fieldPath: string,
): string | null {
  const item = (items ?? []).find((i) => i.fieldPath === fieldPath)
  if (!item) return null
  return parseStoredScalar(item.newValue) ?? parseStoredScalar(item.oldValue)
}

const METRIC_TYPE_LABELS: Record<string, string> = {
  BLOOD_PRESSURE_SYS: '收缩压',
  BLOOD_PRESSURE_DIA: '舒张压',
  BLOOD_GLUCOSE: '指尖血糖',
  HEIGHT: '身高',
  WEIGHT: '体重',
  WAIST: '腰围',
  HEART_RATE: '心率',
  TEMPERATURE: '体温',
  STEPS: '步数',
  SLEEP_HOURS: '睡眠',
}

const EXAM_TYPE_LABELS: Record<string, string> = {
  ECG: '心电图',
  UCG: '心脏彩超',
  CAROTID_US: '颈动脉彩超',
  FUNDUS: '眼底',
  PFT: '肺功能',
  CHEST_IMAGING: '胸部影像',
}

export function summarizeRevisionChanges(
  items?: Array<{ fieldPath: string; oldValue?: string; newValue?: string }>,
  bizType?: string,
  bizKey?: string | null,
): string {
  const visible = (items ?? []).filter(isMeaningfulRevisionItem)
  const deleted = visible.find((item) => item.fieldPath === '_deleted')
  if (deleted) {
    const drug = revisionDrugName(items)
    if (bizType === 'MEDICATION') return drug ? `删除用药 · ${drug}` : '删除用药'
    if (bizType === 'METRIC') {
      const mt = revisionFieldScalar(items, 'metricType')
      return mt ? `删除指标 · ${METRIC_TYPE_LABELS[mt] ?? mt}` : '删除指标'
    }
    if (bizType === 'LAB') return '删除检验报告'
    if (bizType === 'EXAM') {
      const et = revisionFieldScalar(items, 'examType')
      return et ? `删除检查 · ${EXAM_TYPE_LABELS[et] ?? et}` : '删除检查'
    }
    return '删除记录'
  }
  const created = visible.find((item) => item.fieldPath === '_created')
  if (created) {
    if (bizType === 'CARE_PLAN') return '创建/更新管理方案'
    if (bizType === 'DISEASE_ARCHIVE' && bizKey) {
      const diseaseLabel = DISEASE_CODE_LABELS[bizKey] ?? bizKey
      return `创建${diseaseLabel}档案`
    }
    if (bizType === 'MEDICATION') {
      const drug = revisionDrugName(items)
      return drug ? `添加用药 · ${drug}` : '添加用药'
    }
    if (bizType === 'MEDICATION_INTAKE') {
      const drug = revisionDrugName(items)
      return drug ? `记录依从性 · ${drug}` : '记录依从性'
    }
    if (bizType === 'METRIC') {
      const mt = revisionFieldScalar(items, 'metricType')
      return mt ? `录入指标 · ${METRIC_TYPE_LABELS[mt] ?? mt}` : '录入指标'
    }
    if (bizType === 'LAB') return '录入检验报告'
    if (bizType === 'EXAM') {
      const et = revisionFieldScalar(items, 'examType')
      return et ? `录入检查 · ${EXAM_TYPE_LABELS[et] ?? et}` : '录入检查'
    }
    return '创建档案'
  }
  if (!visible.length) return '档案更新'
  if (bizType === 'CARE_PLAN') {
    const action = revisionFieldScalar(items, 'action')
    if (action === 'DISCARD_DRAFT') return '丢弃方案草稿'
    if (action === 'CLONE_ACTIVE_TO_DRAFT') return '编辑已发布方案'
    if (action === 'CREATE_BLANK_DRAFT') return '新建空白方案草稿'
    if (visible.some((i) => i.fieldPath === 'status' || i.fieldPath === 'currentVersionId')) {
      return '发布管理方案'
    }
    return '更新管理方案'
  }
  if (bizType === 'MEDICATION') {
    const drug = revisionDrugName(items)
    const labels = visible
      .filter((item) => item.fieldPath !== 'drugName')
      .map((item) => formatFieldPathLabel(item.fieldPath))
    const focus = labels.length ? labels.slice(0, 2).join('、') : '用药信息'
    return drug ? `更新用药 · ${drug}（${focus}）` : `更新用药（${focus}）`
  }
  if (bizType === 'MEDICATION_INTAKE') {
    const drug = revisionDrugName(items)
    return drug ? `更新依从性 · ${drug}` : '更新依从性'
  }
  if (bizType === 'METRIC') {
    const mt = revisionFieldScalar(items, 'metricType')
    return mt ? `更新指标 · ${METRIC_TYPE_LABELS[mt] ?? mt}` : '更新指标'
  }
  if (bizType === 'LAB') return '更新检验报告'
  if (bizType === 'EXAM') {
    const et = revisionFieldScalar(items, 'examType')
    return et ? `更新检查 · ${EXAM_TYPE_LABELS[et] ?? et}` : '更新检查'
  }
  const labels = visible.map((item) => formatFieldPathLabel(item.fieldPath, bizKey))
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

/** 管理方案内容块：详情里折叠为「已更新」，避免铺开 diet.* 等叶子字段。 */
const CARE_PLAN_CONTENT_ROOTS = new Set(['diet', 'exercise', 'execution'])

/**
 * 修订详情展示用条目。管理方案发布/保存时内容 JSON 很大，按模块折叠。
 */
export function visibleRevisionItemsForDisplay(
  items?: Array<{ fieldPath: string; oldValue?: string; newValue?: string; oldDisplay?: string; newDisplay?: string }>,
  bizType?: string,
): Array<{ fieldPath: string; oldValue?: string; newValue?: string; oldDisplay?: string; newDisplay?: string }> {
  const visible = (items ?? []).filter(isMeaningfulRevisionItem)
  if (bizType !== 'CARE_PLAN') return visible

  const kept: Array<{
    fieldPath: string
    oldValue?: string
    newValue?: string
    oldDisplay?: string
    newDisplay?: string
  }> = []
  const sections = new Set<string>()

  for (const item of visible) {
    if (item.fieldPath === '_created') continue
    // 版本 ID 对健管无业务可读性，版本号已够用
    if (item.fieldPath === 'currentVersionId') continue

    const root = item.fieldPath.split('.')[0] || item.fieldPath
    if (CARE_PLAN_CONTENT_ROOTS.has(root)) {
      sections.add(root)
      continue
    }
    kept.push(item)
  }

  for (const root of ['exercise', 'diet', 'execution']) {
    if (!sections.has(root)) continue
    kept.push({
      fieldPath: `carePlan.${root}`,
      oldValue: undefined,
      newValue: JSON.stringify('UPDATED'),
      newDisplay: '已更新',
    })
  }
  return kept
}

function optionLabel(maps: RevisionOptionMaps, fieldPath: string, code: string): string {
  const map =
    maps[fieldPath] ??
    maps[fieldPath.replace(/\.status$/, '')] ??
    maps[fieldPath.split('.').slice(0, 2).join('.')] ??
    maps[fieldPath.split('.')[0] ?? '']
  return map?.get(code) ?? code
}

function formatParsedValue(
  fieldPath: string,
  value: unknown,
  optionMaps: RevisionOptionMaps,
  bizKey?: string | null,
  bizType?: string | null,
): string {
  if (fieldPath === '_created' || fieldPath === '_deleted') {
    return value ? '是' : '空'
  }

  if (fieldPath.startsWith('carePlan.') && value === 'UPDATED') {
    return '已更新'
  }

  if (value == null || value === '') return '空'

  if (fieldPath === 'presentIllness' && Array.isArray(value)) {
    const labels = value.map((c) => optionLabel(optionMaps, fieldPath, String(c)))
    return labels.length ? labels.join('、') : '空'
  }

  if (
    (fieldPath === 'symptoms' || fieldPath === 'emergencyComplications') &&
    Array.isArray(value)
  ) {
    const mapKey =
      bizKey === 'hypertension' || bizKey === 'diabetes'
        ? `${bizKey}.${fieldPath}`
        : fieldPath
    const labels = value.map((c) => optionLabel(optionMaps, mapKey, String(c)))
    return labels.length ? labels.join('、') : '空'
  }

  if (fieldPath === 'hasAdverseReaction' && typeof value === 'boolean') {
    return value ? '是' : '否'
  }

  if (fieldPath === 'pastHistoryItems' && value && typeof value === 'object') {
    const summary = serializePastHistory(normalizePastHistory(value))
    return summary || '空'
  }

  if (fieldPath.startsWith('pastHistoryItems.')) {
    const subKey = fieldPath.slice('pastHistoryItems.'.length)
    return formatPastHistorySubValue(subKey, value)
  }

  if (fieldPath === 'status' && typeof value === 'string') {
    if (bizType === 'CARE_PLAN') {
      return CARE_PLAN_STATUS_LABELS[value] ?? value
    }
    return MEDICATION_STATUS_LABELS[value] ?? value
  }

  if (fieldPath === 'timeSlot' && typeof value === 'string') {
    return TIME_SLOT_LABELS[value] ?? value
  }

  if (fieldPath === 'intakeStatus' && typeof value === 'string') {
    return INTAKE_STATUS_LABELS[value] ?? value
  }

  if (fieldPath === 'metricType' && typeof value === 'string') {
    return optionMaps.metricType?.get(value) ?? METRIC_TYPE_LABELS[value] ?? value
  }

  if (fieldPath === 'examType' && typeof value === 'string') {
    return optionMaps.examType?.get(value) ?? EXAM_TYPE_LABELS[value] ?? value
  }

  if (
    (fieldPath === 'bpContext' || fieldPath === 'extra.bpContext') &&
    typeof value === 'string'
  ) {
    return (
      optionMaps.bpContext?.get(value) ??
      (value === 'HOME' ? '家庭' : value === 'CLINIC' ? '诊室' : value)
    )
  }

  if (
    (fieldPath === 'mealContext' || fieldPath === 'extra.mealContext') &&
    typeof value === 'string'
  ) {
    return (
      optionMaps.mealContext?.get(value) ??
      ({ FASTING: '空腹', POSTPRANDIAL: '餐后', RANDOM: '随机' } as Record<string, string>)[value] ??
      value
    )
  }

  if (fieldPath === 'specimenType' && typeof value === 'string') {
    return value === 'URINE' ? '尿液' : value === 'BLOOD' ? '血液' : value
  }

  if (fieldPath === 'source' && typeof value === 'string') {
    const sourceLabels: Record<string, string> = {
      SELF: '用户录入',
      PATIENT: '用户录入',
      PATIENT_OCR: '用户录入-OCR识别',
      DEVICE: '设备同步',
      STAFF: '医护代录',
      STAFF_OCR: '医护代录-OCR识别',
      MANUAL: '医护代录',
      PRESCRIPTION: '外部医嘱',
      OCR: '医护代录-OCR识别',
    }
    return sourceLabels[value] ?? MEDICATION_SOURCE_LABELS[value] ?? value
  }

  if (fieldPath === 'abnormalFlag' && typeof value === 'string') {
    return ({ H: '偏高', L: '偏低', N: '正常' } as Record<string, string>)[value] ?? value
  }

  const optionFields = new Set([
    'diet.appetite',
    'diet.habit',
    'diet.type',
    'exercise.frequency',
    'exercise.intensity',
    'sleep.quality',
    'sleep.disorder',
    'lifestyle.smoking',
    'lifestyle.smoking.status',
    'lifestyle.drinking',
    'lifestyle.drinking.status',
    'lifestyle.drinking.frequency',
    'diabetesType',
    'hypertensionType',
    'hypertensionGrade',
    'cvRiskStratification',
    'hypoglycemiaReaction',
    'doseUnit',
    'usageMethod',
    'frequency',
    'metricType',
    'examType',
    'bpContext',
    'mealContext',
  ])
  if (optionFields.has(fieldPath) && typeof value === 'string') {
    return value ? optionLabel(optionMaps, fieldPath, value) : '空'
  }

  // 兼容旧扁平 smoking/drinking 字符串，或整对象替换
  if (
    (fieldPath === 'lifestyle.smoking' || fieldPath === 'lifestyle.drinking') &&
    value &&
    typeof value === 'object' &&
    !Array.isArray(value)
  ) {
    const obj = value as Record<string, unknown>
    const status = obj.status != null ? String(obj.status) : ''
    const statusLabel = status
      ? optionLabel(optionMaps, `${fieldPath}.status`, status)
      : ''
    const extras = Object.entries(obj)
      .filter(([k, v]) => k !== 'status' && v != null && v !== '')
      .map(([k, v]) => `${k}=${v}`)
    return [statusLabel || status, ...extras].filter(Boolean).join('，') || '空'
  }

  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) {
    if (!value.length) return '空'
    return value
      .map((v) => {
        if (v == null) return ''
        if (typeof v === 'object') {
          const obj = v as Record<string, unknown>
          const name = obj.name ?? obj.itemName ?? obj.label ?? obj.title
          if (name != null && String(name).trim()) return String(name)
          return JSON.stringify(v)
        }
        return String(v)
      })
      .filter(Boolean)
      .join('、')
  }
  return JSON.stringify(value)
}

/** 优先使用后端 oldDisplay/newDisplay，否则按字典与字段路径解析 */
export function formatRevisionValue(
  fieldPath: string,
  raw?: string | null,
  display?: string | null,
  optionMaps: RevisionOptionMaps = {},
  bizKey?: string | null,
  bizType?: string | null,
): string {
  if (display?.trim()) return display.trim()
  return formatParsedValue(fieldPath, parseRevisionRaw(raw), optionMaps, bizKey, bizType)
}

export function dictItemsToMap(items: Array<{ dictCode: string; dictCodeDesc: string }>): Map<string, string> {
  return new Map(items.map((o) => [o.dictCode, o.dictCodeDesc]))
}
