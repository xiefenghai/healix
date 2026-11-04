/**
 * 跨浏览器标签同步：首诊等在新标签完成后，通知仍打开的患者详情页刷新。
 * 优先 BroadcastChannel；不支持时退回 localStorage 事件。
 */

export type PatientCrossTabEvent = {
  peopleId: string
  reason: 'INTAKE_SUBMITTED' | 'INTAKE_SUPPLEMENT' | string
  at: number
}

const CHANNEL = 'healix-patient-cross-tab'
const STORAGE_KEY = 'healix-patient-cross-tab'

export function publishPatientCrossTab(event: Omit<PatientCrossTabEvent, 'at'>) {
  const payload: PatientCrossTabEvent = { ...event, at: Date.now() }
  try {
    const bc = new BroadcastChannel(CHANNEL)
    bc.postMessage(payload)
    bc.close()
  } catch {
    /* ignore */
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* ignore */
  }
}

export function subscribePatientCrossTab(
  handler: (event: PatientCrossTabEvent) => void,
): () => void {
  let bc: BroadcastChannel | null = null
  const onBc = (ev: MessageEvent) => {
    const data = ev.data as PatientCrossTabEvent
    if (data?.peopleId) handler(data)
  }
  try {
    bc = new BroadcastChannel(CHANNEL)
    bc.addEventListener('message', onBc)
  } catch {
    /* ignore */
  }
  const onStorage = (ev: StorageEvent) => {
    if (ev.key !== STORAGE_KEY || !ev.newValue) return
    try {
      const data = JSON.parse(ev.newValue) as PatientCrossTabEvent
      if (data?.peopleId) handler(data)
    } catch {
      /* ignore */
    }
  }
  window.addEventListener('storage', onStorage)
  return () => {
    bc?.removeEventListener('message', onBc)
    bc?.close()
    window.removeEventListener('storage', onStorage)
  }
}
