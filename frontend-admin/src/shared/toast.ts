import { ElMessage, type MessageHandler, type MessageOptions } from 'element-plus'

/**
 * 全局 Toast：同一时间只保留一条。
 * 新提示弹出前关闭旧实例，避免连续点击叠罗汉。
 */
let current: MessageHandler | null = null

type ToastType = 'success' | 'warning' | 'info' | 'error'

function show(type: ToastType, message: string, options?: Omit<MessageOptions, 'type' | 'message'>) {
  try {
    current?.close()
  } catch {
    /* ignore */
  }
  ElMessage.closeAll()
  current = ElMessage({
    type,
    message,
    duration: 2200,
    grouping: true,
    showClose: false,
    ...options,
  })
  return current
}

export const toast = {
  success: (message: string, options?: Omit<MessageOptions, 'type' | 'message'>) =>
    show('success', message, options),
  warning: (message: string, options?: Omit<MessageOptions, 'type' | 'message'>) =>
    show('warning', message, options),
  info: (message: string, options?: Omit<MessageOptions, 'type' | 'message'>) =>
    show('info', message, options),
  error: (message: string, options?: Omit<MessageOptions, 'type' | 'message'>) =>
    show('error', message, options),
  closeAll: () => {
    try {
      current?.close()
    } catch {
      /* ignore */
    }
    current = null
    ElMessage.closeAll()
  },
}

/** 给既有 ElMessage.xxx 调用打补丁：先关旧再开新，全站防叠。 */
export function installElMessageSingleton() {
  const kinds = ['success', 'warning', 'info', 'error'] as const
  for (const kind of kinds) {
    const original = ElMessage[kind].bind(ElMessage) as (
      opts: string | MessageOptions,
    ) => MessageHandler
    ElMessage[kind] = ((opts: string | MessageOptions) => {
      try {
        current?.close()
      } catch {
        /* ignore */
      }
      ElMessage.closeAll()
      if (typeof opts === 'string') {
        current = original({ message: opts, duration: 2200, grouping: true })
      } else {
        current = original({ duration: 2200, grouping: true, ...opts })
      }
      return current
    }) as typeof ElMessage.success
  }
}
