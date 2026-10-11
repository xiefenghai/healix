const TASK_FILTERS_KEY = 'healix_staff_task_filters'
const NOTIFY_KEY = 'healix_staff_chat_notify'

export type TaskPool = 'PUBLIC' | 'MINE' | 'DONE'

export interface TaskFilterPrefs {
  pool?: TaskPool
  taskType?: string
  careTeamId?: string
}

export function loadTaskFilterPrefs(): TaskFilterPrefs {
  try {
    const raw = localStorage.getItem(TASK_FILTERS_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as TaskFilterPrefs
    return {
      pool: parsed.pool === 'PUBLIC' || parsed.pool === 'MINE' || parsed.pool === 'DONE' ? parsed.pool : undefined,
      taskType: typeof parsed.taskType === 'string' ? parsed.taskType : '',
      careTeamId: typeof parsed.careTeamId === 'string' ? parsed.careTeamId : '',
    }
  } catch {
    return {}
  }
}

export function saveTaskFilterPrefs(prefs: TaskFilterPrefs) {
  try {
    localStorage.setItem(
      TASK_FILTERS_KEY,
      JSON.stringify({
        pool: prefs.pool || 'MINE',
        taskType: prefs.taskType || '',
        careTeamId: prefs.careTeamId || '',
      }),
    )
  } catch {
    /* ignore */
  }
}

export function isChatNotifyEnabled() {
  return localStorage.getItem(NOTIFY_KEY) === '1'
}

export function setChatNotifyEnabled(on: boolean) {
  localStorage.setItem(NOTIFY_KEY, on ? '1' : '0')
}
