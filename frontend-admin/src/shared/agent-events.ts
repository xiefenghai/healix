type StopHandle = () => void

const listeners = new Set<(peopleId: string) => void>()

export function emitAgentCarePlanUpdated(peopleId: string) {
  listeners.forEach((cb) => {
    try {
      cb(peopleId)
    } catch {
      // ignore listener errors
    }
  })
}

/**
 * 订阅 Agent 方案更新。
 * @param peopleId 固定 id，或返回当前 peopleId 的 getter（推荐）
 */
export function onAgentCarePlanUpdated(
  peopleId: string | (() => string),
  cb: () => void,
): StopHandle {
  const resolveId = typeof peopleId === 'function' ? peopleId : () => peopleId
  const listener = (updatedPeopleId: string) => {
    if (updatedPeopleId && updatedPeopleId === resolveId()) cb()
  }
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
