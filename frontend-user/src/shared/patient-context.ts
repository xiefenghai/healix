import { api, getToken, setToken } from '../api/http'

interface PatientCard {
  id: string
  peopleId: string
  displayName: string
}

/** 从 C 端 JWT 解析当前就诊人 peopleId */
export function currentPatientIdFromToken(): string | null {
  const token = getToken()
  if (!token) return null
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'))
    const payload = JSON.parse(json) as { patientId?: string }
    return payload.patientId || null
  } catch {
    return null
  }
}

/**
 * 消息中心等账号级入口：若目标就诊人与当前 JWT 不一致，先切换卡片再继续。
 * @returns 是否发生了切换
 */
export async function ensureActivePeople(peopleId?: string | null): Promise<boolean> {
  const target = peopleId?.trim()
  if (!target) return false
  const current = currentPatientIdFromToken()
  if (current === target) return false

  const res = await api<{ data: PatientCard[] }>('/api/c/v1/patient-cards')
  const card = (res.data || []).find((c) => c.peopleId === target)
  if (!card) {
    throw new Error('该消息对应的就诊人已不在您的账号下，请先添加后再查看')
  }
  const selected = await api<{ data: { accessToken: string } }>(
    `/api/c/v1/patient-cards/${card.id}/select`,
    { method: 'POST' },
  )
  setToken(selected.data.accessToken)
  return true
}
