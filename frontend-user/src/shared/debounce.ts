/** 简单尾部防抖；返回的函数带 cancel。 */
export function debounce(fn: () => void, waitMs: number) {
  let timer: ReturnType<typeof setTimeout> | null = null
  const run = () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn()
    }, waitMs)
  }
  run.cancel = () => {
    if (timer) clearTimeout(timer)
    timer = null
  }
  return run
}
