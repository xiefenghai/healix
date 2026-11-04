/** C 端初步评估：类型、显隐、预填跳过、控件策略 */

export type IntakeDepth = 'MINIMAL' | 'QUICK' | 'FULL'

/** 患者可见文案；内部仍用 MINIMAL/QUICK/FULL 码 */
export const DEPTH_TABS: {
  code: IntakeDepth
  label: string
  hint: string
  eta: string
  about: string
}[] = [
  { code: 'MINIMAL', label: '快捷', hint: '只答最关键的几题', eta: '约 1 分钟', about: '最快了解你的情况' },
  {
    code: 'QUICK',
    label: '标准',
    hint: '禁忌、习惯与开方关键项（约 30–40 题）',
    eta: '约 5 分钟',
    about: '够定制一版方案',
  },
  { code: 'FULL', label: '详细', hint: '尽量说清楚习惯与病史', eta: '约 12 分钟', about: '方便更精细的方案' },
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

export type IntakeView = {
  id: string
  peopleId: string
  status: string
  title?: string
  answers?: Record<string, unknown> | null
  prefill?: Record<string, { value?: unknown } | unknown> | null
  completeness?: {
    depth?: string
    depthLabel?: string
    depthMissingCount?: number
    requiredDone?: number
    requiredTotal?: number
    firstVisitDone?: number
    firstVisitTotal?: number
  } | null
  modules?: IntakeModule[]
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
  'goal.constraints': '目前最大的阻力或限制是什么？（选填）',
  'basic.exercise.availableSlots': '一般什么时候方便运动？（可多选）',
  'allergy.foodTags': '对哪些食物过敏或不耐受？（可多选）',
  'allergy.drugTags': '对哪些药物过敏？（可多选）',
}

/**
 * C 端「标准」档白名单（与后端 PatientCQuickFields 对齐）。
 * FULL 档不受此限制；MINIMAL 仅 REQUIRED。
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
  'basic.diet.cook',
  'basic.exercise.frequency',
  'basic.exercise.types',
  'basic.exercise.availableSlots',
  'basic.lifestyle.activityLevel',
  'basic.sleep.hours',
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

export function matchShowIf(showIf: unknown, get: (field: string) => unknown): boolean {
  if (showIf == null || typeof showIf !== 'object') return true
  const s = showIf as Record<string, unknown>
  if (Array.isArray(s.all)) return s.all.every((c) => matchShowIf(c, get))
  if (Array.isArray(s.any)) return s.any.some((c) => matchShowIf(c, get))
  const field = typeof s.field === 'string' ? s.field : ''
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

/** 是否应在 C 端步骤中出现（depth 与后端 PatientCQuickFields 对齐） */
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
  if (depth === 'QUICK' && !C_QUICK_FIELDS.has(item.field)) return false
  if (depth === 'MINIMAL' && item.level !== 'REQUIRED') return false
  return true
}

/** 身高+体重同屏（减少翻页） */
export const BUNDLE_GROUPS: { id: string; fields: string[]; title: string }[] = [
  { id: 'height_weight', fields: ['metric.HEIGHT', 'metric.WEIGHT'], title: '身高和体重' },
]
