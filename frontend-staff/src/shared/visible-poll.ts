/** 仅在浏览器前台时按固定间隔回调；切回前台立即再跑一次。 */
export function startVisiblePoll(fn: () => void, intervalMs: number): () => void {
  let timer: ReturnType<typeof setInterval> | null = null

  const run = () => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    try {
      fn()
    } catch {
      /* ignore */
    }
  }

  const clear = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  const arm = () => {
    clear()
    timer = setInterval(run, intervalMs)
  }

  const onVisibility = () => {
    if (document.visibilityState === 'visible') {
      run()
      arm()
    } else {
      clear()
    }
  }

  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('focus', run)
  arm()

  return () => {
    clear()
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('focus', run)
  }
}
