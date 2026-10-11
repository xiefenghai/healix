/** 饮食记录：类型、标签与前端预估（以服务端保存结果为准）。 */

export type MealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK'
export type OilLevel = 'LOW' | 'NORMAL' | 'HIGH'

export const MEAL_TYPES: MealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK']

export const MEAL_LABELS: Record<MealType, string> = {
  BREAKFAST: '早餐',
  LUNCH: '午餐',
  DINNER: '晚餐',
  SNACK: '加餐',
}

export const OIL_LABELS: Record<OilLevel, string> = {
  LOW: '少油',
  NORMAL: '正常',
  HIGH: '多油',
}

const OIL_FACTOR: Record<OilLevel, number> = { LOW: 0.5, NORMAL: 1, HIGH: 1.5 }
/** 烹调油约 899 kcal/100g，脂肪 99.9g/100g */
const OIL_KCAL_PER_G = 8.99
const OIL_FAT_PER_G = 0.999

export const GOAL_TYPE_LABELS: Record<string, string> = {
  LOSE: '减重',
  MAINTAIN: '维持体重',
  GAIN: '增重',
}

export const ACTIVITY_OPTIONS = [
  { value: 'SEDENTARY', label: '久坐少动', hint: '办公室工作，基本不运动' },
  { value: 'LIGHT', label: '轻度活动', hint: '每周运动 1–3 次' },
  { value: 'MODERATE', label: '中度活动', hint: '每周运动 3–5 次' },
  { value: 'ACTIVE', label: '重度活动', hint: '每天运动或体力劳动' },
]

export const PRECISION_LABELS: Record<string, string> = {
  A: '权威数据',
  B: '按菜谱计算',
  C: '估算',
}

export interface Nutrients {
  energyKcal: number
  carbG: number
  proteinG: number
  fatG: number
  fiberG: number
  sodiumMg: number
}

export interface FoodUnit {
  unit: string
  grams: number
}

export interface RecipeLine {
  code: string
  name?: string
  grams: number
  oilAdjustable: boolean
}

export interface FoodItem {
  code: string
  name: string
  aliases?: string[]
  kind: 'INGREDIENT' | 'DISH' | 'PACKAGED'
  category?: string
  energyKcal: number
  proteinG?: number | null
  fatG?: number | null
  carbG?: number | null
  fiberG?: number | null
  sodiumMg?: number | null
  gi?: number | null
  giLevel?: string | null
  units?: FoodUnit[]
  tags?: string[]
  servingGrams?: number | null
  precisionLevel?: string
  needsReview?: boolean
  recipe?: RecipeLine[] | null
}

export interface DietFlag {
  code: string
  level: 'RED' | 'YELLOW' | 'INFO'
  message: string
}

export interface MealItem {
  id?: string
  foodCode?: string | null
  foodName: string
  amount?: number | null
  unit?: string | null
  grams?: number | null
  oilLevel?: OilLevel | null
  energyKcal?: number | null
  carbG?: number | null
  proteinG?: number | null
  fatG?: number | null
  fiberG?: number | null
  sodiumMg?: number | null
  precisionLevel?: string | null
  confidence?: number | null
  gramsConfirmed?: boolean | null
}

export interface Meal {
  id: string
  mealDate: string
  mealType: MealType
  source?: string
  nutrients: Nutrients
  estimatedKcalRatio?: number | null
  qualityLevel?: 'GREEN' | 'YELLOW' | 'RED'
  flags: DietFlag[]
  note?: string | null
  staffComment?: string | null
  commentedAt?: string | null
  recordedByStaff?: boolean
  items: MealItem[]
}

export interface DietGoal {
  id?: string
  source: 'AUTO' | 'STAFF'
  goalType: string
  rateKgPerWeek?: number | null
  activityLevel: string
  diseaseKey?: string
  diseaseLabel?: string
  basisWeightKg?: number | null
  basisHeightCm?: number | null
  basisAge?: number | null
  basisGender?: string | null
  bmrKcal?: number | null
  tdeeKcal?: number | null
  energyKcal?: number | null
  carbG?: number | null
  proteinG?: number | null
  fatG?: number | null
  fiberG?: number | null
  sodiumMg?: number | null
  mealSplit?: Record<string, number>
  floorApplied?: boolean
  note?: string | null
  updatedAt?: string | null
}

export interface DietGoalStatus {
  goal: DietGoal | null
  gender?: string | null
  age?: number | null
  heightCm?: number | null
  weightKg?: number | null
  diseaseKey?: string
  diseaseLabel?: string
  missing: string[]
  editableByPatient: boolean
}

export interface DietDay {
  date: string
  goal: DietGoal | null
  intake: Nutrients
  remainingKcal: number | null
  mealTargetsKcal?: Record<string, number> | null
  mainMealsLogged: string[]
  threeMealsComplete: boolean
  dayFlags: DietFlag[]
  principles: string[]
  meals: Meal[]
}

export interface DietDraft {
  mealType: MealType
  items: MealItem[]
  warnings: string[]
}

export interface FrequentFood {
  food: FoodItem
  unit?: string | null
  amount?: number | null
  times: number
}

export function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + days)
  return ymd(dt)
}

export function defaultMealType(now = new Date()): MealType {
  const h = now.getHours()
  if (h < 10) return 'BREAKFAST'
  if (h < 15) return 'LUNCH'
  if (h < 21) return 'DINNER'
  return 'SNACK'
}

export function unitGrams(food: FoodItem | undefined, unit: string | null | undefined): number | null {
  if (!unit || unit === 'g' || unit === '克') return 1
  const hit = food?.units?.find((u) => u.unit === unit)
  return hit ? hit.grams : null
}

/** 前端预估营养（与服务端同口径；菜肴油量按菜谱可调油脂近似）。 */
export function estimateNutrients(food: FoodItem, grams: number, oil?: OilLevel | null): Nutrients {
  const k = grams / 100
  const base: Nutrients = {
    energyKcal: food.energyKcal * k,
    carbG: (food.carbG ?? 0) * k,
    proteinG: (food.proteinG ?? 0) * k,
    fatG: (food.fatG ?? 0) * k,
    fiberG: (food.fiberG ?? 0) * k,
    sodiumMg: (food.sodiumMg ?? 0) * k,
  }
  if (food.kind !== 'DISH' || !oil || oil === 'NORMAL' || !food.servingGrams || !food.recipe?.length) {
    return base
  }
  const portion = grams / food.servingGrams
  const oilGrams = food.recipe
    .filter((r) => r.oilAdjustable)
    .reduce((s, r) => s + r.grams, 0) * portion * (OIL_FACTOR[oil] - 1)
  return {
    ...base,
    energyKcal: base.energyKcal + oilGrams * OIL_KCAL_PER_G,
    fatG: base.fatG + oilGrams * OIL_FAT_PER_G,
  }
}

export function sumNutrients(list: Partial<Nutrients>[]): Nutrients {
  return list.reduce<Nutrients>(
    (acc, n) => ({
      energyKcal: acc.energyKcal + (n.energyKcal ?? 0),
      carbG: acc.carbG + (n.carbG ?? 0),
      proteinG: acc.proteinG + (n.proteinG ?? 0),
      fatG: acc.fatG + (n.fatG ?? 0),
      fiberG: acc.fiberG + (n.fiberG ?? 0),
      sodiumMg: acc.sodiumMg + (n.sodiumMg ?? 0),
    }),
    { energyKcal: 0, carbG: 0, proteinG: 0, fatG: 0, fiberG: 0, sodiumMg: 0 },
  )
}

export function fmtAmount(v: number | null | undefined): string {
  if (v == null) return ''
  return Number.isInteger(v) ? String(v) : String(Math.round(v * 10) / 10)
}

export function itemAmountText(it: MealItem): string {
  if (!it.unit || it.unit === 'g') return `${fmtAmount(it.grams ?? it.amount)}g`
  return `${fmtAmount(it.amount)}${it.unit}（${fmtAmount(it.grams)}g）`
}
