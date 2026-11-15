/**
 * 企微添加联系人 / 活码链接（成功页引流）。
 * 未配置则不展示「加健管师企微」按钮，避免空链画饼。
 */
export const WECOM_CONTACT_URL = String(
  (import.meta as ImportMeta & { env?: Record<string, string> }).env?.VITE_WECOM_CONTACT_URL || '',
).trim()
