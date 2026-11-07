/** 示例餐文案：主食 / 蛋白 / 蔬菜 / 汤 / 加餐 结构化解析 */

const STAPLE_RE =
  /饭|面|粥|馒头|窝头|饼|杂粮|全麦|薏米|燕麦|红薯|玉米|芋头|包子|饺子|馄饨|米饭|面条|米粉|花卷/
const PROTEIN_RE =
  /鸡|鸭|鹅|鱼|虾|蟹|贝|牛|猪|羊|肉|蛋|豆腐|豆干|香干|鲈|鳕|带鱼|排骨|腿|胸|里脊|肉丝|肉片|虾仁|牛奶|酸奶/
const VEG_RE =
  /菜|菇|笋|瓜|椒|茄|芹|菠菜|西兰花|空心菜|白菜|生菜|油麦|番茄(?!汤)|西红柿(?!汤)|黄瓜|胡萝卜|海带|木耳|洋葱|豆角|芦笋/
const SOUP_RE = /汤|羹|盅|煲/
const SNACK_TIP_RE = /(?:加餐提示|加餐建议|加餐)[：:]/
const PORTION_RE = /碗|份|两/

const FRUIT_RE =
  /水果|苹果|香蕉|橙|橘|桔|柚|梨|葡萄|猕猴桃|莓|草莓|蓝莓|西瓜|哈密瓜|火龙果|樱桃|桃|李|杏|芒果|木瓜|柚子/
const NUT_RE = /坚果|杏仁|核桃|腰果|花生|瓜子|开心果|榛子|碧根果|巴旦木/
const DAIRY_RE = /酸奶|牛奶|鲜奶|豆浆|豆奶|奶酪|芝士|奶粉/

/**
 * 未采集过敏史时的常见高致敏物。
 * 虾蟹贝/花生等才标高危；淡水鱼相对稳妥，不与「海鲜」混为一谈。
 */
export function matchHighRiskAllergens(text: string): string[] {
  const t = String(text || '')
  if (!t) return []
  const hits: string[] = []
  if (/虾|蟹|贝|海鲜|蛤|蚝|牡蛎|蛏|螺/.test(t)) hits.push('虾蟹/海鲜')
  if (/鸡蛋|蛋羹|蒸蛋|荷包蛋|茶叶蛋|煎蛋|水煮蛋|(?:^|[、，,\s])蛋(?:$|[、，,\s0-9个只])/.test(t)) {
    hits.push('鸡蛋')
  }
  if (/牛奶|鲜奶|酸奶|奶粉|奶酪|芝士|炼乳/.test(t)) hits.push('牛奶')
  if (/花生/.test(t)) hits.push('花生')
  if (/坚果|腰果|杏仁|核桃|开心果|榛子/.test(t)) hits.push('坚果')
  if (/芒果/.test(t)) hits.push('芒果')
  if (/芝麻/.test(t)) hits.push('芝麻')
  return [...new Set(hits)]
}

/** 已知过敏关键词命中（鸡蛋→水煮蛋；排除「优质蛋白」误伤） */
export function matchKnownAllergen(text: string, keywords: string[]): string | null {
  let cleaned = String(text || '')
  if (!cleaned) return null
  for (const ex of ['蛋白', '贝贝南瓜', '螺旋藻', '无过敏时', '如无过敏', '不过敏']) {
    cleaned = cleaned.replaceAll(ex, ' ')
  }
  for (const a of keywords || []) {
    const k = String(a || '').trim()
    if (!k) continue
    if (cleaned.includes(k)) return k
    if (/蛋|egg/i.test(k) && /蛋/.test(cleaned)) return k
    if (/奶|乳|milk/i.test(k) && /奶|酸奶/.test(cleaned)) return k
    if ((/大豆|黄豆|soy/i.test(k) || k === 'soy') && /豆腐|豆浆|豆干/.test(cleaned)) return k
    if (/虾|蟹|贝|海鲜|shellfish/i.test(k) && /虾|蟹|贝|海鲜/.test(cleaned)) return k
  }
  return null
}

/** @deprecated 保留给外部简单判断；优先用 matchHighRiskAllergens */
export const HIGH_RISK_ALLERGEN_RE =
  /虾|蟹|贝|海鲜|鸡蛋|蛋羹|蒸蛋|牛奶|鲜奶|酸奶|花生|坚果|腰果|杏仁|芒果|芝麻/

/** 常见控糖控压平替蛋白（本地抽卡池；调用方需按过敏史再过滤） */
export const CARE_PLAN_SAFE_PROTEIN_SWAPS = [
  '清蒸鲈鱼',
  '清蒸鳕鱼',
  '清蒸鸡腿（去皮）',
  '清炒鸡丁',
  '芹菜炒牛肉（少油）',
  '清炒豆腐',
  '豆腐炖白菜',
  '清蒸带鱼',
  '番茄炖牛腩（少油少盐）',
  '清炒里脊丝',
  '白切鸡（去皮）',
] as const

/** 仅在过敏史已确认无虾蟹/蛋时才可加入的候选 */
export const CARE_PLAN_CONDITIONAL_PROTEIN_SWAPS = [
  '白灼虾',
  '蒜蓉虾仁',
  '蒸蛋羹',
] as const

export type MealPartKind =
  | 'staple'
  | 'protein'
  | 'veg'
  | 'soup'
  | 'tip'
  | 'fruit'
  | 'nut'
  | 'dairy'
  | 'snack'
  | 'other'

export type MealPart = {
  kind: MealPartKind
  text: string
  /** 命中已知过敏原名 */
  allergenHit?: string | null
  /** 未采集时的高敏提示 */
  highRisk?: boolean
  /** 高敏命中的常见致敏物名（鸡蛋/牛奶/海鲜…） */
  highRiskLabels?: string[]
}

export type MealStructure = {
  parts: MealPart[]
  raw: string
  lines: string
}

export type ParseMealOpts = {
  allergenKeywords?: string[]
  /** 过敏史未采集且尚未确权时才标黄叹号 */
  allergyUnknown?: boolean
  /** snacks 列用水果/坚果/奶制品标签 */
  slot?: 'breakfast' | 'lunch' | 'dinner' | 'snacks' | string
}

function splitSnackTip(raw: string): { body: string; tip: string } {
  const s = String(raw || '').trim()
  if (!s) return { body: '', tip: '' }
  const bySemi = s.split(/[；;]/).map((x) => x.trim()).filter(Boolean)
  if (bySemi.length > 1) {
    const tipIdx = bySemi.findIndex((p) => SNACK_TIP_RE.test(p) || /^加餐/.test(p))
    if (tipIdx >= 0) {
      return {
        body: bySemi.filter((_, i) => i !== tipIdx).join('；'),
        tip: bySemi[tipIdx]!,
      }
    }
  }
  const tipSource = SNACK_TIP_RE.source
  const m = s.match(new RegExp('^(.+?)[；;\\s]+(' + tipSource + '.+)$'))
  if (m) return { body: m[1]!.trim(), tip: m[2]!.trim() }
  return { body: s, tip: '' }
}

function normalizeTip(tip: string): string {
  const t = tip.trim()
  if (!t) return ''
  if (/^加餐提示[：:]/.test(t)) return t
  if (/^加餐建议[：:]/.test(t)) return t.replace(/^加餐建议/, '加餐提示')
  if (/^加餐[：:]/.test(t)) return t.replace(/^加餐/, '加餐提示')
  return '加餐提示：' + t
}

function classifyPart(text: string): MealPartKind {
  const t = text.trim()
  if (!t) return 'other'
  if (SNACK_TIP_RE.test(t) || /^加餐/.test(t)) return 'tip'
  if (SOUP_RE.test(t)) return 'soup'
  if (STAPLE_RE.test(t)) return 'staple'
  if (PROTEIN_RE.test(t)) return 'protein'
  if (VEG_RE.test(t)) return 'veg'
  return 'other'
}

function classifySnackPart(text: string): MealPartKind {
  const t = text.trim()
  if (!t) return 'other'
  if (FRUIT_RE.test(t)) return 'fruit'
  if (NUT_RE.test(t)) return 'nut'
  if (DAIRY_RE.test(t)) return 'dairy'
  // 加餐里的蛋白菜（鸡胸等）仍可标蛋白，但优先奶/果/坚果
  if (PROTEIN_RE.test(t)) return 'protein'
  if (STAPLE_RE.test(t)) return 'staple'
  return 'snack'
}

function splitParts(body: string): string[] {
  return body
    .split(/[、，,\n]/)
    .map((x) => x.trim())
    .filter(Boolean)
}

/**
 * 把「糙米饭1小碗、清蒸鸡腿、空心菜」拆成多行：
 * 糙米饭1小碗
 * 清蒸鸡腿、空心菜
 */
export function formatMealTextLines(raw: string): string {
  const struct = parseMealStructure(raw)
  return struct.lines
}

export function highRiskTooltip(part: MealPart): string {
  const labels =
    part.highRiskLabels && part.highRiskLabels.length
      ? part.highRiskLabels.join('/')
      : '常见致敏物'
  return `⚠️ 患者过敏史未采集，${labels}属常见致敏物，请确认安全后下发。`
}

function annotateAllergens(
  kind: MealPartKind,
  text: string,
  opts?: ParseMealOpts,
): MealPart {
  const allergenKeywords = (opts?.allergenKeywords || []).map((k) => k.trim()).filter(Boolean)
  const allergyUnknown = !!opts?.allergyUnknown
  const allergenHit = matchKnownAllergen(text, allergenKeywords)
  const highRiskLabels =
    !allergenHit && allergyUnknown ? matchHighRiskAllergens(text) : []
  return {
    kind,
    text,
    allergenHit,
    highRisk: highRiskLabels.length > 0,
    highRiskLabels: highRiskLabels.length ? highRiskLabels : undefined,
  }
}

export function parseMealStructure(raw: string, opts?: ParseMealOpts): MealStructure {
  const src = String(raw || '').trim()
  if (!src) return { parts: [], raw: '', lines: '' }

  const isSnackSlot = opts?.slot === 'snacks'

  let tip = ''
  let body = src
  if (src.includes('\n')) {
    const lines = src
      .split(/\n+/)
      .map((l) => l.trim())
      .filter(Boolean)
    const tipLine = lines.find((l) => SNACK_TIP_RE.test(l) || /^加餐/.test(l))
    tip = tipLine ? normalizeTip(tipLine) : ''
    body = lines.filter((l) => l !== tipLine).join('、')
  } else {
    const split = splitSnackTip(src)
    body = split.body
    tip = split.tip ? normalizeTip(split.tip) : ''
  }

  const tokens = splitParts(body)
  const parts: MealPart[] = []

  if (isSnackSlot) {
    // 加餐：逐项标 水果/坚果/奶制品，避免整行 [蛋白]
    for (const t of tokens) {
      parts.push(annotateAllergens(classifySnackPart(t), t, opts))
    }
    if (tip) parts.push(annotateAllergens('tip', tip, opts))
    const lines = parts.map((p) => p.text).join('\n')
    return { parts, raw: src, lines }
  }

  const buckets: Record<'staple' | 'protein' | 'veg' | 'soup' | 'other', string[]> = {
    staple: [],
    protein: [],
    veg: [],
    soup: [],
    other: [],
  }
  for (const t of tokens) {
    const kind = classifyPart(t)
    if (kind === 'staple' || kind === 'protein' || kind === 'veg' || kind === 'soup') {
      buckets[kind].push(t)
    } else {
      buckets.other.push(t)
    }
  }
  // 避免「首项带量词」误判；保留 PORTION_RE 引用以免死代码告警
  void PORTION_RE

  const pushGroup = (kind: MealPartKind, items: string[]) => {
    if (!items.length) return
    // 蛋白行按项拆开，避免鸡蛋给同组豆浆也打叹号
    if (kind === 'protein' && items.length > 1 && opts?.allergyUnknown) {
      for (const it of items) {
        parts.push(annotateAllergens(kind, it, opts))
      }
      return
    }
    parts.push(annotateAllergens(kind, items.join('、'), opts))
  }

  pushGroup('staple', buckets.staple)
  pushGroup('protein', buckets.protein)
  pushGroup('veg', [...buckets.veg, ...buckets.other.filter((t) => VEG_RE.test(t))])
  const leftover = buckets.other.filter((t) => !VEG_RE.test(t))
  pushGroup('soup', buckets.soup)
  if (leftover.length) pushGroup('other', leftover)
  if (tip) parts.push(annotateAllergens('tip', tip, opts))

  const lines = parts.map((p) => p.text).join('\n')
  return { parts, raw: src, lines }
}

export function isProteinishMealPart(part: string): boolean {
  return PROTEIN_RE.test(part)
}

/** 优质/固态蛋白：豆浆等流食单独不算达标 */
const ADEQUATE_PROTEIN_RE =
  /豆腐|豆干|豆皮|鸡|鸭|鹅|鱼|虾|蟹|牛|猪|羊|瘦肉|里脊|肉丝|肉片|排骨|腿|胸|蛋|鲈|鳕|带鱼|虾仁|白切鸡|鸡丁|无糖原味酸奶|无糖酸奶|原味酸奶/

export type MealStructureGaps = {
  hasStaple: boolean
  /** 含豆腐/瘦肉/鱼虾/蛋等；仅豆浆不算 */
  hasAdequateProtein: boolean
  hasVeg: boolean
  missing: Array<'staple' | 'protein' | 'veg'>
}

export function assessMainMealGaps(raw: string): MealStructureGaps {
  const struct = parseMealStructure(raw)
  const blob = struct.parts.map((p) => p.text).join('\n') || String(raw || '')
  const hasStaple = struct.parts.some((p) => p.kind === 'staple') || STAPLE_RE.test(blob)
  const hasVeg = struct.parts.some((p) => p.kind === 'veg') || VEG_RE.test(blob)
  const proteinBlob = struct.parts
    .filter((p) => p.kind === 'protein')
    .map((p) => p.text)
    .join('、')
  const hasAdequateProtein = ADEQUATE_PROTEIN_RE.test(proteinBlob || blob)
  const missing: MealStructureGaps['missing'] = []
  if (!hasStaple) missing.push('staple')
  if (!hasAdequateProtein) missing.push('protein')
  if (!hasVeg) missing.push('veg')
  return { hasStaple, hasAdequateProtein, hasVeg, missing }
}

/** 按类别插入一行；已有同类则追加到该类行末 */
export function appendMealKindLine(raw: string, kind: 'staple' | 'protein' | 'veg', line: string): string {
  const text = String(line || '').trim()
  if (!text) return formatMealTextLines(raw)
  const struct = parseMealStructure(raw)
  const parts = [...struct.parts]
  const idx = parts.findIndex((p) => p.kind === kind)
  if (idx >= 0) {
    const cur = parts[idx]!
    if (cur.text.includes(text) || text.includes(cur.text)) {
      parts[idx] = { ...cur, text }
    } else {
      parts[idx] = { ...cur, text: `${cur.text}、${text}` }
    }
  } else {
    const tipIdx = parts.findIndex((p) => p.kind === 'tip')
    const order: MealPartKind[] = ['staple', 'protein', 'veg', 'soup', 'other']
    let insertAt = tipIdx >= 0 ? tipIdx : parts.length
    for (let i = 0; i < parts.length; i++) {
      const pi = order.indexOf(parts[i]!.kind)
      const ki = order.indexOf(kind)
      if (pi > ki) {
        insertAt = i
        break
      }
    }
    parts.splice(insertAt, 0, { kind, text })
  }
  return parts.map((p) => p.text).join('\n')
}

/** 仅补缺失的优质蛋白，不改动已有主食/蔬菜 */
export function fillMissingAdequateProtein(
  raw: string,
  pool: string[],
  avoidKeywords: string[] = [],
): string | null {
  const gaps = assessMainMealGaps(raw)
  if (gaps.hasAdequateProtein) return null
  const avoid = avoidKeywords.map((k) => k.trim().toLowerCase()).filter(Boolean)
  const cleanPool = [...new Set(pool.map((p) => p.trim()).filter(Boolean))].filter((p) => {
    const low = p.toLowerCase()
    if (avoid.some((a) => a && low.includes(a))) return false
    return ADEQUATE_PROTEIN_RE.test(p)
  })
  if (!cleanPool.length) return null
  const pick = cleanPool[Math.floor(Math.random() * cleanPool.length)]!
  return appendMealKindLine(raw, 'protein', pick)
}

/** 从餐文中抽出可替换的蛋白菜名 */
export function extractProteinParts(raw: string): string[] {
  const struct = parseMealStructure(raw)
  const proteins = struct.parts.filter((p) => p.kind === 'protein')
  if (!proteins.length) return []
  return proteins.flatMap((p) =>
    p.text
      .split(/[、，,]/)
      .map((x) => x.trim())
      .filter(Boolean),
  )
}

export type FoodLabelKind = 'staple' | 'protein' | 'veg' | 'fruit' | 'other'

/** 推荐食物标签粗分类（展示用）；水果与蔬菜分开，避免同色混淆 */
export function classifyFoodLabel(label: string): FoodLabelKind {
  const t = String(label || '').trim()
  if (!t) return 'other'
  if (SOUP_RE.test(t) && !STAPLE_RE.test(t) && !PROTEIN_RE.test(t)) return 'other'
  if (STAPLE_RE.test(t)) return 'staple'
  if (ADEQUATE_PROTEIN_RE.test(t) || PROTEIN_RE.test(t) || DAIRY_RE.test(t)) return 'protein'
  const hasFruit = FRUIT_RE.test(t)
  const hasVeg = VEG_RE.test(t)
  // 纯水果（或水果主导且无菜名）→ 水果；含菜名的归蔬菜
  if (hasFruit && !hasVeg) return 'fruit'
  if (hasVeg) return 'veg'
  if (hasFruit) return 'fruit'
  return 'other'
}

export const FOOD_LABEL_GROUP_META: Array<{
  key: FoodLabelKind
  label: string
}> = [
  { key: 'staple', label: '主食' },
  { key: 'protein', label: '蛋白' },
  { key: 'veg', label: '蔬菜' },
  { key: 'fruit', label: '水果' },
  { key: 'other', label: '其他' },
]

export type MealQuickGroup = {
  key: 'staple' | 'protein' | 'veg'
  label: string
  items: string[]
}

/** 午/晚正餐默认快捷库 */
export const MEAL_QUICK_GROUPS: MealQuickGroup[] = [
  {
    key: 'staple',
    label: '补主食',
    items: ['杂粮饭约150g（熟重）', '全麦馒头约70g（1个）', '荞麦面约120g（熟重）', '玉米约180g（中等1根）'],
  },
  {
    key: 'protein',
    label: '补蛋白',
    items: [
      '清蒸鱼约80g',
      '鸡胸肉约80g',
      '豆腐约100g',
      '瘦肉丝约60g',
      '水煮鸡蛋1个',
      '无糖原味酸奶约150g',
    ],
  },
  {
    key: 'veg',
    label: '补蔬菜',
    items: ['西兰花约100g', '绿叶菜约150g', '蒜蓉空心菜约150g', '凉拌黄瓜约100g'],
  },
]

/** 早餐：偏轻口、常见搭配（少鱼肉硬菜） */
const MEAL_QUICK_BREAKFAST: MealQuickGroup[] = [
  {
    key: 'staple',
    label: '补主食',
    items: ['全麦馒头约70g（1个）', '玉米约180g（中等1根）', '杂粮饭约100g（熟重）', '全麦面包约40g（1片）'],
  },
  {
    key: 'protein',
    label: '补蛋白',
    items: ['水煮鸡蛋1个', '蒸蛋羹约100g', '豆腐约100g', '无糖原味酸奶约150g', '清炒豆腐约100g'],
  },
  {
    key: 'veg',
    label: '补蔬菜',
    items: ['凉拌菠菜约100g', '凉拌黄瓜约100g', '西兰花约80g', '西红柿切片约100g'],
  },
]

/** 加餐：水果 / 奶制品 / 坚果 */
const MEAL_QUICK_SNACKS: MealQuickGroup[] = [
  {
    key: 'staple',
    label: '主食类',
    items: ['全麦饼干约20g（2片）', '玉米约90g（半根）'],
  },
  {
    key: 'protein',
    label: '蛋白/奶',
    items: ['无糖原味酸奶约150g', '无糖豆浆约200ml', '水煮鸡蛋1个'],
  },
  {
    key: 'veg',
    label: '水果/坚果',
    items: ['苹果约150g（中等1个）', '猕猴桃1个', '原味坚果约10g'],
  },
]

/** 按餐次返回分类快捷标签 */
export function mealQuickGroupsForSlot(
  slot?: 'breakfast' | 'lunch' | 'dinner' | 'snacks' | string,
): MealQuickGroup[] {
  if (slot === 'breakfast') return MEAL_QUICK_BREAKFAST
  if (slot === 'snacks') return MEAL_QUICK_SNACKS
  return MEAL_QUICK_GROUPS
}

/**
 * 随机替换一道蛋白菜；保留主食与加餐提示。
 */
export function randomSwapProteinInMeal(
  raw: string,
  pool: string[],
  avoidKeywords: string[] = [],
): string | null {
  const formatted = formatMealTextLines(raw)
  const proteins = extractProteinParts(formatted)
  const avoid = avoidKeywords.map((k) => k.trim().toLowerCase()).filter(Boolean)

  const cleanPool = [...new Set(pool.map((p) => p.trim()).filter(Boolean))].filter((p) => {
    const low = p.toLowerCase()
    if (avoid.some((a) => a && low.includes(a))) return false
    if (
      proteins.some(
        (cur) => cur === p || cur.includes(p) || p.includes(cur.replace(/（.*?）/g, '')),
      )
    ) {
      return false
    }
    return true
  })
  if (!cleanPool.length) return null

  const pick = cleanPool[Math.floor(Math.random() * cleanPool.length)]!
  if (!proteins.length) {
    const struct = parseMealStructure(formatted)
    const next = [...struct.parts]
    const tipIdx = next.findIndex((p) => p.kind === 'tip')
    const insertAt = tipIdx >= 0 ? tipIdx : next.length
    next.splice(insertAt, 0, { kind: 'protein', text: pick })
    return next.map((p) => p.text).join('\n')
  }

  const target = proteins[Math.floor(Math.random() * proteins.length)]!
  const idx = formatted.indexOf(target)
  if (idx < 0) return null
  return formatMealTextLines(formatted.slice(0, idx) + pick + formatted.slice(idx + target.length))
}

export const MEAL_PART_LABELS: Record<MealPartKind, string> = {
  staple: '主食',
  protein: '蛋白',
  veg: '蔬菜',
  soup: '汤',
  tip: '加餐',
  fruit: '水果',
  nut: '坚果',
  dairy: '奶制品',
  snack: '加餐',
  other: '其他',
}

/** 糖尿病示例餐：粥类 → 固态粗粮（与后端 CarePlanDiabetesDietGuard 对齐） */
export function replaceDiabetesPorridge(text: string): string {
  const raw = String(text || '')
  if (!raw || !/[粥稀饭]/.test(raw)) return raw
  let out = raw
  out = out.replace(/皮蛋瘦肉粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）配瘦肉丝')
  out = out.replace(/小米粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  out = out.replace(/杂粮粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  out = out.replace(/燕麦粥[^、，,\n；;]*/g, '原味燕麦片约40g（少水冲稠）')
  out = out.replace(/南瓜粥[^、，,\n；;]*/g, '蒸南瓜约100g')
  out = out.replace(/八宝粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  out = out.replace(/(?:白米|大米|白)?粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  out = out.replace(/稀饭[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  out = out.replace(/[\u4e00-\u9fff]{0,6}粥[^、，,\n；;]*/g, '杂粮饭约150g（熟重）')
  return out.trim()
}
