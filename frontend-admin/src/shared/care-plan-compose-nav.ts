/**
 * 管理方案「全屏制定/精修」入口：驾驶舱与患者管理共用同一路由与 UI。
 * 以后改制定页体验，只动 PatientCarePlanView 的 compose 全屏壳即可。
 */
import type { Router, RouteLocationRaw } from 'vue-router'

export type CarePlanComposeEntry = 'fresh' | 'continue' | 'adjust'

export type CarePlanComposeNavOpts = {
  /** 返回落点：cockpit → 驾驶舱；默认回患者管理方案页 */
  from?: 'cockpit' | 'patient' | string
  entry?: CarePlanComposeEntry
  fresh?: boolean
  adjustHint?: string
}

const RETURN_KEY = 'healix.carePlanCompose.return'

type ComposeReturnCtx = {
  peopleId: string
  from: string
}

/** 进入制定页前记下返回落点（防 query.from 被中途 replace 丢掉） */
export function rememberCarePlanComposeReturn(peopleId: string, from?: string | null) {
  const f = String(from || '').trim()
  if (!peopleId || f !== 'cockpit') {
    clearCarePlanComposeReturn()
    return
  }
  try {
    sessionStorage.setItem(RETURN_KEY, JSON.stringify({ peopleId, from: 'cockpit' } satisfies ComposeReturnCtx))
  } catch {
    /* ignore */
  }
}

export function peekCarePlanComposeReturn(): ComposeReturnCtx | null {
  try {
    const raw = sessionStorage.getItem(RETURN_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as ComposeReturnCtx
    if (!parsed?.peopleId || parsed.from !== 'cockpit') return null
    return parsed
  } catch {
    return null
  }
}

export function clearCarePlanComposeReturn() {
  try {
    sessionStorage.removeItem(RETURN_KEY)
  } catch {
    /* ignore */
  }
}

export function carePlanComposePath(peopleId: string): string {
  return `/workspace/patients/${encodeURIComponent(peopleId)}/care-plan/compose`
}

export function carePlanComposeLocation(
  peopleId: string,
  opts?: CarePlanComposeNavOpts,
): RouteLocationRaw {
  const query: Record<string, string> = {}
  if (opts?.from) query.from = opts.from
  if (opts?.entry) query.entry = opts.entry
  if (opts?.fresh) query.fresh = '1'
  if (opts?.adjustHint) query.adjustHint = opts.adjustHint
  rememberCarePlanComposeReturn(peopleId, opts?.from)
  return {
    path: carePlanComposePath(peopleId),
    query,
  }
}

export function pushCarePlanCompose(
  router: Router,
  peopleId: string,
  opts?: CarePlanComposeNavOpts,
) {
  return router.push(carePlanComposeLocation(peopleId, opts))
}

/**
 * 从制定页返回：
 * - 驾驶舱来源 → 直接回驾驶舱并带上 peopleId（恢复原会话），不要先落到患者管理/分配页
 * - 其它 → 患者方案列表
 */
export function carePlanComposeBackPath(
  peopleId: string,
  from?: string | null,
): RouteLocationRaw {
  const remembered = peekCarePlanComposeReturn()
  const fromCockpit =
    String(from || '') === 'cockpit' ||
    (remembered?.from === 'cockpit' && (!peopleId || remembered.peopleId === peopleId))
  const pid = peopleId || remembered?.peopleId || ''
  if (fromCockpit) {
    clearCarePlanComposeReturn()
    return {
      path: '/workspace/cockpit',
      query: pid ? { peopleId: pid } : {},
    }
  }
  clearCarePlanComposeReturn()
  return `/workspace/patients/${encodeURIComponent(pid)}/care-plan`
}
