/** C 端开放问答（发现 · 健康助手）。生产构建默认关闭，需显式 VITE_PATIENT_AGENT=true 开启 */
export const PATIENT_AGENT_ENABLED = import.meta.env.VITE_PATIENT_AGENT
  ? import.meta.env.VITE_PATIENT_AGENT === 'true'
  : import.meta.env.DEV

/**
 * 企微添加联系人 / 活码链接（成功页引流）。
 * 未配置则不展示「加健管师企微」按钮，避免空链画饼。
 */
export const WECOM_CONTACT_URL = String(
  (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_WECOM_CONTACT_URL || '',
).trim()
