/**
 * 微信 / iOS WKWebView 里 100vh、100dvh 常大于「真正看得见的高度」，
 * 底栏会被裁到屏幕外或压在微信底栏下面，表现为点不到。
 * 用 visualViewport / innerHeight 写入 CSS 变量，壳层按真实可视高度布局。
 */
const VAR = '--hx-app-height'

export function syncAppViewportHeight() {
  const vv = window.visualViewport
  const h = Math.round(vv?.height ?? window.innerHeight)
  if (h > 0) {
    document.documentElement.style.setProperty(VAR, `${h}px`)
  }
}

export function startAppViewportWatch() {
  syncAppViewportHeight()
  const onChange = () => syncAppViewportHeight()
  window.addEventListener('resize', onChange)
  window.addEventListener('orientationchange', onChange)
  window.addEventListener('pageshow', onChange)
  window.visualViewport?.addEventListener('resize', onChange)
  window.visualViewport?.addEventListener('scroll', onChange)
  // 微信偶发先给错高度，短延迟再校准一次
  window.setTimeout(onChange, 50)
  window.setTimeout(onChange, 300)

  return () => {
    window.removeEventListener('resize', onChange)
    window.removeEventListener('orientationchange', onChange)
    window.removeEventListener('pageshow', onChange)
    window.visualViewport?.removeEventListener('resize', onChange)
    window.visualViewport?.removeEventListener('scroll', onChange)
  }
}
