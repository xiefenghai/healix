/** 疾病评估（患病风险评估 / 严重等级分层）前端类型与展示辅助 */

export type AssessmentTone = 'danger' | 'warning' | 'success' | 'info' | 'muted'

export interface AssessmentDimension {
  code: string
  label: string
  value?: string
  tier?: string | null
  hint?: string | null
}

export interface AssessmentItem {
  code: string
  label: string
  value?: string
  points?: number | null
  rationale?: string | null
}

export interface AssessmentHit {
  code: string
  label: string
  detail?: string | null
  source?: string | null
}

export interface AssessmentUnchecked {
  field: string
  label: string
  questionnaire: boolean
}

export interface AssessmentFactorGroup {
  code: string
  title: string
  hits: AssessmentHit[]
  unchecked: AssessmentUnchecked[]
}

export interface AssessmentMissing {
  field: string
  label: string
  fillHint?: string | null
  questionnaire: boolean
}

export interface AssessmentComparison {
  label: string
  value?: string
  computed?: string
  consistent: boolean
  hint?: string | null
}

export interface AssessmentSnapshot {
  id: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  engineCode: string
  engineLabel: string
  diseaseCode: string
  diseaseLabel: string
  rulePackVersion?: string
  provisional: boolean
  status: 'COMPLETE' | 'INCOMPLETE' | string
  level?: string | null
  levelLabel?: string | null
  tier?: string | null
  tierLabel?: string | null
  tone?: AssessmentTone
  score?: number | null
  advice?: string | null
  dimensions: AssessmentDimension[]
  items: AssessmentItem[]
  factorGroups: AssessmentFactorGroup[]
  missing: AssessmentMissing[]
  comparisons: AssessmentComparison[]
  notes: string[]
  suspicion?: { diseaseCode: string; diseaseLabel: string; reason: string } | null
  guideline?: { name?: string; version?: string; publishedYear?: number } | null
  triggerSource?: string
  triggerLabel?: string
  assessedAt?: string
  legacy: boolean
}

export interface AssessmentGroup {
  diseaseCode: string
  diseaseLabel: string
  diagnosed: boolean
  diagnosedReason?: string | null
  hasDiseaseArchive: boolean
  engineCode: string
  engineLabel: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  applicable: boolean
  notApplicableReason?: string | null
  latest?: AssessmentSnapshot | null
}

export interface QuestionnaireRequest {
  id: string
  status: 'PENDING' | 'SUBMITTED' | 'EXPIRED' | 'CANCELLED'
  createdAt?: string
  expiresAt?: string
  submittedAt?: string | null
}

/** 问卷活动：发送请求或提交记录 */
export interface QuestionnaireActivity {
  kind: 'REQUEST' | 'SUBMISSION'
  id: string
  status?: string | null
  portal?: 'B' | 'C' | string | null
  title: string
  summary?: string | null
  answerCount?: number
  requestId?: string | null
  createdAt?: string | null
  expiresAt?: string | null
  submittedAt?: string | null
}

export interface AssessmentOverview {
  groups: AssessmentGroup[]
  lastQuestionnaire?: { id: string; portal: 'B' | 'C'; submittedAt: string } | null
  pendingRequest?: QuestionnaireRequest | null
  evaluatedAt?: string
  disclaimer?: string
}

export interface QuestionOption {
  code: string
  label: string
}

export interface Question {
  field: string
  label: string
  type: 'NUMBER' | 'BOOL' | 'ENUM' | 'MULTI'
  text: string
  unit?: string | null
  min?: number | null
  max?: number | null
  options: QuestionOption[]
  audience: 'PATIENT' | 'STAFF'
  required: boolean
  missing: boolean
  currentValue?: unknown
  currentText?: string | null
  engines: string[]
}

export interface QuestionnaireView {
  sections: Array<{ code: string; title: string; questions: Question[] }>
  hints: Array<{ field: string; label: string; hint: string; engines: string[] }>
  missingCount: number
  totalCount: number
  pendingRequest?: QuestionnaireRequest | null
  lastSubmittedAt?: string | null
}

export interface QuestionnaireConflict {
  field: string
  label: string
  currentText?: string | null
  newText?: string | null
}

export interface QuestionnaireSubmitResult {
  status: 'OK' | 'CONFLICT'
  conflicts: QuestionnaireConflict[]
  submissionId?: string | null
  overview?: AssessmentOverview | null
}

export interface RuleSheetSection {
  title: string
  headers?: string[] | null
  rows?: string[][] | null
  bullets?: string[] | null
}

export interface AssessmentRuleSheet {
  engineCode: string
  engineLabel: string
  category: 'RISK' | 'SEVERITY'
  categoryLabel: string
  diseaseCode: string
  diseaseLabel: string
  guidelineName?: string
  guidelineVersion?: string
  rulePackVersion: string
  provisional: boolean
  levels: Array<{ code: string; label: string; tier?: string | null; tierLabel?: string | null; tone?: AssessmentTone }>
  sheet: { summary?: string; sections: RuleSheetSection[]; notes?: string[] }
}

export const NONE_CODE = 'NONE'
export const UNKNOWN_CODE = 'UNKNOWN'

const SOURCE_LABELS: Record<string, string> = {
  ARCHIVE: '档案',
  METRIC: '指标',
  LAB: '检验',
  MEDICATION: '用药',
  QUESTIONNAIRE: '问卷',
  DERIVED: '推算',
}

export function sourceLabel(source?: string | null): string {
  return source ? SOURCE_LABELS[source] || source : ''
}

/** 映射到 hx-chip 修饰类 */
export function toneChip(tone?: string | null): string {
  if (tone === 'danger') return 'danger'
  if (tone === 'warning') return 'warn'
  return ''
}

export function tierTone(tier?: string | null): AssessmentTone {
  if (tier === 'HIGH' || tier === 'VERY_HIGH') return 'danger'
  if (tier === 'MID') return 'warning'
  if (tier === 'LOW') return 'success'
  return 'muted'
}

export function formatAssessTime(v?: string | null): string {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 16)
}

export function requestStatusText(req?: QuestionnaireRequest | null): string {
  if (!req) return ''
  switch (req.status) {
    case 'PENDING':
      return `已发送评估问卷，等待患者填写（有效至 ${formatAssessTime(req.expiresAt)}）`
    case 'SUBMITTED':
      return `患者已于 ${formatAssessTime(req.submittedAt)} 提交评估问卷`
    case 'EXPIRED':
      return `评估问卷已过期未填写（发送于 ${formatAssessTime(req.createdAt)}）`
    default:
      return ''
  }
}
