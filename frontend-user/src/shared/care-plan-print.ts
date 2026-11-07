/**
 * 健康管理方案导出：按方案详情的卡片版式渲染打印文档，交由浏览器输出 PDF。
 *
 * 页边距为 0，避免浏览器把当前页面地址印在页脚。文件名用文档标题，并去掉路径非法字符。
 */
/** C 端无指南库：患者导出不解析 staff 参考条目 */
async function loadGuidelineLibrary(): Promise<null> {
  return null
}
function guidelineIndex(_lib: unknown): Map<string, { sourceLabel: string; staffText: string }> {
  return new Map()
}
import { patientDietNoteLines } from './care-plan-diet-notes'
import { carePlanExerciseKind, carePlanModerateEquivMin } from './care-plan-labels'
import {
  classifyFoodLabel,
  FOOD_LABEL_GROUP_META,
  matchHighRiskAllergens,
  matchKnownAllergen,
  MEAL_PART_LABELS,
  parseMealStructure,
  type MealPart,
} from './care-plan-meal-format'

export interface PrintableCarePlanTask {
  title?: string
  category?: string
  frequency?: string
  timeSlot?: string
  enabled?: boolean
  targetMl?: number | null
  rationale?: string
  refs?: string[]
}

export interface PrintableCarePlanCheckin {
  checkinDate?: string
  taskTitle?: string
  taskCategory?: string
  timeSlot?: string
  status?: string
  note?: string
}

export interface PrintableCarePlan {
  title?: string
  peopleName?: string
  goalSummary?: string
  summary?: string
  rationaleOverview?: string
  source?: string
  versionLabel?: string
  status?: string
  publishedAt?: string
  signStatus?: string
  exercise?: {
    goal?: string
    rationale?: string
    refs?: string[]
    precautions?: string[]
    contraindications?: string[]
    reviewHint?: string
    weeklyPlan?: Array<{ day?: string; items?: unknown[] }>
  }
  diet?: {
    principles?: string[] | string
    rationale?: string
    refs?: string[]
    calorieHint?: string
    energyTarget?: {
      energyKcal?: number
      carbG?: number
      proteinG?: number
      fatG?: number
      goalType?: string
      rateKgPerWeek?: number
    } | null
    notes?: string
    recommended?: Array<{ label?: string; code?: string } | string>
    limited?: Array<{ label?: string; code?: string } | string>
    allergensAvoid?: Array<{ label?: string; code?: string; highRisk?: boolean } | string>
    intolerances?: Array<{ label?: string; code?: string } | string>
    sampleDay?: Record<string, unknown>
    sampleWeek?: Record<string, unknown>
  }
  execution?: {
    horizonDays?: number
    rationale?: string
    refs?: string[]
    tasks?: PrintableCarePlanTask[]
  }
  checkins?: PrintableCarePlanCheckin[]
  /** 患者页上的过敏提醒；有则优先于饮食块里的过敏标签 */
  allergyAlert?: {
    avoid?: Array<{ name?: string; highRisk?: boolean }>
    intolerances?: string[]
  }
  /** 患者导出不包含健管师审阅用的生成依据 */
  audience?: 'staff' | 'patient'
  /** 方案引用的指南条目（打印前由 printCarePlan 解析；仅 B 端导出附录） */
  guidelineRefs?: Array<{ code: string; source: string; text: string }>
}

const FOOD_LABELS: Record<string, string> = {
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

const FREQUENCY_LABELS: Record<string, string> = {
  QD: '每日1次',
  BID: '每日2次',
  TID: '每日3次',
  TIW: '每周3次',
  QOD: '隔日1次',
  QW: '每周1次',
  HABIT: '全天微习惯',
  PRN: '必要时',
}

const TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '早晨',
  AFTERNOON: '下午',
  EVENING: '晚上',
  AFTER_DINNER: '餐后',
  BEDTIME: '睡前',
  OTHER: '其他',
  ALL: '全天',
}

const CATEGORY_LABELS: Record<string, string> = {
  EXERCISE: '运动',
  DIET: '饮食',
  WATER: '饮水',
  MEDICATION: '用药',
  SLEEP: '睡眠',
  MOOD: '心情',
  OTHER: '其他',
}

const EXERCISE_TYPE_LABELS: Record<string, string> = {
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
  LIGHT_INDOOR_ACTIVITY: '室内轻活动',
  INDOOR_WALKING: '室内步行',
  SLOW_WALKING: '慢走',
  LIGHT_WALKING: '慢走',
  LIGHT_ACTIVITY: '轻度活动',
  SEDENTARY_BREAK: '久坐起身',
  STANDING_BREAK: '久坐起身',
  SIT_BREAK: '久坐起身',
  HOUSEWORK: '家务',
  GARDENING: '园艺',
  REST: '休息',
  OTHER: '其他',
}

const INTENSITY_LABELS: Record<string, string> = {
  LIGHT: '低',
  MODERATE: '中',
  VIGOROUS: '高',
}

const DAY_LABELS: Record<string, string> = {
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  SUN: '周日',
}

const MEAL_LABELS: Array<{ key: string; label: string }> = [
  { key: 'breakfast', label: '早餐' },
  { key: 'lunch', label: '午餐' },
  { key: 'dinner', label: '晚餐' },
  { key: 'snacks', label: '加餐' },
]

function lookup(map: Record<string, string>, code?: string | null): string {
  if (!code) return ''
  return map[code] || map[code.toUpperCase()] || code
}

function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 去掉误入正文的 HTML / Markdown，防止打印成「&lt;table&gt;主食…」或「# 周五」 */
function plainText(value: unknown): string {
  return String(value ?? '')
    .replace(/<\s*\/?\s*[a-zA-Z][^>]*>/g, ' ')
    .replace(/&lt;\s*\/?\s*[a-zA-Z][^&]*&gt;/g, ' ')
    .replace(/^\s*#+\s*/gm, '')
    .replace(/#+\s*(?=周[一二三四五六日天]|早餐|午餐|晚餐|加餐)/g, '')
    .replace(/(?:^|[\s、，,])table(?=[\s主食蛋白蔬果]|$)/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function foodLabel(item?: { label?: string; code?: string } | string | null): string {
  if (item == null) return ''
  if (typeof item === 'string') return plainText(FOOD_LABELS[item] || item)
  const code = (item.code || '').trim()
  const label = plainText(item.label || '')
  if (label && label !== code && !/^[A-Z][A-Z0-9_]*$/.test(label)) return label
  return plainText(FOOD_LABELS[code] || label || code)
}

function splitSentences(list: string[] | string | undefined, sep: RegExp): string[] {
  const arr = Array.isArray(list) ? list : list ? [list] : []
  return arr
    .flatMap((s) => String(s).split(sep))
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 禁忌/注意事项：兼容数组、整段粘连、Markdown 列表、无分隔符粘句 */
function normalizeBulletItems(list: string[] | string | undefined): string[] {
  const arr = Array.isArray(list) ? list : list ? [list] : []
  const out: string[] = []
  for (const raw of arr) {
    let text = plainText(raw).replace(/\r\n/g, '\n')
    if (!text) continue
    text = text.replace(/^[-*•·]\s*/gm, '')
    // 「休息运动时」「憋气久坐」这类无标点粘连：在旧句尾与新提示词之间切开
    text = text.replace(
      /(休息|分钟|憋气|补水|监测|头晕|坐下|缓慢|循序渐进)(?=(?:运动时|久坐|服用|注意|避免|热身|不要|请勿|出现))/g,
      '$1\n',
    )
    text = text.replace(/，(?=(?:运动时|久坐|服用|热身|避免|不要|请))/g, '\n')
    const chunks = text
      .split(/\n+|(?=[-*•·]\s)|[；;]+|[。！？]+/)
      .map((s) => s.replace(/^[-*•·]\s*/, '').trim())
      .filter(Boolean)
    if (chunks.length) out.push(...chunks)
    else out.push(text)
  }
  return [...new Set(out)]
}

function parseMeal(raw: unknown): { text: string; swapHint: string } {
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

function mealsFromSample(raw: unknown) {
  if (!raw || typeof raw !== 'object') return [] as Array<{ label: string; text: string; swapHint: string }>
  const o = raw as Record<string, unknown>
  return MEAL_LABELS.map(({ key, label }) => {
    let meal = parseMeal(o[key])
    if (!meal.text && key === 'snacks') meal = parseMeal(o.snack)
    return meal.text ? { label, text: meal.text, swapHint: meal.swapHint } : null
  }).filter((x): x is { label: string; text: string; swapHint: string } => !!x)
}

function statusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || ''
}

function isAiSource(source?: string) {
  return source === 'LLM' || source === 'LLM_THEN_EDIT'
}

function sourceLabel(source?: string) {
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || ''
}

/** 角标强调审核责任；人工智能起草写在方案说明里，避免只剩「AI 生成」。 */
function reviewLabel(plan: PrintableCarePlan) {
  if (!isAiSource(plan.source)) return sourceLabel(plan.source)
  if (plan.status === 'DRAFT') return '待健管师确认'
  if (plan.signStatus === 'SIGNED') return '医生已签署'
  return '健管师已审核'
}

function aiNote(plan: PrintableCarePlan) {
  if (!isAiSource(plan.source)) return ''
  if (plan.status === 'DRAFT') {
    return `<section class="ai"><strong>方案说明</strong><p>本方案由人工智能辅助起草，尚未发布，需健康管理师确认后才会提供给患者。仅供健康管理参考，不构成医学诊断或处方。</p></section>`
  }
  const reviewed =
    plan.signStatus === 'SIGNED'
      ? '本方案由人工智能辅助起草，经健康管理师审核，并由医生签署后发布'
      : '本方案由人工智能辅助起草，并经健康管理师审核后发布'
  return `<section class="ai"><strong>方案说明</strong><p>${reviewed}，供健康管理参考，不构成医学诊断或处方。如有不适请及时就医或联系健管师。</p></section>`
}

function checkinStatusLabel(status: string | undefined, patient: boolean) {
  if (status === 'DONE') return '已完成'
  if (status === 'SKIPPED') return '已跳过'
  if (status === 'MISSED') return patient ? '未完成' : '未打卡'
  return status || '—'
}

function formatDateTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function printFileName(plan: PrintableCarePlan): string {
  const raw = (plan.title || '').trim() || '健康管理方案'
  const safe = raw
    .replace(/(\d{1,2})\/(\d{1,2})/g, '$1-$2')
    .replace(/[–—]/g, '至')
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
  const name = (plan.peopleName || '').trim()
  const titled = name && !safe.includes(name) ? `${name}-${safe}` : safe
  return titled || '健康管理方案'
}

/** 用段落+显式项目符，避免 ul 跨页时打印引擎丢掉圆点 */
function listHtml(items: string[], ordered = false): string {
  if (!items.length) return ''
  const rows = items
    .map((item, i) => {
      const mark = ordered ? `${i + 1}.` : '•'
      return `<p class="bullet"><span class="bullet-mark">${mark}</span><span class="bullet-text">${esc(item)}</span></p>`
    })
    .join('')
  return `<div class="bullets">${rows}</div>`
}

function tagsHtml(label: string, tone: string, items: Array<{ label?: string; code?: string } | string> | undefined): string {
  const labels = (items || []).map((item) => foodLabel(item)).filter(Boolean)
  if (!labels.length) return ''
  return `<div class="tags"><span class="tags-label">${esc(label)}</span>${labels
    .map((text) => `<span class="tag tag--${tone}">${esc(text)}</span>`)
    .join('')}</div>`
}

function allergenKeywordsOf(plan: PrintableCarePlan): string[] {
  const words = new Set<string>()
  for (const a of plan.allergyAlert?.avoid || []) {
    const n = (a.name || '').trim()
    if (n) words.add(n)
  }
  for (const item of plan.diet?.allergensAvoid || []) {
    const n = foodLabel(item).trim()
    if (n && n !== '无已知过敏') words.add(n)
  }
  return [...words]
}

function allergyUnknownOf(plan: PrintableCarePlan): boolean {
  // 患者 PDF 只标真实过敏冲突，避免「无过敏记录」时把虾蟹标成高危吓人
  if (plan.audience === 'patient') return false
  return allergenKeywordsOf(plan).length === 0 && !(plan.allergyAlert?.intolerances || []).length
}

function splitFoodChips(label: string): string[] {
  return String(label || '')
    .split(/[、，,；;]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 推荐食物：纯文本分行，零 flex/table/胶囊，杜绝打印引擎泄露标签名 */
function recommendedGroupedHtml(plan: PrintableCarePlan): string {
  const items = plan.diet?.recommended || []
  if (!items.length) return ''
  const keys = allergenKeywordsOf(plan)
  const unknown = allergyUnknownOf(plan)
  const groups = FOOD_LABEL_GROUP_META.map((g) => ({ ...g, chips: [] as string[] }))
  for (const item of items) {
    const full = foodLabel(item)
    if (!full) continue
    for (const chip of splitFoodChips(full)) {
      const clean = plainText(chip)
      if (!clean || /^table$/i.test(clean)) continue
      const kind = classifyFoodLabel(clean)
      const g = groups.find((x) => x.key === kind) || groups[groups.length - 1]!
      g.chips.push(clean)
    }
  }
  const rows = groups
    .filter((g) => g.chips.length)
    .map((g) => {
      const body = g.chips
        .map((chip) => {
          const hit = matchKnownAllergen(chip, keys)
          const high = !hit && unknown ? matchHighRiskAllergens(chip)[0] : null
          if (hit) return `${chip}（忌口）`
          if (high) return `${chip}（高敏）`
          return chip
        })
        .join('、')
      return `<p class="food-line"><b>${esc(g.label)}</b>：${esc(body)}</p>`
    })
    .join('')
  return rows ? `<div class="food-groups"><p class="block-title">推荐食物</p>${rows}</div>` : ''
}

function mealPartsHtml(text: string, plan: PrintableCarePlan): string {
  const clean = plainText(text)
  const parts = parseMealStructure(clean, {
    allergenKeywords: allergenKeywordsOf(plan),
    allergyUnknown: allergyUnknownOf(plan),
  }).parts
  if (!parts.length) return esc(clean)
  return parts
    .map((p: MealPart) => {
      const lab = MEAL_PART_LABELS[p.kind] || '其他'
      const body = plainText(p.text)
      let flag = ''
      if (p.allergenHit) flag = `<span class="meal-flag meal-flag--bad" title="命中过敏：${esc(p.allergenHit)}">×</span>`
      else if (p.highRisk) flag = `<span class="meal-flag meal-flag--warn" title="高致敏，请确认过敏史">!</span>`
      const tone = p.allergenHit ? 'hit' : p.highRisk ? 'warn' : p.kind
      return `<span class="meal-part meal-part--${tone}"><em>${esc(lab)}</em>${esc(body)}${flag}</span>`
    })
    .join('')
}

function mealLines(rows: Array<{ label: string; text: string; swapHint: string }>, plan: PrintableCarePlan): string {
  return rows
    .map(
      (row) =>
        `<li><strong>${esc(row.label)}</strong><div class="meal-parts">${mealPartsHtml(row.text, plan)}</div>${
          row.swapHint ? `<span class="swap">可替换：${esc(row.swapHint)}</span>` : ''
        }</li>`,
    )
    .join('')
}

function exerciseGlanceHtml(weeklyPlan: Array<{ day?: string; items?: unknown[] }> | undefined): string {
  const aerobicType = new Map<string, { count: number; minutes: number }>()
  let strengthCount = 0
  let stretchCount = 0
  let aerobicRaw = 0
  let moderateEquiv = 0
  let any = 0
  for (const day of weeklyPlan || []) {
    for (const raw of day.items || []) {
      if (!raw || typeof raw !== 'object') continue
      const item = raw as { type?: string; title?: string; intensity?: string; durationMin?: number }
      const m = Number(item.durationMin) || 0
      if (m <= 0) continue
      any += 1
      const kind = carePlanExerciseKind(item.type)
      if (kind === 'strength') {
        strengthCount += 1
      } else if (kind === 'stretch') {
        stretchCount += 1
      } else {
        aerobicRaw += m
        moderateEquiv += carePlanModerateEquivMin(item)
        const label =
          lookup(EXERCISE_TYPE_LABELS, item.type != null ? String(item.type) : '') ||
          (item.title != null ? String(item.title).trim() : '') ||
          '有氧'
        const cur = aerobicType.get(label) || { count: 0, minutes: 0 }
        cur.count += 1
        cur.minutes += m
        aerobicType.set(label, cur)
      }
    }
  }
  if (!any) return ''
  const aerobicBits = [...aerobicType.entries()]
    .map(([label, v]) => `${v.count} 次${label}`)
    .join('、')
  const extras = [
    strengthCount ? `${strengthCount} 次力量训练` : '',
    stretchCount ? `${stretchCount} 次拉伸` : '',
  ]
    .filter(Boolean)
    .join('和')
  // 与一周安排逐项对齐：用实际有氧分钟与类型次数，避免「课次」口径混淆
  const lead =
    aerobicRaw > 0
      ? `本周累计有氧 ${aerobicRaw} 分钟${aerobicBits ? `（含 ${aerobicBits}）` : ''}${
          extras ? `，此外包含 ${extras}` : ''
        }。`
      : `本周安排${extras || '运动练习'}。`
  const note =
    moderateEquiv > 0 && moderateEquiv !== aerobicRaw
      ? `<p class="glance-note">指南折算中等强度有氧约 ${moderateEquiv} 分钟（高强度按 2 倍计入；力量/拉伸不计入有氧达标）</p>`
      : moderateEquiv > 0
        ? `<p class="glance-note">上述有氧均按中等强度计；力量/拉伸不计入有氧达标</p>`
        : ''
  return `<div class="glance glance--plain"><p class="glance-lead">${esc(lead)}</p>${note}</div>`
}

function rationaleHtml(text: string | undefined, patient: boolean, label = '依据'): string {
  const value = (text || '').trim()
  if (patient || !value) return ''
  return `<p class="rationale"><span>${esc(label)}</span>${esc(value)}</p>`
}

function buildExercise(plan: PrintableCarePlan, patient: boolean): string {
  const ex = plan.exercise
  if (!ex) return ''
  const contra = normalizeBulletItems(ex.contraindications)
  const precautions = normalizeBulletItems(ex.precautions)
  const days = (ex.weeklyPlan || [])
    .map((day) => {
      const items = Array.isArray(day.items) ? day.items : []
      const lines = items
        .map((item) => {
          if (!item || typeof item !== 'object') return ''
          const o = item as Record<string, unknown>
          const type =
            lookup(EXERCISE_TYPE_LABELS, o.type != null ? String(o.type) : '') ||
            (o.title != null ? String(o.title) : '') ||
            '运动'
          const intensity = lookup(INTENSITY_LABELS, o.intensity != null ? String(o.intensity) : '')
          const duration = o.durationMin != null && o.durationMin !== '' ? `${o.durationMin} 分钟` : ''
          const note = o.note != null ? String(o.note).trim() : ''
          const meta = [intensity && `强度${intensity}`, duration].filter(Boolean).join(' · ')
          return `<li><strong>${esc(type)}</strong>${meta ? ` ${esc(meta)}` : ''}${
            note ? `<span class="swap">${esc(note)}</span>` : ''
          }</li>`
        })
        .filter(Boolean)
      if (!lines.length) return ''
      const dayLabel = DAY_LABELS[String(day.day || '').toUpperCase()] || String(day.day || '')
      return `<div class="week-day"><div class="week-name">${esc(dayLabel)}</div><ul>${lines.join('')}</ul></div>`
    })
    .filter(Boolean)
  const goal = (ex.goal || '').trim()
  const glance = exerciseGlanceHtml(ex.weeklyPlan)
  if (
    !goal &&
    !contra.length &&
    !precautions.length &&
    !ex.reviewHint &&
    !days.length &&
    !glance &&
    !(ex.rationale || '').trim()
  ) {
    return ''
  }
  // 概述与一周安排拆卡，避免长卡跨页把注意事项列表截断成无项目符粘句
  const overview = card(
    '运动方案',
    'exercise',
    `${goal ? `<p class="lead">${esc(goal)}</p>` : ''}${rationaleHtml(ex.rationale, patient)}${glance}${
      contra.length ? `<div class="block block--warn"><p class="block-title">禁忌</p>${listHtml(contra)}</div>` : ''
    }${
      precautions.length
        ? `<div class="block block--warn"><p class="block-title">注意事项</p>${listHtml(precautions)}</div>`
        : ''
    }${ex.reviewHint ? `<p class="hint">${esc(ex.reviewHint)}</p>` : ''}`,
  )
  const weekCard = days.length
    ? card('运动 · 一周安排', 'exercise', `<div class="week-days">${days.join('')}</div>`)
    : ''
  return [overview, weekCard].filter(Boolean).join('')
}

function energyHtml(plan: PrintableCarePlan): { card: string; tips: string } {
  const diet = plan.diet
  const t = diet?.energyTarget
  const kcal = t && typeof t.energyKcal === 'number' && t.energyKcal > 0 ? t : null
  let tips = (diet?.calorieHint || '').trim()
  if (kcal) {
    tips = tips.replace(/^每日约?\s*\d{3,4}\s*(千卡|大卡|kcal)\s*([（(][^）)]*[）)])?\s*[，,；;]?\s*/i, '')
    tips = tips.replace(/^(以维持体重为目标|按每周[减增]重\s*[\d.]+\s*kg)\s*[，,；;]?\s*/i, '')
    tips = tips.replace(/^[；;，,。]+/, '').trim()
  }
  if (!kcal) return { card: '', tips }
  const pct = (grams: number, factor: number) =>
    kcal.energyKcal ? Math.round(((grams * factor) / kcal.energyKcal) * 100) : 0
  const macros = [
    { key: 'carb', label: '碳水', grams: kcal.carbG, pct: pct(kcal.carbG ?? 0, 4) },
    { key: 'protein', label: '蛋白质', grams: kcal.proteinG, pct: pct(kcal.proteinG ?? 0, 4) },
    { key: 'fat', label: '脂肪', grams: kcal.fatG, pct: pct(kcal.fatG ?? 0, 9) },
  ]
  let goal = '维持体重'
  if (kcal.goalType === 'LOSE') {
    goal = kcal.rateKgPerWeek ? `减重 · 每周约 ${kcal.rateKgPerWeek} kg` : '减重'
  } else if (kcal.goalType === 'GAIN') {
    goal = kcal.rateKgPerWeek ? `增重 · 每周约 ${kcal.rateKgPerWeek} kg` : '增重'
  }
  const cardHtml = `<div class="energy">
    <div class="energy-main"><strong>${esc(kcal.energyKcal)}</strong><span>千卡 / 日</span><em>${esc(goal)}</em></div>
    <div class="macros">${macros
      .map(
        (m) => `<div class="macro macro--${m.key}">
          <div class="macro-head"><span>${m.label}</span><em>${m.pct}%</em></div>
          <div class="macro-grams">${m.grams ?? '—'}<small>g</small></div>
          <div class="macro-bar"><i style="width:${Math.min(m.pct, 100)}%"></i></div>
        </div>`,
      )
      .join('')}</div>
    ${tips ? `<p class="energy-tips">${esc(tips)}</p>` : ''}
  </div>`
  return { card: cardHtml, tips: '' }
}

const OSA_PRINT_HINT =
  '睡眠呼吸：建议尽快前往呼吸内科或耳鼻喉科进行睡眠呼吸监测（多导睡眠图）。打鼾严重会明显影响血压和血糖控制，请勿忽视。'

/** 已生成方案未重跑后端时，从总结/依据文案识别 OSA 高危并补强执行提醒 */
function ensureOsaNoteLines(plan: PrintableCarePlan, notes: string[]): string[] {
  const hasStrong = notes.some((n) => n.includes('睡眠呼吸监测') || n.includes('多导睡眠'))
  if (hasStrong) return notes.filter((n) => !/关注睡眠呼吸|留意睡眠呼吸|注意睡眠呼吸/.test(n))
  const blob = [plan.summary, plan.rationaleOverview, plan.goalSummary, plan.diet?.notes]
    .map((s) => String(s || ''))
    .join(' ')
  const snore = /打鼾|打呼噜|SNORING/.test(blob)
  const fatigue = /疲惫|疲劳|不解乏|未解乏/.test(blob)
  const risk = /肥胖|超重|BMI|糖尿病|高血压|糖压/.test(blob)
  if (!(snore && fatigue && risk)) return notes
  return [...notes.filter((n) => !/关注睡眠呼吸|留意睡眠呼吸|注意睡眠呼吸/.test(n)), OSA_PRINT_HINT]
}

function buildDiet(plan: PrintableCarePlan, patient: boolean): string {
  const diet = plan.diet
  const principles = splitSentences(diet?.principles, /[；;]/)
  const notes = ensureOsaNoteLines(plan, patientDietNoteLines(diet?.notes || ''))
  const energy = energyHtml(plan)
  const sampleDay = mealsFromSample(diet?.sampleDay)
  const sampleWeek = Object.entries(DAY_LABELS)
    .map(([code, label]) => {
      const meals = mealsFromSample(diet?.sampleWeek?.[code])
      return meals.length ? { label, meals } : null
    })
    .filter((x): x is { label: string; meals: Array<{ label: string; text: string; swapHint: string }> } => !!x)
  const alert = plan.allergyAlert
  const avoid = (alert?.avoid || []).map((a) => (a.name || '').trim()).filter(Boolean)
  const alertHtml = alert
    ? `${
        avoid.length || (alert.intolerances || []).length
          ? `<div class="block block--warn"><p class="block-title">过敏提醒</p>${
              avoid.length
                ? `<p>请避免食用：${alert.avoid!
                    .filter((a) => (a.name || '').trim())
                    .map((a) => `${esc(a.name)}${a.highRisk ? '（高危）' : ''}`)
                    .join('、')}。外出就餐、购买加工食品时注意查看配料。</p>`
                : ''
            }${
              (alert.intolerances || []).length
                ? `<p>不耐受：${esc(alert.intolerances!.join('、'))}，可少量尝试，注意食用量。</p>`
                : ''
            }${
              alert.avoid?.some((a) => a.highRisk)
                ? '<p>如误食后出现呼吸困难、喉头发紧、全身风团或头晕，请立即就医或拨打 120。</p>'
                : ''
            }</div>`
          : ''
      }`
    : ''
  // 拆成多张卡，避免超长单卡跨页时打印引擎截断 DOM（表现为后半周碎裂、标签变纯文本、甚至重复）
  const overview = [
    rationaleHtml(diet?.rationale, patient),
    energy.card,
    !energy.card && energy.tips ? `<p class="hint">${esc(energy.tips)}</p>` : '',
    principles.length ? `<div class="block block--diet"><p class="block-title">饮食原则</p>${listHtml(principles, true)}</div>` : '',
    notes.length ? `<div class="block block--warn"><p class="block-title">执行提醒</p>${listHtml(notes)}</div>` : '',
    recommendedGroupedHtml(plan),
    tagsHtml('限制食物', 'warn', diet?.limited),
    alert ? '' : tagsHtml('过敏回避', 'danger', diet?.allergensAvoid),
    alert ? '' : tagsHtml('不耐受', 'warn', diet?.intolerances),
    alertHtml,
  ]
    .filter(Boolean)
    .join('')
  const sampleDayCard = sampleDay.length
    ? card('示例日', 'diet', `<ul class="meal-list">${mealLines(sampleDay, plan)}</ul>`)
    : ''
  // 每天一张独立卡片 + 纯文本餐次：彻底避开跨页截断 DOM / Markdown 标题泄露
  const weekDayCards = sampleWeek
    .map((day, idx) => {
      const meals = ['早餐', '午餐', '晚餐', '加餐']
        .map((name) => {
          const meal = day.meals.find((item) => item.label === name)
          if (!meal) return ''
          const text = plainText(meal.text)
          if (!text) return ''
          const swap = meal.swapHint ? `<span class="swap">可替换：${esc(plainText(meal.swapHint))}</span>` : ''
          return `<p class="diet-meal"><b>${esc(name)}</b>：${esc(text)}${swap}</p>`
        })
        .filter(Boolean)
        .join('')
      if (!meals) return ''
      return card(`${day.label}饮食`, 'diet', meals, { pageBreak: idx === 3 })
    })
    .filter(Boolean)
    .join('')
  const overviewCard = overview ? card('饮食方案', 'diet', overview) : ''
  return [overviewCard, sampleDayCard, weekDayCards].filter(Boolean).join('')
}

function buildExecution(plan: PrintableCarePlan, patient: boolean): string {
  const execution = plan.execution
  // 患者导出只保留启用任务（与患者端今日待办一致）；B 端仍展示未启用并标注
  const tasks = (execution?.tasks || []).filter((task) => {
    if (!(task.title || '').trim()) return false
    if (patient && task.enabled === false) return false
    return true
  })
  const rationale = rationaleHtml(execution?.rationale, patient)
  if (!tasks.length && !rationale) return ''
  // 单列卡片流：避免宽表在 A4 上右侧被裁切
  const cards = tasks
    .map((task) => {
      const pills = [
        lookup(CATEGORY_LABELS, task.category),
        lookup(FREQUENCY_LABELS, task.frequency),
        lookup(TIME_SLOT_LABELS, task.timeSlot),
        task.targetMl ? `${task.targetMl} ml` : '',
        !patient && task.enabled === false ? '未启用' : '',
      ].filter(Boolean)
      const why = (task.rationale || '').trim() ? `<div class="why">${esc(task.rationale)}</div>` : ''
      const meta = pills.length
        ? `<div class="exec-meta">${pills.map((p) => `<span class="exec-pill">${esc(p)}</span>`).join('')}</div>`
        : ''
      return `<div class="exec-card${task.enabled === false ? ' exec-card--off' : ''}"><div class="exec-title">${esc(
        task.title,
      )}</div>${why}${meta}</div>`
    })
    .join('')
  const badge = execution?.horizonDays ? `<span class="badge">${execution.horizonDays} 天</span>` : ''
  return card(
    `执行计划${badge}`,
    'execution',
    `${rationale}${cards ? `<div class="exec-cards">${cards}</div>` : '<p class="muted">暂无任务</p>'}`,
  )
}

function buildCheckins(plan: PrintableCarePlan, patient: boolean): string {
  // 患者成品 / 无记录：不输出该模块，避免「暂无打卡记录」占页
  if (patient || !plan.checkins?.length) return ''
  const rows = plan.checkins
    .map(
      (row) =>
        `<tr><td>${esc(row.checkinDate || '—')}</td><td>${esc(row.taskTitle || '任务')}</td><td>${esc(
          lookup(CATEGORY_LABELS, row.taskCategory) || '—',
        )}</td><td>${esc(lookup(TIME_SLOT_LABELS, row.timeSlot) || '—')}</td><td>${esc(
          checkinStatusLabel(row.status, patient),
        )}</td><td>${esc(row.note || '')}</td></tr>`,
    )
    .join('')
  return card(
    `打卡记录<span class="badge">${plan.checkins.length} 条</span>`,
    'checkins',
    `<table><thead><tr><th>日期</th><th>任务</th><th>类别</th><th>时段</th><th>状态</th><th>备注</th></tr></thead><tbody>${rows}</tbody></table>`,
  )
}

/** 患者可见参考依据：去掉系统算法兜底话术，只保留指南表述 */
function patientFacingRefText(text: string): string {
  let t = plainText(text)
  t = t.replace(/[；;,]?\s*系统按体重估算饮水目标并限定在\s*1200\s*[–—\-至~～]\s*2000\s*ml/gi, '')
  t = t.replace(/并限定在\s*1200\s*[–—\-至~～]\s*2000\s*ml/gi, '')
  t = t.replace(/系统按体重估算[^。；;]*/g, '')
  t = t.replace(/^[；;，,\s]+|[；;，,\s]+$/g, '').trim()
  if (/足量饮水|少量多次/.test(t) && t.length < 8) {
    return '足量饮水、少量多次（参考《中国居民膳食指南》）'
  }
  if (!t && /饮水|WATER/i.test(text)) {
    return '足量饮水、少量多次（参考《中国居民膳食指南》）'
  }
  return t || plainText(text)
}

function buildReferences(plan: PrintableCarePlan, patient: boolean): string {
  const refs = plan.guidelineRefs ?? []
  if (patient || !refs.length) return ''
  const items = refs
    .map((r) => {
      const text = patientFacingRefText(r.text || '')
      if (!text) return ''
      return `<li>${esc(text)}<div class="src">${esc(r.source)}</div></li>`
    })
    .filter(Boolean)
    .join('')
  if (!items) return ''
  return card(
    '参考依据',
    'refs',
    `<ul class="refs">${items}</ul><p class="why">条目为对公开指南推荐意见的转述，仅供健康管理参考，以指南原文为准。</p>`,
  )
}

/** 三块及任务上引用的条目编码，按出现顺序去重。 */
export function carePlanRefCodes(plan: PrintableCarePlan): string[] {
  const codes = [
    ...(plan.exercise?.refs ?? []),
    ...(plan.diet?.refs ?? []),
    ...(plan.execution?.refs ?? []),
    ...(plan.execution?.tasks ?? []).flatMap((t) => t.refs ?? []),
  ]
  return [...new Set(codes.filter((c) => typeof c === 'string' && c))]
}

async function resolveGuidelineRefs(plan: PrintableCarePlan): Promise<PrintableCarePlan> {
  if (plan.audience === 'patient' || plan.guidelineRefs) return plan
  const codes = carePlanRefCodes(plan)
  if (!codes.length) return plan
  const lib = await loadGuidelineLibrary().catch(() => null)
  const index = guidelineIndex(lib)
  const guidelineRefs = codes.flatMap((code) => {
    const rec = index.get(code)
    return rec ? [{ code, source: rec.sourceLabel, text: rec.staffText }] : []
  })
  return { ...plan, guidelineRefs }
}

function card(
  title: string,
  tone: string,
  body: string,
  opts?: { pageBreak?: boolean },
): string {
  const page = opts?.pageBreak ? ' card--page' : ''
  return `<section class="card card--${tone}${page}"><h2>${title}</h2>${body}</section>`
}

const PRINT_CSS = `
  @page { size: A4; margin: 0; }
  * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  body {
    margin: 0;
    padding: 0;
    font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    font-size: 12px;
    line-height: 1.55;
    color: #13222e;
    background: #f7fbf9;
  }
  .sheet { padding: 10mm 12mm 12mm; }
  .hero, .card, .energy, .ai {
    background: #fff;
    border: 1px solid #e6eef2;
    border-radius: 14px;
  }
  .hero { padding: 16px 18px; margin-bottom: 12px; }
  .pills { margin-bottom: 8px; line-height: 1.8; }
  .pills .pill { margin-right: 6px; }
  .pill {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 700;
    background: #e8eef2;
    color: #3d4b58;
  }
  .pill--ink { background: #13222e; color: #fff; }
  .pill--review { background: #e7f6f2; color: #0f766e; }
  h1 { margin: 0; font-size: 22px; font-weight: 900; line-height: 1.35; }
  .mark { background: linear-gradient(transparent 62%, #ffd84d 62%); padding: 0 2px; }
  .meta { margin: 6px 0 0; font-size: 12px; font-weight: 600; color: #5b6a77; }
  .goal { margin: 10px 0 0; padding-top: 10px; border-top: 1px dashed #e6eef2; white-space: pre-wrap; }
  .rationale {
    margin: 8px 0 0;
    padding: 8px 10px;
    border-radius: 8px;
    background: #f8fafc;
    border: 1px solid #e8edf4;
    color: #64748b;
    font-size: 12px;
  }
  .rationale span {
    display: inline-block;
    margin-right: 6px;
    padding: 0 6px;
    border-radius: 4px;
    background: #e2e8f0;
    color: #475569;
    font-size: 11px;
    font-weight: 700;
  }
  .block-title {
    display: block;
    margin: 0 0 8px;
    padding: 0;
    background: transparent;
    color: #334155;
    font-size: 12px;
    font-weight: 800;
  }
  .bullets { margin: 0; }
  .bullet {
    display: block;
    margin: 4px 0;
    padding: 0;
    line-height: 1.55;
    break-inside: avoid;
    page-break-inside: avoid;
  }
  .bullet-mark {
    display: inline-block;
    min-width: 1.1em;
    margin-right: 4px;
    font-weight: 800;
    color: #0f172a;
  }
  .bullet-text { color: #13222e; }
  .diet-meal {
    margin: 6px 0;
    line-height: 1.6;
    break-inside: avoid;
    page-break-inside: avoid;
  }
  .card--page {
    break-before: page;
    page-break-before: always;
  }
  .ai { margin-bottom: 12px; padding: 12px 14px; border-color: #bfdbfe; background: #f3f8ff; }
  .ai strong { display: block; margin-bottom: 4px; color: #1e63d9; }
  .ai p { margin: 0; }
  .card { padding: 14px 16px; margin-bottom: 12px; }
  .hero, .ai, .energy, .block, tr { break-inside: avoid; page-break-inside: avoid; }
  h2 { break-after: avoid; page-break-after: avoid; }
  h2 { margin: 0 0 8px; font-size: 15px; font-weight: 800; }
  .card--exercise h2 { color: #0369a1; }
  .card--diet h2 { color: #15803d; }
  .card--execution h2, .card--summary h2 { color: #6d28d9; }
  .card--checkins h2 { color: #0f766e; }
  .card--refs h2 { color: #475569; }
  .refs li { margin-bottom: 4px; }
  .refs .src { color: #64748b; font-size: 11px; }
  .badge {
    margin-left: 8px;
    padding: 1px 8px;
    border-radius: 999px;
    background: #ede9fe;
    color: #6d28d9;
    font-size: 11px;
    font-weight: 700;
  }
  .lead { margin: 0; white-space: pre-wrap; }
  .muted { margin: 0; color: #8b98a5; }
  .hint { margin: 10px 0 0; padding: 8px 10px; border-radius: 8px; background: #f0f9ff; color: #0369a1; }
  .block { margin-top: 10px; padding: 8px 10px; border-radius: 10px; background: #f8fafc; }
  .block--warn { background: #fff7ed; }
  .block--diet { background: #f0fdf4; }
  ul, ol { margin: 6px 0 0; padding-left: 18px; list-style: disc; }
  li { margin: 3px 0; break-inside: avoid; page-break-inside: avoid; }
  .swap {
    display: block;
    margin-top: 4px;
    padding: 4px 8px;
    border: 1px dashed #cbd5e1;
    border-radius: 6px;
    color: #64748b;
    font-size: 11px;
    background: #f8fafc;
  }
  .week-days .week-day {
    margin-top: 8px;
    break-inside: avoid;
    page-break-inside: avoid;
  }
  .exec-cards { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
  .exec-card {
    padding: 10px 12px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #fff;
    break-inside: avoid;
    page-break-inside: avoid;
  }
  .exec-card--off { opacity: 0.65; background: #f8fafc; }
  .exec-title { font-size: 13px; font-weight: 800; color: #0f172a; word-break: break-word; }
  .exec-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
  .exec-pill {
    display: inline-block;
    padding: 1px 8px;
    border-radius: 999px;
    background: #f1f5f9;
    color: #475569;
    font-size: 11px;
    font-weight: 700;
  }
  .week-name { margin-top: 8px; font-weight: 800; }
  .glance {
    margin-top: 10px;
    padding: 10px 12px;
    border-radius: 12px;
    background: #f0f9ff;
    border: 1px solid #dbeafe;
  }
  .glance-lead { margin: 0; font-size: 13px; color: #0f172a; line-height: 1.5; }
  .glance-note {
    margin: 6px 0 0;
    font-size: 11px;
    color: #64748b;
    text-align: left;
  }
  .energy { margin-top: 8px; padding: 12px; }
  .energy-main strong { font-size: 26px; }
  .energy-main span, .energy-main em { margin-left: 6px; color: #5b6a77; font-style: normal; }
  .macros { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-top: 10px; }
  .macro-head { display: flex; justify-content: space-between; font-size: 11px; color: #5b6a77; }
  .macro-grams { font-size: 16px; font-weight: 800; }
  .macro-grams small { margin-left: 2px; font-size: 11px; font-weight: 600; }
  .macro-bar { height: 6px; border-radius: 999px; background: #e8eef2; overflow: hidden; }
  .macro-bar i { display: block; height: 100%; border-radius: inherit; background: #12a389; }
  .macro--protein .macro-bar i { background: #5b8def; }
  .macro--fat .macro-bar i { background: #e5a100; }
  .energy-tips { margin: 8px 0 0; color: #5b6a77; }
  .food-groups { margin-top: 10px; }
  .food-line { margin: 6px 0 0; line-height: 1.7; color: #13222e; }
  .food-line b { color: #334155; }
  .tags { margin-top: 10px; line-height: 1.9; }
  .tags .tag { display: inline-block; margin: 0 6px 4px 0; }
  .tags-label { font-size: 11px; font-weight: 700; color: #5b6a77; margin-right: 6px; }
  .tag { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; }
  .tag--ok { background: #e3f6ef; color: #0b7a66; }
  .tag--warn { background: #fff3da; color: #8a5c00; }
  .tag--danger { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
  .tag--risk { background: #fffbeb; color: #b45309; border: 1px solid #fcd34d; }
  .meal-list li { margin: 8px 0; }
  /* 不用 flex：部分打印引擎跨页时会把 flex 误序列化成 table 并泄露标签名 */
  .meal-parts { margin-top: 4px; line-height: 1.9; }
  .meal-part {
    display: inline-block;
    vertical-align: middle;
    margin: 0 4px 4px 0;
    padding: 2px 8px;
    border-radius: 6px;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    font-size: 11px;
  }
  .meal-part em { font-style: normal; font-weight: 800; color: #64748b; font-size: 10px; }
  .meal-part--staple { background: #fff7ed; border-color: #fed7aa; }
  .meal-part--protein, .meal-part--dairy { background: #eff6ff; border-color: #bfdbfe; }
  .meal-part--veg { background: #f0fdf4; border-color: #bbf7d0; }
  .meal-part--fruit { background: #fdf4ff; border-color: #f5d0fe; }
  .meal-part--hit { background: #fef2f2; border-color: #fecaca; color: #b91c1c; font-weight: 700; }
  .meal-part--warn { background: #fffbeb; border-color: #fcd34d; }
  .meal-flag {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    font-size: 9px;
    font-weight: 900;
    color: #fff;
  }
  .meal-flag--bad { background: #ef4444; }
  .meal-flag--warn { background: #f59e0b; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  th, td { padding: 6px 8px; border-bottom: 1px solid #e6eef2; text-align: left; vertical-align: top; word-break: break-word; }
  th { font-size: 11px; color: #5b6a77; }
  .why { margin-top: 4px; color: #64748b; font-size: 11px; font-weight: 500; line-height: 1.45; }
  .footer {
    margin-top: 10px;
    padding-top: 6px;
    font-size: 11px;
    color: #8b98a5;
    break-before: avoid-page;
    page-break-before: avoid;
  }
`

export function buildCarePlanPrintHtml(plan: PrintableCarePlan): string {
  const patient = plan.audience === 'patient'
  const fileName = printFileName(plan)
  const title = (plan.title || '').trim() || '健康管理方案'
  const reviewed = reviewLabel(plan)
  const pills = [
    statusLabel(plan.status) ? `<span class="pill pill--ink">${esc(statusLabel(plan.status))}</span>` : '',
    reviewed ? `<span class="pill${isAiSource(plan.source) ? ' pill--review' : ''}">${esc(reviewed)}</span>` : '',
  ]
    .filter(Boolean)
    .join('')
  const meta = [plan.peopleName, plan.versionLabel, plan.publishedAt ? `${formatDateTime(plan.publishedAt)} 发布` : '']
    .filter(Boolean)
    .join(' · ')
  const ai = aiNote(plan)
  const summary = (plan.summary || '').trim()
  const summaryCard = summary ? card('方案总结', 'summary', `<p class="lead">${esc(summary)}</p>`) : ''
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${esc(fileName)}</title>
  <style>${PRINT_CSS}</style>
</head>
<body>
  <div class="sheet">
  <header class="hero">
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    <h1><span class="mark">${esc(title)}</span></h1>
    ${meta ? `<p class="meta">${esc(meta)}</p>` : ''}
    ${plan.goalSummary ? `<p class="goal">${esc(plan.goalSummary)}</p>` : ''}
    ${rationaleHtml(plan.rationaleOverview, patient, '生成依据')}
  </header>
  ${ai}
  ${summaryCard}
  ${buildExercise(plan, patient)}
  ${buildDiet(plan, patient)}
  ${buildExecution(plan, patient)}
  ${buildCheckins(plan, patient)}
  ${buildReferences(plan, patient)}
  <p class="footer">本方案仅用于健康管理参考，不作为诊断依据；如有不适请及时就医。</p>
  </div>
</body>
</html>`
}

/** 在隐藏 iframe 中打印，避免弹窗拦截与当前页面样式污染。 */
export async function printCarePlan(input: PrintableCarePlan): Promise<boolean> {
  const plan = await resolveGuidelineRefs(input)
  return new Promise((resolve) => {
    const iframe = document.createElement('iframe')
    iframe.setAttribute('aria-hidden', 'true')
    iframe.style.position = 'fixed'
    iframe.style.left = '-10000px'
    iframe.style.top = '0'
    iframe.style.width = '210mm'
    iframe.style.height = '297mm'
    iframe.style.border = '0'
    document.body.appendChild(iframe)

    const cleanup = () => {
      window.setTimeout(() => iframe.remove(), 1000)
    }
    const fileName = printFileName(plan)
    const previousTitle = document.title
    const restoreTitle = () => {
      document.title = previousTitle
    }

    iframe.onload = () => {
      try {
        const win = iframe.contentWindow
        if (!win) {
          cleanup()
          resolve(false)
          return
        }
        document.title = fileName
        const finish = () => {
          restoreTitle()
          cleanup()
        }
        win.addEventListener('afterprint', finish, { once: true })
        window.addEventListener('afterprint', finish, { once: true })
        win.focus()
        win.print()
        resolve(true)
      } catch {
        restoreTitle()
        cleanup()
        resolve(false)
      }
    }

    const doc = iframe.contentDocument
    if (!doc) {
      cleanup()
      resolve(false)
      return
    }
    doc.open()
    doc.write(buildCarePlanPrintHtml(plan))
    doc.close()
  })
}
