export const EXAM_OCR_DRAFT_KEY = 'healix-exam-ocr-draft'

export interface ExamOcrDraft {
  examType?: string | null
  examTypeName?: string | null
  examinedAt?: string | null
  conclusion?: string | null
  findings?: Record<string, unknown> | null
  ignoredFindings?: string[]
  warnings?: string[]
  fromOcr?: boolean
}

export function saveExamOcrDraft(draft: ExamOcrDraft) {
  sessionStorage.setItem(EXAM_OCR_DRAFT_KEY, JSON.stringify({ ...draft, fromOcr: true }))
}

export function loadExamOcrDraft(): ExamOcrDraft | null {
  const raw = sessionStorage.getItem(EXAM_OCR_DRAFT_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as ExamOcrDraft
  } catch {
    return null
  }
}

export function clearExamOcrDraft() {
  sessionStorage.removeItem(EXAM_OCR_DRAFT_KEY)
}
