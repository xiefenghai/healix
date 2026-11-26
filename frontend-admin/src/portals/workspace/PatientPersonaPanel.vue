<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hasRole } from '../../shared/http'
import { StaffRole, formatGender } from '../../shared/enums'
import { formatCarePlanTemplateKey } from '../../shared/care-plan-labels'
import { PRESENT_ILLNESS_SHORT_LABEL } from '../../shared/intake'
import {
  generatePersona,
  getPersona,
  savePersonaNarrative,
  unlockPersona,
  type PersonaView,
} from '../../shared/persona'
import { formatAllergyBrief } from '../../shared/allergy'
import { usePersonaHealthSnapshot } from '../../shared/usePersonaHealthSnapshot'
import MetricSparklineChart from '../../shared/MetricSparklineChart.vue'

const props = defineProps<{ peopleId?: string }>()

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const {
  healthLoading,
  bpCategories,
  bpSeries,
  bpHasData,
  latestWeightKg,
  activeMeds,
  medSub,
  historyFields,
  historyHasData,
  labTitleDate,
  labRows,
  labHasData,
  labSub,
  planTitle,
  planSub,
  planColumns,
  planHasData,
  allergyBrief,
  healthLoaded,
  loadHealth,
  resetHealth,
} = usePersonaHealthSnapshot(peopleId)

const canWrite = computed(
  () => hasRole(StaffRole.CARE_MANAGER) || hasRole(StaffRole.TENANT_ADMIN),
)

const loading = ref(false)
const busy = ref(false)
const editing = ref(false)
const view = ref<PersonaView | null>(null)

const editHeadline = ref('')
const editNarrative = ref('')
const editWatch = ref('')

const hasNarrative = computed(() => !!view.value?.narrative?.headline)
const staffLocked = computed(() => !!view.value?.narrative?.staffLocked)
const isTemplate = computed(() => view.value?.narrative?.model === 'template')
const generating = computed(() => view.value?.status === 'GENERATING')
/** 血压/病种/过敏等临床数据已变，叙事未重跑 */
const dimensionsStale = computed(() => view.value?.dimensionsStale === true)

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000

function parsePersonaTime(raw?: string | null): Date | null {
  if (!raw) return null
  const d = new Date(raw)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 轻量时间：2026-11-07 14:30 */
function formatGeneratedAt(raw?: string | null): string {
  const d = parsePersonaTime(raw)
  if (!d) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const generatedAtLabel = computed(() => formatGeneratedAt(view.value?.generatedAt))

const isOlderThan3Days = computed(() => {
  const d = parsePersonaTime(view.value?.generatedAt)
  if (!d) return false
  return Date.now() - d.getTime() > THREE_DAYS_MS
})

/** 超 3 天且有新增临床数据 → 建议更新（标签 + 按钮呼吸） */
const suggestAgeUpdate = computed(() => isOlderThan3Days.value && dimensionsStale.value)

/** 叙事全文：高亮短医学指标与关键风险词，禁止整句黄底 */
function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const NARRATIVE_RISK_WORDS = [
  '2型糖尿病',
  '糖尿病前期',
  '糖尿病',
  '高血压',
  '高血脂',
  '肥胖',
  '超重',
  '吸烟',
  '抽烟',
  '饮酒',
  '喝酒',
  '外卖',
  '应酬',
  '偏咸',
  '久坐',
  '失眠',
  '高危',
  '极高危',
  '中危',
]

/** 短指标 + 疾病/生活风险词浅底加粗（跳过已包裹片段，避免长短词嵌套） */
function highlightNarrative(raw: string | null | undefined): string {
  if (!raw) return ''
  const wrap = (m: string) =>
    m.length > 16 ? m : `<strong class="narrative-em">${m}</strong>`
  let html = escapeHtml(raw)
    .replace(/\d{2,3}\s*\/\s*\d{2,3}(?:\s*mmHg)?/g, wrap)
    .replace(/\d+(?:\.\d+)?\s*(?:mmol\/L|mg\/dL|mmHg|kg\/m²|kg\/m2|bpm)/gi, wrap)
    .replace(/(^|[^\d.])(\d{1,2}\.\d)(?!\d)/g, (_m, pre: string, n: string) => `${pre}${wrap(n)}`)

  let out = ''
  let i = 0
  while (i < html.length) {
    if (html.startsWith('<strong', i)) {
      const end = html.indexOf('</strong>', i)
      if (end < 0) {
        out += html.slice(i)
        break
      }
      out += html.slice(i, end + 9)
      i = end + 9
      continue
    }
    let hit = ''
    for (const word of NARRATIVE_RISK_WORDS) {
      if (html.startsWith(word, i) && word.length > hit.length) hit = word
    }
    if (hit) {
      out += wrap(hit)
      i += hit.length
      continue
    }
    out += html[i]
    i += 1
  }
  return out
}

/** 按语义拆成两段：诊断与体征 / 生活习惯与管理重点 */
function splitNarrativeParagraphs(raw: string | null | undefined): string[] {
  const text = String(raw || '').trim()
  if (!text) return []
  const parts = text
    .split(/(?<=[。！？；])\s*/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (parts.length <= 1) return [text]
  const clinicalRe = /诊|血压|血糖|BMI|体重|分层|患病|过敏|指标|收缩|舒张|超标|偏高|偏低/
  const lifestyleRe = /生活|饮食|运动|烟|酒|睡眠|管理|方案|依从|习惯|外卖|应酬|心理|情绪|准备/
  const clinical: string[] = []
  const lifestyle: string[] = []
  for (const p of parts) {
    const c = clinicalRe.test(p)
    const l = lifestyleRe.test(p)
    if (c && !l) clinical.push(p)
    else if (l && !c) lifestyle.push(p)
    else if (clinical.length <= lifestyle.length) clinical.push(p)
    else lifestyle.push(p)
  }
  if (!clinical.length || !lifestyle.length) {
    const mid = Math.ceil(parts.length / 2)
    return [parts.slice(0, mid).join(''), parts.slice(mid).join('')].filter(Boolean)
  }
  return [clinical.join(''), lifestyle.join('')].filter(Boolean)
}

const narrativeParagraphs = computed(() =>
  splitNarrativeParagraphs(view.value?.narrative?.narrative).map(highlightNarrative),
)

/** 近 14 日血压均值，供卡片头与图例分列展示 */
const bpAverages = computed(() => {
  const avg = (data: Array<number | null | undefined>) => {
    const nums = data.filter((v): v is number => v != null && !Number.isNaN(Number(v))).map(Number)
    if (!nums.length) return null
    return Math.round(nums.reduce((a, b) => a + b, 0) / nums.length)
  }
  const sys = avg(bpSeries.value[0]?.data || [])
  const dia = avg(bpSeries.value[1]?.data || [])
  if (sys == null && dia == null) return ''
  if (sys != null && dia != null) return `均值 ${sys}/${dia}`
  if (sys != null) return `收缩压均值 ${sys}`
  return `舒张压均值 ${dia}`
})

/** 分层文案统一成「…分层」，供健康风险列表展示 */
function formatRiskLayer(row: Record<string, unknown>): string {
  const raw = String(row.levelLabel || '').trim()
  const tier = String(row.tier || row.level || '').toUpperCase()
  if (raw) {
    if (/分层$/.test(raw)) return raw
    if (/高危|极高|中危|低危|中度|轻度|重度|很高|严重/.test(raw)) return `${raw}分层`
    const mapped = codeLabel(TIER_LABELS, raw, '')
    if (mapped === '高' || mapped === '很高') return mapped === '很高' ? '极高危分层' : '高危分层'
    if (mapped === '中') return '中度分层'
    if (mapped === '低') return '低危分层'
  }
  if (tier === 'VERY_HIGH') return '极高危分层'
  if (tier === 'HIGH') return '高危分层'
  if (tier === 'MEDIUM' || tier === 'MID') return '中度分层'
  if (tier === 'LOW') return '低危分层'
  return raw || '需关注'
}

/** 从饮食行为标签提炼生活方式风险（不用 planEffect 内部备注） */
function lifestyleRiskFromDiet(diet: unknown): string | null {
  const tags = Array.isArray(diet)
    ? diet.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  if (!tags.length) return null
  const takeout = tags.some((t) => /外卖|外食/.test(t))
  const social = tags.some((t) => /应酬/.test(t))
  const salty = tags.some((t) => /偏咸|口味偏咸/.test(t))
  if (!takeout && !social) return null
  const bits: string[] = []
  if (takeout) bits.push('外卖')
  if (social) bits.push('应酬')
  const head = `${bits.join('/')}频繁`
  return salty ? `生活方式：${head}，需低盐干预` : `生活方式：${head}`
}

/** 健康风险：统一「病种：分层 / 过敏风险：… / 生活方式：…」；禁止塞入方案 Prompt 备注 */
const medicalFocus = computed(() => {
  const d = view.value?.dimensions
  const out: string[] = []
  const tags = dedupeRiskTagRows(d?.risk?.tags || [])
  for (const t of tags) {
    if (out.length >= 4) break
    const row = t as Record<string, unknown>
    const tier = String(row.tier || row.level || '').toUpperCase()
    if (!/HIGH|MEDIUM|MID|高|中/.test(tier) && !/高|严重|中/.test(String(row.levelLabel || ''))) {
      continue
    }
    const disease = codeLabel(DISEASE_TAG_LABELS, row.diseaseCode, String(row.diseaseCode || ''))
    if (!disease) continue
    out.push(`${disease}：${formatRiskLayer(row)}`)
  }
  const allergens = (d?.constraints?.allergens || [])
    .map((a) => String(a || '').trim())
    .filter(Boolean)
  if (allergens.length && out.length < 4) {
    out.push(`过敏风险：${allergens.join('、')}`)
  }
  const lifestyle = lifestyleRiskFromDiet(d?.behavior?.diet)
  if (lifestyle && out.length < 4) {
    out.push(lifestyle)
  }
  return out
})

/** 运营待办：工作台任务 + 下一步 */
const opsFocus = computed(() => {
  const d = view.value?.dimensions
  const out: string[] = []
  for (const t of d?.focus?.openTasks || []) {
    if (out.length >= 4) break
    const title = String(t?.title || t?.type || '').trim()
    if (title) out.push(title)
  }
  const next = String(d?.focus?.nextStep || '').trim()
  if (next && !out.includes(next)) out.push(next)
  // 叙事里的盯梢点仅作补充（已锁定文案）
  if (!out.length && view.value?.narrative?.watchPoints?.length) {
    return view.value.narrative.watchPoints.slice(0, 3)
  }
  return out
})

type DimRow = {
  label: string
  value: string
  tone?: 'danger' | 'warn' | 'accent' | 'muted'
  /** 病种等胶囊标签；有值时优先渲染 chips */
  chips?: string[]
  /** 生活习惯等：以浅暖橙胶囊展示，替代纯红文字 */
  capsule?: boolean
}

type ChipGroup = {
  prefix: string
  chips: DimRow[]
}
type DimBlock = {
  key: string
  title: string
  rows: DimRow[]
  empty?: string
  /** 案头需优先扫到的维度 */
  priority?: boolean
  hint?: string
}

function isBlankDimValue(v: unknown): boolean {
  const s = String(v ?? '').trim()
  return !s || s === '—' || s === '-' || s === '暂无' || s === '暂无数据'
}

const DISEASE_TAG_LABELS: Record<string, string> = {
  ...PRESENT_ILLNESS_SHORT_LABEL,
  WEIGHT_LOSS: '减重',
  hypertension: '高血压',
  HYPERTENSION: '高血压',
  diabetes: '2型糖尿病',
  DIABETES: '2型糖尿病',
  T2DM: '2型糖尿病',
  TYPE2_DIABETES: '2型糖尿病',
  PREDIABETES: '糖尿病前期',
  obesity: '肥胖',
}

const GOAL_SUMMARY_LABELS: Record<string, string> = {
  BODY_SHAPE: '塑形增肌',
  FAT_LOSS: '减脂减重',
  WEIGHT_LOSS: '减重',
  GLUCOSE: '控糖',
  BP: '控压',
  LIPID: '调脂',
  METABOLIC: '三高 / 代谢综合',
  NONE: '暂不加入',
}

const GOAL_MODE_LABELS: Record<string, string> = {
  LOSS: '减重',
  GAIN: '增重',
  MAINTAIN: '维持',
  WEIGHT_LOSS: '减重',
  WEIGHT_GAIN: '增重',
}

/** 与 AssessmentTier 对齐：HIGH / MID / LOW / VERY_HIGH */
const TIER_LABELS: Record<string, string> = {
  HIGH: '高',
  VERY_HIGH: '很高',
  MEDIUM: '中',
  MID: '中',
  LOW: '低',
}

function codeLabel(map: Record<string, string>, code: unknown, fallback = '—'): string {
  if (code == null || code === '') return fallback
  const c = String(code).trim()
  if (!c) return fallback
  return map[c] || map[c.toUpperCase()] || fallback
}

/** 把数组 / JSON 数组字符串 / 逗号分隔码统一成 code 列表 */
function asCodeList(raw: unknown): string[] {
  if (raw == null || raw === '') return []
  if (Array.isArray(raw)) {
    return raw.map((x) => String(x ?? '').trim()).filter(Boolean)
  }
  const s = String(raw).trim()
  if (!s) return []
  if (s.startsWith('[')) {
    try {
      const parsed = JSON.parse(s) as unknown
      if (Array.isArray(parsed)) {
        return parsed.map((x) => String(x ?? '').trim()).filter(Boolean)
      }
    } catch {
      /* 非 JSON，按原文 */
    }
  }
  if (/[,、|]/.test(s)) {
    return s
      .split(/[,、|]/)
      .map((x) => x.trim().replace(/^["']|["']$/g, ''))
      .filter(Boolean)
  }
  return [s]
}

function formatCodedList(raw: unknown, map: Record<string, string>, empty = '—'): string {
  const codes = asCodeList(raw)
  if (!codes.length) return empty
  return codes.map((c) => codeLabel(map, c, c)).join('、') || empty
}

function listText(v: unknown, map?: Record<string, string>): string {
  if (map) return formatCodedList(v, map)
  if (!Array.isArray(v) || !v.length) return '—'
  return v
    .map((x) => String(x ?? '').trim())
    .filter(Boolean)
    .join('、') || '—'
}

function isConfirmedDiabetesLabel(s: string) {
  const t = s.trim()
  const u = t.toUpperCase()
  if (u === 'DIABETES' || u === 'T2DM' || u === 'TYPE2_DIABETES') return true
  return (t.includes('糖尿病') || t.includes('2型')) && !t.includes('前期') && !u.includes('PREDIABETES')
}

function isPrediabetesLabel(s: string) {
  const t = s.trim()
  const u = t.toUpperCase()
  return (
    u === 'PREDIABETES' ||
    t.includes('糖尿病前期') ||
    t.includes('糖前期') ||
    t.includes('空腹血糖受损') ||
    t.includes('糖耐量异常')
  )
}

function isConfirmedHypertensionLabel(s: string) {
  const t = s.trim()
  const u = t.toUpperCase()
  if (u === 'HYPERTENSION' || u === 'HTN') return true
  return t.includes('高血压') && !t.includes('前期') && !t.includes('偏高')
}

function isElevatedBpLabel(s: string) {
  const t = s.trim()
  const u = t.toUpperCase()
  return (
    u === 'PREHYPERTENSION' ||
    u === 'BP_ELEVATED' ||
    u === 'ELEVATED_BP' ||
    t.includes('高血压前期') ||
    t.includes('血压偏高') ||
    t === '偏高血压'
  )
}

/** 确诊糖尿病覆盖前期；确诊高血压覆盖血压偏高/前期 */
function dedupeDiseaseTagChips(raw: unknown, map: Record<string, string>): string[] {
  const codes = asCodeList(raw)
  const labels = codes
    .map((c) => codeLabel(map, c, c))
    .map((s) => s.trim())
    .filter(Boolean)
  const hasDiabetes = labels.some(isConfirmedDiabetesLabel) || codes.some(isConfirmedDiabetesLabel)
  const hasHtn = labels.some(isConfirmedHypertensionLabel) || codes.some(isConfirmedHypertensionLabel)
  const out: string[] = []
  const seen = new Set<string>()
  for (let i = 0; i < codes.length; i++) {
    const code = codes[i]
    const label = labels[i] || code
    if (hasDiabetes && (isPrediabetesLabel(code) || isPrediabetesLabel(label))) continue
    if (hasHtn && (isElevatedBpLabel(code) || isElevatedBpLabel(label))) continue
    if (seen.has(label)) continue
    seen.add(label)
    out.push(label)
  }
  return out
}

/** 风险分层行：与病种标签同一套互斥（有 2 型糖尿病则去掉糖尿病前期） */
function dedupeRiskTagRows(raw: unknown): Array<Record<string, unknown>> {
  const list = Array.isArray(raw) ? (raw as Array<Record<string, unknown>>) : []
  const codes = list.map((r) => String(r?.diseaseCode || '').trim()).filter(Boolean)
  const hasDiabetes = codes.some(isConfirmedDiabetesLabel)
  const hasHtn = codes.some(isConfirmedHypertensionLabel)
  const seen = new Set<string>()
  const out: Array<Record<string, unknown>> = []
  for (const row of list) {
    const code = String(row?.diseaseCode || '').trim()
    if (!code) continue
    if (hasDiabetes && isPrediabetesLabel(code)) continue
    if (hasHtn && isElevatedBpLabel(code)) continue
    const key = code.toUpperCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(row)
  }
  return out
}

type PersonaLayout = {
  primary: DimBlock[]
  chipGroups: ChipGroup[]
}

const emptyLayout = (): PersonaLayout => ({
  primary: [],
  chipGroups: [],
})

const dimBlocks = computed((): PersonaLayout => {
  const d = view.value?.dimensions
  if (!d) return emptyLayout()

  const identity = (d.identity || {}) as Record<string, unknown>
  const goals = (d.goals || {}) as Record<string, unknown>
  const risk = d.risk || {}
  const constraints = d.constraints || {}
  const behavior = d.behavior || {}

  const tags = dedupeRiskTagRows(risk.tags)
  const riskChips: DimRow[] = tags.slice(0, 4).map((t) => {
    const row = t as Record<string, unknown>
    const disease = codeLabel(DISEASE_TAG_LABELS, row.diseaseCode, String(row.diseaseCode || '病种'))
    const levelRaw = String(row.levelLabel || row.level || '').trim()
    const level = codeLabel(TIER_LABELS, levelRaw, levelRaw)
    const tierRaw = String(row.tier || '').toUpperCase()
    const tone: DimRow['tone'] =
      tierRaw === 'HIGH' || tierRaw === 'VERY_HIGH' || /高|严重|极高/.test(level)
        ? 'danger'
        : tierRaw === 'MEDIUM' || tierRaw === 'MID' || /中/.test(level)
          ? 'warn'
          : undefined
    return {
      label: disease,
      value: level || '需关注',
      tone,
    }
  })

  // 过敏：未选=未采集；显式 NKA=没有过敏；勿用空 allergens 推断
  const allergyFromDims = formatAllergyBrief(
    typeof constraints.allergyStatus === 'string' ? constraints.allergyStatus : undefined,
    constraints.allergens,
  )
  const allergyView = healthLoaded.value ? allergyBrief.value : allergyFromDims
  const allergenText = allergyView.text
  const allergenChips =
    allergyView.hasAllergy && Array.isArray(constraints.allergens)
      ? constraints.allergens.map((a) => String(a || '').trim()).filter(Boolean)
      : []
  const dietList = Array.isArray(behavior.diet)
    ? behavior.diet.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  const exerciseList = Array.isArray(behavior.exercise)
    ? behavior.exercise.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  const psychList = Array.isArray(behavior.psych)
    ? behavior.psych.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  const dietText = listText(behavior.diet)
  const exerciseText = listText(behavior.exercise)
  const psychText = listText(behavior.psych)
  const careTracksText = formatCodedList(goals.careTracks, GOAL_SUMMARY_LABELS, '')

  const smokeField = historyFields.value.find((f) => f.key === 'smoking')
  const drinkField = historyFields.value.find((f) => f.key === 'drinking')
  /** 兜底：把 WEEKLY_1_2 等残留枚举换成中文，避免胶囊直接露出常量 */
  const scrubEnum = (s: string) =>
    s.replace(/\bWEEKLY_1_2\b/g, '每周1~2次')
      .replace(/\bWEEKLY_3_PLUS\b/g, '每周3次以上')
      .replace(/\bWEEKLY_1\b/g, '每周1~2次')
      .replace(/\bWEEKLY_3\b/g, '每周3次以上')
  const smokeValue =
    smokeField && !smokeField.empty ? scrubEnum(smokeField.value) : ''
  const drinkValue =
    drinkField && !drinkField.empty ? scrubEnum(drinkField.value) : ''
  const smokeRisk = /吸烟|抽烟|当前|偶尔|仍吸烟/.test(smokeValue)
  const drinkRisk = /饮酒|喝酒|经常|每周|仍饮酒/.test(drinkValue)

  const lifestyleRows: DimRow[] = [
    {
      label: '饮食',
      value: dietText,
      chips: dietList.length ? dietList : undefined,
      capsule: dietList.length > 0,
      tone: dietText !== '—' ? 'accent' : 'muted',
    },
    {
      label: '运动',
      value: exerciseText,
      chips: exerciseList.length ? exerciseList : undefined,
      capsule: exerciseList.length > 0,
      tone: exerciseText !== '—' ? 'accent' : 'muted',
    },
  ]
  if (smokeValue) {
    lifestyleRows.push({
      label: '吸烟',
      value: smokeValue,
      capsule: true,
      tone: smokeRisk ? 'warn' : 'muted',
    })
  }
  if (drinkValue) {
    lifestyleRows.push({
      label: '饮酒',
      value: drinkValue,
      capsule: true,
      tone: drinkRisk ? 'warn' : 'muted',
    })
  }
  if (psychText !== '—') {
    lifestyleRows.push({
      label: '心理',
      value: psychText,
      chips: psychList.length ? psychList : undefined,
      capsule: psychList.length > 0,
    })
  }

  const diseaseChips = dedupeDiseaseTagChips(identity.diseaseTags, DISEASE_TAG_LABELS)
  const safetyRows: DimRow[] = [
    {
      label: '已确诊',
      value: diseaseChips.length ? diseaseChips.join('、') : '暂无',
      chips: diseaseChips.length ? diseaseChips : undefined,
      tone: diseaseChips.length ? undefined : 'muted',
    },
    {
      label: '过敏史',
      value: allergenText,
      chips: allergenChips.length ? allergenChips : undefined,
      tone: allergyView.hasAllergy ? 'danger' : allergyView.collected ? undefined : 'muted',
    },
    {
      label: '主管健管师',
      value: String(identity.primaryCareManagerName || '未分配'),
    },
    {
      label: '健管组',
      value: String(identity.careTeamName || '未分配'),
    },
    {
      label: '主诉求',
      value: formatCodedList(goals.summary, GOAL_SUMMARY_LABELS, '暂无'),
      tone: asCodeList(goals.summary).length ? 'accent' : 'muted',
    },
  ]
  if (careTracksText) {
    safetyRows.push({ label: '管理轨道', value: careTracksText })
  }

  const chipGroups: ChipGroup[] = []
  if (riskChips.length) {
    chipGroups.push({ prefix: '高危风险：', chips: riskChips })
  } else if (allergyView.hasAllergy) {
    chipGroups.push({
      prefix: '高危风险：',
      chips: [{ label: '过敏', value: allergenText, tone: 'danger' }],
    })
  }
  const lifestyleChips: DimRow[] = []
  if (dietText !== '—') lifestyleChips.push({ label: '饮食', value: dietText, tone: 'accent' })
  if (exerciseText !== '—') lifestyleChips.push({ label: '运动', value: exerciseText, tone: 'accent' })
  if (smokeValue) lifestyleChips.push({ label: '吸烟', value: smokeValue, tone: smokeRisk ? 'warn' : 'muted' })
  if (drinkValue) lifestyleChips.push({ label: '饮酒', value: drinkValue, tone: drinkRisk ? 'warn' : 'muted' })
  if (lifestyleChips.length) {
    chipGroups.push({ prefix: '生活习惯：', chips: lifestyleChips })
  }
  if (psychList.length) {
    chipGroups.push({
      prefix: '心理状态：',
      chips: psychList.map((p) => ({ label: '心理', value: p, tone: 'accent' as const })),
    })
  }

  return {
    primary: [
      {
        key: 'identity',
        title: '基本资料',
        rows: [
          { label: '性别', value: formatGender(identity.sex as string) },
          { label: '年龄', value: identity.age != null ? `${identity.age} 岁` : '—' },
          {
            label: '体重',
            value:
              latestWeightKg.value != null
                ? `${Number.isInteger(latestWeightKg.value) ? latestWeightKg.value : latestWeightKg.value.toFixed(1)} kg`
                : '—',
            tone: latestWeightKg.value != null ? undefined : ('muted' as const),
          },
          {
            label: '在管时长',
            value: identity.enrolledDays != null ? `${identity.enrolledDays} 天` : '—',
          },
          {
            label: '方案类型',
            value: identity.activePlanTemplateKey
              ? formatCarePlanTemplateKey(String(identity.activePlanTemplateKey))
              : '暂无',
          },
          {
            label: '目标模式',
            value: goals.mode ? codeLabel(GOAL_MODE_LABELS, goals.mode, '暂无') : '暂无',
          },
        ],
      },
      {
        key: 'safety',
        title: '安全与联系人',
        rows: safetyRows,
      },
      {
        key: 'lifestyle',
        title: '生活习惯',
        rows: lifestyleRows,
        empty: lifestyleRows.some((r) => r.value && r.value !== '—') ? undefined : '暂无生活习惯采集',
      },
    ] as DimBlock[],
    chipGroups,
  }
})

const primaryBlocks = computed(() => dimBlocks.value.primary)
const chipGroups = computed(() => dimBlocks.value.chipGroups)
const hasSoftPanel = computed(() => chipGroups.value.length > 0)

function patientBasePath() {
  return `/workspace/patients/${peopleId.value}`
}

function goMedications() {
  if (!peopleId.value) return
  void router.push(`${patientBasePath()}/medications`)
}

function goLabs() {
  if (!peopleId.value) return
  void router.push(`${patientBasePath()}/observations/labs`)
}

function goCarePlan() {
  if (!peopleId.value) return
  void router.push(`${patientBasePath()}/care-plan`)
}

function goArchive() {
  if (!peopleId.value) return
  void router.push(`${patientBasePath()}/archive`)
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const [personaRes] = await Promise.allSettled([
      getPersona(peopleId.value),
      loadHealth(),
    ])
    if (personaRes.status === 'fulfilled') {
      view.value = personaRes.value
    } else {
      view.value = null
      ElMessage.error(
        personaRes.reason instanceof Error ? personaRes.reason.message : '加载用户画像失败',
      )
    }
  } finally {
    loading.value = false
  }
}

async function onGenerate() {
  if (!canWrite.value) return
  // 指纹未变：无新临床指标，提示确认，避免无效重算
  if (hasNarrative.value && !dimensionsStale.value) {
    try {
      await ElMessageBox.confirm(
        '近期暂无新增数据，重新生成可能导致画像无变化。确认继续？',
        '提示',
        {
          type: 'warning',
          confirmButtonText: '继续生成',
          cancelButtonText: '取消',
          distinguishCancelAndClose: true,
        },
      )
    } catch {
      return
    }
  }
  busy.value = true
  try {
    view.value = await generatePersona(peopleId.value)
    if (view.value?.llmSkipped) {
      ElMessage.success('画像未变，已使用缓存')
    } else {
      ElMessage.success(isTemplate.value ? '已写入模板摘要' : '叙事已生成')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
    await load()
  } finally {
    busy.value = false
  }
}

function openEdit() {
  const n = view.value?.narrative
  editHeadline.value = n?.headline || view.value?.displayHeadline || ''
  editNarrative.value = n?.narrative || ''
  editWatch.value = (n?.watchPoints || []).join('\n')
  editing.value = true
}

async function onSaveEdit() {
  if (!canWrite.value) return
  busy.value = true
  try {
    const watchPoints = editWatch.value
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
      .slice(0, 3)
    view.value = await savePersonaNarrative(peopleId.value, {
      headline: editHeadline.value.trim(),
      narrative: editNarrative.value.trim(),
      watchPoints,
    })
    editing.value = false
    ElMessage.success('已保存并锁定')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    busy.value = false
  }
}

async function onUnlock(withRefresh: boolean) {
  if (!canWrite.value) return
  busy.value = true
  try {
    view.value = await unlockPersona(peopleId.value, withRefresh)
    ElMessage.success(withRefresh ? '已解锁并刷新' : '已解锁')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解锁失败')
  } finally {
    busy.value = false
  }
}

watch(
  peopleId,
  () => {
    editing.value = false
    resetHealth()
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section v-loading="loading" class="persona-page" aria-label="用户画像">
    <header class="persona-toolbar">
      <div class="hero-kicker">
        <h2 class="persona-title">用户画像</h2>
        <span
          v-if="generatedAtLabel"
          class="persona-gen-time"
          :title="view?.generatedAt || undefined"
        >
          画像生成于：{{ generatedAtLabel }}
          <span v-if="suggestAgeUpdate" class="age-update-tag"> 建议更新</span>
        </span>
        <span class="persona-sub">AI 辅助健康摘要（非医疗诊断）</span>
        <span v-if="staffLocked" class="tag lock">已锁定</span>
        <span v-else-if="isTemplate" class="tag soft">模板</span>
        <span v-else-if="generating" class="tag soft">生成中</span>
      </div>
      <div class="persona-actions">
        <button
          v-if="canWrite && !hasNarrative"
          type="button"
          class="btn primary"
          :disabled="busy || generating"
          @click="onGenerate"
        >
          生成叙事
        </button>
        <button
          v-if="canWrite && hasNarrative && !staffLocked"
          type="button"
          class="btn primary"
          :class="{ 'btn-pulse': suggestAgeUpdate }"
          :disabled="busy || generating"
          @click="onGenerate"
        >
          重新生成
        </button>
        <button
          v-if="canWrite && hasNarrative"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="openEdit"
        >
          编辑
        </button>
        <button
          v-if="canWrite && staffLocked"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="onUnlock(false)"
        >
          解锁
        </button>
        <button
          v-if="canWrite && staffLocked"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="onUnlock(true)"
        >
          解锁并刷新
        </button>
      </div>
    </header>

    <!-- C 位：叙事全文 + 核心提取标签；仅主动重新生成时本卡 Loading，不静默刷新文案 -->
    <article
      v-loading="busy && !editing"
      class="narrative-card narrative-card--hero"
      :class="{ 'narrative-card--empty': !view?.narrative?.narrative }"
    >
      <div class="section-head section-head--row">
        <div class="section-head-main">
          <h3>叙事全文</h3>
          <p v-if="view?.displayHeadline && view?.narrative?.narrative" class="narrative-kicker">
            {{ view.displayHeadline }}
          </p>
        </div>
        <span
          v-if="dimensionsStale && hasNarrative"
          class="stale-hint stale-hint--card"
          title="血压、血糖、疾病或过敏原等数据已更新"
        >
           数据已更新，建议重新生成
        </span>
      </div>
      <div v-if="narrativeParagraphs.length" class="narrative-body-stack">
        <p
          v-for="(para, idx) in narrativeParagraphs"
          :key="`nar-${idx}`"
          class="narrative-body"
          v-html="para"
        />
      </div>
      <p v-else class="narrative-body muted">
        {{ loading ? '加载中…' : '暂无案头叙事，可点击右上角生成' }}
      </p>
      <div v-if="hasSoftPanel" class="narrative-extract" aria-label="核心提取标签">
        <div
          v-for="(group, gi) in chipGroups"
          :key="`cg-${gi}`"
          class="core-chip-group"
        >
          <span class="core-chip-prefix">{{ group.prefix }}</span>
          <div class="core-chips">
            <span
              v-for="(c, ci) in group.chips"
              :key="`${group.prefix}-${c.label}-${ci}`"
              class="core-chip"
              :class="c.tone ? `tone-${c.tone}` : undefined"
            >
              <em v-if="group.prefix.startsWith('高危')">{{ c.label }}</em>
              {{
                isBlankDimValue(c.value)
                  ? '未采集'
                  : group.prefix.startsWith('高危')
                    ? c.value
                    : `${c.label} ${c.value}`
              }}
            </span>
          </div>
        </div>
      </div>
    </article>

    <div
      v-if="medicalFocus.length || opsFocus.length"
      class="watch-split watch-split--compact"
    >
      <div v-if="medicalFocus.length" class="watch-panel watch-panel--risk">
        <div class="watch-head">
          <span class="watch-badge watch-badge--risk">健康风险</span>
          <span class="watch-count">{{ medicalFocus.length }} 条</span>
        </div>
        <ol class="persona-points">
          <li v-for="(p, i) in medicalFocus" :key="`m-${i}`">
            <span class="point-index">{{ i + 1 }}</span>
            <span class="point-text">{{ p }}</span>
          </li>
        </ol>
      </div>
      <div v-if="opsFocus.length" class="watch-panel watch-panel--ops">
        <div class="watch-head">
          <span class="watch-badge watch-badge--ops">待办</span>
          <span class="watch-count">{{ opsFocus.length }} 条</span>
        </div>
        <ol class="persona-points">
          <li v-for="(p, i) in opsFocus" :key="`o-${i}`">
            <span class="point-index">{{ i + 1 }}</span>
            <span class="point-text">{{ p }}</span>
          </li>
        </ol>
      </div>
    </div>

    <article v-if="primaryBlocks.length" class="archive-card" aria-label="基础档案">
      <div class="archive-card-head">
        <h3>基础档案</h3>
      </div>
      <div class="archive-cols">
        <section
          v-for="b in primaryBlocks"
          :key="b.key"
          class="archive-col"
          :data-dim="b.key"
        >
          <h4 class="archive-col-title">{{ b.title }}</h4>
          <p v-if="b.empty" class="dim-empty">{{ b.empty }}</p>
          <dl v-else class="dim-rows dim-rows--tight">
            <div
              v-for="(r, i) in b.rows"
              :key="`${b.key}-${i}`"
              class="dim-row"
              :class="[
                r.tone ? `tone-${r.tone}` : undefined,
                r.chips?.length || r.capsule ? 'has-chips' : undefined,
              ]"
            >
              <dt>{{ r.label }}</dt>
              <dd v-if="r.chips?.length" class="dim-chips">
                <span
                  v-for="c in r.chips"
                  :key="c"
                  :class="b.key === 'lifestyle' || r.capsule ? 'lifestyle-chip' : 'disease-chip'"
                >{{ c }}</span>
              </dd>
              <dd v-else-if="r.capsule" class="dim-chips">
                <span class="lifestyle-chip">{{ r.value }}</span>
              </dd>
              <dd v-else>{{ r.value }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </article>

    <!-- 健康数据快照：血压 / 用药 / 病史 / 化验 / 方案 -->
    <div class="health-snap" :aria-busy="healthLoading" aria-label="健康数据快照">
      <section class="health-row health-row--2">
        <article class="health-card health-card--bp">
          <div class="health-card-head">
            <div>
              <h3>血压趋势 · 近 14 天</h3>
              <p class="health-sub">收缩压 / 舒张压</p>
            </div>
            <div class="health-head-meta">
              <span v-if="bpAverages" class="health-avg">{{ bpAverages }}</span>
              <div class="health-legend" aria-hidden="true">
                <span><i class="dot rose" />收缩压</span>
                <span><i class="dot brand" />舒张压</span>
              </div>
            </div>
          </div>
          <div class="health-card-body health-card-body--chart">
            <MetricSparklineChart
              v-if="bpHasData"
              class="bp-chart"
              :categories="bpCategories"
              :series="bpSeries"
              unit="mmHg"
              :precision="0"
              panel
              :normal-range="{ min: 90, max: 140 }"
              empty-text="暂无血压记录"
            />
            <p v-else class="health-empty">暂无血压记录</p>
          </div>
        </article>

        <article class="health-card">
          <div class="health-card-head">
            <div>
              <h3>当前用药</h3>
              <p class="health-sub">{{ medSub }}</p>
            </div>
            <button type="button" class="health-btn" @click="goMedications">添加</button>
          </div>
          <div class="health-card-body">
            <ul v-if="activeMeds.length" class="med-list">
              <li v-for="m in activeMeds" :key="m.id" class="med-item">
                <div class="med-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M10.5 20H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h7l5 5v3" />
                    <circle cx="13" cy="13" r="2" />
                  </svg>
                </div>
                <div class="med-body">
                  <div class="med-name">{{ m.name }}</div>
                  <div v-if="m.desc" class="med-desc">{{ m.desc }}</div>
                </div>
                <span class="med-tag">在用</span>
              </li>
            </ul>
            <p v-else class="health-empty">暂无在用药物，可前往用药页添加</p>
          </div>
        </article>
      </section>

      <section class="health-row health-row--2">
        <article class="health-card">
          <div class="health-card-head">
            <div>
              <h3>病史摘要</h3>
              <p class="health-sub">{{ historyHasData ? '建档档案摘要' : '暂无病史采集' }}</p>
            </div>
            <button type="button" class="health-btn" @click="goArchive">查看档案</button>
          </div>
          <div class="health-card-body">
            <dl class="hist-fields">
              <div v-for="f in historyFields" :key="f.key" class="hist-field">
                <dt>{{ f.label }}</dt>
                <dd :class="f.tone ? `tone-${f.tone}` : undefined">
                  <template v-if="f.chips?.length">
                    <span v-for="c in f.chips" :key="c" class="hist-chip">{{ c }}</span>
                  </template>
                  <template v-else>
                    <span :class="{ muted: f.empty }">{{ f.value }}</span>
                  </template>
                </dd>
              </div>
            </dl>
          </div>
        </article>

        <article class="health-card">
          <div class="health-card-head">
            <div>
              <h3>最近化验{{ labTitleDate ? ` · ${labTitleDate}` : '' }}</h3>
              <p class="health-sub">{{ labSub }}</p>
            </div>
            <button type="button" class="health-btn" @click="goLabs">OCR 上传</button>
          </div>
          <div class="health-card-body">
            <dl v-if="labHasData && labRows.length" class="hist-fields">
              <div v-for="row in labRows" :key="row.key" class="hist-field">
                <dt>{{ row.name }}</dt>
                <dd :class="row.tone ? `tone-${row.tone}` : undefined">
                  <strong>{{ row.value }}</strong>
                  <span v-if="row.flag" class="lab-flag">{{ row.flag }}</span>
                </dd>
              </div>
            </dl>
            <p v-else class="health-empty">暂无化验报告</p>
          </div>
        </article>
      </section>

      <section class="health-row">
        <article class="health-card">
          <div class="health-card-head">
            <div>
              <h3>{{ planTitle || '当前健康方案' }}</h3>
              <p class="health-sub">{{ planSub || '暂无生效方案' }}</p>
            </div>
            <div class="health-head-actions">
              <button type="button" class="health-btn" @click="goCarePlan">查看历史版本</button>
              <button type="button" class="health-btn health-btn--primary" @click="goCarePlan">
                调方案
              </button>
            </div>
          </div>
          <div class="health-card-body">
            <div v-if="planHasData && planColumns.length" class="plan-cols">
              <div v-for="col in planColumns" :key="col.key" class="plan-col">
                <div class="plan-col-title">{{ col.title }}</div>
                <ul class="plan-lines">
                  <li v-for="(line, i) in col.lines" :key="`${col.key}-${i}`">{{ line }}</li>
                </ul>
              </div>
            </div>
            <p v-else class="health-empty">暂无生效健康方案，可前往方案页制定</p>
          </div>
        </article>
      </section>
    </div>

    <div v-if="editing" class="persona-edit" role="dialog" aria-label="编辑叙事">
      <div class="edit-card">
        <h3>编辑叙事（保存后整包锁定）</h3>
        <label>
          标题
          <input v-model="editHeadline" maxlength="40" />
        </label>
        <label>
          正文
          <textarea v-model="editNarrative" rows="6" />
        </label>
        <label>
          盯梢点（每行一条，最多 3）
          <textarea v-model="editWatch" rows="3" />
        </label>
        <div class="edit-actions">
          <button type="button" class="btn ghost" :disabled="busy" @click="editing = false">取消</button>
          <button type="button" class="btn primary" :disabled="busy" @click="onSaveEdit">保存并锁定</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.persona-page {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 2px 0 40px;
  min-height: 240px;
}

.persona-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}

.hero-kicker {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.persona-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: 0.01em;
}

.persona-gen-time {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  line-height: 1.3;
  color: var(--gray-999);
  font-weight: 400;
}

.age-update-tag {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--amber-600);
  background: var(--amber-150);
  border: 1px solid var(--amber-200);
  border-radius: 4px;
  padding: 2px 6px;
}

.persona-sub {
  font-size: 12px;
  color: var(--auto-7a8896);
}

.tag {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.tag.lock {
  color: var(--amber-800);
  background: var(--auto-fff6e0);
  border-color: var(--auto-f0d59a);
}

.tag.soft {
  color: var(--auto-4a657a);
  background: var(--auto-eef3f7);
  border-color: var(--auto-d5e0e8);
}

.watch-split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: stretch;
  gap: 8px;
}

.watch-split:has(.watch-panel:only-child) {
  grid-template-columns: 1fr;
}

.watch-split--compact .watch-panel {
  padding: 8px 12px 10px;
}

.watch-panel {
  min-width: 0;
  height: 100%;
  min-height: 88px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid var(--auto-f0d59a);
  background: linear-gradient(180deg, var(--auto-fff9eb) 0%, var(--auto-fffdf7) 100%);
  box-shadow: inset 0 1px 0 var(--auto-rgba-25525525507);
  overflow: visible;
  box-sizing: border-box;
}

.watch-panel--risk {
  border-color: var(--auto-f0c0b8);
  background: linear-gradient(180deg, var(--auto-fff5f3) 0%, var(--auto-fffdfc) 100%);
}

.watch-panel--ops {
  border-color: var(--auto-c5d8e8);
  background: linear-gradient(180deg, var(--auto-f3f8fc) 0%, var(--auto-fbfdfe) 100%);
}

.watch-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.watch-badge {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--amber-800);
  background: var(--auto-ffe8b0);
  border-radius: 999px;
  padding: 2px 8px;
}

.watch-badge--risk {
  color: var(--auto-a33b2c);
  background: var(--auto-ffd5ce);
}

.watch-badge--ops {
  color: var(--auto-2f5f7a);
  background: var(--auto-d5e8f5);
}

.watch-count {
  font-size: 11px;
  color: var(--auto-8090a0);
}

.watch-panel--risk .watch-count {
  color: var(--auto-b06055);
}

.watch-panel--ops .watch-count {
  color: var(--auto-5a7a90);
}

.persona-points {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.persona-points li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  line-height: 1.45;
  color: var(--auto-3a2a10);
  font-weight: 550;
  min-width: 0;
}

.point-index {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--amber-800);
  background: var(--auto-ffe8b0);
}

.point-text {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.archive-card {
  border: 1px solid var(--auto-e6eef2);
  border-radius: 12px;
  background: var(--white);
  padding: 18px 24px;
}

.archive-card-head {
  margin-bottom: 10px;
}

.archive-card-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  color: var(--auto-425566);
}

.archive-cols {
  display: flex;
  align-items: stretch;
  gap: 0;
  min-width: 0;
}

.archive-col {
  flex: 1 1 0;
  min-width: 0;
  padding: 0 24px 0 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #eceef2;
}

.archive-col + .archive-col {
  padding-left: 24px;
}

.archive-col:last-child {
  padding-right: 0;
  border-right: none;
}

.archive-col-title,
.archive-col h4 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 650;
  color: var(--ink-500);
  letter-spacing: 0.02em;
}

.lifestyle-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 2px 8px;
  border-radius: 6px;
  background: #fff7e8;
  color: #ff7d00;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.45;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.dim-rows--tight {
  gap: 2px;
}

.archive-col .dim-row {
  grid-template-columns: 72px 1fr;
  gap: 4px;
  font-size: 12px;
  line-height: 1.3;
}

.archive-col .dim-row.has-chips {
  align-items: flex-start;
}

.dim-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 6px;
  row-gap: 6px;
  column-gap: 6px;
  align-items: center;
  align-content: flex-start;
}

.archive-col[data-dim='safety'] .dim-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 6px;
  row-gap: 8px;
  column-gap: 6px;
  align-content: flex-start;
}

.archive-col[data-dim='safety'] .dim-row.has-chips {
  align-items: flex-start;
  padding-bottom: 2px;
}

.archive-col[data-dim='safety'] .disease-chip {
  margin: 0;
}

.disease-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--auto-dbe7f3);
  background: var(--auto-f3f7fb);
  color: var(--auto-3d5a73);
  font-size: 11px;
  font-weight: 550;
  line-height: 1.45;
  white-space: nowrap;
}

.persona-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
  flex-shrink: 0;
}

.btn {
  border-radius: 8px;
  border: 1px solid var(--auto-d5dee6);
  background: var(--white);
  padding: 8px 12px;
  font-size: 12px;
  cursor: pointer;
  color: var(--auto-2a3b4c);
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn.primary {
  background: var(--auto-1f6b5c);
  border-color: var(--auto-1f6b5c);
  color: var(--white);
}

@keyframes persona-btn-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 var(--ink-a20);
  }
  50% {
    box-shadow: 0 0 0 6px var(--auto-rgba-1523420);
  }
}

.btn.primary.btn-pulse:not(:disabled) {
  animation: persona-btn-pulse 2.2s ease-in-out infinite;
}

.btn.ghost:hover:not(:disabled) {
  background: var(--auto-f2f6f8);
}

.narrative-card {
  padding: 16px;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  background: var(--ink-35);
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  overflow: hidden;
}

.narrative-card--hero {
  background: linear-gradient(180deg, var(--auto-f3f7fb) 0%, var(--ink-35) 100%);
  border-color: var(--auto-dbe7f3);
  box-shadow: 0 1px 0 var(--auto-rgba-44126248004);
}

.narrative-card--empty {
  background: var(--ink-35);
  border-style: dashed;
}

.section-head {
  margin-bottom: 8px;
}

.section-head--row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.section-head-main {
  min-width: 0;
  flex: 1;
}

.stale-hint {
  display: inline-flex;
  align-items: center;
  max-width: 220px;
  font-size: 12px;
  line-height: 1.35;
  color: var(--amber-600);
  background: var(--amber-100);
  border: 1px solid var(--amber-200);
  border-radius: 6px;
  padding: 4px 8px;
  white-space: normal;
}

.stale-hint--card {
  flex-shrink: 0;
  max-width: 200px;
}

.section-head h3,
.dim-card h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  color: var(--auto-425566);
  letter-spacing: 0.02em;
}

.narrative-kicker {
  margin: 4px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  line-height: 1.4;
}

.narrative-body-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.narrative-body {
  margin: 0;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  font-size: 14px;
  line-height: 1.75;
  color: var(--ink-800);
  white-space: normal;
  word-wrap: break-word;
  overflow-wrap: break-word;
  word-break: break-word;
}

.narrative-body.muted {
  color: var(--ink-400);
  font-size: 13px;
}

.narrative-body :deep(.narrative-em) {
  font-weight: 700;
  color: var(--ink-900);
  background: #fff7e8;
  border-radius: 3px;
  padding: 0 2px;
  box-decoration-break: clone;
  white-space: normal;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.narrative-extract {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 0 dashed var(--auto-d7e2ec);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.core-chip-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}

.core-chip-prefix {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 650;
  color: var(--ink-500);
}

.dim-card {
  background: var(--white);
  border-radius: 12px;
  padding: 10px 12px 8px;
  border: 1px solid var(--auto-e6eef2);
  box-shadow: 0 1px 0 var(--auto-rgba-154060002);
}

.dim-card.priority {
  border-color: var(--auto-f0d59a);
  background: linear-gradient(180deg, var(--auto-fffdf8) 0%, var(--white) 48%);
  box-shadow: 0 1px 0 var(--auto-rgba-138900004);
}

.dim-card[data-dim='risk'].priority {
  border-color: var(--auto-f5c2c2);
  background: linear-gradient(180deg, var(--auto-fff8f8) 0%, var(--white) 48%);
}

.dim-card[data-dim='focus'].priority {
  border-color: var(--auto-9fd6cb);
  background: linear-gradient(180deg, var(--auto-f3fbf9) 0%, var(--white) 48%);
}

.dim-card--compact .dim-head {
  margin-bottom: 6px;
  padding-bottom: 6px;
}

.dim-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 0 solid var(--auto-eef2f5);
}

.dim-card.priority .dim-head {
  border-bottom-color: var(--auto-rgba-138900012);
}

.dim-card[data-dim='risk'].priority .dim-head {
  border-bottom-color: var(--auto-rgba-1857070012);
}

.dim-card[data-dim='focus'].priority .dim-head {
  border-bottom-color: var(--auto-rgba-3110792014);
}

.dim-card h4 {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.dim-priority-tag {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--amber-800);
  background: var(--auto-ffe8b0);
  border-radius: 999px;
  padding: 2px 6px;
}

.dim-card[data-dim='risk'] .dim-priority-tag {
  color: var(--auto-9b2c2c);
  background: var(--auto-fde2e2);
}

.dim-card[data-dim='focus'] .dim-priority-tag {
  color: var(--auto-1f6b5c);
  background: var(--auto-rgba-3110792012);
}

.dim-hint,
.dim-hint-line {
  font-size: 11px;
  color: var(--auto-8a98a6);
  font-weight: 500;
}

.dim-hint-line {
  margin: -2px 0 10px;
}

.dim-empty {
  margin: 0;
  font-size: 12px;
  color: var(--auto-8a98a6);
}

.dim-rows {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dim-card--compact .dim-rows {
  gap: 4px;
}

.dim-row {
  display: grid;
  grid-template-columns: 84px 1fr;
  gap: 6px;
  align-items: start;
  font-size: 12px;
  line-height: 1.35;
}

.core-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.core-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  font-size: 12px;
  color: var(--ink-600);
  line-height: 1.3;
}

.core-chip em {
  font-style: normal;
  font-weight: 650;
  color: var(--ink-500);
}

.core-chip.tone-danger {
  border-color: var(--rose-200);
  background: var(--auto-fff5f5);
  color: var(--auto-9b2c2c);
}

.core-chip.tone-accent {
  border-color: var(--auto-cce8e1);
  background: var(--auto-f3fbf9);
  color: var(--auto-1f6b5c);
}

.core-chip.tone-muted {
  color: var(--ink-400);
}

.dim-row dt {
  margin: 0;
  color: var(--auto-7a8896);
  font-weight: 500;
}

.dim-row dd {
  margin: 0;
  color: var(--auto-2c3e50);
  word-break: break-word;
}

.dim-row.tone-danger dd {
  color: var(--auto-9b2c2c);
  font-weight: 650;
}

.dim-row.tone-warn dd {
  color: var(--auto-9a6700);
  font-weight: 600;
}

.dim-row.tone-accent dd {
  color: var(--auto-1f6b5c);
  font-weight: 600;
}

.dim-row.tone-muted dd {
  color: var(--auto-8a98a6);
  font-weight: 400;
}

.persona-edit {
  position: fixed;
  inset: 0;
  background: var(--auto-rgba-102030035);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 16px;
}

.edit-card {
  width: min(520px, 100%);
  background: var(--white);
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 12px 40px var(--auto-rgba-000018);
}

.edit-card h3 {
  margin: 0 0 12px;
  font-size: 15px;
}

.edit-card label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--auto-5a6b7a);
}

.edit-card input,
.edit-card textarea {
  border: 1px solid var(--auto-d5dee6);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

/* —— 健康数据快照 —— */
.health-snap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 4px;
}

.health-row {
  display: grid;
  gap: 12px;
}

.health-row--2 {
  grid-template-columns: 1fr 1fr;
}

.health-card {
  background: #fff;
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 12px;
  min-width: 0;
  overflow: visible;
}

.health-card--bp {
  overflow: visible;
}

.health-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px 0;
}

.health-head-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
  max-width: 52%;
}

.health-avg {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600, #475569);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.health-card-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
}

.health-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--ink-500, #64748b);
}

.health-legend {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--ink-500, #64748b);
  flex-shrink: 0;
}

.health-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.health-legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.health-legend .dot.rose {
  background: #b91c1c;
}

.health-legend .dot.brand {
  background: #1d4ed8;
}

.health-head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.health-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid var(--ink-200, #e2e8f0);
  background: #fff;
  color: var(--ink-700, #334155);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease;
}

.health-btn:hover {
  background: var(--ink-50, #f8fafc);
  border-color: var(--ink-300, #cbd5e1);
}

.health-btn--primary {
  background: var(--brand-500, #2c7ef8);
  border-color: var(--brand-500, #2c7ef8);
  color: #fff;
}

.health-btn--primary:hover {
  background: var(--brand-600, #1e63d9);
  border-color: var(--brand-600, #1e63d9);
}

.health-card-body {
  padding: 12px 16px 16px;
  overflow: visible;
}

.health-card-body--chart {
  min-height: 220px;
  overflow: visible;
}

.bp-chart {
  height: 220px;
  min-height: 220px;
  width: 100%;
  display: block;
}

.health-empty {
  margin: 8px 0 4px;
  font-size: 13px;
  color: var(--ink-400, #94a3b8);
}

.med-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.med-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 10px;
  transition: border-color 160ms ease, background 160ms ease;
}

.med-item:hover {
  border-color: var(--brand-500, #2c7ef8);
  background: #f8fbff;
}

.med-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #eaf2ff;
  color: #2c7ef8;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.med-icon svg {
  width: 18px;
  height: 18px;
}

.med-body {
  flex: 1;
  min-width: 0;
}

.med-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
}

.med-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--ink-500, #64748b);
}

.med-tag {
  flex-shrink: 0;
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 5px;
  background: #e6faf7;
  color: #0f766e;
}

.hist-fields {
  margin: 0;
}

.hist-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
}

.hist-field:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.hist-field:first-child {
  padding-top: 0;
}

.hist-field dt {
  width: 64px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--ink-500, #64748b);
  line-height: 1.5;
}

.hist-field dd {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-800, #1e293b);
  line-height: 1.5;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
}

.hist-field dd.tone-danger,
.hist-field dd.tone-danger strong {
  color: #ef4444;
  font-weight: 600;
}

.hist-field dd.tone-warn,
.hist-field dd.tone-warn strong {
  color: #d97706;
  font-weight: 600;
}

.hist-field dd .muted {
  color: var(--ink-400, #94a3b8);
  font-weight: 400;
}

.hist-chip {
  display: inline-flex;
  align-items: center;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 5px;
  background: #f1ecfe;
  color: #6d28d9;
}

.lab-flag {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink-400, #94a3b8);
  margin-left: 2px;
}

.plan-cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.plan-col-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500, #64748b);
  margin-bottom: 8px;
}

.plan-lines {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 13px;
  line-height: 1.75;
  color: var(--ink-800, #1e293b);
}

.plan-lines li::before {
  content: '· ';
  color: var(--ink-400, #94a3b8);
}

@media (max-width: 960px) {
  .health-row--2 {
    grid-template-columns: 1fr;
  }

  .plan-cols {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .persona-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .persona-actions {
    justify-content: flex-start;
  }

  .watch-split {
    grid-template-columns: 1fr;
  }

  .archive-cols {
    flex-direction: column;
    gap: 8px;
  }

  .archive-col,
  .archive-col:last-child {
    padding: 0;
    border-right: none;
  }

  .archive-col + .archive-col {
    border-top: 1px solid #eceef2;
    padding-top: 12px;
    padding-left: 0;
  }

  .dim-row,
  .archive-col .dim-row {
    grid-template-columns: 84px 1fr;
  }

  .health-row--2 {
    grid-template-columns: 1fr;
  }

  .plan-cols {
    grid-template-columns: 1fr;
  }

  .health-card-head {
    flex-wrap: wrap;
  }
}

</style>
