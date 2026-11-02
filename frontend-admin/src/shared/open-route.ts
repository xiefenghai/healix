import type { RouteLocationRaw, Router } from 'vue-router'

/** 新标签打开应用内路由（少数需对照的页面；首诊请用同标签 router.push 或驾驶舱抽屉）。 */
export function openInNewTab(router: Router, to: RouteLocationRaw) {
  const { href } = router.resolve(to)
  window.open(href, '_blank', 'noopener,noreferrer')
}
