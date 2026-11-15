<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
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

const props = defineProps<{ peopleId?: string }>()

const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

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

/** 叙事全文：仅高亮短医学指标（血压/带单位数值/短小数），禁止整句黄底 */
function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 仅短指标：血压、带单位数值、一位小数（如 BMI 28.7）；不碰「近7日…」长句 / 裸 % / 关键词 */
function highlightNarrative(raw: string | null | undefined): string {
  if (!raw) return ''
  const wrap = (m: string) =>
    m.length > 16 ? m : `<strong class="narrative-em">${m}</strong>`
  return escapeHtml(raw)
    .replace(/\d{2,3}\s*\/\s*\d{2,3}(?:\s*mmHg)?/g, wrap)
    .replace(/\d+(?:\.\d+)?\s*(?:mmol\/L|mg\/dL|mmHg|kg\/m²|kg\/m2|bpm)/gi, wrap)
    .replace(/(^|[^\d.])(\d{1,2}\.\d)(?!\d)/g, (_m, pre: string, n: string) => `${pre}${wrap(n)}`)
}

const narrativeHtml = computed(() => highlightNarrative(view.value?.narrative?.narrative))

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

const RISK_CATEGORY_LABELS: Record<string, string> = {
  RISK: '患病风险',
  SEVERITY: '严重分层',
  SUSPECTED: '疑似患病',
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
  coreChips: DimRow[]
}

const emptyLayout = (): PersonaLayout => ({
  primary: [],
  coreChips: [],
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
  const riskRows: DimRow[] = tags.length
    ? tags.map((t) => {
        const row = t as Record<string, unknown>
        const disease = codeLabel(DISEASE_TAG_LABELS, row.diseaseCode, String(row.diseaseCode || '病种'))
        const cat = codeLabel(RISK_CATEGORY_LABELS, row.category, '')
        // level 可能是 MID/HIGH 等枚举，优先中文 levelLabel
        const levelRaw = String(row.levelLabel || row.level || '').trim()
        const level = codeLabel(TIER_LABELS, levelRaw, levelRaw)
        const tierRaw = String(row.tier || '').toUpperCase()
        const tierZh = row.tier ? codeLabel(TIER_LABELS, row.tier, '') : ''
        // 分层文案已含「中/高」时不再叠「中档」
        const tier = tierZh && !level.includes(tierZh) ? ` · ${tierZh}档` : ''
        const tone: DimRow['tone'] =
          tierRaw === 'HIGH' || tierRaw === 'VERY_HIGH' || /高|严重|极高/.test(level)
            ? 'danger'
            : tierRaw === 'MEDIUM' || tierRaw === 'MID' || /中/.test(level)
              ? 'warn'
              : undefined
        return {
          label: disease,
          value: ([cat, level].filter(Boolean).join(' · ') + tier) || '—',
          tone,
        }
      })
    : []

  const allergenText = listText(constraints.allergens)
  const dietText = listText(behavior.diet)
  const exerciseText = listText(behavior.exercise)
  const careTracksText = formatCodedList(goals.careTracks, GOAL_SUMMARY_LABELS, '')
  const goalRows: DimRow[] = [
    {
      label: '主诉求',
      value: formatCodedList(goals.summary, GOAL_SUMMARY_LABELS, '暂无'),
      tone: asCodeList(goals.summary).length ? 'accent' : 'muted',
    },
    {
      label: '目标模式',
      value: goals.mode ? codeLabel(GOAL_MODE_LABELS, goals.mode, '暂无') : '暂无',
    },
  ]
  if (careTracksText) {
    goalRows.push({ label: '管理轨道', value: careTracksText })
  }
  goalRows.push({ label: '来自首诊', value: goals.fromIntake ? '是' : '否' })

  return {
    primary: [
      {
        key: 'identity',
        title: '管理身份',
        rows: [
          { label: '性别', value: formatGender(identity.sex as string) },
          { label: '年龄', value: identity.age != null ? `${identity.age} 岁` : '—' },
          { label: '主管健管师', value: String(identity.primaryCareManagerName || '未分配') },
          { label: '健管组', value: String(identity.careTeamName || '未分配') },
          {
            label: '入组',
            value: identity.enrolledDays != null ? `${identity.enrolledDays} 天` : '—',
          },
          {
            label: '方案类型',
            value: identity.activePlanTemplateKey
              ? formatCarePlanTemplateKey(String(identity.activePlanTemplateKey))
              : '暂无',
          },
          (() => {
            const chips = dedupeDiseaseTagChips(identity.diseaseTags, DISEASE_TAG_LABELS)
            return {
              label: '病种标签',
              value: chips.length ? chips.join('、') : '—',
              chips: chips.length ? chips : undefined,
              tone: chips.length ? undefined : ('muted' as const),
            }
          })(),
        ],
      },
      {
        key: 'goals',
        title: '目标与诉求',
        rows: goalRows,
      },
      {
        key: 'risk',
        title: '风险与病情',
        priority: true,
        hint: '评估与分层',
        rows: riskRows,
        empty: riskRows.length ? undefined : '暂无评估标签',
      },
    ] as DimBlock[],
    coreChips: [
      { label: '过敏', value: allergenText, tone: allergenText !== '—' ? 'danger' : 'muted' },
      { label: '饮食', value: dietText, tone: dietText !== '—' ? 'accent' : 'muted' },
      { label: '运动', value: exerciseText, tone: exerciseText !== '—' ? 'accent' : 'muted' },
    ] as DimRow[],
  }
})

const primaryBlocks = computed(() => dimBlocks.value.primary)
const coreChips = computed(() => dimBlocks.value.coreChips)
const hasSoftPanel = computed(() => coreChips.value.length > 0)

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    view.value = await getPersona(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户画像失败')
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
          <span v-if="suggestAgeUpdate" class="age-update-tag">⚠️ 建议更新</span>
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
          ⚠️ 数据已更新，建议重新生成
        </span>
      </div>
      <p
        v-if="view?.narrative?.narrative"
        class="narrative-body"
        v-html="narrativeHtml"
      />
      <p v-else class="narrative-body muted">
        {{ loading ? '加载中…' : '暂无案头叙事，可点击右上角生成' }}
      </p>
      <div v-if="hasSoftPanel" class="narrative-extract" aria-label="核心提取标签">
        <div class="core-chips">
          <span
            v-for="c in coreChips"
            :key="c.label"
            class="core-chip"
            :class="c.tone ? `tone-${c.tone}` : undefined"
          >
            <em>{{ c.label }}</em>
            {{ isBlankDimValue(c.value) ? '未采集' : c.value }}
          </span>
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
          <h4>{{ b.title }}</h4>
          <p v-if="b.empty" class="dim-empty">{{ b.empty }}</p>
          <dl v-else class="dim-rows dim-rows--tight">
            <div
              v-for="(r, i) in b.rows"
              :key="`${b.key}-${i}`"
              class="dim-row"
              :class="[r.tone ? `tone-${r.tone}` : undefined, r.chips?.length ? 'has-chips' : undefined]"
            >
              <dt>{{ r.label }}</dt>
              <dd v-if="r.chips?.length" class="dim-chips">
                <span v-for="c in r.chips" :key="c" class="disease-chip">{{ c }}</span>
              </dd>
              <dd v-else>{{ r.value }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </article>

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
  padding: 2px 0 12px;
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
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-900, #1a2b3c);
  letter-spacing: 0.01em;
}

.persona-gen-time {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  line-height: 1.3;
  color: #999;
  font-weight: 400;
}

.age-update-tag {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fde68a;
  border-radius: 4px;
  padding: 1px 6px;
}

.persona-sub {
  font-size: 12px;
  color: #7a8896;
}

.tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.tag.lock {
  color: #8a5a00;
  background: #fff6e0;
  border-color: #f0d59a;
}

.tag.soft {
  color: #4a657a;
  background: #eef3f7;
  border-color: #d5e0e8;
}

.watch-split {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 8px;
}

.watch-split--compact .watch-panel {
  padding: 8px 12px 10px;
}

.watch-panel {
  flex: 1 1 0;
  min-width: 0;
  min-height: 88px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #f0d59a;
  background: linear-gradient(180deg, #fff9eb 0%, #fffdf7 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
  overflow: visible;
}

.watch-panel--risk {
  border-color: #f0c0b8;
  background: linear-gradient(180deg, #fff5f3 0%, #fffdfc 100%);
}

.watch-panel--ops {
  border-color: #c5d8e8;
  background: linear-gradient(180deg, #f3f8fc 0%, #fbfdfe 100%);
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
  color: #8a5a00;
  background: #ffe8b0;
  border-radius: 999px;
  padding: 2px 8px;
}

.watch-badge--risk {
  color: #a33b2c;
  background: #ffd5ce;
}

.watch-badge--ops {
  color: #2f5f7a;
  background: #d5e8f5;
}

.watch-count {
  font-size: 11px;
  color: #8090a0;
}

.watch-panel--risk .watch-count {
  color: #b06055;
}

.watch-panel--ops .watch-count {
  color: #5a7a90;
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
  font-size: 12.5px;
  line-height: 1.45;
  color: #3a2a10;
  font-weight: 550;
  min-width: 0;
}

.point-index {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  color: #8a5a00;
  background: #ffe8b0;
}

.point-text {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.archive-card {
  border: 1px solid #e6eef2;
  border-radius: 12px;
  background: #fff;
  padding: 8px 12px 10px;
}

.archive-card-head {
  margin-bottom: 6px;
}

.archive-card-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  color: #425566;
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
  padding: 0 12px;
  display: flex;
  flex-direction: column;
}

/* 目标与诉求行数较少时垂直居中，避免缺「管理轨道」时底部留白 */
.archive-col[data-dim='goals'] .dim-rows--tight {
  flex: 1;
  justify-content: center;
}

.archive-col:first-child {
  padding-left: 0;
}

.archive-col:last-child {
  padding-right: 0;
}

.archive-col + .archive-col {
  border-left: 1px solid #eef2f5;
}

.archive-col h4 {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 650;
  color: #64748b;
}

.dim-rows--tight {
  gap: 2px;
}

.archive-col .dim-row {
  grid-template-columns: 72px 1fr;
  gap: 4px;
  font-size: 11.5px;
  line-height: 1.3;
}

.archive-col .dim-row.has-chips {
  align-items: center;
}

.dim-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.disease-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid #dbe7f3;
  background: #f3f7fb;
  color: #3d5a73;
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
  border: 1px solid #d5dee6;
  background: #fff;
  padding: 7px 12px;
  font-size: 12px;
  cursor: pointer;
  color: #2a3b4c;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn.primary {
  background: #1f6b5c;
  border-color: #1f6b5c;
  color: #fff;
}

@keyframes persona-btn-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(31, 107, 92, 0.35);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(31, 107, 92, 0);
  }
}

.btn.primary.btn-pulse:not(:disabled) {
  animation: persona-btn-pulse 2.2s ease-in-out infinite;
}

.btn.ghost:hover:not(:disabled) {
  background: #f2f6f8;
}

.narrative-card {
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8f9fa;
}

.narrative-card--hero {
  background: linear-gradient(180deg, #f3f7fb 0%, #f8f9fa 100%);
  border-color: #dbe7f3;
  box-shadow: 0 1px 0 rgba(44, 126, 248, 0.04);
}

.narrative-card--empty {
  background: #f8f9fa;
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
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
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
  color: #425566;
  letter-spacing: 0.02em;
}

.narrative-kicker {
  margin: 4px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  line-height: 1.4;
}

.narrative-body {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: #1e293b;
  white-space: pre-wrap;
}

.narrative-body.muted {
  color: #94a3b8;
  font-size: 13px;
}

.narrative-body :deep(.narrative-em) {
  font-weight: 650;
  color: #0f172a;
  background: rgba(15, 23, 42, 0.06);
  border-radius: 2px;
  padding: 0;
  box-decoration-break: clone;
}

.narrative-extract {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #d7e2ec;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dim-card {
  background: #fff;
  border-radius: 12px;
  padding: 10px 12px 8px;
  border: 1px solid #e6eef2;
  box-shadow: 0 1px 0 rgba(15, 40, 60, 0.02);
}

.dim-card.priority {
  border-color: #f0d59a;
  background: linear-gradient(180deg, #fffdf8 0%, #ffffff 48%);
  box-shadow: 0 1px 0 rgba(138, 90, 0, 0.04);
}

.dim-card[data-dim='risk'].priority {
  border-color: #f5c2c2;
  background: linear-gradient(180deg, #fff8f8 0%, #ffffff 48%);
}

.dim-card[data-dim='focus'].priority {
  border-color: #9fd6cb;
  background: linear-gradient(180deg, #f3fbf9 0%, #ffffff 48%);
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
  border-bottom: 1px solid #eef2f5;
}

.dim-card.priority .dim-head {
  border-bottom-color: rgba(138, 90, 0, 0.12);
}

.dim-card[data-dim='risk'].priority .dim-head {
  border-bottom-color: rgba(185, 70, 70, 0.12);
}

.dim-card[data-dim='focus'].priority .dim-head {
  border-bottom-color: rgba(31, 107, 92, 0.14);
}

.dim-card h4 {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.dim-priority-tag {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #8a5a00;
  background: #ffe8b0;
  border-radius: 999px;
  padding: 1px 7px;
}

.dim-card[data-dim='risk'] .dim-priority-tag {
  color: #9b2c2c;
  background: #fde2e2;
}

.dim-card[data-dim='focus'] .dim-priority-tag {
  color: #1f6b5c;
  background: rgba(31, 107, 92, 0.12);
}

.dim-hint,
.dim-hint-line {
  font-size: 11px;
  color: #8a98a6;
  font-weight: 500;
}

.dim-hint-line {
  margin: -2px 0 10px;
}

.dim-empty {
  margin: 0;
  font-size: 12px;
  color: #8a98a6;
}

.dim-rows {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
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
}

.core-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  font-size: 12px;
  color: #475569;
  line-height: 1.3;
}

.core-chip em {
  font-style: normal;
  font-weight: 650;
  color: #64748b;
}

.core-chip.tone-danger {
  border-color: #fecaca;
  background: #fff5f5;
  color: #9b2c2c;
}

.core-chip.tone-accent {
  border-color: #cce8e1;
  background: #f3fbf9;
  color: #1f6b5c;
}

.core-chip.tone-muted {
  color: #94a3b8;
}

.dim-row dt {
  margin: 0;
  color: #7a8896;
  font-weight: 500;
}

.dim-row dd {
  margin: 0;
  color: #2c3e50;
  word-break: break-word;
}

.dim-row.tone-danger dd {
  color: #9b2c2c;
  font-weight: 650;
}

.dim-row.tone-warn dd {
  color: #9a6700;
  font-weight: 600;
}

.dim-row.tone-accent dd {
  color: #1f6b5c;
  font-weight: 600;
}

.dim-row.tone-muted dd {
  color: #8a98a6;
  font-weight: 400;
}

.persona-edit {
  position: fixed;
  inset: 0;
  background: rgba(10, 20, 30, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 16px;
}

.edit-card {
  width: min(520px, 100%);
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
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
  color: #5a6b7a;
}

.edit-card input,
.edit-card textarea {
  border: 1px solid #d5dee6;
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

@media (max-width: 720px) {
  .persona-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .persona-actions {
    justify-content: flex-start;
  }

  .watch-panel {
    flex: 1 1 100%;
  }

  .archive-cols {
    flex-direction: column;
    gap: 8px;
  }

  .archive-col,
  .archive-col:first-child,
  .archive-col:last-child {
    padding: 0;
  }

  .archive-col + .archive-col {
    border-left: none;
    border-top: 1px solid #eef2f5;
    padding-top: 8px;
  }

  .dim-row,
  .archive-col .dim-row {
    grid-template-columns: 84px 1fr;
  }
}
</style>
