import type { Directive, DirectiveBinding } from 'vue'

/** 让 textarea 按内容撑满高度，避免框内滚动条 */
export function autosizeTextarea(el: HTMLTextAreaElement | null | undefined) {
  if (!el) return
  const min = Number(el.dataset.minHeight || 0) || 0
  el.style.overflowY = 'hidden'
  el.style.resize = 'none'
  el.style.height = 'auto'
  const next = Math.max(el.scrollHeight, min)
  el.style.height = `${next}px`
}

type AutosizeEl = HTMLTextAreaElement & {
  __autosizeCleanup?: () => void
}

function bind(el: AutosizeEl, binding: DirectiveBinding) {
  el.__autosizeCleanup?.()
  const minHeight = Number(binding.value?.minHeight ?? binding.arg ?? 0) || 0
  if (minHeight > 0) el.dataset.minHeight = String(minHeight)

  const resize = () => autosizeTextarea(el)
  const onInput = () => resize()
  el.addEventListener('input', onInput)
  // 字体/布局就绪后再量一次
  requestAnimationFrame(resize)
  const ro =
    typeof ResizeObserver !== 'undefined'
      ? new ResizeObserver(() => resize())
      : null
  ro?.observe(el)

  el.__autosizeCleanup = () => {
    el.removeEventListener('input', onInput)
    ro?.disconnect()
    delete el.__autosizeCleanup
  }
}

/** v-autosize / v-autosize="{ minHeight: 72 }" */
export const vAutosize: Directive<HTMLTextAreaElement, { minHeight?: number } | undefined> = {
  mounted(el, binding) {
    bind(el as AutosizeEl, binding)
  },
  updated(el, binding) {
    // 外部 value 变化后重新量高
    if (binding.value?.minHeight != null) {
      el.dataset.minHeight = String(binding.value.minHeight)
    }
    requestAnimationFrame(() => autosizeTextarea(el))
  },
  unmounted(el) {
    ;(el as AutosizeEl).__autosizeCleanup?.()
  },
}
