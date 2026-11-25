/** 患者端：执行提醒拆条，过滤内部质检话术与非饮食内容 */

const STAFF_ONLY_RE =
  /过敏忌口尚未采集|过敏史未(?:采集|录入|确认)|无法排除[^。；;]{0,12}过敏|请尽快补(?:全|采)[^。；;]{0,8}过敏|如已知对[^。；;]{0,20}过敏|饮食建议无法排除过敏风险/

/** 睡眠呼吸等与饮食页无关，C 端饮食注意事项不展示 */
const NON_DIET_RE =
  /睡眠呼吸|多导睡眠|打鼾|打呼噜|呼吸暂停|耳鼻喉科|呼吸内科|OSA|睡眠监测/

export type DietNoteSection = { title: string; lines: string[] }

const NOTE_SECTION_RULES: Array<{ title: string; re: RegExp }> = [
  { title: '进餐顺序', re: /进餐顺序|先吃蔬|先吃菜|最后吃主食/ },
  { title: '外食技巧', re: /外食|外出就餐|少盐少油|酱料/ },
  { title: '火锅 / 外卖', re: /火锅|外卖|点餐|涮|麻辣烫|烧烤/ },
  { title: '服药配合', re: /服药|随餐|第一口饭|跟餐|阿卡波糖/ },
  { title: '日常习惯', re: /降压|起身|按时服用|宵夜|零食|饮水/ },
]

function normalize(raw: string): string {
  let t = String(raw || '')
    .replace(/^[☑✓✔☐□]\s*/, '')
    .trim()
  if (!t || STAFF_ONLY_RE.test(t) || NON_DIET_RE.test(t)) return ''
  t = t
    .replace(/遵医嘱/g, '按医生建议') // sensitive-ok: 清洗输入侧旧表述
    .replace(/按医嘱/g, '按医生建议') // sensitive-ok
    .replace(/医嘱/g, '医生建议') // sensitive-ok
  return t.replace(/^[；;，,。]+|[；;，,。]+$/g, '').trim()
}

export function patientDietNoteLines(raw: string): string[] {
  return String(raw || '')
    .split(/[；;\n。！？.]+/)
    .map((x) => x.trim())
    .map(normalize)
    .filter(Boolean)
    .filter((t, i, arr) => arr.indexOf(t) === i)
}

/** 注意事项按主题分段，便于折叠展开后扫读 */
export function groupDietNoteSections(lines: string[]): DietNoteSection[] {
  const buckets = new Map<string, string[]>()
  const order: string[] = []
  const push = (title: string, line: string) => {
    if (!buckets.has(title)) {
      buckets.set(title, [])
      order.push(title)
    }
    buckets.get(title)!.push(line)
  }
  for (const line of lines) {
    const hit = NOTE_SECTION_RULES.find((r) => r.re.test(line))
    push(hit?.title || '其他提醒', line.replace(/^[^：:]{1,12}[：:]\s*/, ''))
  }
  return order.map((title) => ({ title, lines: buckets.get(title)! }))
}

export type DietPrincipleCard = {
  key: string
  title: string
  body: string
  tone: 'salt' | 'sugar' | 'oil' | 'veg' | 'protein' | 'water' | 'meal' | 'other'
}

const PRINCIPLE_RULES: Array<{
  tone: DietPrincipleCard['tone']
  title: string
  re: RegExp
}> = [
  { tone: 'salt', title: '低盐', re: /盐|钠|腌|酱菜|咸/ },
  { tone: 'sugar', title: '控糖', re: /糖|甜|升糖|血糖|含糖|精米|白面/ },
  { tone: 'oil', title: '少油', re: /油|脂|油炸|肥腻|煎炸/ },
  { tone: 'veg', title: '多蔬', re: /蔬|纤维|粗粮|全谷|绿叶|杂粮/ },
  { tone: 'protein', title: '优质蛋白', re: /蛋白|鱼|豆|瘦肉|蛋/ },
  { tone: 'water', title: '足量饮水', re: /饮水|喝水|水分/ },
  { tone: 'meal', title: '规律进餐', re: /餐|规律|宵夜|零食|少食多餐|细嚼/ },
]

const PRINCIPLE_PRIORITY: DietPrincipleCard['tone'][] = [
  'salt',
  'sugar',
  'oil',
  'veg',
  'protein',
  'meal',
  'water',
  'other',
]

/** @deprecated 保留兼容；新 UI 用 toDietPrincipleItems */
export function toDietPrincipleCards(principles: string[]): DietPrincipleCard[] {
  return toDietPrincipleItems(principles).map((item) => ({
    key: item.key,
    title: item.title,
    body: item.body,
    tone: item.tone,
  }))
}

export type DietPrincipleItem = {
  key: string
  title: string
  body: string
  /** 展示用完整短句，不截断 */
  label: string
  tone: DietPrincipleCard['tone']
}

/** 原则清单：每条提炼「标题：要点」，完整展示不截断 */
export function toDietPrincipleItems(principles: string[]): DietPrincipleItem[] {
  const used = new Set<string>()
  const items: DietPrincipleItem[] = []
  principles.forEach((raw, i) => {
    const text = String(raw || '').trim()
    if (!text) return
    const hit = PRINCIPLE_RULES.find((r) => r.re.test(text))
    const tone = hit?.tone || 'other'
    const title = hit?.title || '要点'
    let body = text.replace(new RegExp(`^${title}[：:\\s]*`), '').trim()
    // 去掉标题前缀后若为空，退回原文
    if (!body) body = text
    // 已是「标题：内容」形态则直接用
    const titled = new RegExp(`^${title}[：:]`).test(text)
    const label = titled ? text : `${title}：${body}`
    const key = `${tone}-${i}`
    if (used.has(label)) return
    used.add(label)
    items.push({ key, title, body, label, tone })
  })
  // 同主题相邻合并感不强时保持原序；按优先级轻排可扫读
  return items.sort((a, b) => {
    const pa = PRINCIPLE_PRIORITY.indexOf(a.tone)
    const pb = PRINCIPLE_PRIORITY.indexOf(b.tone)
    return (pa < 0 ? 99 : pa) - (pb < 0 ? 99 : pb)
  })
}

/** 热量摘要精简为一行，去掉括号宏量说明 */
export function shortCalorieHint(
  hint: string,
  energyKcal?: number | null,
): string {
  const raw = String(hint || '').trim()
  const kcal =
    energyKcal && energyKcal > 0
      ? energyKcal
      : Number((raw.match(/(\d{3,4})\s*(?:千卡|大卡|kcal)/i) || [])[1] || 0) || null
  const rate = (raw.match(/每周减重\s*([\d.]+)\s*kg/i) || [])[1]
  const parts: string[] = []
  if (kcal) parts.push(`每日约 ${kcal} 千卡`)
  if (rate) parts.push(`每周减重 ${rate}kg`)
  if (parts.length) return parts.join(' · ')
  return raw
    .replace(/（[^）]*）|\([^)]*\)/g, '')
    .replace(/[，,；;].*$/, '')
    .trim() || raw
}
