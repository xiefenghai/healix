/** 示例周营养结构粗览（健管师审阅用，非精确营养计算） */

export type WeekMealSlot = 'breakfast' | 'lunch' | 'dinner' | 'snacks'

export type WeekMealsByDay = Record<
  string,
  Partial<Record<WeekMealSlot, { text?: string } | string | null | undefined>>
>

export type WeekNutritionOverview = {
  eggBreakfastDays: number
  eggTotalHits: number
  fishHits: number
  leanMeatHits: number
  soyHits: number
  dairySnackHits: number
  fruitSnackHits: number
  vegHits: number
  darkVegHits: number
  darkVegRatio: number | null
  tips: string[]
  proteinLine: string
  vegLine: string
}

const EGG_RE = /鸡蛋|蛋羹|蒸蛋|荷包蛋|茶叶蛋|煎蛋|水煮蛋|(?:^|[、，,\s\n])蛋(?:$|[、，,\s0-9个只])/
const FISH_RE = /鱼|虾|蟹|贝|海鲜|鲈|鳕|带鱼|虾仁/
const LEAN_RE = /鸡|鸭|牛|猪|羊|瘦肉|里脊|肉丝|肉片|排骨|腿|胸|白切鸡|鸡丁/
const SOY_RE = /豆腐|豆干|香干|豆浆|豆皮|豆制品/
const DAIRY_RE = /酸奶|牛奶|鲜奶|豆奶/
const FRUIT_RE = /水果|苹果|香蕉|橙|橘|柚|梨|葡萄|猕猴桃|莓|草莓|蓝莓|西瓜|火龙果|樱桃/
const VEG_RE =
  /菜|菇|笋|瓜|椒|茄|芹|菠菜|西兰花|空心菜|白菜|生菜|油麦|番茄|西红柿|黄瓜|胡萝卜|海带|木耳|洋葱|豆角|芦笋/
const DARK_VEG_RE =
  /菠菜|西兰花|空心菜|油麦|紫甘蓝|木耳|海带|芹菜|油菜|生菜|青菜|蒜蓉|深色/

function mealText(raw: { text?: string } | string | null | undefined): string {
  if (raw == null) return ''
  if (typeof raw === 'string') return raw
  return String(raw.text || '')
}

function countHits(text: string, re: RegExp): number {
  if (!text) return 0
  return re.test(text) ? 1 : 0
}

export function analyzeSampleWeek(week: WeekMealsByDay | null | undefined): WeekNutritionOverview | null {
  if (!week || typeof week !== 'object') return null
  const days = Object.keys(week)
  if (!days.length) return null

  let eggBreakfastDays = 0
  let eggTotalHits = 0
  let fishHits = 0
  let leanMeatHits = 0
  let soyHits = 0
  let dairySnackHits = 0
  let fruitSnackHits = 0
  let vegHits = 0
  let darkVegHits = 0

  for (const day of days) {
    const row = week[day]
    if (!row) continue
    const breakfast = mealText(row.breakfast)
    const lunch = mealText(row.lunch)
    const dinner = mealText(row.dinner)
    const snacks = mealText(row.snacks)
    const dayAll = [breakfast, lunch, dinner, snacks].join('\n')

    if (EGG_RE.test(breakfast)) eggBreakfastDays += 1
    eggTotalHits += countHits(dayAll, EGG_RE)
    fishHits += countHits(dayAll, FISH_RE)
    leanMeatHits += countHits(dayAll, LEAN_RE)
    soyHits += countHits(dayAll, SOY_RE)
    dairySnackHits += countHits(snacks, DAIRY_RE)
    fruitSnackHits += countHits(snacks, FRUIT_RE)

    if (VEG_RE.test(dayAll)) {
      vegHits += 1
      if (DARK_VEG_RE.test(dayAll)) darkVegHits += 1
    }
  }

  const darkVegRatio = vegHits > 0 ? Math.round((darkVegHits / vegHits) * 100) : null
  const tips: string[] = []
  if (eggBreakfastDays > 3 || eggTotalHits > 4) {
    tips.push(
      `本周鸡蛋出现约 ${Math.max(eggBreakfastDays, eggTotalHits)} 次（早餐 ${eggBreakfastDays} 天），偏高；建议与无糖豆浆/豆腐轮换，有血脂/胆固醇顾虑时尤需控制。`,
    )
  }
  if (eggBreakfastDays === 0 && soyHits === 0 && dairySnackHits === 0) {
    tips.push('早餐蛋白来源较单一，可穿插豆浆、无糖酸奶或豆制品。')
  }
  if (darkVegRatio != null && darkVegRatio < 40) {
    tips.push('深色蔬菜占比偏低，可多安排绿叶菜、西兰花等。')
  }
  if (!tips.length) {
    tips.push('蛋白与蔬菜轮换大致均衡，发布前再核对份量克数即可。')
  }

  const proteinBits = [
    eggTotalHits ? `鸡蛋 ${eggTotalHits} 次` : '',
    fishHits ? `鱼/虾 ${fishHits} 次` : '',
    leanMeatHits ? `畜禽瘦肉 ${leanMeatHits} 次` : '',
    soyHits ? `豆制品 ${soyHits} 次` : '',
  ].filter(Boolean)
  const proteinLine = proteinBits.length
    ? proteinBits.join('，') + (eggBreakfastDays <= 3 && eggTotalHits <= 4 ? '。（搭配尚可）' : '。')
    : '暂未识别到明确蛋白菜，请人工核对。'

  const vegLine =
    darkVegRatio == null
      ? '暂未识别到蔬菜条目。'
      : `深色蔬菜约占有菜天数的 ${darkVegRatio}%${darkVegRatio >= 50 ? '（达标）' : '（建议提高）'}。`

  return {
    eggBreakfastDays,
    eggTotalHits,
    fishHits,
    leanMeatHits,
    soyHits,
    dairySnackHits,
    fruitSnackHits,
    vegHits,
    darkVegHits,
    darkVegRatio,
    tips,
    proteinLine,
    vegLine,
  }
}
