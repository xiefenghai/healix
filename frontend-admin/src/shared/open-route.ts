import type { RouteLocationRaw, Router } from 'vue-router'

/** 新标签打开应用内路由（首诊工作台等全屏页）。 */
export function openInNewTab(router: Router, to: RouteLocationRaw) {
  const { href } = router.resolve(to)
  window.open(href, '_blank', 'noopener,noreferrer')
}
