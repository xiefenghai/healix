/** 随访展示文案与选项 */

export const FOLLOWUP_CONTACT_TARGET_OPTIONS = [
  { label: '患者本人', value: 'PATIENT' },
  { label: '父母', value: 'PARENT' },
  { label: '配偶', value: 'SPOUSE' },
  { label: '子女', value: 'CHILD' },
  { label: '朋友', value: 'FRIEND' },
  { label: '其他亲友', value: 'OTHER_RELATIVE' },
] as const

export const FOLLOWUP_METHOD_OPTIONS = [
  { label: '电话', value: 'PHONE' },
  { label: '面对面', value: 'FACE_TO_FACE' },
  { label: '在线沟通', value: 'ONLINE' },
  { label: '微信', value: 'WECHAT' },
  { label: '短信', value: 'SMS' },
  { label: '门诊', value: 'CLINIC' },
  { label: '家庭', value: 'HOME' },
] as const

/** 用户可建随访类型（content.followupType / FollowupType） */
export const FOLLOWUP_TYPE_OPTIONS = [
  { label: '常规健康回访', value: 'ROUTINE' },
  { label: '方案执行随访', value: 'PLAN_ADHERENCE' },
  { label: '用药随访', value: 'MEDICATION' },
  { label: '指标/症状随访', value: 'SYMPTOM_METRIC' },
  { label: '入组/首诊随访', value: 'ONBOARDING' },
  { label: '其他随访', value: 'OTHER' },
] as const

/** 记录通道（record_type） */
export const FOLLOWUP_RECORD_TYPE_OPTIONS = [
  { label: '定期随访', value: 'PERIODIC' },
  { label: '指标异常处理', value: 'METRIC_REVIEW' },
  { label: '打卡跟进', value: 'PLAN_NUDGE' },
] as const

export const ONBOARDING_NEXT_ACTION_OPTIONS = [
  { label: '补档案', value: 'SUPPLEMENT_ARCHIVE' },
  { label: '制定方案', value: 'CREATE_PLAN' },
  { label: '安排复测', value: 'RETEST' },
  { label: '无需', value: 'NONE' },
] as const

export const LIFESTYLE_LEVEL_OPTIONS = [
  { label: '好', value: 'GOOD' },
  { label: '一般', value: 'FAIR' },
  { label: '差', value: 'POOR' },
] as const

export const PLAN_SATISFACTION_OPTIONS = [
  { label: '满意', value: 'SATISFIED' },
  { label: '一般', value: 'NEUTRAL' },
  { label: '不满意', value: 'UNSATISFIED' },
] as const

/** 方案执行随访：主要未完成原因 */
export const PLAN_BLOCKER_OPTIONS = [
  { label: '没时间', value: 'NO_TIME' },
  { label: '忘记', value: 'FORGOT' },
  { label: '难度大', value: 'TOO_HARD' },
  { label: '身体不适', value: 'DISCOMFORT' },
  { label: '缺动力', value: 'NO_MOTIVATION' },
  { label: '环境限制', value: 'ENV_LIMIT' },
  { label: '其他', value: 'OTHER' },
] as const

/** 用药随访：漏服频次 */
export const MISSED_DOSE_FREQUENCY_OPTIONS = [
  { label: '无漏服', value: 'NONE' },
  { label: '偶有', value: 'RARELY' },
  { label: '时有', value: 'SOMETIMES' },
  { label: '经常', value: 'OFTEN' },
] as const

/** 用药随访：漏服原因 */
export const MISSED_DOSE_REASON_OPTIONS = [
  { label: '忘记服药', value: 'FORGOT' },
  { label: '担心/出现副作用', value: 'SIDE_EFFECT' },
  { label: '药品用完', value: 'RAN_OUT' },
  { label: '费用问题', value: 'COST' },
  { label: '自觉好转', value: 'FEEL_BETTER' },
  { label: '种类过多', value: 'TOO_MANY' },
  { label: '其他', value: 'OTHER' },
] as const

/** 指标/症状随访：处置结论 */
export const SYMPTOM_DISPOSITION_OPTIONS = [
  { label: '继续观察', value: 'OBSERVE' },
  { label: '安排复测', value: 'RETEST' },
  { label: '建议就诊', value: 'VISIT_CLINIC' },
  { label: '紧急转诊', value: 'URGENT_REFERRAL' },
  { label: '调整方案', value: 'ADJUST_PLAN' },
] as const

/** 入组随访：基线指标可选项（与 MetricTypeEnum 对齐） */
export const BASELINE_METRIC_OPTIONS = [
  { label: '空腹血糖', value: 'BLOOD_GLUCOSE', unit: 'mmol/L' },
  { label: '收缩压', value: 'BLOOD_PRESSURE_SYS', unit: 'mmHg' },
  { label: '舒张压', value: 'BLOOD_PRESSURE_DIA', unit: 'mmHg' },
  { label: '心率', value: 'HEART_RATE', unit: '次/分' },
  { label: '身高', value: 'HEIGHT', unit: 'cm' },
  { label: '体重', value: 'WEIGHT', unit: 'kg' },
  { label: '腰围', value: 'WAIST', unit: 'cm' },
  { label: '体温', value: 'TEMPERATURE', unit: '℃' },
] as const

/** 指标/症状随访：常见症状候选 */
export const SYMPTOM_TAG_OPTIONS = [
  '头晕',
  '头痛',
  '心悸',
  '胸闷',
  '气短',
  '乏力',
  '水肿',
  '视物模糊',
  '手足麻木',
  '多饮多尿',
] as const

export type BaselineMetricEntry = {
  metricType: string
  value: string
  unit?: string
}

export type FollowupSection = {
  /** 入组：已写回档案 */
  archiveWritten?: boolean
  diseaseCodes?: string[]
  nextAction?: string
  /** 入组：基线指标，办结时写入体征记录 */
  baselineMetrics?: BaselineMetricEntry[]
  adherenceNote?: string
  lifestyleNote?: string
  lifestyleLevel?: string
  planSatisfaction?: string
  unsatisfiedReason?: string
  symptomNote?: string
  content?: string
  /** 方案执行随访 */
  selfRatePct?: number | null
  mainBlocker?: string
  blockerNote?: string
  planChange?: string
  /** 用药随访 */
  missedDoseFrequency?: string
  missedDoseReason?: string
  hasAdverseReaction?: boolean | null
  adverseNote?: string
  needDoctorAdjust?: boolean
  /** 指标/症状随访 */
  symptoms?: string[]
  retestNote?: string
  disposition?: string
  /** 旧数据兼容展示 */
  archiveChecks?: string[]
  gapNote?: string
  diseaseChecked?: boolean
}

export function emptyFollowupSection(): FollowupSection {
  return {
    archiveWritten: false,
    diseaseCodes: [],
    nextAction: '',
    baselineMetrics: [],
    adherenceNote: '',
    lifestyleNote: '',
    lifestyleLevel: '',
    planSatisfaction: '',
    unsatisfiedReason: '',
    symptomNote: '',
    content: '',
    selfRatePct: null,
    mainBlocker: '',
    blockerNote: '',
    planChange: '',
    missedDoseFrequency: '',
    missedDoseReason: '',
    hasAdverseReaction: null,
    adverseNote: '',
    needDoctorAdjust: false,
    symptoms: [],
    retestNote: '',
    disposition: '',
  }
}

const TYPE_LABELS = Object.fromEntries(FOLLOWUP_TYPE_OPTIONS.map((o) => [o.value, o.label]))
const RECORD_TYPE_LABELS = Object.fromEntries(FOLLOWUP_RECORD_TYPE_OPTIONS.map((o) => [o.value, o.label]))
const METHOD_LABELS = Object.fromEntries(FOLLOWUP_METHOD_OPTIONS.map((o) => [o.value, o.label]))
const TARGET_LABELS = Object.fromEntries(FOLLOWUP_CONTACT_TARGET_OPTIONS.map((o) => [o.value, o.label]))
const NEXT_ACTION_LABELS = Object.fromEntries(ONBOARDING_NEXT_ACTION_OPTIONS.map((o) => [o.value, o.label]))
const LIFESTYLE_LEVEL_LABELS = Object.fromEntries(LIFESTYLE_LEVEL_OPTIONS.map((o) => [o.value, o.label]))
const PLAN_SATISFACTION_LABELS = Object.fromEntries(PLAN_SATISFACTION_OPTIONS.map((o) => [o.value, o.label]))

function lookup(map: Record<string, string>, code?: string | null) {
  if (!code) return ''
  return map[code] ?? code
}

export function formatFollowupType(code?: string | null) {
  return lookup(TYPE_LABELS, code)
}

export function formatFollowupRecordType(code?: string | null) {
  return lookup(RECORD_TYPE_LABELS, code)
}

export function formatFollowupMethod(code?: string | null) {
  return lookup(METHOD_LABELS, code)
}

export function formatFollowupContactTarget(code?: string | null) {
  return lookup(TARGET_LABELS, code)
}

export function formatNextAction(code?: string | null) {
  return lookup(NEXT_ACTION_LABELS, code)
}

export function formatLifestyleLevel(code?: string | null) {
  return lookup(LIFESTYLE_LEVEL_LABELS, code)
}

export function formatPlanSatisfaction(code?: string | null) {
  return lookup(PLAN_SATISFACTION_LABELS, code)
}

export function formatFollowupStatus(status?: string | null) {
  if (status === 'OPEN') return '待办'
  if (status === 'DONE') return '已完成'
  if (status === 'CANCELLED') return '已取消'
  return status || '-'
}

const STRUCTURED_TYPES = new Set([
  'ONBOARDING',
  'ROUTINE',
  'PLAN_ADHERENCE',
  'MEDICATION',
  'SYMPTOM_METRIC',
])

/** 有专属 section 表单的类型（其余走通用「随访内容」） */
export function usesStructuredSection(followupType?: string | null) {
  return !!followupType && STRUCTURED_TYPES.has(followupType)
}

const PLAN_BLOCKER_LABELS = Object.fromEntries(PLAN_BLOCKER_OPTIONS.map((o) => [o.value, o.label]))
const MISSED_DOSE_FREQUENCY_LABELS = Object.fromEntries(
  MISSED_DOSE_FREQUENCY_OPTIONS.map((o) => [o.value, o.label]),
)
const MISSED_DOSE_REASON_LABELS = Object.fromEntries(
  MISSED_DOSE_REASON_OPTIONS.map((o) => [o.value, o.label]),
)
const SYMPTOM_DISPOSITION_LABELS = Object.fromEntries(
  SYMPTOM_DISPOSITION_OPTIONS.map((o) => [o.value, o.label]),
)

export function formatPlanBlocker(code?: string | null) {
  return lookup(PLAN_BLOCKER_LABELS, code)
}

export function formatMissedDoseFrequency(code?: string | null) {
  return lookup(MISSED_DOSE_FREQUENCY_LABELS, code)
}

export function formatMissedDoseReason(code?: string | null) {
  return lookup(MISSED_DOSE_REASON_LABELS, code)
}

export function formatSymptomDisposition(code?: string | null) {
  return lookup(SYMPTOM_DISPOSITION_LABELS, code)
}

const BASELINE_METRIC_LABELS = Object.fromEntries(
  BASELINE_METRIC_OPTIONS.map((o) => [o.value, o.label]),
)

export function formatBaselineMetric(code?: string | null) {
  return lookup(BASELINE_METRIC_LABELS, code)
}

/** 前端必填校验（与后端一致；入组的档案写回由提交前 saveAll 保证） */
export function validateFollowupSection(
  followupType: string,
  section: FollowupSection,
): string | null {
  if (followupType === 'ONBOARDING') {
    if (!section.nextAction) return '请选择下次动作'
    const filled = (section.baselineMetrics || []).filter((m) => m.metricType || m.value?.trim())
    for (const m of filled) {
      if (!m.metricType) return '请选择基线指标类型'
      if (!m.value?.trim()) return '请填写基线指标数值'
      if (Number.isNaN(Number(m.value)) || Number(m.value) <= 0) return '基线指标数值须为大于 0 的数字'
    }
    const types = filled.map((m) => m.metricType)
    if (new Set(types).size !== types.length) return '基线指标类型重复'
    return null
  }
  if (followupType === 'ROUTINE') {
    if (!section.adherenceNote?.trim()) return '请填写近期打卡概况'
    if (!section.lifestyleLevel) return '请选择生活习惯执行情况'
    if (!section.planSatisfaction) return '请选择方案满意度'
    if (section.planSatisfaction === 'UNSATISFIED' && !section.unsatisfiedReason?.trim()) {
      return '不满意时请填写原因'
    }
    return null
  }
  if (followupType === 'PLAN_ADHERENCE') {
    const rate = section.selfRatePct
    if (rate == null || Number.isNaN(Number(rate))) return '请填写自评执行率（0-100）'
    if (Number(rate) < 0 || Number(rate) > 100) return '自评执行率须在 0-100 之间'
    if (!section.mainBlocker) return '请选择主要未完成原因'
    if (section.mainBlocker === 'OTHER' && !section.blockerNote?.trim()) {
      return '选择「其他」时请说明原因'
    }
    if (!section.planChange?.trim()) return '请填写拟调整项'
    return null
  }
  if (followupType === 'MEDICATION') {
    if (!section.missedDoseFrequency) return '请选择漏服频次'
    if (section.missedDoseFrequency !== 'NONE' && !section.missedDoseReason) {
      return '有漏服时请选择漏服原因'
    }
    if (section.hasAdverseReaction == null) return '请选择是否有不良反应'
    if (section.hasAdverseReaction && !section.adverseNote?.trim()) {
      return '有不良反应时请填写描述'
    }
    return null
  }
  if (followupType === 'SYMPTOM_METRIC') {
    if (!section.symptoms?.length && !section.symptomNote?.trim()) {
      return '请填写症状清单或症状说明'
    }
    if (!section.disposition) return '请选择处置结论'
    return null
  }
  if (!section.content?.trim()) return '请填写随访内容'
  return null
}

export function buildFollowupContentPayload(
  followupType: string,
  base: {
    contactTarget: string
    followupMethod: string
    guidance?: string
    suggestPlanAdjust?: boolean
  },
  section: FollowupSection,
) {
  const sectionPayload: Record<string, unknown> = {}
  if (followupType === 'ONBOARDING') {
    sectionPayload.nextAction = section.nextAction
    sectionPayload.archiveWritten = true
    if (section.diseaseCodes?.length) sectionPayload.diseaseCodes = section.diseaseCodes
    const baseline = (section.baselineMetrics || [])
      .filter((m) => m.metricType && m.value?.trim())
      .map((m) => ({ metricType: m.metricType, value: m.value.trim(), unit: m.unit || undefined }))
    if (baseline.length) sectionPayload.baselineMetrics = baseline
  } else if (followupType === 'ROUTINE') {
    sectionPayload.adherenceNote = section.adherenceNote?.trim()
    sectionPayload.lifestyleLevel = section.lifestyleLevel
    if (section.lifestyleNote?.trim()) sectionPayload.lifestyleNote = section.lifestyleNote.trim()
    sectionPayload.planSatisfaction = section.planSatisfaction
    if (section.unsatisfiedReason?.trim()) {
      sectionPayload.unsatisfiedReason = section.unsatisfiedReason.trim()
    }
    if (section.symptomNote?.trim()) sectionPayload.symptomNote = section.symptomNote.trim()
  } else if (followupType === 'PLAN_ADHERENCE') {
    sectionPayload.selfRatePct = Number(section.selfRatePct)
    sectionPayload.mainBlocker = section.mainBlocker
    if (section.blockerNote?.trim()) sectionPayload.blockerNote = section.blockerNote.trim()
    sectionPayload.planChange = section.planChange?.trim()
  } else if (followupType === 'MEDICATION') {
    sectionPayload.missedDoseFrequency = section.missedDoseFrequency
    if (section.missedDoseFrequency !== 'NONE' && section.missedDoseReason) {
      sectionPayload.missedDoseReason = section.missedDoseReason
    }
    sectionPayload.hasAdverseReaction = !!section.hasAdverseReaction
    if (section.adverseNote?.trim()) sectionPayload.adverseNote = section.adverseNote.trim()
    sectionPayload.needDoctorAdjust = !!section.needDoctorAdjust
  } else if (followupType === 'SYMPTOM_METRIC') {
    if (section.symptoms?.length) sectionPayload.symptoms = section.symptoms
    if (section.symptomNote?.trim()) sectionPayload.symptomNote = section.symptomNote.trim()
    if (section.retestNote?.trim()) sectionPayload.retestNote = section.retestNote.trim()
    sectionPayload.disposition = section.disposition
  } else {
    sectionPayload.content = section.content?.trim()
  }
  return {
    followupType,
    contactTarget: base.contactTarget,
    followupMethod: base.followupMethod,
    guidance: base.guidance?.trim() || undefined,
    suggestPlanAdjust: base.suggestPlanAdjust || undefined,
    section: sectionPayload,
  }
}
