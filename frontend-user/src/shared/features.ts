/** C 端开放问答（发现 · 健康助手）。生产构建默认关闭，需显式 VITE_PATIENT_AGENT=true 开启 */
export const PATIENT_AGENT_ENABLED = import.meta.env.VITE_PATIENT_AGENT
  ? import.meta.env.VITE_PATIENT_AGENT === 'true'
  : import.meta.env.DEV
