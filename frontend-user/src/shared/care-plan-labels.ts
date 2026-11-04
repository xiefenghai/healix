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

/** 解析 sampleDay 餐次：字符串或 { text, swapHint } */
export function parseSampleMeal(raw: unknown): { text: string; swapHint?: string } {
  if (raw == null) return { text: '' }
  if (typeof raw === 'string') return { text: raw.trim() }
  if (typeof raw === 'object') {
    const o = raw as { text?: string; swapHint?: string | null }
    return {
      text: String(o.text || '').trim(),
      swapHint: o.swapHint ? String(o.swapHint).trim() : undefined,
    }
  }
  return { text: '' }
}

export const CARE_PLAN_TASK_CATEGORY_LABELS: Record<string, string> = {
  EXERCISE: '运动',
  DIET: '饮食',
  WATER: '饮水',
  SLEEP: '睡眠',
  MOOD: '心情',
  OTHER: '其他',
}

/** XHS 对齐：任务类别分色 class（配合 `.hx-cat`） */
export function carePlanCategoryTone(category?: string | null, taskCode?: string | null): string {
  const c = (category || '').toUpperCase()
  const code = (taskCode || '').toUpperCase()
  if (c === 'WATER' || code === 'WATER_LOG') return 'water'
  if (c === 'SLEEP' || code === 'SLEEP_LOG') return 'sleep'
  if (c === 'MOOD' || code === 'MOOD_LOG') return 'mood'
  if (c === 'DIET' || code === 'DIET_LOG') return 'diet'
  if (c === 'EXERCISE') return 'exercise'
  return 'other'
}

export interface RecordableTask {
  taskCode?: string
  category?: string
  /** BLOOD_GLUCOSE / BLOOD_PRESSURE：录入对应指标后系统自动完成 */
  metricRef?: string | null
}

/** 靠记录数据自动完成的任务跳到对应录入页；返回 null 表示只能手动打卡 */
export function carePlanTaskRecordPath(task: RecordableTask): string | null {
  if (task.metricRef) return `/health/record?metric=${task.metricRef}`
  if (task.taskCode === 'DIET_LOG' || task.category === 'DIET') return '/diet'
  if (task.taskCode === 'WATER_LOG' || task.category === 'WATER') return '/water'
  if (task.taskCode === 'SLEEP_LOG' || task.category === 'SLEEP') return '/sleep'
  if (task.taskCode === 'MOOD_LOG' || task.category === 'MOOD') return '/mood'
  if (task.category === 'EXERCISE') return '/exercise'
  return null
}

export function carePlanTaskRecordLabel(task: RecordableTask): string {
  return task.metricRef ? '去测' : '去记'
}

export function carePlanTaskCategoryLabel(task: RecordableTask): string {
  if (task.metricRef) return '监测'
  return formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, task.category)
}

export const CARE_PLAN_CHECKIN_STATUS_LABELS: Record<string, string> = {
  DONE: '已完成',
  SKIPPED: '已跳过',
  MISSED: '未完成',
}

export const CARE_PLAN_EXERCISE_TYPE_LABELS: Record<string, string> = {
  WALKING: '步行',
  BRISK_WALKING: '快走',
  NORDIC_WALKING: '健走',
  JOGGING: '慢跑',
  RUNNING: '跑步',
  STAIRS: '爬楼梯',
  HIKING: '徒步',
  CYCLING: '骑行',
  STATIONARY_BIKE: '健身车',
  ELLIPTICAL: '椭圆机',
  ROWING: '划船机',
  SWIMMING: '游泳',
  WATER_AEROBICS: '水中健身',
  AEROBIC: '有氧',
  HOME_AEROBIC: '居家有氧',
  AEROBICS: '健身操',
  SQUARE_DANCE: '广场舞',
  DANCING: '跳舞',
  TAICHI: '太极',
  TAI_CHI: '太极',
  BADUANJIN: '八段锦',
  WUQINXI: '五禽戏',
  YOGA: '瑜伽',
  PILATES: '普拉提',
  STRETCHING: '拉伸/柔韧',
  STRETCH: '拉伸',
  STRENGTH: '力量训练',
  SEATED_STRENGTH: '坐姿力量',
  RESISTANCE: '抗阻训练',
  BODYWEIGHT: '徒手力量',
  MACHINE_STRENGTH: '器械力量',
  ELASTIC_BAND: '弹力带',
  PLANK: '平板支撑',
  BALANCE: '平衡训练',
  BALANCE_TRAINING: '平衡训练',
  BADMINTON: '羽毛球',
  TABLE_TENNIS: '乒乓球',
  TENNIS: '网球',
  BASKETBALL: '篮球',
  FOOTBALL: '足球',
  VOLLEYBALL: '气排球',
  GATEBALL: '门球',
  GOLF: '高尔夫',
  ROPE_SKIPPING: '跳绳',
  SHUTTLECOCK: '踢毽子',
  BED_EXERCISE: '床旁活动',
  SEATED_ACTIVITY: '坐位活动',
  BED_MOBILITY: '床上活动',
  ANKLE_PUMP: '踝泵',
  BEDSIDE_ANKLE_PUMP: '床旁踝泵',
  UPPER_LIMB_ROM: '上肢活动',
  BEDSIDE_UPPER_LIMB: '床旁上肢活动',
  PASSIVE_ROM: '被动活动',
  POSITION_CHANGE: '体位变换',
  BREATHING: '呼吸训练',
  BREATHING_TRAINING: '呼吸训练',
  HOUSEWORK: '家务',
  GARDENING: '园艺',
  REST: '休息',
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
  if (source === 'LLM' || source === 'LLM_THEN_EDIT') return 'AI 辅助 · 已审核'
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

