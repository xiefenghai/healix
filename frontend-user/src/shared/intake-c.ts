/** C 端初步评估：类型、显隐、预填跳过、控件策略 */

export type IntakeDepth = 'MINIMAL' | 'QUICK' | 'FULL'

/**
 * C 端对外只展示两档：极简（C_FAST / MINIMAL）→ 完整补充（FULL）。
 * QUICK 仍保留在类型里兼容旧会话，但不进选择器。
 */
export const DEPTH_TABS: {
  code: IntakeDepth
  label: string
  hint: string
  eta: string
  about: string
}[] = [
  {
    code: 'MINIMAL',
    label: '极简',
    hint: '代谢与安全红线，约 15 题',
    eta: '约 1–2 分钟',
    about: '即刻生成初步方案',
  },
  {
    code: 'FULL',
    label: '完整补充',
    hint: '习惯、病史与细节，方案更精准',
    eta: '约 10 分钟',
    about: '面诊前可分次补完',
  },
]

export const C_INTAKE_TITLE = '初步评估'

export type IntakeItem = {
  field: string
  label: string
  level?: string
  type?: string | null
  unit?: string | null
  options?: string | null
  min?: number | null
  max?: number | null
  exclusive?: string[] | null
  showIf?: unknown
}

export type IntakeModule = {
  code: string
  title: string
  script?: string | null
  showIf?: unknown
  items: IntakeItem[]
}

export type IntakePrefillCellC = {
  value?: unknown
  staffSuggested?: boolean
  updatedByType?: string | null
  sourceClientCode?: string | null
  gmtModified?: string | null
}

export type IntakeReviewHints = {
  confirmMode?: boolean
  staffDisplayName?: string | null
  staffSuggestedCount?: number
  staffSuggestedFields?: string[]
  previousSubmittedAt?: string | null
}

export type IntakeView = {
  id: string
  peopleId: string
  status: string
  title?: string
  answers?: Record<string, unknown> | null
  prefill?: Record<string, IntakePrefillCellC | unknown> | null
  completeness?: {
    depth?: string
    depthLabel?: string
    depthMissingCount?: number
    requiredDone?: number
    requiredTotal?: number
    firstVisitDone?: number
    firstVisitTotal?: number
    reviewHints?: IntakeReviewHints | null
  } | null
  modules?: IntakeModule[]
}

export function readPrefillCell(
  prefill: IntakeView['prefill'],
  field: string,
): IntakePrefillCellC | null {
  if (!prefill || typeof prefill !== 'object') return null
  const cell = (prefill as Record<string, unknown>)[field]
  if (!cell || typeof cell !== 'object') return null
  return cell as IntakePrefillCellC
}

export function isStaffSuggestedField(view: IntakeView | null | undefined, field: string): boolean {
  const hints = view?.completeness?.reviewHints
  if (hints?.staffSuggestedFields?.includes(field)) return true
  return !!readPrefillCell(view?.prefill, field)?.staffSuggested
}

export type IntakeStatus = {
  hasSubmitted: boolean
  submittedDepth?: string | null
  submittedDepthLabel?: string | null
  pendingCount?: number
}

/** 建档已有值则 C 端不再追问 */
export const ARCHIVE_IDENTITY_FIELDS = new Set([
  'profile.displayName',
  'profile.gender',
  'profile.birthday',
  'profile.mobile',
])

/** C 端隐藏：由系统代填 / 提交确认页处理，不当问卷题展示 */
export const C_HIDDEN_FIELDS = new Set([
  'goal.careTracks',
  'consent.method', // 同意后自动 ELECTRONIC
  'consent.sensitiveHealth', // 放在提交确认页，不当「下一题」
])

/** C 端题干改写（B 端仍用问卷原文） */
export const C_LABEL_OVERRIDES: Record<string, string> = {
  'goal.appealText': '用一句话说说你的期望（选填）',
  'goal.mode': '这次你最看重哪一类目标？',
  'goal.doctorAdvised': '是医生建议你来做这次管理的吗？',
  'goal.constraints': '执行健康计划时，最大的阻力是什么？（可多选）',
  'basic.exercise.availableSlots': '一般什么时候方便运动？（可多选）',
  'allergy.foodTags': '对哪些食物过敏或不耐受？（可多选）',
  'allergy.drugTags': '对哪些药物过敏？（可多选）',
  'measure.method': '这些身高体重数据是怎么来的？',
  'basic.presentIllness': '医生说过你有哪些病？（可多选）',
  'basic.medicationFlags': '目前在吃哪类药？（可多选）',
}

/**
 * C 端选项文案改写（码不变；B 端仍用字典原文如「客户自述」）。
 * key = options 字典父码 或 field 码。
 */
export const C_OPTION_LABELS: Record<string, Record<string, string>> = {
  measureMethod: {
    ONSITE: '现场刚量的',
    DEVICE: '家里秤 / 设备量的',
    SELF_REPORT: '我凭印象填的（可能不太准）',
  },
  /** 现病史：大白话，降低医学名词劝退 */
  presentIllness: {
    NONE: '都没有（医生没说过）',
    HYPERTENSION: '高血压',
    DIABETES: '糖尿病',
    PREDIABETES: '血糖偏高',
    HYPERLIPIDEMIA: '血脂高',
    CHD: '冠心病 / 心绞痛',
    HEART_FAILURE: '心衰（心脏泵血不好）',
    ATRIAL_FIBRILLATION: '房颤（心跳不齐）',
    STROKE: '中风 / 脑梗',
    GOUT: '痛风',
    COPD: '老慢支 / 慢阻肺（总喘不上气）',
    ASTHMA: '哮喘',
    CKD: '慢性肾病',
    CHRONIC_HEPATITIS: '慢性肝炎',
    CIRRHOSIS: '肝硬化',
    NAFLD: '脂肪肝',
    CHRONIC_GI_DISEASE: '慢性胃炎 / 胃溃疡',
    OSTEOPOROSIS: '骨质疏松',
    RHEUMATOID_ARTHRITIS: '类风湿',
    OSTEOARTHRITIS: '骨关节疼 / 骨刺',
    ALZHEIMERS: '老年痴呆',
    PARKINSONS: '帕金森',
    DEPRESSION: '抑郁',
    ANXIETY: '焦虑',
    MALIGNANT_TUMOR: '恶性肿瘤（癌症）',
    BPH: '前列腺增生',
    THYROID_DISORDER: '甲状腺问题',
    OBESITY: '肥胖',
    PCOS: '多囊卵巢',
    OSA: '睡觉打鼾 / 呼吸暂停',
  },
  medicationFlags: {
    NONE: '都没有',
    HYPOGLYCEMIC: '降糖药或胰岛素',
    ANTIHYPERTENSIVE: '降压药',
    STATIN: '降脂药',
    THYROID: '甲状腺药',
    WEIGHT_LOSS_DRUG: '减重药（如司美格鲁肽）',
    STEROID: '长期激素',
    PSYCH: '精神/神经类药（如安眠、抗焦虑等）',
    OTHER: '其他',
  },
  /** 过敏 Tag：C 端白话；B 端字典仍可保留「请健管师补明细」 */
  foodAllergyTags: {
    SEAFOOD: '海鲜/水产类',
    OTHER: '其他（请手动填写）',
  },
  drugAllergyTags: {
    OTHER: '其他药物（请手动填写）',
  },
}

/**
 * C 端食物过敏首屏隐藏细分子类，只留「海鲜/水产类」统称（码仍写 SEAFOOD；
 * 档案侧 SEAFOOD 已覆盖鱼/虾/蟹/贝关键词）。
 */
export const C_FOOD_ALLERGY_HIDE = new Set(['FISH', 'SHELLFISH'])

/** C 端现病史首屏高频（其余折叠） */
export const C_ILLNESS_PRIMARY = [
  'NONE',
  'HYPERTENSION',
  'DIABETES',
  'PREDIABETES',
  'HYPERLIPIDEMIA',
  'CHD',
  'OBESITY',
  'GOUT',
] as const

/** C 端用药首屏：精神类等放进「展开」 */
export const C_MED_PRIMARY = [
  'NONE',
  'HYPOGLYCEMIC',
  'ANTIHYPERTENSIVE',
  'STATIN',
  'THYROID',
  'WEIGHT_LOSS_DRUG',
  'OTHER',
] as const

export function multiExclusiveHint(field: string): string {
  if (field === 'basic.presentIllness') return '可多选 · 「都没有」与具体疾病互斥'
  if (field === 'basic.medicationFlags') return '可多选 · 「都没有」与具体用药互斥'
  if (field === 'basic.medical.exerciseRestriction') return '可多选 · 「没有」与其它情况互斥'
  return '可多选'
}

export function cOptionLabel(
  optionsParent: string | null | undefined,
  code: string,
  fallback: string,
): string {
  if (!optionsParent) return fallback
  return C_OPTION_LABELS[optionsParent]?.[code] || fallback
}

/**
 * C 端「极简 / C_FAST」白名单（与后端 PatientCFastFields 对齐）。
 * Magic Link 默认只渲染这些题。
 */
export const C_FAST_FIELDS = new Set<string>([
  'profile.displayName',
  'profile.gender',
  'profile.birthday',
  'profile.mobile',
  'metric.HEIGHT',
  'metric.WEIGHT',
  'measure.method',
  'goal.primaryAppeal',
  'basic.presentIllness',
  'basic.medical.exerciseRestriction',
  'allergy.status',
  'allergy.foodTags',
  'allergy.drugTags',
  'basic.medicationFlags',
  'profile.workSchedule',
  'basic.lifestyle.activityLevel',
  'basic.diet.mealRegularity',
  'basic.diet.takeoutFrequency',
])

/**
 * C 端旧「标准」档白名单（与后端 PatientCQuickFields 对齐；兼容续填）。
 * FULL 档不受此限制。
 */
export const C_QUICK_FIELDS = new Set<string>([
  'goal.primaryAppeal',
  'goal.appealText',
  'goal.mode',
  'goal.targetWeightKg',
  'goal.targetDate',
  'goal.exerciseMinutesPerDay',
  'goal.dietEffort',
  'goal.followupChannel',
  'goal.followupFrequency',
  'goal.constraints',
  'profile.displayName',
  'profile.gender',
  'profile.birthday',
  'profile.mobile',
  'profile.occupation',
  'profile.workSchedule',
  'metric.HEIGHT',
  'metric.WEIGHT',
  'metric.WAIST',
  'metric.BLOOD_PRESSURE_SYS',
  'metric.BLOOD_PRESSURE_DIA',
  'measure.method',
  'basic.presentIllness',
  'basic.medicationFlags',
  'medication.mealRelation',
  'basic.medical.mainDiscomfort',
  'basic.medical.exerciseRestriction',
  'basic.medical.jointIssues',
  'basic.women.reproductiveStatus',
  'basic.women.gdmHistory',
  'allergy.status',
  'allergy.foodTags',
  'allergy.drugTags',
  'basic.diet.restriction',
  'basic.familyHistoryStatus',
  'disease.diabetes.type',
  'disease.diabetes.hypoglycemia',
  'disease.diabetes.latestHba1c',
  'disease.diabetes.monitorFreq',
  'disease.hypertension.homeMonitor',
  'disease.hypertension.latestSystolic',
  'disease.hypertension.latestDiastolic',
  'disease.hypertension.orthostatic',
  'basic.weightHistory.triedBefore',
  'basic.weightHistory.failReasons',
  'basic.weightHistory.expectedPace',
  'basic.psych.eatingDisorderHistory',
  'basic.diet.mealRegularity',
  'basic.diet.takeoutFrequency',
  'basic.diet.snackNight',
  'basic.diet.sugaryDrinks',
  'basic.diet.taste',
  'basic.diet.dinnerTime',
  'basic.diet.cook',
  'basic.exercise.frequency',
  'basic.exercise.types',
  'basic.schedule.offWork',
  'basic.schedule.offWorkNote',
  'basic.exercise.availableSlots',
  'basic.lifestyle.activityLevel',
  'basic.sleep.hours',
  'basic.sleep.refreshed',
  'basic.sleep.quality',
  'basic.psych.stressLevel',
  'basic.psych.stressEating',
  'basic.psych.readiness',
])

export function cLabel(item: IntakeItem): string {
  return C_LABEL_OVERRIDES[item.field] || item.label
}

export function isAnswerEmpty(item: IntakeItem, value: unknown): boolean {
  if (item.type === 'MULTI') {
    return !Array.isArray(value) || value.length === 0
  }
  return value === undefined || value === null || value === ''
}

export function isFilledValue(value: unknown): boolean {
  if (value === undefined || value === null || value === '') return false
  if (Array.isArray(value)) return value.length > 0
  return true
}

/** 短文本用单行 */
export function isShortTextField(field: string): boolean {
  return (
    field === 'profile.displayName' ||
    field === 'profile.mobile' ||
    field === 'profile.wechatId' ||
    field.endsWith('.displayName') ||
    field.endsWith('.mobile') ||
    field.endsWith('.wechatId')
  )
}

/** 核心诉求 → 管理方向（驱动糖/压/减重专线） */
export function suggestedCareTracksFromAppeal(appeal: unknown): string[] {
  const codes = (Array.isArray(appeal) ? appeal : appeal != null && appeal !== '' ? [appeal] : []).map((x) =>
    String(x).toUpperCase(),
  )
  const out: string[] = []
  const add = (t: string) => {
    if (!out.includes(t)) out.push(t)
  }
  for (const code of codes) {
    if (code === 'FAT_LOSS' || code === 'BODY_SHAPE' || code === 'LIPID') add('WEIGHT_LOSS')
    else if (code === 'GLUCOSE') add('DIABETES')
    else if (code === 'BP') add('HYPERTENSION')
    else if (code === 'METABOLIC') {
      add('WEIGHT_LOSS')
      add('DIABETES')
      add('HYPERTENSION')
    }
  }
  return out
}

export function applyCareTracksFromAppeal(answers: Record<string, unknown>): string[] {
  const tracks = suggestedCareTracksFromAppeal(answers['goal.primaryAppeal'])
  answers['goal.careTracks'] = tracks.length ? tracks : ['NONE']
  return tracks
}

/** 客户点同意时补授权方式 */
export function applyPatientConsentDefaults(answers: Record<string, unknown>) {
  const given = answers['consent.sensitiveHealth']
  if (given === 'YES' || given === true || given === 'true') {
    if (!isFilledValue(answers['consent.method'])) {
      answers['consent.method'] = 'ELECTRONIC'
    }
  }
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

function ageYears(raw: unknown): number | null {
  if (raw == null || raw === '') return null
  const s = String(raw).trim().slice(0, 10)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null
  const b = new Date(`${s}T00:00:00`)
  if (Number.isNaN(b.getTime())) return null
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const md = now.getMonth() - b.getMonth()
  if (md < 0 || (md === 0 && now.getDate() < b.getDate())) age -= 1
  return age >= 0 && age < 150 ? age : null
}

export function matchShowIf(showIf: unknown, get: (field: string) => unknown): boolean {
  if (showIf == null || typeof showIf !== 'object') return true
  const s = showIf as Record<string, unknown>
  if (Array.isArray(s.all)) return s.all.every((c) => matchShowIf(c, get))
  if (Array.isArray(s.any)) return s.any.some((c) => matchShowIf(c, get))
  const field = typeof s.field === 'string' ? s.field : ''
  if (typeof s.ageGte === 'number') {
    const age = ageYears(get('profile.birthday'))
    if (age != null && age < s.ageGte) return false
    if (!field) return true
  }
  if (!field) return true
  const cur = get(field)
  if (s.eq !== undefined) {
    if (Array.isArray(cur)) return cur.map(String).includes(String(s.eq))
    return String(cur ?? '') === String(s.eq)
  }
  if (Array.isArray(s.in)) {
    const want = s.in.map(String)
    if (Array.isArray(cur)) return cur.map(String).some((x) => want.includes(x))
    return want.includes(String(cur ?? ''))
  }
  if (Array.isArray(s.notIn)) {
    const ban = s.notIn.map(String)
    if (Array.isArray(cur)) return !cur.map(String).some((x) => ban.includes(x))
    return !ban.includes(String(cur ?? ''))
  }
  if (Array.isArray(s.intersects)) {
    const want = new Set(s.intersects.map(String))
    return asArray(cur).map(String).some((x) => want.has(x))
  }
  return true
}

export function prefillValue(prefill: IntakeView['prefill'], field: string): unknown {
  if (!prefill || typeof prefill !== 'object') return undefined
  const cell = (prefill as Record<string, unknown>)[field]
  if (cell && typeof cell === 'object' && 'value' in (cell as object)) {
    return (cell as { value?: unknown }).value
  }
  return cell
}

export function initAnswers(view: IntakeView): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const ans = view.answers && typeof view.answers === 'object' ? view.answers : {}
  for (const m of view.modules || []) {
    for (const it of m.items || []) {
      if (ans[it.field] !== undefined && ans[it.field] !== null) {
        out[it.field] = ans[it.field]
        continue
      }
      const pv = prefillValue(view.prefill, it.field)
      if (pv !== undefined && pv !== null && pv !== '') {
        out[it.field] = pv
      } else if (it.type === 'MULTI') {
        out[it.field] = []
      } else {
        out[it.field] = ''
      }
    }
  }
  for (const [k, v] of Object.entries(ans)) {
    if (out[k] === undefined) out[k] = v
  }
  applyCareTracksFromAppeal(out)
  applyPatientConsentDefaults(out)
  return out
}

/** 是否应在 C 端步骤中出现（depth 与后端 C_FAST / C_QUICK 白名单对齐） */
export function shouldAskOnC(
  item: IntakeItem,
  answers: Record<string, unknown>,
  depth: IntakeDepth = 'FULL',
): boolean {
  if (item.type === 'LIST' || item.type === 'EXTERNAL') return false
  if (item.level === 'STAFF') return false
  if (C_HIDDEN_FIELDS.has(item.field)) return false
  if (ARCHIVE_IDENTITY_FIELDS.has(item.field) && isFilledValue(answers[item.field])) return false
  // 文案含「健管师」的题默认不对客户展示（双保险）
  if (/健管师|案头|内部|请勿对客户/.test(item.label || '')) return false
  if (depth === 'MINIMAL' && !C_FAST_FIELDS.has(item.field)) return false
  if (depth === 'QUICK' && !C_QUICK_FIELDS.has(item.field)) return false
  return true
}

/** 身高+体重同屏（减少翻页） */
export const BUNDLE_GROUPS: { id: string; fields: string[]; title: string }[] = [
  { id: 'height_weight', fields: ['metric.HEIGHT', 'metric.WEIGHT'], title: '身高和体重' },
]
