import { api } from './http'

export type PersonaNarrative = {
  headline?: string
  narrative?: string
  watchPoints?: string[]
  model?: string
  staffLocked?: boolean
  generatedAt?: string
  editedByStaffId?: string
  editedAt?: string
}

export type PersonaDimensions = {
  identity?: Record<string, unknown>
  goals?: Record<string, unknown>
  risk?: { tags?: Array<Record<string, unknown>> }
  constraints?: {
    allergens?: string[]
    highlightCodes?: string[]
    notes?: string[]
  }
  /** 饮食 / 运动 / 心理开方抓手 */
  behavior?: {
    diet?: string[]
    exercise?: string[]
    psych?: string[]
  }
  execution?: Record<string, unknown>
  focus?: {
    openTasks?: Array<{ type?: string; title?: string }>
    nextStep?: string | null
  }
  aggregatedAt?: string
  sourceFingerprint?: string
}

export type PersonaView = {
  peopleId: string
  persisted: boolean
  status?: string | null
  dimensions: PersonaDimensions
  narrative?: PersonaNarrative | null
  displayHeadline?: string
  sourceFingerprint?: string
  generatedAt?: string | null
  dimensionsAt?: string | null
  narrativeSkippedLocked?: boolean | null
}

type ApiWrap<T> = { code: number; data: T; message?: string }

export async function getPersona(peopleId: string): Promise<PersonaView> {
  const res = await api<ApiWrap<PersonaView>>(`/api/b/v1/patients/${peopleId}/persona`)
  return res.data
}

export async function generatePersona(peopleId: string): Promise<PersonaView> {
  const res = await api<ApiWrap<PersonaView>>(`/api/b/v1/patients/${peopleId}/persona/generate`, {
    method: 'POST',
  })
  return res.data
}

export async function refreshPersona(peopleId: string): Promise<PersonaView> {
  const res = await api<ApiWrap<PersonaView>>(`/api/b/v1/patients/${peopleId}/persona/refresh`, {
    method: 'POST',
  })
  return res.data
}

export async function savePersonaNarrative(
  peopleId: string,
  body: { headline: string; narrative: string; watchPoints?: string[] },
): Promise<PersonaView> {
  const res = await api<ApiWrap<PersonaView>>(`/api/b/v1/patients/${peopleId}/persona/narrative`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
  return res.data
}

export async function unlockPersona(peopleId: string, refresh = false): Promise<PersonaView> {
  const q = refresh ? '?refresh=true' : ''
  const res = await api<ApiWrap<PersonaView>>(
    `/api/b/v1/patients/${peopleId}/persona/unlock${q}`,
    { method: 'POST' },
  )
  return res.data
}
