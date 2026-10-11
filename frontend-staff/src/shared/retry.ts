import { showConfirmDialog } from 'vant'

/** 自动重试（弱网）：默认 2 次、递增间隔 */
export async function withRetry<T>(
  fn: () => Promise<T>,
  opts?: { retries?: number; delayMs?: number },
): Promise<T> {
  const retries = opts?.retries ?? 2
  const delayMs = opts?.delayMs ?? 500
  let last: unknown
  for (let i = 0; i <= retries; i++) {
    try {
      return await fn()
    } catch (e) {
      last = e
      if (i === retries) break
      await new Promise((r) => setTimeout(r, delayMs * (i + 1)))
    }
  }
  throw last
}

/**
 * 提交失败弹窗重试；用户取消返回 null。
 * 每次尝试会先做短自动重试，仍失败再问用户。
 */
export async function submitWithRetry<T>(
  fn: () => Promise<T>,
  opts?: { title?: string },
): Promise<T | null> {
  const title = opts?.title || '提交失败'
  for (;;) {
    try {
      return await withRetry(fn, { retries: 1, delayMs: 400 })
    } catch (e) {
      const message = e instanceof Error ? e.message : '网络不稳定，请重试'
      try {
        await showConfirmDialog({
          title,
          message,
          confirmButtonText: '重试',
          cancelButtonText: '取消',
        })
      } catch {
        return null
      }
    }
  }
}
