import { api } from './http'
import { parseMealStructure } from './care-plan-meal-format'

type FoodHit = {
  name?: string
  energyKcal?: number | null
}

/** 常见控糖示例菜粗略 kcal/100g（熟重/可食部近似），匹配不到食物库时兜底 */
const LOCAL_KCAL_PER_100G: Array<{ re: RegExp; kcal: number }> = [
  { re: /杂粮饭|糙米饭|米饭|米饭/, kcal: 116 },
  { re: /荞麦面|全麦面|面条|挂面/, kcal: 110 },
  { re: /全麦馒头|馒头|花卷/, kcal: 220 },
  { re: /全麦面包|面包/, kcal: 240 },
  { re: /玉米/, kcal: 106 },
  { re: /红薯|芋头|土豆/, kcal: 85 },
  { re: /燕麦/, kcal: 150 },
  { re: /清蒸鱼|鲈鱼|鳕鱼|带鱼|鱼/, kcal: 110 },
  { re: /鸡胸|鸡腿|鸡肉|白切鸡|清蒸鸡/, kcal: 130 },
  { re: /牛肉|牛腩|瘦牛肉|里脊/, kcal: 140 },
  { re: /瘦肉|肉丝|肉片|排骨/, kcal: 145 },
  { re: /虾|虾仁/, kcal: 95 },
  { re: /豆腐|豆腐脑/, kcal: 75 },
  { re: /水煮蛋|鸡蛋|蒸蛋|蛋羹/, kcal: 145 },
  { re: /无糖.*酸奶|原味酸奶|酸奶/, kcal: 55 },
  { re: /无糖豆浆|豆浆/, kcal: 30 },
  { re: /低脂牛奶|牛奶|鲜奶/, kcal: 50 },
  { re: /西兰花|菠菜|空心菜|绿叶菜|油麦|生菜|白菜|黄瓜|西红柿|番茄|芹菜|豆角|芦笋|海带|木耳/, kcal: 25 },
  { re: /紫菜汤|冬瓜汤|番茄汤|蔬菜汤|汤/, kcal: 15 },
  { re: /苹果|柚子|猕猴桃|草莓|蓝莓|梨|橙/, kcal: 50 },
  { re: /坚果/, kcal: 580 },
]

/** 份量启发式 → 克数（液体按 ml≈g） */
export function guessGrams(token: string): number {
  const t = token.trim()
  const ml = t.match(/(\d+(?:\.\d+)?)\s*(?:ml|毫升)/i)
  if (ml) return Math.round(Number(ml[1]))
  if (/半碗|半份/.test(t)) return 80
  if (/小碗|一小碗/.test(t)) return 100
  if (/大碗/.test(t)) return 200
  if (/碗|份/.test(t)) return 150
  const g = t.match(/约?\s*(\d+(?:\.\d+)?)\s*(?:g|克)/i)
  if (g) return Math.round(Number(g[1]))
  const liang = t.match(/(\d+(?:\.\d+)?)\s*两/)
  if (liang) return Math.round(Number(liang[1]) * 50)
  if (/个|只/.test(t) && /蛋/.test(t)) return 50
  if (/个|只|根|片/.test(t) && /馒头|玉米|面包/.test(t)) return 70
  return 100
}

/** 食物库检索词：去掉份量与括号，保留菜名 */
export function foodSearchKeyword(token: string): string {
  let q = String(token || '')
    .replace(/[（(].*?[）)]/g, ' ')
    .replace(/约?\s*\d+(?:\.\d+)?\s*(?:g|克|ml|毫升|两|个|只|根|片|碗|份)/gi, ' ')
    .replace(/[、，,；;]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  q = q.replace(/^约|约$/g, '').trim()
  if (!q || q.length < 2) return ''
  return q.slice(0, 16)
}

function localKcalPer100g(token: string): number | null {
  const t = foodSearchKeyword(token) || token
  for (const row of LOCAL_KCAL_PER_100G) {
    if (row.re.test(t) || row.re.test(token)) return row.kcal
  }
  return null
}

const searchCache = new Map<string, number | null>()

async function searchKcalPer100g(keyword: string): Promise<number | null> {
  const q = foodSearchKeyword(keyword)
  if (!q) return localKcalPer100g(keyword)
  if (searchCache.has(q)) {
    const cached = searchCache.get(q)!
    return cached ?? localKcalPer100g(keyword)
  }
  try {
    const res = await api<{ data: FoodHit[] }>(
      `/api/b/v1/foods/search?keyword=${encodeURIComponent(q)}&limit=5`,
    )
    const hit = (res.data || [])[0]
    const per = typeof hit?.energyKcal === 'number' && hit.energyKcal > 0 ? hit.energyKcal : null
    searchCache.set(q, per)
    return per ?? localKcalPer100g(keyword)
  } catch {
    searchCache.set(q, null)
    return localKcalPer100g(keyword)
  }
}

export type MealSlotEstimate = {
  key: 'breakfast' | 'lunch' | 'dinner' | 'snacks'
  kcal: number
  matched: number
  total: number
}

export type MealDayEstimate = {
  kcal: number
  matched: number
  total: number
  coverage: number
  /** 覆盖尚可时展示数字；否则仍给出约估值但标 unreliable */
  reliable: boolean
  bySlot: MealSlotEstimate[]
}

const MIN_COVERAGE = 0.4
const ABSURD_LOW_KCAL = 350

async function estimateMealText(text: string): Promise<{ kcal: number; matched: number; total: number }> {
  const struct = parseMealStructure(text)
  const tokens = struct.parts
    .filter((p) => p.kind !== 'tip')
    .flatMap((p) => p.text.split(/[、，,]/).map((x) => x.trim()).filter(Boolean))
  let kcal = 0
  let matched = 0
  let total = 0
  for (const token of tokens) {
    total += 1
    const per = await searchKcalPer100g(token)
    if (per == null) continue
    matched += 1
    kcal += (per * guessGrams(token)) / 100
  }
  return { kcal: Math.round(kcal), matched, total }
}

/** 对示例日各餐粗算能量；bySlot 供餐卡展示 */
export async function estimateSampleDayKcal(meals: {
  breakfast?: { text?: string }
  lunch?: { text?: string }
  dinner?: { text?: string }
  snacks?: { text?: string }
}): Promise<MealDayEstimate> {
  const slots: Array<{ key: MealSlotEstimate['key']; text: string }> = [
    { key: 'breakfast', text: String(meals.breakfast?.text || '').trim() },
    { key: 'lunch', text: String(meals.lunch?.text || '').trim() },
    { key: 'dinner', text: String(meals.dinner?.text || '').trim() },
    { key: 'snacks', text: String(meals.snacks?.text || '').trim() },
  ]

  const bySlot: MealSlotEstimate[] = []
  let kcal = 0
  let matched = 0
  let total = 0

  for (const slot of slots) {
    if (!slot.text) {
      bySlot.push({ key: slot.key, kcal: 0, matched: 0, total: 0 })
      continue
    }
    const one = await estimateMealText(slot.text)
    bySlot.push({ key: slot.key, ...one })
    kcal += one.kcal
    matched += one.matched
    total += one.total
  }

  const coverage = total > 0 ? matched / total : 0
  const reliable = total > 0 && coverage >= MIN_COVERAGE && kcal >= ABSURD_LOW_KCAL

  return {
    kcal,
    matched,
    total,
    coverage,
    reliable,
    bySlot,
  }
}

export function slotEstimateKcal(
  estimate: MealDayEstimate | null | undefined,
  key: MealSlotEstimate['key'],
): number | null {
  if (!estimate) return null
  const row = estimate.bySlot?.find((s) => s.key === key)
  if (!row || row.total <= 0 || row.matched <= 0) return null
  return row.kcal
}
