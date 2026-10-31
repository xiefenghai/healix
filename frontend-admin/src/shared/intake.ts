/**
 * B 端首诊问卷工作台：类型、API 与显示条件 / 高亮判定辅助。
 */
import { api } from './http'
import {
  formatFamilyHistoryTag,
  type FamilyHistoryEntry,
  type KinshipLevel,
} from './family-history-options'

export type IntakeProgramCode = 'WEIGHT_LOSS' | 'NONE'

export type IntakeFieldType =
  | 'TEXT'
  | 'NUMBER'
  | 'ENUM'
  | 'MULTI'
  | 'DATE'
  | 'TIME'
  | 'LIST'
  | 'OBJECT'
  | string

export type IntakeItemLevel = 'REQUIRED' | 'FIRST_VISIT' | 'LATER' | 'STAFF' | string

export interface IntakeShowIf {
  field?: string
  eq?: string
  in?: string[]
  notIn?: string[]
  intersects?: string[]
  all?: IntakeShowIf[]
  any?: IntakeShowIf[]
}

export interface IntakeHighlightWhen {
  always?: boolean
  eq?: string
  in?: string[]
  notIn?: string[]
  intersects?: string[]
  op?: string
  value?: number
  text?: string
  computed?: string
}

export interface IntakeHighlightRule {
  when?: IntakeHighlightWhen
  severity?: 'CAUTION' | 'REFER' | 'BLOCK' | string
  followUp?: string | null
  planEffect?: string | null
}

export interface IntakeItemView {
  field: string
  label: string
  level: IntakeItemLevel
  type?: IntakeFieldType | null
  unit?: string | null
  /** 选项字典 parentCode */
  options?: string | null
  /** 数值题下限（field-catalog） */
  min?: number | null
  /** 数值题上限（field-catalog） */
  max?: number | null
  /** 多选互斥码，如 NONE / UNKNOWN */
  exclusive?: string[] | null
  sensitive?: boolean
  showIf?: IntakeShowIf | null
  highlights?: IntakeHighlightRule[] | null
}

/** 多选：点互斥项则只保留该项；点其他项则清掉互斥项 */
export function toggleExclusiveMulti(
  prev: string[],
  code: string,
  exclusive: string[] | null | undefined,
  availableCodes?: string[],
): string[] {
  const exclusiveSet = new Set(
    (exclusive?.length
      ? exclusive
      : ['NONE', 'UNKNOWN'].filter((c) => !availableCodes || availableCodes.includes(c))
    ).map(String),
  )
  const cur = [...prev]
  const idx = cur.indexOf(code)
  if (idx >= 0) {
    cur.splice(idx, 1)
    return cur
  }
  if (exclusiveSet.has(code)) return [code]
  return [...cur.filter((c) => !exclusiveSet.has(c)), code]
}

export interface IntakeModuleView {
  code: string
  version?: number
  title: string
  script?: string | null
  items: IntakeItemView[]
}

export interface IntakePrefillCell {
  value: unknown
  sourceBizCode?: string | null
  gmtModified?: string | null
}

export interface IntakeCompleteness {
  requiredDone?: number
  requiredTotal?: number
  firstVisitDone?: number
  firstVisitTotal?: number
  allDone?: number
  allTotal?: number
  requiredMissing?: string[]
  firstVisitMissing?: string[]
}

export interface IntakeHighlightHit {
  field: string
  severity?: string
  planEffect?: string | null
  followUp?: string | null
}

/** 高亮严重程度码 → 界面中文（数据仍存 CAUTION / REFER / BLOCK） */
const SEVERITY_LABELS: Record<string, string> = {
  CAUTION: '谨慎',
  REFER: '转介',
  BLOCK: '阻断',
}

export function severityLabel(severity?: string | null): string {
  if (!severity) return '提示'
  return SEVERITY_LABELS[severity] || severity
}

export interface QuestionnaireResponseView {
  id: string
  peopleId: string
  questionnaireCode?: string
  questionnaireVersion?: number
  purpose?: string
  status?: string
  channel?: string | null
  programCode?: string | null
  enrollmentId?: string | null
  followupRecordId?: string | null
  title?: string | null
  prefill?: Record<string, IntakePrefillCell> | null
  answers?: Record<string, unknown> | null
  highlights?: IntakeHighlightHit[] | null
  completeness?: IntakeCompleteness | null
  changes?: Record<string, { from?: unknown; to?: unknown }> | null
  modules?: IntakeModuleView[] | null
  submittedAt?: string | null
}

export interface OpenIntakeBody {
  followupRecordId?: string | null
  programCode?: string | null
  channel?: string | null
}

export interface IntakePendingField {
  field: string
  label?: string
  level?: string
  moduleCode?: string
  moduleTitle?: string
}

export interface IntakeStatusView {
  hasSubmitted: boolean
  /** 最近一次已提交作答，供只读详情 */
  latestSubmittedId?: string | null
  submittedAt?: string | null
  questionnaireCode?: string
  submittedVersion?: number | null
  latestVersion: number
  pendingCount: number
  pendingFields?: IntakePendingField[]
}

export interface SaveDraftBody {
  answers: Record<string, unknown>
  programCode?: string | null
  channel?: string | null
}

export async function openIntake(peopleId: string, body: OpenIntakeBody = {}) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/patients/${peopleId}/intake/open`,
    { method: 'POST', body: JSON.stringify(body) },
  )
  return res.data
}

export async function getIntakeStatus(peopleId: string) {
  const res = await api<{ data: IntakeStatusView }>(`/api/b/v1/patients/${peopleId}/intake/status`)
  return res.data
}

export async function openIntakeSupplement(peopleId: string, body: OpenIntakeBody = {}) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/patients/${peopleId}/intake/supplement/open`,
    { method: 'POST', body: JSON.stringify(body) },
  )
  return res.data
}

export async function getQuestionnaireResponse(id: string) {
  const res = await api<{ data: QuestionnaireResponseView }>(`/api/b/v1/questionnaire-responses/${id}`)
  return res.data
}

export async function saveIntakeDraft(id: string, body: SaveDraftBody) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/questionnaire-responses/${id}/draft`,
    { method: 'PUT', body: JSON.stringify(body) },
  )
  return res.data
}

export async function submitIntake(id: string, conflictResolutions?: Record<string, unknown>) {
  const res = await api<{ data: QuestionnaireResponseView }>(
    `/api/b/v1/questionnaire-responses/${id}/submit`,
    {
      method: 'POST',
      body: JSON.stringify(conflictResolutions ? { conflictResolutions } : {}),
    },
  )
  return res.data
}

export interface IntakeAiSummaryResult {
  summary: string
  keyPoints?: string[]
  risks?: string[]
  nextActions?: string[]
  fromLlm: boolean
  note?: string | null
}

/** 将 AI 结构化结果拼成案头可编辑条文（重点条目清晰） */
export function formatIntakeAiSummaryText(draft: IntakeAiSummaryResult): string {
  const blocks: string[] = []
  const summary = (draft.summary || '').trim()
  if (summary) blocks.push(summary)

  const hasRiskSection = /【风险/.test(summary)
  const hasNextSection = /【建议下一步】|【下一步】/.test(summary)

  if (draft.keyPoints?.length && !/【要点】/.test(summary)) {
    blocks.push(['【要点】', ...draft.keyPoints.map((k) => `· ${k}`)].join('\n'))
  }
  if (draft.risks?.length && !hasRiskSection) {
    blocks.push(['【风险与禁忌】', ...draft.risks.map((k) => `· ${k}`)].join('\n'))
  }
  if (draft.nextActions?.length && !hasNextSection) {
    blocks.push(['【建议下一步】', ...draft.nextActions.map((k) => `· ${k}`)].join('\n'))
  }
  return blocks.filter(Boolean).join('\n\n')
}

/** 根据当前草稿答案生成 AI 案头总结（不落库）；可传本地 answers 避免未保存草稿过期 */
export async function generateIntakeAiSummary(id: string, answers?: Record<string, unknown>) {
  const res = await api<{ data: IntakeAiSummaryResult }>(
    `/api/b/v1/questionnaire-responses/${id}/ai-summary`,
    {
      method: 'POST',
      body: JSON.stringify(answers ? { answers } : {}),
    },
  )
  return res.data
}

export const FIELD_STAFF_AI_SUMMARY = 'staff.aiSummary'

export function isAnswerEmpty(v: unknown): boolean {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  if (Array.isArray(v)) {
    if (v.length === 0) return true
    // 用药明细：全是空药名视为未填
    if (v.every((item) => item && typeof item === 'object' && 'drugName' in (item as object))) {
      return v.every((item) => !String((item as { drugName?: unknown }).drugName || '').trim())
    }
    return false
  }
  if (typeof v === 'object') return Object.keys(v as object).length === 0
  return false
}

function flattenAnswer(v: unknown): string[] {
  if (v == null) return []
  if (Array.isArray(v)) {
    return v.map((x) => String(x).trim()).filter(Boolean)
  }
  if (typeof v === 'object') return []
  const s = String(v).trim()
  return s ? [s] : []
}

export function matchShowIf(
  showIf: IntakeShowIf | null | undefined,
  answers: Record<string, unknown>,
): boolean {
  if (!showIf) return true
  if (showIf.all?.length) return showIf.all.every((s) => matchShowIf(s, answers))
  if (showIf.any?.length) return showIf.any.some((s) => matchShowIf(s, answers))
  if (!showIf.field) return true
  const actual = flattenAnswer(answers[showIf.field])
  if (showIf.eq != null) {
    return actual.length === 1 && actual[0].toLowerCase() === showIf.eq.toLowerCase()
  }
  if (showIf.in) {
    return actual.some((a) => showIf.in!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (showIf.notIn) {
    if (!actual.length) return true
    return actual.every((a) => showIf.notIn!.every((w) => w.toLowerCase() !== a.toLowerCase()))
  }
  if (showIf.intersects) {
    return actual.some((a) => showIf.intersects!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  return true
}

function compareNum(num: number, op: string, thr: number): boolean {
  switch (op) {
    case '<':
      return num < thr
    case '<=':
    case '≤':
      return num <= thr
    case '>':
      return num > thr
    case '>=':
    case '≥':
      return num >= thr
    default:
      return false
  }
}

export function matchHighlightWhen(when: IntakeHighlightWhen | null | undefined, answer: unknown): boolean {
  if (!when) return false
  if (when.always) return true
  if (when.text != null || when.computed != null) return false
  const actual = flattenAnswer(answer)
  if (when.eq != null) {
    return actual.length === 1 && actual[0].toLowerCase() === when.eq.toLowerCase()
  }
  if (when.in) {
    return actual.some((a) => when.in!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (when.notIn) {
    if (!actual.length) return false
    return actual.every((a) => when.notIn!.every((w) => w.toLowerCase() !== a.toLowerCase()))
  }
  if (when.intersects) {
    return actual.some((a) => when.intersects!.some((w) => w.toLowerCase() === a.toLowerCase()))
  }
  if (when.op != null && when.value != null) {
    if (!actual.length) return false
    const num = Number(actual[0])
    if (Number.isNaN(num)) return false
    return compareNum(num, when.op, Number(when.value))
  }
  return false
}

export function collectLocalHighlights(
  modules: IntakeModuleView[],
  answers: Record<string, unknown>,
): IntakeHighlightHit[] {
  const out: IntakeHighlightHit[] = []
  for (const m of modules) {
    for (const item of m.items) {
      if (!matchShowIf(item.showIf, answers)) continue
      for (const rule of item.highlights || []) {
        if (matchHighlightWhen(rule.when, answers[item.field])) {
          out.push({
            field: item.field,
            severity: rule.severity,
            planEffect: rule.planEffect,
            followUp: rule.followUp,
          })
        }
      }
    }
  }
  return out
}

/** 预填写入 answers：仅空位 */
export function mergePrefillIntoAnswers(
  answers: Record<string, unknown> | null | undefined,
  prefill: Record<string, IntakePrefillCell> | null | undefined,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...(answers || {}) }
  if (!prefill) return out
  for (const [field, cell] of Object.entries(prefill)) {
    if (!isAnswerEmpty(out[field]) || cell == null || isAnswerEmpty(cell.value)) continue
    out[field] = cell.value
  }
  return out
}

export function calcBmi(heightCm: unknown, weightKg: unknown): number | null {
  const h = Number(heightCm)
  const w = Number(weightKg)
  if (!Number.isFinite(h) || !Number.isFinite(w) || h <= 0 || w <= 0) return null
  const m = h / 100
  const bmi = w / (m * m)
  return Number.isFinite(bmi) ? Math.round(bmi * 10) / 10 : null
}

export function formatPrefillSource(sourceBizCode?: string | null): string {
  const code = (sourceBizCode || '').toUpperCase()
  const map: Record<string, string> = {
    PROFILE: '档案资料',
    ARCHIVE: '档案页',
    METADATA: '元数据',
    METRIC: '指标',
    ASSESSMENT: '评估问卷',
    INTAKE: '既往首诊',
    INTAKE_QUESTIONNAIRE: '既往首诊',
    MEDICATION: '用药清单',
  }
  return map[code] || sourceBizCode || '预填'
}

export function valuesEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (isAnswerEmpty(a) && isAnswerEmpty(b)) return true
  try {
    return JSON.stringify(a) === JSON.stringify(b)
  } catch {
    return false
  }
}

export interface DictOption {
  code: string
  label: string
}

/** parentCode → 选项列表；详情/变更对照解析 ENUM·MULTI 用 */
export type IntakeOptionMaps = Record<string, DictOption[]>

const optionCache = new Map<string, DictOption[]>()

/**
 * field-catalog optionSets 本地兜底（字典未同步时仍能显示中文）。
 * 与 backend/.../field-catalog.json 的 optionSets 保持同码同文。
 */
const OPTION_SET_FALLBACKS: Record<string, string[]> = {
  yesNoUnknown: ['YES=是', 'NO=否', 'UNKNOWN=不清楚'],
  yesNo: ['YES=是', 'NO=否'],
  gender: ['MALE=男', 'FEMALE=女'],
  maritalStatus: ['UNMARRIED=未婚', 'MARRIED=已婚', 'DIVORCED=离异', 'WIDOWED=丧偶', 'OTHER=其他'],
  workSchedule: ['DAY=规律白班', 'SHIFT=倒班', 'NIGHT=夜班为主', 'IRREGULAR=不固定'],
  historyStatus: ['none=没有', 'has=有'],
  reproductiveStatus: [
    'NONE=都没有',
    'PREPARING=备孕中',
    'PREGNANT=怀孕中',
    'LACTATING=哺乳期',
    'UNSURE=不确定',
  ],
  menstrualStatus: [
    'REGULAR=规律',
    'IRREGULAR=不规律',
    'AMENORRHEA=停经（非绝经）',
    'PERIMENOPAUSE=围绝经期',
    'POSTMENOPAUSE=已绝经',
  ],
  medicationFlags: [
    'NONE=都没有',
    'HYPOGLYCEMIC=降糖药或胰岛素',
    'ANTIHYPERTENSIVE=降压药',
    'STATIN=降脂药',
    'PSYCH=抗抑郁 / 抗精神病药',
    'STEROID=长期激素',
    'THYROID=甲状腺药',
    'WEIGHT_LOSS_DRUG=减重药（如司美格鲁肽）',
    'OTHER=其他',
  ],
  supplements: [
    'NONE=都没有',
    'MEAL_REPLACEMENT=代餐',
    'PROTEIN=蛋白粉',
    'DIET_TEA=减肥茶 / 非处方减肥产品',
    'FAT_BURNER=燃脂类',
    'VITAMIN=维生素 / 矿物质',
    'OTHER=其他',
  ],
  exerciseRestriction: [
    'NONE=没有',
    'DOCTOR_LIMIT=医生明确限制',
    'CARDIAC=运动时胸痛 / 心悸 / 晕厥',
    'BP_UNCONTROLLED=血压控制不好',
    'RECENT_SURGERY=近期手术未恢复',
    'OTHER=其他',
  ],
  jointIssues: [
    'NONE=没有',
    'KNEE=膝',
    'ANKLE=踝',
    'HIP=髋',
    'LOWER_BACK=腰',
    'SHOULDER=肩颈',
    'OTHER=其他',
  ],
  allergyStatus: ['NKA=没有已知过敏', 'HAS=有'],
  dietRestriction: [
    'NONE=没有',
    'VEGETARIAN=蛋奶素',
    'VEGAN=纯素',
    'HALAL=清真',
    'NO_SPICY=不吃辣',
    'NO_BEEF_LAMB=不吃牛羊肉',
    'NO_SEAFOOD=不吃海鲜',
    'OTHER=其他',
  ],
  mealRegularity: ['REGULAR=基本规律', 'SOMETIMES=偶尔不规律', 'IRREGULAR=经常不规律'],
  breakfast: ['DAILY=每天吃', 'SOMETIMES=有时吃', 'RARELY=基本不吃'],
  takeoutFrequency: [
    'RARELY=很少',
    'WEEKLY_1_3=每周 1–3 次',
    'WEEKLY_4_7=每周 4–7 次',
    'DAILY_MULTI=每天不止一次',
  ],
  weeklyFrequency3: ['RARELY=很少', 'WEEKLY_1_3=每周 1–3 次', 'MOST_DAYS=大多数天'],
  sugaryDrinks: [
    'NONE=基本不喝',
    'WEEKLY_1_3=每周 1–3 杯',
    'DAILY_1=每天 1 杯',
    'DAILY_2PLUS=每天 2 杯以上',
  ],
  staple: ['REFINED=以白米白面为主', 'MIXED=粗细搭配', 'LOW=主食吃得很少'],
  vegFruit: ['ENOUGH=每天都有不少', 'SOME=有但不多', 'RARE=很少吃'],
  cooking: ['STEAM_BOIL=蒸煮', 'STIR_FRY=炒', 'DEEP_FRY=油炸', 'BRAISE=红烧 / 卤'],
  eatingSpeed: ['FAST=10 分钟以内', 'NORMAL=10–20 分钟', 'SLOW=20 分钟以上'],
  taste: ['LIGHT=清淡', 'SALTY=偏咸 / 常吃咸菜腌制品', 'SWEET=偏甜', 'OILY=偏油', 'SPICY=偏辣'],
  dietTracked: ['NEVER=没有', 'TRIED=试过没坚持', 'ONGOING=一直在记'],
  cook: ['SELF=自己做', 'FAMILY=家人做', 'CANTEEN=单位食堂', 'EAT_OUT=外卖 / 外食为主'],
  exerciseTypes: [
    'WALK=快走',
    'RUN=跑步',
    'CYCLE=骑行',
    'SWIM=游泳',
    'STRENGTH=力量训练',
    'BALL=球类',
    'YOGA=瑜伽 / 普拉提',
    'DANCE=跳操 / 广场舞',
    'OTHER=其他',
  ],
  strengthExperience: ['NONE=没练过', 'BEGINNER=偶尔练', 'REGULAR=规律练'],
  dailySteps: [
    'LT3000=3000 步以下',
    'S3000_6000=3000–6000 步',
    'S6000_10000=6000–10000 步',
    'GT10000=1 万步以上',
    'UNKNOWN=不清楚',
  ],
  exerciseSlots: ['MORNING=早上', 'NOON=中午', 'EVENING=傍晚', 'NIGHT=晚上', 'WEEKEND=只有周末'],
  exerciseVenue: ['HOME=家里', 'OUTDOOR=户外', 'GYM=健身房', 'OFFICE=单位'],
  activityLevel: [
    'SEDENTARY=久坐为主',
    'LIGHT=轻体力（站立走动较多）',
    'MODERATE=中等体力',
    'HEAVY=重体力',
  ],
  stressLevel: ['LOW=不大', 'MEDIUM=有一些', 'HIGH=长期压力很大'],
  stressEating: ['NO=没变化', 'EAT_MORE=吃得更多', 'EAT_LESS=吃得更少'],
  emotionalEating: ['NEVER=很少', 'SOMETIMES=有时', 'OFTEN=经常'],
  readiness: [
    'NOT_READY=还没想好',
    'THINKING=想改但还没开始',
    'READY=准备好了',
    'ACTING=已经在做了',
  ],
  familySupport: ['SUPPORTIVE=支持', 'NEUTRAL=无所谓', 'UNSUPPORTIVE=不太支持'],
  willingToRecord: ['YES=愿意', 'MAYBE=可以试试', 'NO=不太愿意'],
  weightLossTried: ['NEVER=没有', 'ONCE=1 次', 'MULTIPLE=多次'],
  weightLossMethods: [
    'DIET_CONTROL=控制饮食',
    'LOW_CARB=低碳 / 生酮',
    'MEAL_REPLACEMENT=代餐',
    'FASTING=断食 / 轻断食',
    'EXERCISE=运动',
    'DRUG=药物',
    'SURGERY=减重手术',
    'OTHER=其他',
  ],
  weightLossFailReasons: [
    'REBOUND=反弹了',
    'HUNGRY=太饿坚持不了',
    'NO_TIME=没时间',
    'NO_RESULT=没效果',
    'SOCIAL=应酬多',
    'INJURY=受伤',
    'OTHER=其他',
  ],
  weightTrend: [
    'STABLE=基本稳定',
    'UP=涨了',
    'DOWN_INTENDED=有意减下来了',
    'DOWN_UNINTENDED=没刻意减却瘦了',
  ],
  eatingDisorderHistory: ['NO=没有', 'YES=有过', 'UNSURE=说不清', 'DECLINE=不想回答'],
  soughtHelp: ['YES=找过', 'NO=没有'],
  bodySatisfaction: [
    'SATISFIED=满意',
    'NEUTRAL=一般',
    'DISSATISFIED=不太满意',
    'VERY_DISSATISFIED=很不满意',
  ],
  bodyAnxiety: ['NONE=不会', 'MILD=偶尔', 'SEVERE=经常，比较严重'],
  expectedPace: ['STEADY=稳一点就好', 'MODERATE=适中', 'ASAP=越快越好'],
  primaryAppeal: [
    'FAT_LOSS=减脂减重',
    'BODY_SHAPE=塑形增肌',
    'GLUCOSE=控制血糖',
    'BP=控制血压',
    'SLEEP=改善睡眠',
    'GUT=改善肠胃',
    'CONSULT=先了解看看',
    'OTHER=其他',
  ],
  goalMode: ['LOSS=减重', 'BODY_COMPOSITION=改善体成分', 'MAINTAIN=维持'],
  dietEffort: ['COOK=愿意自己做', 'ADJUST_ORDER=只能调整点餐', 'MINIMAL=尽量少改变'],
  followupChannel: ['WECHAT=微信', 'PHONE=电话', 'ONSITE=到店'],
  followupFrequency: ['DAILY=每天', 'WEEKLY_2_3=每周 2–3 次', 'WEEKLY_1=每周 1 次'],
  consentGiven: ['YES=已同意'],
  consentMethod: ['VERBAL=口头', 'WRITTEN=书面', 'ELECTRONIC=电子'],
  referral: [
    'NONE=无',
    'NUTRITION_CLINIC=营养科',
    'ENDOCRINE=内分泌科',
    'CARDIOLOGY=心内科',
    'PSYCH=心理科',
    'OBSTETRICS=产科',
    'OTHER=其他',
  ],
  measureMethod: ['ONSITE=现场测量', 'DEVICE=家用设备', 'SELF_REPORT=客户自述'],
  familyHistoryFirstDegree: [
    'FH_DIABETES=糖尿病',
    'FH_HYPERTENSION=高血压',
    'FH_CHD=冠心病',
    'FH_STROKE=脑卒中',
    'FH_EARLY_MI=早发心梗（男 55 岁 / 女 65 岁前发病）',
    'NONE=都没有',
    'UNKNOWN=不清楚',
  ],
  dmChronicComplications: [
    'NEPHROPATHY=糖尿病肾病',
    'RETINOPATHY=糖尿病视网膜病变',
    'NEUROPATHY=周围神经病变',
    'DIABETIC_FOOT=糖尿病足',
    'NONE=都没有',
  ],
}

function parseOptionPairs(pairs: string[]): DictOption[] {
  return pairs
    .map((p) => {
      const i = p.indexOf('=')
      if (i <= 0) return null
      return { code: p.slice(0, i), label: p.slice(i + 1) }
    })
    .filter((x): x is DictOption => !!x)
}

function fallbackOptions(parentCode: string): DictOption[] {
  const pairs = OPTION_SET_FALLBACKS[parentCode]
  return pairs ? parseOptionPairs(pairs) : []
}

/** 按 options parentCode 拉字典；失败时返回空，调用方用编码占位 */
export async function loadDictOptions(parentCode: string): Promise<DictOption[]> {
  if (!parentCode) return []
  const cached = optionCache.get(parentCode)
  if (cached) return cached
  try {
    const res = await api<{ data: Array<{ dictCode: string; dictCodeDesc: string }> }>(
      `/api/b/v1/dict?dictType=OPTION&parentCode=${encodeURIComponent(parentCode)}`,
    )
    const list = (res.data || []).map((d) => ({
      code: d.dictCode,
      label: d.dictCodeDesc || d.dictCode,
    }))
    const final = list.length ? list : fallbackOptions(parentCode)
    optionCache.set(parentCode, final)
    return final
  } catch {
    const fb = fallbackOptions(parentCode)
    optionCache.set(parentCode, fb)
    return fb
  }
}

/** 收集模块里带 options 的 parentCode（ENUM/MULTI） */
export function collectOptionParentCodes(modules: IntakeModuleView[] | null | undefined): string[] {
  const set = new Set<string>()
  for (const m of modules || []) {
    for (const it of m.items || []) {
      if (it.options) set.add(it.options)
    }
  }
  return [...set]
}

/** 批量加载选项字典 */
export async function loadIntakeOptionMaps(
  modules: IntakeModuleView[] | null | undefined,
): Promise<IntakeOptionMaps> {
  const codes = collectOptionParentCodes(modules)
  const maps: IntakeOptionMaps = {}
  await Promise.all(
    codes.map(async (code) => {
      maps[code] = await loadDictOptions(code)
    }),
  )
  return maps
}

export function responseStatusLabel(status?: string | null): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '已提交',
    VOID: '已作废',
    CANCELLED: '已取消',
  }
  if (!status) return '—'
  return map[status] || status
}

function optionLabelOf(parentCode: string | null | undefined, code: string, maps?: IntakeOptionMaps) {
  if (!code) return ''
  if (parentCode && maps?.[parentCode]) {
    const hit = maps[parentCode].find((o) => o.code === code)
    if (hit?.label) return hit.label
  }
  // 无 maps 时仍尝试本地兜底（提交对话框未加载完等）
  if (parentCode) {
    const fb = fallbackOptions(parentCode).find((o) => o.code === code)
    if (fb?.label) return fb.label
  }
  return code
}

function formatStructuredItem(item: unknown): string | null {
  if (item == null) return null
  if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') {
    return String(item)
  }
  if (typeof item !== 'object' || Array.isArray(item)) return null
  const o = item as Record<string, unknown>

  if ('drugName' in o || 'usageMethod' in o) {
    const name = String(o.drugName ?? '').trim()
    if (!name) return null
    const parts = [name]
    const dose = [o.doseAmount, o.doseUnit].filter((x) => x != null && String(x).trim()).join('')
    if (dose) parts.push(dose)
    if (o.timingNote) parts.push(`（${String(o.timingNote).trim()}）`)
    return parts.join(' ')
  }

  if ('allergenName' in o || 'allergenCode' in o) {
    const name = String(o.allergenName || o.allergenCode || '').trim()
    if (!name) return null
    const severityMap: Record<string, string> = {
      MILD: '轻度',
      MODERATE: '中度',
      SEVERE: '严重',
      UNKNOWN: '不清楚',
    }
    const bits = [name]
    const sev = o.severity != null ? severityMap[String(o.severity)] || String(o.severity) : ''
    if (sev && sev !== '不清楚') bits.push(sev)
    if (Array.isArray(o.reactions) && o.reactions.length) {
      bits.push(o.reactions.map(String).join('/'))
    }
    return bits.join(' · ')
  }

  if ('member' in o && 'diseases' in o) {
    const member = String(o.member || '').trim()
    const diseases = Array.isArray(o.diseases) ? o.diseases.map(String).filter(Boolean) : []
    if (!member && !diseases.length) return null
    const entry: FamilyHistoryEntry = {
      kinshipLevel: (String(o.kinshipLevel || '1') as KinshipLevel) || '1',
      member,
      diseases,
      note: o.note ? String(o.note) : undefined,
    }
    return formatFamilyHistoryTag(entry)
  }

  return null
}

function formatObjectAnswer(v: Record<string, unknown>): string {
  // 既往史结构化
  if (
    'diseases' in v ||
    'surgeries' in v ||
    'allergies' in v ||
    'transfusions' in v ||
    'vaccinations' in v ||
    'medicationNote' in v
  ) {
    const parts: string[] = []
    const pushList = (label: string, key: string, nameKey: string) => {
      const arr = v[key]
      if (!Array.isArray(arr) || !arr.length) return
      const names = arr
        .map((x) => {
          if (!x || typeof x !== 'object') return null
          const n = String((x as Record<string, unknown>)[nameKey] || '').trim()
          return n || null
        })
        .filter(Boolean)
      if (names.length) parts.push(`${label}${names.join('、')}`)
    }
    pushList('疾病：', 'diseases', 'name')
    pushList('手术：', 'surgeries', 'name')
    pushList('过敏：', 'allergies', 'name')
    pushList('输血：', 'transfusions', 'name')
    pushList('接种：', 'vaccinations', 'vaccine')
    if (typeof v.medicationNote === 'string' && v.medicationNote.trim()) {
      parts.push(`用药：${v.medicationNote.trim()}`)
    }
    if (parts.length) return parts.join('；')
  }
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/**
 * 答案展示：ENUM/MULTI 转中文；LIST/OBJECT 转可读摘要。
 * @param item 题目元数据（含 type / options）
 * @param maps loadIntakeOptionMaps 结果
 */
export function formatAnswerBrief(
  v: unknown,
  item?: Pick<IntakeItemView, 'type' | 'options' | 'unit' | 'field'> | null,
  maps?: IntakeOptionMaps,
): string {
  if (isAnswerEmpty(v)) return '—'

  const parent = item?.options || null
  const type = (item?.type || '').toUpperCase()

  if (Array.isArray(v)) {
    const parts = v
      .map((itemVal) => {
        const structured = formatStructuredItem(itemVal)
        if (structured != null) return structured
        if (typeof itemVal === 'string' || typeof itemVal === 'number') {
          return optionLabelOf(parent, String(itemVal), maps)
        }
        return null
      })
      .filter((x): x is string => !!x && !!String(x).trim())
    return parts.join('、') || '—'
  }

  if (v && typeof v === 'object') {
    const structured = formatStructuredItem(v)
    if (structured != null) return structured
    return formatObjectAnswer(v as Record<string, unknown>)
  }

  const raw = String(v)
  if (type === 'ENUM' || type === 'MULTI' || parent) {
    const labeled = optionLabelOf(parent, raw, maps)
    if (item?.unit && type === 'NUMBER') return `${labeled} ${item.unit}`
    return labeled
  }
  if (type === 'NUMBER' && item?.unit) return `${raw} ${item.unit}`
  return raw
}
