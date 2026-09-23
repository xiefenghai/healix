/** 驾驶舱 / 患者抽屉共用的 OCR 核对类型与组装逻辑 */

export interface OcrLabItem {
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  refLow?: number | null
  refHigh?: number | null
  abnormalFlag?: string
}

export interface OcrLabDraft {
  specimenType?: string | null
  sampledAt?: string | null
  reportedAt?: string | null
  note?: string | null
  items: OcrLabItem[]
  ignoredItems?: Array<{ rawName: string; reason?: string }>
  warnings?: string[]
}

export interface OcrExamDraft {
  examType?: string | null
  examTypeName?: string | null
  examinedAt?: string | null
  conclusion?: string | null
  findings?: Record<string, unknown> | null
  ignoredFindings?: string[]
  warnings?: string[]
}

export interface OcrMedItem {
  drugName: string
  usageMethod?: string | null
  frequency?: string | null
  doseAmount?: string | null
  doseUnit?: string | null
  timingNote?: string | null
  courseDays?: number | null
  startDate?: string | null
}

export interface OcrMedDraft {
  items: OcrMedItem[]
  warnings?: string[]
}

export interface OcrReview {
  peopleId: string
  kind: 'LAB' | 'EXAM' | 'MED'
  title: string
  warnings: string[]
  imageUrl?: string
  lab?: OcrLabDraft | null
  exam?: OcrExamDraft | null
  med?: OcrMedDraft | null
  status: 'pending' | 'saving' | 'confirmed' | 'discarded'
  reportId?: string
}

export const MED_USAGE_OPTIONS = [
  { value: 'ORAL', label: '口服' },
  { value: 'INJECTION', label: '注射' },
  { value: 'INHALATION', label: '吸入' },
] as const

export const MED_TIMING_PRESETS = ['晨起', '饭前', '饭后', '睡前', '痛时服'] as const

export const MED_FREQUENCY_FALLBACK = [
  { value: 'QD', label: '每日1次' },
  { value: 'BID', label: '每日2次' },
  { value: 'TID', label: '每日3次' },
  { value: 'QID', label: '每日4次' },
  { value: 'QN', label: '每晚1次' },
  { value: 'QOD', label: '隔日1次' },
  { value: 'PRN', label: '必要时' },
] as const

export const MED_DOSE_UNIT_FALLBACK = [
  { value: 'G', label: 'g' },
  { value: 'MG', label: 'mg' },
  { value: 'ML', label: 'ml' },
  { value: 'TABLET', label: '片' },
  { value: 'CAPSULE', label: '粒' },
  { value: 'BAG', label: '袋' },
  { value: 'BOTTLE', label: '支' },
  { value: 'SPRAY', label: '喷' },
] as const

export type MedDictOption = { value: string; label: string }

/** OCR 时间统一成日期选择器可绑的 YYYY-MM-DDTHH:mm */
export function toPickerDateTime(raw?: string | null): string | null {
  if (!raw) return null
  const s = String(raw).trim().replace(' ', 'T').slice(0, 16)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(s) ? s : null
}

export function toApiDateTime(local?: string | null): string | undefined {
  if (!local) return undefined
  const s = String(local).trim()
  if (!s) return undefined
  return s.length === 16 ? `${s}:00` : s
}

export function formatOcrDt(raw?: string | null) {
  if (!raw) return '—'
  return raw.replace('T', ' ').slice(0, 16)
}

export function pickKnownCode(raw: string | null | undefined, known: Set<string>): string | null {
  if (!raw?.trim()) return null
  const code = raw.trim().toUpperCase()
  return known.has(code) ? code : null
}

export function cloneLabDraft(lab: OcrLabDraft): OcrLabDraft {
  return {
    specimenType: lab.specimenType ?? null,
    sampledAt: toPickerDateTime(lab.sampledAt),
    reportedAt: toPickerDateTime(lab.reportedAt),
    note: lab.note ?? null,
    items: (lab.items || []).map((i) => ({ ...i })),
    ignoredItems: lab.ignoredItems ? [...lab.ignoredItems] : [],
    warnings: lab.warnings ? [...lab.warnings] : [],
  }
}

export function cloneExamDraft(exam: OcrExamDraft): OcrExamDraft {
  return {
    examType: exam.examType ?? null,
    examTypeName: exam.examTypeName ?? null,
    examinedAt: toPickerDateTime(exam.examinedAt),
    conclusion: exam.conclusion ?? null,
    findings: exam.findings ? { ...exam.findings } : {},
    ignoredFindings: exam.ignoredFindings ? [...exam.ignoredFindings] : [],
    warnings: exam.warnings ? [...exam.warnings] : [],
  }
}

export function cloneMedDraft(
  med: OcrMedDraft,
  known: { usage: Set<string>; frequency: Set<string>; doseUnit: Set<string> },
): OcrMedDraft {
  return {
    items: (med.items || []).map((i) => ({
      ...i,
      usageMethod: pickKnownCode(i.usageMethod, known.usage),
      frequency: pickKnownCode(i.frequency, known.frequency),
      doseUnit: pickKnownCode(i.doseUnit, known.doseUnit),
      timingNote: i.timingNote?.trim() || null,
      courseDays: typeof i.courseDays === 'number' && i.courseDays > 0 ? i.courseDays : null,
    })),
    warnings: med.warnings ? [...med.warnings] : [],
  }
}

export function buildOcrReview(
  capability: string,
  extracted: unknown,
  peopleId: string,
  previewUrl: string | undefined,
  known: { usage: Set<string>; frequency: Set<string>; doseUnit: Set<string> },
): OcrReview | null {
  const raw = extracted as Record<string, unknown>
  if (!raw) return null

  if (raw.kind === 'LAB' || raw.kind === 'EXAM' || raw.kind === 'MED') {
    const kind = raw.kind as 'LAB' | 'EXAM' | 'MED'
    const title =
      typeof raw.title === 'string' && raw.title
        ? raw.title
        : kind === 'LAB'
          ? '检验单'
          : kind === 'MED'
            ? '用药单'
            : '检查单'
    return {
      peopleId,
      kind,
      title,
      warnings: Array.isArray(raw.warnings) ? (raw.warnings as string[]) : [],
      imageUrl: previewUrl,
      lab: kind === 'LAB' && raw.lab ? cloneLabDraft(raw.lab as OcrLabDraft) : null,
      exam: kind === 'EXAM' && raw.exam ? cloneExamDraft(raw.exam as OcrExamDraft) : null,
      med: kind === 'MED' && raw.med ? cloneMedDraft(raw.med as OcrMedDraft, known) : null,
      status: 'pending',
    }
  }

  if (capability === 'OCR_LAB') {
    const lab = raw as unknown as OcrLabDraft
    if (!lab.items?.length) return null
    return {
      peopleId,
      kind: 'LAB',
      title: '检验单',
      warnings: lab.warnings || [],
      imageUrl: previewUrl,
      lab: cloneLabDraft(lab),
      exam: null,
      status: 'pending',
    }
  }
  if (capability === 'OCR_EXAM') {
    const exam = raw as unknown as OcrExamDraft
    const hasFindings = exam.findings && Object.keys(exam.findings).length > 0
    if (!exam.examType && !exam.conclusion && !hasFindings) return null
    return {
      peopleId,
      kind: 'EXAM',
      title: exam.examTypeName || '检查单',
      warnings: exam.warnings || [],
      imageUrl: previewUrl,
      lab: null,
      exam: cloneExamDraft(exam),
      status: 'pending',
    }
  }
  if (capability === 'OCR_MED') {
    const med = raw as unknown as OcrMedDraft
    if (!med.items?.length) return null
    return {
      peopleId,
      kind: 'MED',
      title: '用药单',
      warnings: med.warnings || [],
      imageUrl: previewUrl,
      lab: null,
      exam: null,
      med: cloneMedDraft(med, known),
      status: 'pending',
    }
  }
  return null
}

export function buildConfirmBody(review: OcrReview): Record<string, unknown> {
  if (review.kind === 'LAB') {
    return {
      kind: 'LAB',
      lab: {
        specimenType: review.lab?.specimenType || undefined,
        sampledAt: toApiDateTime(review.lab?.sampledAt),
        reportedAt: toApiDateTime(review.lab?.reportedAt),
        note: review.lab?.note || undefined,
        items: (review.lab?.items || []).map((i) => ({
          itemCode: i.itemCode,
          itemName: i.itemName,
          valueNum: i.valueNum ?? undefined,
          valueText: i.valueText || undefined,
          unit: i.unit || undefined,
          refLow: i.refLow ?? undefined,
          refHigh: i.refHigh ?? undefined,
          abnormalFlag: i.abnormalFlag || undefined,
        })),
      },
    }
  }
  if (review.kind === 'MED') {
    return {
      kind: 'MED',
      med: {
        items: (review.med?.items || [])
          .filter((i) => i.drugName?.trim())
          .map((i) => ({
            drugName: i.drugName.trim(),
            usageMethod: i.usageMethod || 'ORAL',
            frequency: i.frequency || undefined,
            doseAmount: i.doseAmount || undefined,
            doseUnit: i.doseUnit || undefined,
            timingNote: i.timingNote?.trim() || undefined,
            courseDays: i.courseDays ?? undefined,
            startDate: i.startDate || undefined,
          })),
      },
    }
  }
  return {
    kind: 'EXAM',
    exam: {
      examType: review.exam?.examType,
      examTypeName: review.exam?.examTypeName || undefined,
      examinedAt: toApiDateTime(review.exam?.examinedAt),
      conclusion: review.exam?.conclusion || undefined,
      findings: review.exam?.findings || {},
    },
  }
}

export interface ReportReviewPreview {
  reportTitle?: string
  periodTypeLabel?: string
  staffComment?: string
  nextFocus?: string
  quarterAdvice?: string
  fromLlm?: boolean
  newlyGenerated?: boolean
  revised?: boolean
  note?: string
}

export function buildReportReview(extracted: unknown): ReportReviewPreview | null {
  const raw = extracted as Record<string, unknown>
  if (!raw) return null
  const staffComment = typeof raw.staffComment === 'string' ? raw.staffComment.trim() : ''
  if (!staffComment) return null
  return {
    reportTitle: typeof raw.reportTitle === 'string' ? raw.reportTitle : undefined,
    periodTypeLabel: typeof raw.periodTypeLabel === 'string' ? raw.periodTypeLabel : undefined,
    staffComment,
    nextFocus: typeof raw.nextFocus === 'string' ? raw.nextFocus.trim() : undefined,
    quarterAdvice: typeof raw.quarterAdvice === 'string' ? raw.quarterAdvice.trim() : undefined,
    fromLlm: raw.fromLlm === true,
    newlyGenerated: raw.newlyGenerated === true,
    revised: raw.revised === true,
    note: typeof raw.note === 'string' ? raw.note : undefined,
  }
}

export function reportStatusLine(preview: ReportReviewPreview): string {
  const title = preview.reportTitle || '管理报告'
  const head = preview.revised
    ? `已按你的意见修订「${title}」点评草稿`
    : preview.newlyGenerated
      ? `已生成「${title}」草稿并完成点评`
      : `已为「${title}」生成点评草稿`
  return `${head}。可继续对话调整寄语，或点下方按钮审阅发布。`
}

export function reportFocusItems(text?: string): string[] {
  if (!text?.trim()) return []
  return text
    .split(/\n+/)
    .map((l) => l.replace(/^\d+[、.．)\s]+/, '').trim())
    .filter(Boolean)
}
