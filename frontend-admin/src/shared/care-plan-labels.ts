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
  { value: 'BRISK_WALKING', label: '快走' },
  { value: 'NORDIC_WALKING', label: '健走' },
  { value: 'JOGGING', label: '慢跑' },
  { value: 'RUNNING', label: '跑步' },
  { value: 'STAIRS', label: '爬楼梯' },
  { value: 'HIKING', label: '徒步' },
  { value: 'CYCLING', label: '骑行' },
  { value: 'STATIONARY_BIKE', label: '健身车' },
  { value: 'ELLIPTICAL', label: '椭圆机' },
  { value: 'ROWING', label: '划船机' },
  { value: 'SWIMMING', label: '游泳' },
  { value: 'WATER_AEROBICS', label: '水中健身' },
  { value: 'AEROBIC', label: '有氧' },
  { value: 'HOME_AEROBIC', label: '居家有氧' },
  { value: 'AEROBICS', label: '健身操' },
  { value: 'SQUARE_DANCE', label: '广场舞' },
  { value: 'DANCING', label: '跳舞' },
  { value: 'TAICHI', label: '太极' },
  { value: 'TAI_CHI', label: '太极' },
  { value: 'BADUANJIN', label: '八段锦' },
  { value: 'WUQINXI', label: '五禽戏' },
  { value: 'YOGA', label: '瑜伽' },
  { value: 'PILATES', label: '普拉提' },
  { value: 'STRETCHING', label: '拉伸/柔韧' },
  { value: 'STRETCH', label: '拉伸' },
  { value: 'STRENGTH', label: '力量训练' },
  { value: 'SEATED_STRENGTH', label: '坐姿力量' },
  { value: 'RESISTANCE', label: '抗阻训练' },
  { value: 'BODYWEIGHT', label: '徒手力量' },
  { value: 'MACHINE_STRENGTH', label: '器械力量' },
  { value: 'ELASTIC_BAND', label: '弹力带' },
  { value: 'PLANK', label: '平板支撑' },
  { value: 'BALANCE', label: '平衡训练' },
  { value: 'BALANCE_TRAINING', label: '平衡训练' },
  { value: 'BADMINTON', label: '羽毛球' },
  { value: 'TABLE_TENNIS', label: '乒乓球' },
  { value: 'TENNIS', label: '网球' },
  { value: 'BASKETBALL', label: '篮球' },
  { value: 'FOOTBALL', label: '足球' },
  { value: 'VOLLEYBALL', label: '气排球' },
  { value: 'GATEBALL', label: '门球' },
  { value: 'GOLF', label: '高尔夫' },
  { value: 'ROPE_SKIPPING', label: '跳绳' },
  { value: 'SHUTTLECOCK', label: '踢毽子' },
  { value: 'BED_EXERCISE', label: '床旁活动' },
  { value: 'SEATED_ACTIVITY', label: '坐位活动' },
  { value: 'BED_MOBILITY', label: '床上活动' },
  { value: 'ANKLE_PUMP', label: '踝泵' },
  { value: 'BEDSIDE_ANKLE_PUMP', label: '床旁踝泵' },
  { value: 'UPPER_LIMB_ROM', label: '上肢活动' },
  { value: 'BEDSIDE_UPPER_LIMB', label: '床旁上肢活动' },
  { value: 'PASSIVE_ROM', label: '被动活动' },
  { value: 'POSITION_CHANGE', label: '体位变换' },
  { value: 'BREATHING', label: '呼吸训练' },
  { value: 'BREATHING_TRAINING', label: '呼吸训练' },
  { value: 'HOUSEWORK', label: '家务' },
  { value: 'GARDENING', label: '园艺' },
  { value: 'REST', label: '休息' },
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
  { value: 'WATER', label: '饮水' },
  { value: 'SLEEP', label: '睡眠' },
  { value: 'MOOD', label: '心情' },
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

/** 解析 sampleDay 餐次：字符串或 { text, swapHint } */
export function parseSampleMeal(raw: unknown): { text: string; swapHint: string } {
  if (raw == null) return { text: '', swapHint: '' }
  if (typeof raw === 'string') return { text: raw.trim(), swapHint: '' }
  if (typeof raw === 'object') {
    const o = raw as { text?: string; swapHint?: string | null }
    return {
      text: String(o.text || '').trim(),
      swapHint: o.swapHint ? String(o.swapHint).trim() : '',
    }
  }
  return { text: '', swapHint: '' }
}

/** 保存用：无替换提示时仍写字符串，兼容旧结构 */
export function sampleMealPayload(meal: { text?: string; swapHint?: string }): string | { text: string; swapHint: string } {
  const text = String(meal?.text || '').trim()
  const swapHint = String(meal?.swapHint || '').trim()
  if (!swapHint) return text
  return { text, swapHint }
}

export const CARE_PLAN_TEMPLATE_OPTIONS = [
  { value: 'COMPREHENSIVE', label: '综合管理' },
  { value: 'DIABETES', label: '糖尿病管理' },
  { value: 'HYPERTENSION', label: '高血压管理' },
  { value: 'OBESITY', label: '肥胖管理' },
  { value: 'WELLNESS', label: '健康管理' },
] as const

const TEMPLATE_KEY_LABELS: Record<string, string> = {
  COMPREHENSIVE: '综合管理',
  DIABETES: '糖尿病管理',
  HYPERTENSION: '高血压管理',
  OBESITY: '肥胖管理',
  WELLNESS: '健康管理',
  // 旧键兼容
  GENERAL: '健康管理',
  DIABETES_HYPERTENSION: '综合管理',
}

export function formatCarePlanTemplateKey(code?: string | null): string {
  return lookup(TEMPLATE_KEY_LABELS, code)
}

/** LLM 直接生成，或 AI 生成后经人工编辑 */
export function isAiGeneratedCarePlanSource(source?: string | null): boolean {
  return source === 'LLM' || source === 'LLM_THEN_EDIT'
}
