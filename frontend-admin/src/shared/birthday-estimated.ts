/** 建档时用年龄推算的生日标记（会话级，便于详情页回显「估算」） */
const keyOf = (peopleId: string) => `healix:birthdayEstimated:${peopleId}`

export function markBirthdayEstimated(peopleId: string) {
  if (!peopleId) return
  try {
    sessionStorage.setItem(keyOf(peopleId), '1')
  } catch {
    /* ignore quota / private mode */
  }
}

export function clearBirthdayEstimated(peopleId: string) {
  if (!peopleId) return
  try {
    sessionStorage.removeItem(keyOf(peopleId))
  } catch {
    /* ignore */
  }
}

export function isBirthdayEstimated(peopleId: string | null | undefined): boolean {
  if (!peopleId) return false
  try {
    return sessionStorage.getItem(keyOf(peopleId)) === '1'
  } catch {
    return false
  }
}
