/** 驾驶舱 Agent → 抽屉子视图的一次性草稿传递（取走即清空）。 */

export type AgentDraftPayload = {
  peopleId: string
  mode: string
  draftContent?: string
  openCreate?: boolean
  /** 管理报告审阅预填 */
  reportId?: string
  openReview?: boolean
  staffComment?: string
  nextFocus?: string
  quarterAdvice?: string
}

let pending: AgentDraftPayload | null = null

export function setAgentDraft(draft: AgentDraftPayload) {
  pending = { ...draft }
}

export function takeAgentDraft(peopleId: string, mode: string): AgentDraftPayload | null {
  if (!pending) return null
  if (pending.peopleId !== peopleId || pending.mode !== mode) return null
  const d = pending
  pending = null
  return d
}

export function peekAgentDraft(peopleId: string, mode: string): AgentDraftPayload | null {
  if (!pending) return null
  if (pending.peopleId !== peopleId || pending.mode !== mode) return null
  return pending
}

export function clearAgentDraft() {
  pending = null
}
