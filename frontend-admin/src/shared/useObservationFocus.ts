import { inject, nextTick, onMounted, type Ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  observationFocusFromQuery,
  stripObservationFocusQuery,
  type ObservationFocusTarget,
  type ObservationSearchKind,
} from './observation-search'

type EmbeddedFocus = (ObservationFocusTarget & { nonce: number }) | null

/**
 * 订阅路由 query / 嵌入态 provide 的聚焦指令，匹配本页 kind 后回调并清理 URL。
 */
export function useObservationFocus(
  kind: ObservationSearchKind,
  apply: (target: ObservationFocusTarget) => void | Promise<void>,
) {
  const route = useRoute()
  const router = useRouter()
  const embeddedFocus = inject<Ref<EmbeddedFocus> | undefined>('observationEmbeddedFocus', undefined)
  let lastNonce = 0

  async function clearRouteFocus() {
    if (!route.query.focusKind && !route.query.focusCode) return
    await router.replace({
      path: route.path,
      query: stripObservationFocusQuery(route.query as Record<string, unknown>),
    })
  }

  async function handle(target: ObservationFocusTarget | null, nonce?: number) {
    if (!target || target.kind !== kind) return
    if (nonce != null) {
      if (nonce === lastNonce) return
      lastNonce = nonce
    }
    await nextTick()
    await apply(target)
    await clearRouteFocus()
  }

  onMounted(() => {
    void handle(observationFocusFromQuery(route.query as Record<string, unknown>))
  })

  watch(
    () =>
      [
        route.query.focusKind,
        route.query.focusCode,
        route.query.focusPanel,
        route.query.focusTs,
      ] as const,
    () => {
      void handle(observationFocusFromQuery(route.query as Record<string, unknown>))
    },
  )

  if (embeddedFocus) {
    watch(
      embeddedFocus,
      (v) => {
        if (!v) return
        void handle(v, v.nonce)
      },
      { immediate: true },
    )
  }
}

/** 滚动并短暂高亮目标元素 */
export function flashFocusElement(el: Element | null | undefined) {
  if (!el || !(el instanceof HTMLElement)) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.remove('obs-focus-flash')
  // 强制重启动画
  void el.offsetWidth
  el.classList.add('obs-focus-flash')
  window.setTimeout(() => el.classList.remove('obs-focus-flash'), 2200)
  const focusable = el.querySelector<HTMLElement>(
    'input:not([disabled]), textarea:not([disabled]), .el-input__inner, .el-input-number__input',
  )
  focusable?.focus?.({ preventScroll: true })
}
