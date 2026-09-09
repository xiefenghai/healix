export const LAB_OCR_DRAFT_KEY = 'healix-lab-ocr-draft'

export interface LabOcrItem {
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  refLow?: number | null
  refHigh?: number | null
  abnormalFlag?: string
}

export interface LabOcrDraft {
  specimenType?: string
  sampledAt?: string
  reportedAt?: string
  note?: string
  items: LabOcrItem[]
  ignoredItems?: Array<{ rawName: string; reason?: string }>
  warnings?: string[]
  fromOcr?: boolean
}

export function saveLabOcrDraft(draft: LabOcrDraft) {
  sessionStorage.setItem(LAB_OCR_DRAFT_KEY, JSON.stringify({ ...draft, fromOcr: true }))
}

export function loadLabOcrDraft(): LabOcrDraft | null {
  const raw = sessionStorage.getItem(LAB_OCR_DRAFT_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as LabOcrDraft
  } catch {
    return null
  }
}

export function clearLabOcrDraft() {
  sessionStorage.removeItem(LAB_OCR_DRAFT_KEY)
}
