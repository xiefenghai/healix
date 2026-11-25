/** 食物推荐/限制胶囊 → 半屏抽屉说明（无后端详情时的展示文案） */

export type FoodChipKind = 'recommend' | 'limit'

export type FoodChipDetail = {
  name: string
  kind: FoodChipKind
  reason: string
  dailyHint: string
  recipes: string[]
}

type TipRule = {
  re: RegExp
  recommend?: Omit<FoodChipDetail, 'name' | 'kind'>
  limit?: Omit<FoodChipDetail, 'name' | 'kind'>
}

const TIPS: TipRule[] = [
  {
    re: /豆腐|豆干|豆制品|豆浆/,
    recommend: {
      reason: '富含植物蛋白，升糖指数较低，有助于平稳餐后血糖，也适合作为优质蛋白轮换。',
      dailyHint: '建议每日摄入约 50–100g（豆腐）或适量无糖豆浆。',
      recipes: ['清蒸豆腐', '芹菜炒香干', '番茄豆腐汤'],
    },
  },
  {
    re: /燕麦|全谷|杂粮|糙米|全麦/,
    recommend: {
      reason: '膳食纤维丰富，消化吸收更缓，有助于延长饱腹感并平稳血糖。',
      dailyHint: '建议每日主食中安排 1/3–1/2 为全谷物或杂粮。',
      recipes: ['原味燕麦粥（少熬软烂）', '杂粮饭', '全麦馒头'],
    },
  },
  {
    re: /绿叶|蔬菜|青菜|叶菜/,
    recommend: {
      reason: '低能量、高纤维，优先食用有助于控制总热量并补充微量营养素。',
      dailyHint: '建议每日蔬菜不少于 300g，深色叶菜尽量占一半。',
      recipes: ['清炒时蔬', '蒜蓉西兰花', '凉拌菠菜'],
    },
  },
  {
    re: /鱼|虾|禽|鸡胸|瘦肉|蛋白|低脂奶|酸奶/,
    recommend: {
      reason: '优质蛋白有助于维持肌肉与饱腹感，优先选择低脂烹饪方式。',
      dailyHint: '建议每餐安排一份拳头大小的优质蛋白。',
      recipes: ['清蒸淡水鱼', '香煎鸡胸', '水煮蛋'],
    },
  },
  {
    re: /水果|低糖/,
    recommend: {
      reason: '适量低糖水果可补充维生素与钾，但需控制份量以免糖分超标。',
      dailyHint: '建议每日约 200g，优先浆果、柚子、猕猴桃等。',
      recipes: ['饭后半份水果', '原味无糖酸奶配浆果'],
    },
  },
  {
    re: /含糖|甜饮|奶茶|果汁|碳酸/,
    limit: {
      reason: '游离糖吸收快，易导致血糖快速升高，也增加额外热量。',
      dailyHint: '建议尽量避免，口渴时用白开水或不加糖茶饮替代。',
      recipes: ['气泡水+柠檬片', '无糖豆浆', '淡茶'],
    },
  },
  {
    re: /腌|咸菜|酱菜|高盐/,
    limit: {
      reason: '钠含量高，不利于血压管理，也容易加重口渴与水肿。',
      dailyHint: '建议每周尽量不选或少选，烹调盐控制在每日 6g 以内。',
      recipes: ['鲜蔬清炒', '少盐蒸煮', '酱料另碟少蘸'],
    },
  },
  {
    re: /油炸|高脂|肥肉|奶油|酥/,
    limit: {
      reason: '高脂肪食物能量密度高，过多摄入不利于减重与血脂管理。',
      dailyHint: '建议每周少选，烹调以蒸、煮、炖、少油快炒为主。',
      recipes: ['空气炸少油版', '清蒸替代油炸', '去皮禽肉'],
    },
  },
  {
    re: /粥|软烂/,
    limit: {
      reason: '软烂粥类升糖相对更快，控糖阶段宜减少频率，固态杂粮饭更稳妥。',
      dailyHint: '建议少喝软烂粥，改为杂粮饭或少熬的燕麦。',
      recipes: ['杂粮饭', '少熬燕麦', '全麦馒头'],
    },
  },
  {
    re: /酒|酒精/,
    limit: {
      reason: '酒精提供空热量，并可能影响血糖波动与血压控制。',
      dailyHint: '建议尽量避免，社交场合可用无酒精饮品替代。',
      recipes: ['气泡水', '无糖茶', '柠檬水'],
    },
  },
  {
    re: /嘌呤|内脏|浓汤/,
    limit: {
      reason: '高嘌呤食物可能加重尿酸负担，需按方案限制频率与份量。',
      dailyHint: '建议少选，优先清淡蛋白与足量饮水。',
      recipes: ['清蒸鱼', '豆腐', '白肉少油炒'],
    },
  },
]

const FALLBACK: Record<FoodChipKind, Omit<FoodChipDetail, 'name' | 'kind'>> = {
  recommend: {
    reason: '符合当前饮食方案方向，有助于达成热量与营养目标，建议作为日常优先选择。',
    dailyHint: '建议按示例食谱份量安排，优先清淡烹调。',
    recipes: ['清蒸', '少油快炒', '凉拌'],
  },
  limit: {
    reason: '该食物可能不利于当前控糖、控盐或减重目标，建议减少频率与份量。',
    dailyHint: '建议偶尔少量或用方案中的推荐食物替代。',
    recipes: ['换成推荐清单中的同类食物', '少油少盐烹调', '控制份量'],
  },
}

export function resolveFoodChipDetail(
  item: { code?: string; label?: string } | string,
  kind: FoodChipKind,
): FoodChipDetail {
  const name =
    typeof item === 'string'
      ? item
      : String(item.label || item.code || '').trim() || '食物详情'
  const blob = typeof item === 'string' ? item : `${item.code || ''} ${item.label || ''}`
  const hit = TIPS.find((t) => t.re.test(blob))
  const tip = (kind === 'recommend' ? hit?.recommend : hit?.limit) || FALLBACK[kind]
  return { name, kind, ...tip }
}
