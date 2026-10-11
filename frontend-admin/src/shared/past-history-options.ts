/** 既往史结构化条目（对齐临床 EMR 六大类，MVP 不含 ICD-10 编码） */

export type PastHistoryStatus = '' | 'none' | 'has'

export type SurgeryType = 'SURGERY' | 'TRAUMA' | 'ACCIDENT'

export type AllergyCategory = 'DRUG' | 'FOOD' | 'ENVIRONMENT' | 'OTHER'

export interface PastHistoryDiseaseEntry {
  code?: string
  name: string
  year?: string
  note?: string
}

export interface PastHistorySurgeryEntry {
  type: SurgeryType
  name: string
  date?: string
  hospital?: string
  note?: string
}

export interface PastHistoryAllergyEntry {
  category: AllergyCategory
  allergen: string
  reaction?: string
}

export interface PastHistoryTransfusionEntry {
  date?: string
  hasReaction: boolean
  note?: string
}

export interface PastHistoryVaccinationEntry {
  vaccine: string
  date?: string
  note?: string
}

export interface PastHistoryStructured {
  status: PastHistoryStatus
  diseases: PastHistoryDiseaseEntry[]
  surgeries: PastHistorySurgeryEntry[]
  allergies: PastHistoryAllergyEntry[]
  transfusions: PastHistoryTransfusionEntry[]
  vaccinations: PastHistoryVaccinationEntry[]
  /** 既往长期用药补充说明；在用药管理模块维护当前用药 */
  medicationNote?: string
}

export const SURGERY_TYPE_OPTIONS: Array<{ value: SurgeryType; label: string }> = [
  { value: 'SURGERY', label: '手术' },
  { value: 'TRAUMA', label: '外伤' },
  { value: 'ACCIDENT', label: '意外事故' },
]

export const ALLERGY_CATEGORY_OPTIONS: Array<{ value: AllergyCategory; label: string }> = [
  { value: 'DRUG', label: '药物' },
  { value: 'FOOD', label: '食物' },
  { value: 'ENVIRONMENT', label: '环境' },
  { value: 'OTHER', label: '其他' },
]

export const COMMON_VACCINES = [
  '乙肝疫苗',
  '甲肝疫苗',
  '流感疫苗',
  '肺炎球菌疫苗',
  '带状疱疹疫苗',
  'HPV疫苗',
  '狂犬病疫苗',
  '新冠疫苗',
  '百白破疫苗',
  '麻疹疫苗',
  '水痘疫苗',
]

const surgeryTypeLabel = new Map(SURGERY_TYPE_OPTIONS.map((o) => [o.value, o.label]))
const allergyCategoryLabel = new Map(ALLERGY_CATEGORY_OPTIONS.map((o) => [o.value, o.label]))

export function emptyPastHistory(): PastHistoryStructured {
  return {
    status: '',
    diseases: [],
    surgeries: [],
    allergies: [],
    transfusions: [],
    vaccinations: [],
    medicationNote: '',
  }
}

export function clonePastHistory(data: PastHistoryStructured): PastHistoryStructured {
  return {
    status: data.status,
    diseases: data.diseases.map((d) => ({ ...d })),
    surgeries: data.surgeries.map((s) => ({ ...s })),
    allergies: data.allergies.map((a) => ({ ...a })),
    transfusions: data.transfusions.map((t) => ({ ...t })),
    vaccinations: data.vaccinations.map((v) => ({ ...v })),
    medicationNote: data.medicationNote ?? '',
  }
}

export function pastHistoryHasContent(data: PastHistoryStructured): boolean {
  return (
    data.diseases.length > 0 ||
    data.surgeries.length > 0 ||
    data.allergies.length > 0 ||
    data.transfusions.length > 0 ||
    data.vaccinations.length > 0 ||
    !!data.medicationNote?.trim()
  )
}

export function normalizePastHistory(raw: unknown): PastHistoryStructured {
  const base = emptyPastHistory()
  if (!raw || typeof raw !== 'object') return base
  const o = raw as Record<string, unknown>
  base.diseases = Array.isArray(o.diseases) ? (o.diseases as PastHistoryDiseaseEntry[]) : []
  base.surgeries = Array.isArray(o.surgeries) ? (o.surgeries as PastHistorySurgeryEntry[]) : []
  base.allergies = Array.isArray(o.allergies) ? (o.allergies as PastHistoryAllergyEntry[]) : []
  base.transfusions = Array.isArray(o.transfusions) ? (o.transfusions as PastHistoryTransfusionEntry[]) : []
  base.vaccinations = Array.isArray(o.vaccinations) ? (o.vaccinations as PastHistoryVaccinationEntry[]) : []
  base.medicationNote = String(o.medicationNote ?? '')
  if (pastHistoryHasContent(base) || o.status === 'has') {
    base.status = 'has'
  } else if (o.status === 'none') {
    base.status = 'none'
  } else {
    base.status = ''
  }
  return base
}

function splitLegacyTags(text: string): string[] {
  if (!text?.trim()) return []
  return text
    .split(/[、,，;；\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 从旧版顿号分隔标签迁移为疾病史自由条目 */
export function migratePastHistoryFromTags(tags: string[]): PastHistoryStructured {
  const data = emptyPastHistory()
  if (!tags.length) return data
  data.status = 'has'
  data.diseases = tags.map((name) => ({ name: name.trim() })).filter((d) => d.name)
  return data
}

const ALLERGY_LABEL_TO_CATEGORY = new Map(
  ALLERGY_CATEGORY_OPTIONS.map((o) => [o.label, o.value]),
)

function parseDiseaseChunk(text: string): PastHistoryDiseaseEntry | null {
  const raw = text.trim()
  if (!raw) return null
  const withYear = raw.match(/^(.+?)（([^）]+)）(?:；(.*))?$/)
  if (withYear) {
    return {
      name: withYear[1].trim(),
      year: withYear[2].trim(),
      note: withYear[3]?.trim() || undefined,
    }
  }
  const withNote = raw.match(/^(.+?)；(.+)$/)
  if (withNote) {
    return { name: withNote[1].trim(), note: withNote[2].trim() }
  }
  return { name: raw }
}

function parseAllergyChunk(text: string): PastHistoryAllergyEntry | null {
  const raw = text.trim()
  if (!raw) return null
  const withReaction = raw.match(/^([^：]+)：(.+?)（([^）]+)）$/)
  if (withReaction) {
    return {
      category: ALLERGY_LABEL_TO_CATEGORY.get(withReaction[1].trim()) ?? 'OTHER',
      allergen: withReaction[2].trim(),
      reaction: withReaction[3].trim(),
    }
  }
  const plain = raw.match(/^([^：]+)：(.+)$/)
  if (plain) {
    return {
      category: ALLERGY_LABEL_TO_CATEGORY.get(plain[1].trim()) ?? 'OTHER',
      allergen: plain[2].trim(),
    }
  }
  return { category: 'OTHER', allergen: raw }
}

/** 从 serializePastHistory 生成的摘要中解析各分类（用于结构化字段缺失时的补全） */
export function parsePastHistorySummary(summary: string): Partial<PastHistoryStructured> {
  const result: Partial<PastHistoryStructured> = {}
  if (!summary.trim()) return result

  const sectionRegex = /【([^】]+)】([^【]*)/g
  let match: RegExpExecArray | null
  while ((match = sectionRegex.exec(summary)) !== null) {
    const label = match[1].trim()
    const body = match[2].replace(/；\s*$/, '').trim()
    if (!body) continue

    if (label === '疾病史') {
      result.diseases = body.split(/、/).map(parseDiseaseChunk).filter((d): d is PastHistoryDiseaseEntry => !!d)
    } else if (label === '过敏史') {
      result.allergies = body.split(/、/).map(parseAllergyChunk).filter((a): a is PastHistoryAllergyEntry => !!a)
    } else if (label === '用药史') {
      result.medicationNote = body
    }
  }
  return result
}

/** 摘要中有而结构化对象中缺失的分类，从摘要补全 */
export function mergePastHistoryFromSummary(
  data: PastHistoryStructured,
  summary: string,
): PastHistoryStructured {
  const parsed = parsePastHistorySummary(summary)
  const next = clonePastHistory(data)
  if (!next.diseases.length && parsed.diseases?.length) next.diseases = parsed.diseases
  if (!next.allergies.length && parsed.allergies?.length) next.allergies = parsed.allergies
  if (!next.medicationNote?.trim() && parsed.medicationNote?.trim()) {
    next.medicationNote = parsed.medicationNote.trim()
  }
  if (pastHistoryHasContent(next)) next.status = 'has'
  return next
}

/** 从 content_json 加载既往史（兼容旧文本、摘要补全） */
export function resolvePastHistoryFromContent(content: Record<string, unknown>): PastHistoryStructured {
  const summary = String(content.pastHistory ?? '')
  const topStatus = String(content.pastHistoryStatus ?? '')
  let raw: unknown = content.pastHistoryItems
  if (typeof raw === 'string' && raw.trim()) {
    try {
      raw = JSON.parse(raw) as unknown
    } catch {
      raw = null
    }
  }

  let data: PastHistoryStructured
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    data = normalizePastHistory(raw)
  } else if (summary.trim()) {
    data = migratePastHistoryFromTags(splitLegacyTags(summary))
  } else if (topStatus === 'none' || topStatus === 'has') {
    data = emptyPastHistory()
    data.status = topStatus
  } else {
    data = emptyPastHistory()
  }

  if (summary.includes('【')) {
    data = mergePastHistoryFromSummary(data, summary)
  }
  return data
}

export function formatDiseaseEntry(entry: PastHistoryDiseaseEntry): string {
  let s = entry.name
  if (entry.year?.trim()) s += `（${entry.year.trim()}）`
  if (entry.note?.trim()) s += `；${entry.note.trim()}`
  return s
}

export function formatSurgeryEntry(entry: PastHistorySurgeryEntry): string {
  const type = surgeryTypeLabel.get(entry.type) ?? entry.type
  const parts = [type, entry.name]
  if (entry.date?.trim()) parts.push(entry.date.trim())
  if (entry.hospital?.trim()) parts.push(`@${entry.hospital.trim()}`)
  let s = parts.filter(Boolean).join(' · ')
  if (entry.note?.trim()) s += `；${entry.note.trim()}`
  return s
}

export function formatAllergyEntry(entry: PastHistoryAllergyEntry): string {
  const cat = allergyCategoryLabel.get(entry.category) ?? entry.category
  let s = `${cat}：${entry.allergen}`
  if (entry.reaction?.trim()) s += `（${entry.reaction.trim()}）`
  return s
}

export function formatTransfusionEntry(entry: PastHistoryTransfusionEntry): string {
  const parts: string[] = []
  if (entry.date?.trim()) parts.push(entry.date.trim())
  parts.push(entry.hasReaction ? '有输血反应' : '无输血反应')
  if (entry.note?.trim()) parts.push(entry.note.trim())
  return parts.join(' · ')
}

export function formatVaccinationEntry(entry: PastHistoryVaccinationEntry): string {
  let s = entry.vaccine
  if (entry.date?.trim()) s += `（${entry.date.trim()}）`
  if (entry.note?.trim()) s += `；${entry.note.trim()}`
  return s
}

/** 持久化摘要文本（修订历史 / 全文检索） */
export function serializePastHistory(data: PastHistoryStructured): string {
  if (data.status !== 'has' && !pastHistoryHasContent(data)) return ''
  const sections: string[] = []
  if (data.diseases.length) {
    sections.push(`【疾病史】${data.diseases.map(formatDiseaseEntry).join('、')}`)
  }
  if (data.surgeries.length) {
    sections.push(`【手术及外伤】${data.surgeries.map(formatSurgeryEntry).join('、')}`)
  }
  if (data.allergies.length) {
    sections.push(`【过敏史】${data.allergies.map(formatAllergyEntry).join('、')}`)
  }
  if (data.transfusions.length) {
    sections.push(`【输血史】${data.transfusions.map(formatTransfusionEntry).join('、')}`)
  }
  if (data.vaccinations.length) {
    sections.push(`【预防接种】${data.vaccinations.map(formatVaccinationEntry).join('、')}`)
  }
  if (data.medicationNote?.trim()) {
    sections.push(`【用药史】${data.medicationNote.trim()}`)
  }
  return sections.join('；')
}
