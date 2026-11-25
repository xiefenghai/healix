/** 沟通页消息渲染类型（架构预留；未实现类型不伪造数据） */
export type ChatRenderKind =
  | 'text'
  | 'image'
  | 'health_metric'
  | 'diet'
  | 'care_plan'
  | 'followup'

export interface ChatMetricItem {
  key: string
  label: string
  value: string
  /** 原始数值，供异常判定 */
  numeric?: number | null
  /** 血压舒张压 */
  numericAlt?: number | null
  unit?: string
  /** 血糖餐次：FASTING / POSTPRANDIAL / RANDOM */
  mealContext?: string | null
  status?: string
  abnormal?: boolean
}

export interface ChatHealthMetricPayload {
  title: string
  items: ChatMetricItem[]
}

export interface ChatRenderPayload {
  kind: ChatRenderKind
  metric?: ChatHealthMetricPayload
  /** 图片等扩展字段预留 */
  imageUrl?: string
  dietSummary?: string
  planTitle?: string
  followupTitle?: string
}
