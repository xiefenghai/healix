/** 评估问卷（患者端只填写，不展示评估结果） */

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
  required: boolean
  missing: boolean
  currentValue?: unknown
  currentText?: string | null
}

export interface QuestionnaireRequest {
  id: string
  status: 'PENDING' | 'SUBMITTED' | 'EXPIRED' | 'CANCELLED'
  createdAt?: string
  expiresAt?: string
}

export interface QuestionnaireView {
  sections: Array<{ code: string; title: string; questions: Question[] }>
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
}

export const NONE_CODE = 'NONE'
export const UNKNOWN_CODE = 'UNKNOWN'
