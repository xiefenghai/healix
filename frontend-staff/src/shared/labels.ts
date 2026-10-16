/** 手机端用到的标签与随访表单常量（对齐 admin followup-labels） */

export const FOLLOWUP_TYPE_OPTIONS = [
  { label: '常规健康回访', value: 'ROUTINE' },
  { label: '方案执行随访', value: 'PLAN_ADHERENCE' },
  { label: '用药随访', value: 'MEDICATION' },
  { label: '指标/症状随访', value: 'SYMPTOM_METRIC' },
  { label: '入组/首诊随访', value: 'ONBOARDING' },
  { label: '其他随访', value: 'OTHER' },
] as const

export const TASK_TYPE_OPTIONS = [
  { value: 'TEAM_ASSIGN', label: '分配健管组' },
  { value: 'PLAN_CREATE', label: '制定方案' },
  { value: 'PLAN_REVIEW', label: '方案复核' },
  { value: 'PLAN_NUDGE', label: '打卡跟进' },
  { value: 'METRIC_ALERT', label: '指标异常' },
  { value: 'EXERCISE_ALERT', label: '运动不适' },
  { value: 'FOLLOW_UP', label: '定期随访' },
  { value: 'REPORT_REVIEW', label: '报告审阅' },
  { value: 'MED_FEEDBACK', label: '用药反馈' },
  { value: 'MED_VERIFY', label: '用药核对' },
  { value: 'ASSESSMENT_ESCALATION', label: '评估升级' },
  { value: 'ASSESSMENT_SUSPECTED', label: '疑似患病' },
  { value: 'ARCHIVE_SELF_REPORT', label: '患者自报更新' },
  { value: 'QUESTIONNAIRE_EXPIRED', label: '问卷未完成' },
] as const

export const EXERCISE_DISPOSITION_OPTIONS = [
  { label: '已电话联系', value: 'CONTACTED' },
  { label: '建议就医', value: 'ADVISED_VISIT' },
  { label: '已就医', value: 'VISITED' },
  { label: '误报', value: 'FALSE_ALARM' },
] as const

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

export const NUDGE_CHANNEL_OPTIONS = [
  { label: '电话', value: 'PHONE' },
  { label: '企微', value: 'WECOM' },
  { label: '当面', value: 'IN_PERSON' },
  { label: '其他', value: 'OTHER' },
] as const

export const NUDGE_RESULT_OPTIONS = [
  { label: '已联系到', value: 'REACHED' },
  { label: '未接通', value: 'NO_ANSWER' },
  { label: '拒接/拒绝', value: 'REFUSED' },
  { label: '其他', value: 'OTHER' },
] as const

const TASK_STATUS: Record<string, string> = {
  OPEN: '待处理',
  DONE: '已完成',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

const FOLLOWUP_STATUS: Record<string, string> = {
  OPEN: '未完成',
  DONE: '已完成',
  CANCELLED: '已取消',
}

export function formatTaskStatus(code?: string | null) {
  if (!code) return ''
  return TASK_STATUS[code] ?? code
}

export function formatFollowupStatus(code?: string | null) {
  if (!code) return ''
  return FOLLOWUP_STATUS[code] ?? code
}

export function formatFollowupType(code?: string | null) {
  if (!code) return ''
  return FOLLOWUP_TYPE_OPTIONS.find((o) => o.value === code)?.label ?? code
}

export function formatFollowupMethod(code?: string | null) {
  if (!code) return ''
  return FOLLOWUP_METHOD_OPTIONS.find((o) => o.value === code)?.label ?? code
}

export function formatFollowupContactTarget(code?: string | null) {
  if (!code) return ''
  return FOLLOWUP_CONTACT_TARGET_OPTIONS.find((o) => o.value === code)?.label ?? code
}

export function buildRoutineFollowupPayload(input: {
  contactTarget: string
  followupMethod: string
  guidance?: string
  adherenceNote: string
  lifestyleLevel: string
  planSatisfaction: string
  lifestyleNote?: string
  unsatisfiedReason?: string
  symptomNote?: string
  suggestPlanAdjust?: boolean
}) {
  const section: Record<string, unknown> = {
    adherenceNote: input.adherenceNote.trim(),
    lifestyleLevel: input.lifestyleLevel,
    planSatisfaction: input.planSatisfaction,
  }
  if (input.lifestyleNote?.trim()) section.lifestyleNote = input.lifestyleNote.trim()
  if (input.unsatisfiedReason?.trim()) section.unsatisfiedReason = input.unsatisfiedReason.trim()
  if (input.symptomNote?.trim()) section.symptomNote = input.symptomNote.trim()

  return {
    followupType: 'ROUTINE',
    contactTarget: input.contactTarget,
    followupMethod: input.followupMethod,
    guidance: input.guidance?.trim() || undefined,
    suggestPlanAdjust: !!input.suggestPlanAdjust,
    section,
  }
}

export function validateRoutineForm(input: {
  contactTarget: string
  followupMethod: string
  adherenceNote: string
  lifestyleLevel: string
  planSatisfaction: string
  unsatisfiedReason?: string
}): string | null {
  if (!input.contactTarget) return '请选择联系对象'
  if (!input.followupMethod) return '请选择随访方式'
  if (!input.adherenceNote.trim()) return '请填写近期打卡概况'
  if (!input.lifestyleLevel) return '请选择生活习惯执行情况'
  if (!input.planSatisfaction) return '请选择方案满意度'
  if (input.planSatisfaction === 'UNSATISFIED' && !input.unsatisfiedReason?.trim()) {
    return '不满意时请填写原因'
  }
  return null
}
