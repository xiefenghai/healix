import { ref } from 'vue'
import { api } from './http'

export type AllergyCategory = 'DRUG' | 'FOOD' | 'ENVIRONMENT' | 'OTHER'
export type AllergyStatus = 'UNKNOWN' | 'NKA' | 'HAS'
export type AllergyVerification = 'UNCONFIRMED' | 'CONFIRMED' | 'REFUTED'

export interface AllergyItem {
  id: string
  category: AllergyCategory
  categoryLabel: string
  allergenCode: string | null
  allergenName: string
  allergyType: 'ALLERGY' | 'INTOLERANCE'
  allergyTypeLabel: string
  reactions: string[]
  reactionLabels: string[]
  reactionNote: string | null
  severity: string
  severityLabel: string
  highRisk: boolean
  clinicalStatus: 'ACTIVE' | 'RESOLVED'
  verification: AllergyVerification
  verificationLabel: string
  onsetText: string | null
  lastOccurrence: string | null
  source: 'SELF' | 'STAFF' | 'IMPORT'
  note: string | null
  verifiedAt: string | null
  createdAt: string
  updatedAt: string
  effective: boolean
  editableByPatient: boolean
}

export interface AllergySummary {
  status: AllergyStatus
  statusLabel: string
  reviewedAt: string | null
  reviewedByStaffId: string | null
  highRisk: boolean
  pendingCount: number
  bannerLabels: string[]
  items: AllergyItem[]
}

export interface AllergyCatalog {
  categories: Array<{ code: AllergyCategory; label: string }>
  allergens: Array<{ code: string; category: AllergyCategory; label: string; aliases: string[]; defaultType: string }>
  reactions: Array<{ code: string; label: string; highRisk: boolean }>
  severities: Array<{ code: string; label: string }>
  types: Array<{ code: string; label: string }>
}

export interface AllergyForm {
  category: AllergyCategory
  allergenCode: string
  allergenName: string
  allergyType: string
  reactions: string[]
  reactionNote: string
  severity: string
  clinicalStatus: string
  verification: AllergyVerification
  onsetText: string
  lastOccurrence: string
  note: string
}

export const SOURCE_LABELS: Record<string, string> = {
  SELF: '患者自填',
  STAFF: '医护录入',
  IMPORT: '历史迁移',
}

/** 过敏史变更后递增，供患者头部过敏标识刷新 */
export const allergyTick = ref(0)

let catalogCache: Promise<AllergyCatalog> | null = null

export function loadAllergyCatalog(): Promise<AllergyCatalog> {
  if (!catalogCache) {
    catalogCache = api<{ data: AllergyCatalog }>('/api/b/v1/allergies/catalog')
      .then((r) => r.data)
      .catch((e) => {
        catalogCache = null
        throw e
      })
  }
  return catalogCache
}

export async function fetchAllergySummary(peopleId: string): Promise<AllergySummary> {
  const res = await api<{ data: AllergySummary }>(`/api/b/v1/patients/${peopleId}/allergies`)
  return res.data
}

export type AllergyBrief = {
  status: AllergyStatus
  /** 展示文案：未选=未采集；NKA=没有过敏；HAS=过敏原名或「有过敏」 */
  text: string
  /** 已显式采集（NKA 或 HAS）；未选中不算 */
  collected: boolean
  hasAllergy: boolean
  tone: 'danger' | 'muted'
}

/**
 * 过敏史展示语义（禁止用「条目为空」推断无过敏）：
 * - 未选中 / UNKNOWN → 未采集
 * - 显式选了「无已知过敏」NKA → 没有过敏
 * - HAS → 过敏原列表
 */
export function formatAllergyBrief(
  status?: string | null,
  allergenNames?: Array<string | null | undefined> | null,
): AllergyBrief {
  const st = String(status || 'UNKNOWN').toUpperCase()
  if (st === 'HAS') {
    const names = (allergenNames || [])
      .map((x) => String(x || '').trim())
      .filter(Boolean)
    return {
      status: 'HAS',
      text: names.length ? names.join('、') : '有过敏',
      collected: true,
      hasAllergy: true,
      tone: 'danger',
    }
  }
  if (st === 'NKA') {
    return {
      status: 'NKA',
      text: '没有过敏',
      collected: true,
      hasAllergy: false,
      tone: 'muted',
    }
  }
  return {
    status: 'UNKNOWN',
    text: '未采集',
    collected: false,
    hasAllergy: false,
    tone: 'muted',
  }
}

/** 驾驶舱等接口可能仍返回「未询问」旧文案 */
export function isAllergyUncollectedText(text?: string | null) {
  const t = String(text || '').trim()
  return !t || t === '未采集' || t === '未询问' || t === '待确认'
}

/** NKA 展示文案（含历史用词） */
export function isAllergyNkaText(text?: string | null) {
  const t = String(text || '').trim()
  return (
    t === '没有过敏' ||
    t === '无已知过敏' ||
    t === '暂无已知过敏' ||
    t === '否认已知过敏'
  )
}

export function emptyAllergyForm(): AllergyForm {
  return {
    category: 'DRUG',
    allergenCode: '',
    allergenName: '',
    allergyType: 'ALLERGY',
    reactions: [],
    reactionNote: '',
    severity: 'UNKNOWN',
    clinicalStatus: 'ACTIVE',
    verification: 'CONFIRMED',
    onsetText: '',
    lastOccurrence: '',
    note: '',
  }
}

export function formFromItem(item: AllergyItem): AllergyForm {
  return {
    category: item.category,
    allergenCode: item.allergenCode ?? '',
    allergenName: item.allergenName,
    allergyType: item.allergyType,
    reactions: [...item.reactions],
    reactionNote: item.reactionNote ?? '',
    severity: item.severity,
    clinicalStatus: item.clinicalStatus,
    verification: item.verification,
    onsetText: item.onsetText ?? '',
    lastOccurrence: item.lastOccurrence ?? '',
    note: item.note ?? '',
  }
}

export function formToPayload(form: AllergyForm) {
  return {
    category: form.category,
    allergenCode: form.allergenCode || null,
    allergenName: form.allergenName.trim() || null,
    allergyType: form.allergyType,
    reactions: form.reactions,
    reactionNote: form.reactionNote.trim() || null,
    severity: form.severity,
    clinicalStatus: form.clinicalStatus,
    verification: form.verification,
    onsetText: form.onsetText.trim() || null,
    lastOccurrence: form.lastOccurrence || null,
    note: form.note.trim() || null,
  }
}
