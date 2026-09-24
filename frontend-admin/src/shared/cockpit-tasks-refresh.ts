/** 驾驶舱：业务写库后通知刷新左栏名单 + 右栏焦点待办/待确认。 */

type Listener = (peopleId?: string | null) => void

const listeners = new Set<Listener>()

export function onCockpitTasksPossiblyChanged(fn: Listener) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/** 随访办结、方案发布、录入指标、OCR 入库等可能增删待办时调用。 */
export function notifyCockpitTasksPossiblyChanged(peopleId?: string | null) {
  for (const fn of listeners) {
    try {
      fn(peopleId)
    } catch {
      /* ignore listener errors */
    }
  }
}
