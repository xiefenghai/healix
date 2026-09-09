/** C 端随访展示文案（与 B 端对齐的只读子集） */

const FOLLOWUP_TYPE_LABELS: Record<string, string> = {
  ROUTINE: '常规健康回访',
  PLAN_ADHERENCE: '方案执行随访',
  MEDICATION: '用药随访',
  SYMPTOM_METRIC: '指标/症状随访',
  ONBOARDING: '入组/首诊随访',
  OTHER: '其他随访',
}

const RECORD_TYPE_LABELS: Record<string, string> = {
  PERIODIC: '定期随访',
  METRIC_REVIEW: '指标异常处理',
  PLAN_NUDGE: '打卡跟进',
}

const METHOD_LABELS: Record<string, string> = {
  PHONE: '电话',
  FACE_TO_FACE: '面对面',
  ONLINE: '在线沟通',
  WECHAT: '微信',
  SMS: '短信',
  CLINIC: '门诊',
  HOME: '家庭',
}

const TARGET_LABELS: Record<string, string> = {
  PATIENT: '患者本人',
  PARENT: '父母',
  SPOUSE: '配偶',
  CHILD: '子女',
  FRIEND: '朋友',
  OTHER_RELATIVE: '其他亲友',
}

const NEXT_ACTION_LABELS: Record<string, string> = {
  SUPPLEMENT_ARCHIVE: '补档案',
  CREATE_PLAN: '制定方案',
  RETEST: '安排复测',
  NONE: '无需',
}

const LIFESTYLE_LEVEL_LABELS: Record<string, string> = {
  GOOD: '好',
  FAIR: '一般',
  POOR: '差',
}

const PLAN_SATISFACTION_LABELS: Record<string, string> = {
  SATISFIED: '满意',
  NEUTRAL: '一般',
  UNSATISFIED: '不满意',
}

const PLAN_BLOCKER_LABELS: Record<string, string> = {
  NO_TIME: '没时间',
  FORGOT: '忘记',
  TOO_HARD: '难度大',
  DISCOMFORT: '身体不适',
  NO_MOTIVATION: '缺动力',
  ENV_LIMIT: '环境限制',
  OTHER: '其他',
}

const MISSED_DOSE_FREQUENCY_LABELS: Record<string, string> = {
  NONE: '无漏服',
  RARELY: '偶有',
  SOMETIMES: '时有',
  OFTEN: '经常',
}

const MISSED_DOSE_REASON_LABELS: Record<string, string> = {
  FORGOT: '忘记服药',
  SIDE_EFFECT: '担心/出现副作用',
  RAN_OUT: '药品用完',
  COST: '费用问题',
  FEEL_BETTER: '自觉好转',
  TOO_MANY: '种类过多',
  OTHER: '其他',
}

const SYMPTOM_DISPOSITION_LABELS: Record<string, string> = {
  OBSERVE: '继续观察',
  RETEST: '安排复测',
  VISIT_CLINIC: '建议就诊',
  URGENT_REFERRAL: '紧急转诊',
  ADJUST_PLAN: '调整方案',
}

function lookup(map: Record<string, string>, code?: string | null) {
  if (!code) return ''
  return map[code] ?? code
}

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

const BASELINE_METRIC_LABELS: Record<string, string> = {
  BLOOD_GLUCOSE: '空腹血糖',
  BLOOD_PRESSURE_SYS: '收缩压',
  BLOOD_PRESSURE_DIA: '舒张压',
  HEART_RATE: '心率',
  HEIGHT: '身高',
  WEIGHT: '体重',
  WAIST: '腰围',
  TEMPERATURE: '体温',
}

export function formatBaselineMetric(code?: string | null) {
  return lookup(BASELINE_METRIC_LABELS, code)
}

export function formatFollowupType(code?: string | null) {
  return lookup(FOLLOWUP_TYPE_LABELS, code)
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

export function formatFollowupDateTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
