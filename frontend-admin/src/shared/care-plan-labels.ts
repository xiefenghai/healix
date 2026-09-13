/** 健康管理方案枚举展示文案 */

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

export const CARE_PLAN_EXERCISE_TYPE_OPTIONS = [
  { value: 'WALKING', label: '步行' },
  { value: 'JOGGING', label: '慢跑' },
  { value: 'CYCLING', label: '骑行' },
  { value: 'SWIMMING', label: '游泳' },
  { value: 'STRENGTH', label: '力量训练' },
  { value: 'STRETCHING', label: '拉伸/柔韧' },
  { value: 'TAICHI', label: '太极' },
  { value: 'YOGA', label: '瑜伽' },
  { value: 'BED_EXERCISE', label: '床旁活动' },
  { value: 'BREATHING', label: '呼吸训练' },
  { value: 'OTHER', label: '其他' },
]

export const CARE_PLAN_INTENSITY_OPTIONS = [
  { value: 'LIGHT', label: '低' },
  { value: 'MODERATE', label: '中' },
  { value: 'VIGOROUS', label: '高' },
]

export const CARE_PLAN_FREQUENCY_OPTIONS = [
  { value: 'QD', label: '每日1次' },
  { value: 'BID', label: '每日2次' },
  { value: 'TID', label: '每日3次' },
  { value: 'TIW', label: '每周3次' },
  { value: 'QOD', label: '隔日1次' },
  { value: 'QW', label: '每周1次' },
  { value: 'PRN', label: '必要时' },
]

export const CARE_PLAN_TIME_SLOT_OPTIONS = [
  { value: 'MORNING', label: '早晨' },
  { value: 'AFTERNOON', label: '下午' },
  { value: 'EVENING', label: '晚上' },
  { value: 'AFTER_DINNER', label: '餐后' },
  { value: 'BEDTIME', label: '睡前' },
  { value: 'OTHER', label: '其他' },
]

export const CARE_PLAN_TASK_CATEGORY_OPTIONS = [
  { value: 'EXERCISE', label: '运动' },
  { value: 'DIET', label: '饮食' },
  { value: 'OTHER', label: '其他' },
]

const FREQUENCY_LABELS = Object.fromEntries(CARE_PLAN_FREQUENCY_OPTIONS.map((o) => [o.value, o.label]))
const TIME_SLOT_LABELS = Object.fromEntries(CARE_PLAN_TIME_SLOT_OPTIONS.map((o) => [o.value, o.label]))
const TASK_CATEGORY_LABELS = Object.fromEntries(
  CARE_PLAN_TASK_CATEGORY_OPTIONS.map((o) => [o.value, o.label]),
)

function lookup(map: Record<string, string>, code?: string | null): string {
  if (!code) return ''
  return map[code] ?? code
}

export function formatCarePlanFoodCode(code?: string | null): string {
  return lookup(CARE_PLAN_FOOD_CODE_LABELS, code)
}

/** 食物展示：优先中文 label；若 label 为空或等同英文 code，则用 code 中文映射 */
export function formatCarePlanFoodItem(item?: { code?: string; label?: string } | string | null): string {
  if (item == null) return ''
  if (typeof item === 'string') return formatCarePlanFoodCode(item) || item
  const code = (item.code || '').trim()
  const label = (item.label || '').trim()
  if (label && label !== code && !/^[A-Z][A-Z0-9_]*$/.test(label)) return label
  return formatCarePlanFoodCode(code) || label || code
}

export function formatCarePlanFrequency(code?: string | null): string {
  return lookup(FREQUENCY_LABELS, code)
}

export function formatCarePlanTimeSlot(code?: string | null): string {
  return lookup(TIME_SLOT_LABELS, code)
}

export function formatCarePlanTaskCategory(code?: string | null): string {
  return lookup(TASK_CATEGORY_LABELS, code)
}

export const CARE_PLAN_TEMPLATE_OPTIONS = [
  { value: 'GENERAL', label: '通用健康管理' },
  { value: 'DIABETES', label: '糖尿病管理' },
  { value: 'HYPERTENSION', label: '高血压管理' },
  { value: 'DIABETES_HYPERTENSION', label: '糖尿病+高血压联合管理' },
] as const

const TEMPLATE_KEY_LABELS = Object.fromEntries(
  CARE_PLAN_TEMPLATE_OPTIONS.map((o) => [o.value, o.label]),
)

export function formatCarePlanTemplateKey(code?: string | null): string {
  return lookup(TEMPLATE_KEY_LABELS, code)
}

/** LLM 直接生成，或 AI 生成后经人工编辑 */
export function isAiGeneratedCarePlanSource(source?: string | null): boolean {
  return source === 'LLM' || source === 'LLM_THEN_EDIT'
}
