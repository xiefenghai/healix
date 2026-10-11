/** C 端管理方案展示文案 */

export const CARE_PLAN_FREQUENCY_LABELS: Record<string, string> = {
  QD: '每日1次',
  BID: '每日2次',
  TID: '每日3次',
  TIW: '每周3次',
  QOD: '隔日1次',
  QW: '每周1次',
  PRN: '必要时',
}

export const CARE_PLAN_TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '早晨',
  AFTERNOON: '下午',
  EVENING: '晚上',
  AFTER_DINNER: '餐后',
  BEDTIME: '睡前',
  OTHER: '其他',
  ALL: '全天',
}

export const CARE_PLAN_TASK_CATEGORY_LABELS: Record<string, string> = {
  EXERCISE: '运动',
  DIET: '饮食',
  OTHER: '其他',
}

export const CARE_PLAN_CHECKIN_STATUS_LABELS: Record<string, string> = {
  DONE: '已完成',
  SKIPPED: '已跳过',
  MISSED: '未完成',
}

export const CARE_PLAN_EXERCISE_TYPE_LABELS: Record<string, string> = {
  WALKING: '步行',
  JOGGING: '慢跑',
  CYCLING: '骑行',
  SWIMMING: '游泳',
  STRENGTH: '力量训练',
  STRETCHING: '拉伸/柔韧',
  TAICHI: '太极',
  YOGA: '瑜伽',
  BED_EXERCISE: '床旁活动',
  BREATHING: '呼吸训练',
  OTHER: '其他',
}

export const CARE_PLAN_INTENSITY_LABELS: Record<string, string> = {
  LIGHT: '低',
  MODERATE: '中',
  VIGOROUS: '高',
}

export const CARE_PLAN_FOOD_CODE_LABELS: Record<string, string> = {
  VEGETABLES: '蔬菜',
  WHOLE_GRAINS: '全谷物',
  LEAN_PROTEIN: '优质蛋白',
  LOW_FAT_DAIRY: '低脂奶制品',
  FRUITS_LOW_SUGAR: '低糖水果',
  OATS: '燕麦',
  LEAFY_GREENS: '绿叶菜',
  HIGH_PURINE: '高嘌呤食物',
  SALTED_FOOD: '腌制食品',
  SUGARY_DRINKS: '含糖饮料',
  SUGARY_DRINK: '含糖饮料',
  ALCOHOL: '酒精',
  HIGH_FAT: '高脂肪食物',
  PICKLED: '高盐腌制品',
}

export function formatCarePlanLabel(map: Record<string, string>, code?: string | null): string {
  if (!code) return ''
  return map[code] ?? code
}

export function formatCarePlanFoodItem(item?: { code?: string; label?: string } | string | null): string {
  if (item == null) return ''
  if (typeof item === 'string') {
    return CARE_PLAN_FOOD_CODE_LABELS[item] || item
  }
  const code = (item.code || '').trim()
  const label = (item.label || '').trim()
  if (label && label !== code && !/^[A-Z][A-Z0-9_]*$/.test(label)) return label
  return CARE_PLAN_FOOD_CODE_LABELS[code] || label || code
}

export function formatCarePlanSource(source?: string | null): string {
  if (source === 'LLM' || source === 'LLM_THEN_EDIT') return 'AI 生成'
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || ''
}

/** LLM 直接生成，或 AI 生成后经人工编辑 */
export function isAiGeneratedCarePlanSource(source?: string | null): boolean {
  return source === 'LLM' || source === 'LLM_THEN_EDIT'
}

export function asStringList(v: unknown): string[] {
  if (!Array.isArray(v)) return []
  return v.map((x) => String(x ?? '').trim()).filter(Boolean)
}

export function asFoodList(v: unknown): Array<{ code?: string; label?: string }> {
  if (!Array.isArray(v)) return []
  return v.map((x) => {
    if (typeof x === 'string') return { code: x, label: x }
    if (x && typeof x === 'object') {
      const o = x as Record<string, unknown>
      return { code: o.code != null ? String(o.code) : undefined, label: o.label != null ? String(o.label) : undefined }
    }
    return {}
  })
}

export function formatWeeklyPlanDay(day?: string | null): string {
  if (!day) return ''
  const map: Record<string, string> = {
    MON: '周一',
    TUE: '周二',
    WED: '周三',
    THU: '周四',
    FRI: '周五',
    SAT: '周六',
    SUN: '周日',
    DAILY: '每天',
  }
  return map[day] || day
}

