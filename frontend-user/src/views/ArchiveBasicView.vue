<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { loadDictOptions, type DictItem } from '../shared/dict'
import { C_ILLNESS_PRIMARY } from '../shared/intake-c'
import {
  abnormalLabel,
  bmiBand,
  calcBmi,
  evaluateBloodPressure,
  evaluateGlucose,
  isAbnormal,
  vitalPlausibilityError,
  type AbnormalFlag,
} from '../shared/metric-ranges'

const PRESENT_ILLNESS_NONE = 'NONE'
const BLOOD_OPTIONS = ['A', 'B', 'AB', 'O'] as const

/** 病种档案 code → 现有疾病字典 code（与健管端一致：只补齐勾选） */
const DISEASE_ARCHIVE_TO_PRESENT: Record<string, string> = {
  diabetes: 'DIABETES',
  hypertension: 'HYPERTENSION',
  obesity: 'OBESITY',
}

/**
 * 与健管端 PRESENT_ILLNESS_PRIMARY 对齐：C 端问卷首屏 + 健管师常用补齐。
 * （脂肪肝 NAFLD 等仍在「展开更多」里，与健管端一致）
 */
const ILLNESS_PRIMARY_CODES = [
  ...C_ILLNESS_PRIMARY.filter((c) => c !== 'NONE'),
  'THYROID_DISORDER',
  'STROKE',
] as const

/** 与健管端短标签一致，避免字典全文（如「高尿酸血症/痛风」）看起来不一致 */
const ILLNESS_SHORT_LABEL: Record<string, string> = {
  NONE: '无',
  HYPERTENSION: '高血压',
  DIABETES: '2型糖尿病',
  PREDIABETES: '糖尿病前期',
  HYPERLIPIDEMIA: '高脂血症',
  CHD: '冠心病',
  OBESITY: '肥胖症',
  GOUT: '痛风',
  THYROID_DISORDER: '甲状腺异常',
  STROKE: '脑卒中',
  NAFLD: '脂肪肝',
  HEART_FAILURE: '心衰',
  ATRIAL_FIBRILLATION: '房颤',
  CKD: '慢性肾病',
  COPD: '慢阻肺',
  ASTHMA: '哮喘',
  OSA: '呼吸暂停',
  PCOS: '多囊卵巢',
}

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const version = ref(0)
/** 完整 contentJson，保存时合并健康字段，避免冲掉生活方式等 */
const content = ref<Record<string, unknown>>({})
const illnessOptions = ref<DictItem[]>([])

const heightCm = ref<number | null>(null)
const weightKg = ref<number | null>(null)
const bloodType = ref('')
const bpSys = ref<number | null>(null)
const bpDia = ref<number | null>(null)
const glucose = ref<number | null>(null)
const glucoseMeal = ref('FASTING')

const heightInput = ref('')
const weightInput = ref('')
const bpSysInput = ref('')
const bpDiaInput = ref('')
const glucoseInput = ref('')

const presentIllness = ref<string[]>([])
const presentIllnessOther = ref('')
const illnessExpanded = ref(false)
const familyHistoryStatus = ref<'none' | 'has' | ''>('')
const familyHistoryText = ref('')
const pastHistoryStatus = ref<'none' | 'has' | ''>('')
const pastHistoryText = ref('')

/** 加载时快照，用于判断体征是否变更 */
const snap = reactive({
  height: null as number | null,
  weight: null as number | null,
  bpSys: null as number | null,
  bpDia: null as number | null,
  glucose: null as number | null,
  glucoseMeal: 'FASTING',
})

type MetricLatestRow = {
  metricType?: string
  value?: number | string | null
  mealContext?: string | null
}

const mealOptions = [
  { text: '空腹', value: 'FASTING' },
  { text: '餐后', value: 'POSTPRANDIAL' },
  { text: '随机', value: 'RANDOM' },
]

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function asStr(v: unknown): string {
  return v == null ? '' : String(v).trim()
}

function normalizeBlood(raw: string): string {
  const s = raw.replace(/型$/u, '').trim().toUpperCase()
  if ((BLOOD_OPTIONS as readonly string[]).includes(s)) return s
  return raw.replace(/型$/u, '').trim()
}

function normalizePresentIllness(codes: string[]): string[] {
  if (!codes.includes(PRESENT_ILLNESS_NONE)) return [...codes]
  const diseases = codes.filter((c) => c !== PRESENT_ILLNESS_NONE)
  return diseases.length > 0 ? diseases : [PRESENT_ILLNESS_NONE]
}

function numToInput(n: number | null | undefined, digits = 1): string {
  if (n == null || !Number.isFinite(n)) return ''
  return Number(n.toFixed(digits)).toString()
}

function parseInput(raw: string): number | null {
  const t = raw.trim()
  if (!t) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

/**
 * 病种档案已建档时，补齐「现有疾病」勾选（与健管端一致：只加不删）。
 * 避免健管端已建糖尿病/高血压档，患者端基础档案仍显示「无」。
 */
function mergePresentIllnessFromDiseaseArchives(diseaseCodes: string[]) {
  const extras = diseaseCodes
    .map((c) => DISEASE_ARCHIVE_TO_PRESENT[c])
    .filter((c): c is string => !!c)
  if (!extras.length) return
  const next = new Set(presentIllness.value.filter((c) => c !== PRESENT_ILLNESS_NONE))
  let changed = false
  for (const code of extras) {
    if (!next.has(code)) {
      next.add(code)
      changed = true
    }
  }
  if (!changed) return
  presentIllness.value = normalizePresentIllness([...next])
  presentIllnessOther.value = ''
}

function applyHistoryFromContent(c: Record<string, unknown>) {
  const rawIllness = c.presentIllness
  presentIllness.value = normalizePresentIllness(
    Array.isArray(rawIllness) ? (rawIllness as string[]) : [],
  )
  presentIllnessOther.value =
    typeof c.presentIllnessOther === 'string' && c.presentIllnessOther !== '已采集'
      ? c.presentIllnessOther
      : ''
  if (presentIllness.value.includes(PRESENT_ILLNESS_NONE)) {
    presentIllnessOther.value = ''
  }

  const statusRaw = String(c.familyHistoryStatus ?? '')
  const rawItems = c.familyHistoryItems
  if (statusRaw === 'none' || statusRaw === 'has') {
    familyHistoryStatus.value = statusRaw
  } else if (Array.isArray(rawItems) && rawItems.length) {
    familyHistoryStatus.value = 'has'
  } else if (Array.isArray(rawItems) && rawItems.length === 0) {
    familyHistoryStatus.value = 'none'
  } else if (asStr(c.familyHistory)) {
    familyHistoryStatus.value = 'has'
  } else {
    familyHistoryStatus.value = ''
  }
  familyHistoryText.value = asStr(c.familyHistory)

  const pastStatusRaw = String(c.pastHistoryStatus ?? '')
  const pastItems = asRecord(c.pastHistoryItems)
  if (pastStatusRaw === 'none' || pastStatusRaw === 'has') {
    pastHistoryStatus.value = pastStatusRaw
  } else if (Object.keys(pastItems).length && pastItems.status === 'none') {
    pastHistoryStatus.value = 'none'
  } else if (Object.keys(pastItems).length || asStr(c.pastHistory)) {
    pastHistoryStatus.value = 'has'
  } else {
    pastHistoryStatus.value = ''
  }
  pastHistoryText.value = asStr(c.pastHistory)
}

function snapshotVitals() {
  snap.height = heightCm.value
  snap.weight = weightKg.value
  snap.bpSys = bpSys.value
  snap.bpDia = bpDia.value
  snap.glucose = glucose.value
  snap.glucoseMeal = glucoseMeal.value || 'FASTING'
}

async function loadPage() {
  const [res, illness, metrics, dietGoal, diseases] = await Promise.all([
    api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      '/api/c/v1/me/archive/basic',
    ),
    loadDictOptions('presentIllness').catch(() => [] as DictItem[]),
    api<{ data: MetricLatestRow[] }>('/api/c/v1/me/metrics/latest').catch(() => null),
    api<{
      data: {
        heightCm?: number | null
        weightKg?: number | null
        goal?: { basisHeightCm?: number | null; basisWeightKg?: number | null }
      }
    }>('/api/c/v1/me/diet/goal').catch(() => null),
    api<{ data: Array<{ diseaseCode?: string }> }>('/api/c/v1/me/archive/disease').catch(
      () => null,
    ),
  ])

  version.value = res.data.version ?? 0
  content.value = (res.data.contentJson as Record<string, unknown>) || {}
  illnessOptions.value = illness
  applyHistoryFromContent(content.value)
  mergePresentIllnessFromDiseaseArchives(
    (diseases?.data || []).map((d) => d.diseaseCode || '').filter(Boolean),
  )

  const latest = new Map<string, number>()
  const latestMeta = new Map<string, MetricLatestRow>()
  for (const m of metrics?.data || []) {
    const t = (m.metricType || '').toUpperCase()
    const n = m.value == null || m.value === '' ? NaN : Number(m.value)
    if (t && Number.isFinite(n)) {
      latest.set(t, n)
      latestMeta.set(t, m)
    }
  }
  bpSys.value = latest.get('BLOOD_PRESSURE_SYS') ?? null
  bpDia.value = latest.get('BLOOD_PRESSURE_DIA') ?? null
  glucose.value = latest.get('BLOOD_GLUCOSE') ?? null
  glucoseMeal.value = (latestMeta.get('BLOOD_GLUCOSE')?.mealContext || 'FASTING').toUpperCase()

  const g = dietGoal?.data
  const pickNum = (...cands: Array<number | null | undefined>) => {
    for (const n of cands) {
      if (n != null && Number.isFinite(n) && n > 0) return n
    }
    return null
  }
  heightCm.value = pickNum(
    latest.get('HEIGHT'),
    g?.heightCm != null ? Number(g.heightCm) : null,
    g?.goal?.basisHeightCm != null ? Number(g.goal.basisHeightCm) : null,
  )
  weightKg.value = pickNum(
    latest.get('WEIGHT'),
    g?.weightKg != null ? Number(g.weightKg) : null,
    g?.goal?.basisWeightKg != null ? Number(g.goal.basisWeightKg) : null,
  )

  const rawBlood =
    asStr(content.value.bloodType) ||
    asStr(asRecord(content.value.vitals).bloodType) ||
    asStr(asRecord(content.value.basic).bloodType)
  bloodType.value = normalizeBlood(rawBlood)

  heightInput.value = numToInput(heightCm.value, 0)
  weightInput.value = numToInput(weightKg.value, 1)
  bpSysInput.value = numToInput(bpSys.value, 0)
  bpDiaInput.value = numToInput(bpDia.value, 0)
  glucoseInput.value = numToInput(glucose.value, 1)
  snapshotVitals()
}

onMounted(async () => {
  try {
    await loadPage()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

const bmiValue = computed(() => {
  const h = parseInput(heightInput.value) ?? heightCm.value
  const w = parseInput(weightInput.value) ?? weightKg.value
  return calcBmi(h, w)
})
const bmiMeta = computed(() => bmiBand(bmiValue.value))

function statusTone(flag: AbnormalFlag | null): 'ok' | 'warn' | 'muted' {
  if (!flag) return 'muted'
  return isAbnormal(flag) ? 'warn' : 'ok'
}

const bpPreview = computed(() => {
  const sys = parseInput(bpSysInput.value)
  const dia = parseInput(bpDiaInput.value)
  if (sys == null || dia == null) return { badge: '', tone: 'muted' as const }
  const flag = evaluateBloodPressure(sys, dia)
  return { badge: abnormalLabel(flag) || '正常', tone: statusTone(flag) }
})

const glucosePreview = computed(() => {
  const v = parseInput(glucoseInput.value)
  if (v == null) return { badge: '', tone: 'muted' as const }
  const flag = evaluateGlucose(v, glucoseMeal.value)
  return { badge: abnormalLabel(flag) || '正常', tone: statusTone(flag) }
})

const illnessChoices = computed(() =>
  illnessOptions.value.filter((o) => o.dictCode !== PRESENT_ILLNESS_NONE),
)
const illnessNoneSelected = computed(() => presentIllness.value.includes(PRESENT_ILLNESS_NONE))

const primaryIllnessSet = new Set<string>(ILLNESS_PRIMARY_CODES as unknown as string[])

function illnessLabel(code: string, fallback?: string) {
  return ILLNESS_SHORT_LABEL[code] || fallback || code
}

const primaryIllnessChoices = computed(() => {
  const byCode = new Map(illnessChoices.value.map((o) => [o.dictCode, o]))
  const ordered: DictItem[] = []
  for (const code of ILLNESS_PRIMARY_CODES) {
    const hit = byCode.get(code)
    if (hit) ordered.push(hit)
  }
  return ordered
})

const moreIllnessChoices = computed(() =>
  illnessChoices.value.filter((o) => !primaryIllnessSet.has(o.dictCode)),
)

/** 已选且落在「更多」里的项，收起时也要露出 */
const selectedMoreIllness = computed(() =>
  moreIllnessChoices.value.filter((o) => presentIllness.value.includes(o.dictCode)),
)

const visibleMoreIllness = computed(() =>
  illnessExpanded.value ? moreIllnessChoices.value : selectedMoreIllness.value,
)

const showIllnessExpand = computed(() => moreIllnessChoices.value.length > 0)

function toggleIllness(code: string) {
  if (code === PRESENT_ILLNESS_NONE) {
    // 点「无」：清空其它疾病；再点一次则取消「无」
    presentIllness.value = illnessNoneSelected.value ? [] : [PRESENT_ILLNESS_NONE]
    presentIllnessOther.value = ''
    return
  }
  // 选具体疾病时自动去掉「无」
  const set = new Set(presentIllness.value.filter((c) => c !== PRESENT_ILLNESS_NONE))
  if (set.has(code)) set.delete(code)
  else set.add(code)
  presentIllness.value = [...set]
}

function selectBlood(code: string) {
  bloodType.value = bloodType.value === code ? '' : code
}

function buildMergedContent(): Record<string, unknown> {
  const next: Record<string, unknown> = { ...content.value }
  const illness = normalizePresentIllness([...presentIllness.value])
  next.bloodType = bloodType.value || undefined
  next.presentIllness = illness
  next.presentIllnessOther = illness.includes(PRESENT_ILLNESS_NONE)
    ? undefined
    : presentIllnessOther.value.trim() || undefined

  const familyChosen =
    familyHistoryStatus.value === 'none' || familyHistoryStatus.value === 'has'
  next.familyHistoryStatus = familyChosen ? familyHistoryStatus.value : undefined
  if (familyHistoryStatus.value === 'none') {
    next.familyHistoryItems = []
    next.familyHistory = ''
  } else if (familyHistoryStatus.value === 'has') {
    next.familyHistory = familyHistoryText.value.trim()
    if (!Array.isArray(next.familyHistoryItems)) next.familyHistoryItems = []
  }

  const pastChosen = pastHistoryStatus.value === 'none' || pastHistoryStatus.value === 'has'
  next.pastHistoryStatus = pastChosen ? pastHistoryStatus.value : undefined
  if (pastHistoryStatus.value === 'none') {
    next.pastHistoryItems = {
      status: 'none',
      diseases: [],
      surgeries: [],
      allergies: [],
      transfusions: [],
      vaccinations: [],
    }
    next.pastHistory = ''
  } else if (pastHistoryStatus.value === 'has') {
    next.pastHistory = pastHistoryText.value.trim()
    const existing = asRecord(content.value.pastHistoryItems)
    if (Object.keys(existing).length) {
      next.pastHistoryItems = { ...existing, status: 'has' }
    } else if (next.pastHistory) {
      next.pastHistoryItems = {
        status: 'has',
        diseases: [{ name: String(next.pastHistory), year: '', note: '' }],
        surgeries: [],
        allergies: [],
        transfusions: [],
        vaccinations: [],
      }
    }
  }

  return next
}

async function postVital(body: Record<string, unknown>) {
  await api('/api/c/v1/vitals', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

async function saveChangedVitals() {
  const recordedAt = new Date().toISOString()
  const height = parseInput(heightInput.value)
  const weight = parseInput(weightInput.value)
  const sys = parseInput(bpSysInput.value)
  const dia = parseInput(bpDiaInput.value)
  const glu = parseInput(glucoseInput.value)

  if (height != null) {
    const err = vitalPlausibilityError('HEIGHT', height)
    if (err) throw new Error(err)
  }
  if (weight != null) {
    const err = vitalPlausibilityError('WEIGHT', weight)
    if (err) throw new Error(err)
  }
  if ((sys == null) !== (dia == null)) {
    throw new Error('请同时填写收缩压和舒张压')
  }
  if (sys != null && dia != null) {
    const sysErr = vitalPlausibilityError('BLOOD_PRESSURE_SYS', sys)
    if (sysErr) throw new Error(sysErr)
    const diaErr = vitalPlausibilityError('BLOOD_PRESSURE_DIA', dia)
    if (diaErr) throw new Error(diaErr)
  }
  if (glu != null) {
    const err = vitalPlausibilityError('BLOOD_GLUCOSE', glu)
    if (err) throw new Error(err)
  }

  if (height != null && height !== snap.height) {
    await postVital({ metricType: 'HEIGHT', value: height, unit: 'cm', recordedAt })
  }
  if (weight != null && weight !== snap.weight) {
    await postVital({ metricType: 'WEIGHT', value: weight, unit: 'kg', recordedAt })
  }
  if (sys != null && dia != null && (sys !== snap.bpSys || dia !== snap.bpDia)) {
    await postVital({
      metricType: 'BLOOD_PRESSURE_SYS',
      value: sys,
      unit: 'mmHg',
      bpContext: 'HOME',
      recordedAt,
    })
    await postVital({
      metricType: 'BLOOD_PRESSURE_DIA',
      value: dia,
      unit: 'mmHg',
      bpContext: 'HOME',
      recordedAt,
    })
  }
  if (glu != null && (glu !== snap.glucose || glucoseMeal.value !== snap.glucoseMeal)) {
    await postVital({
      metricType: 'BLOOD_GLUCOSE',
      value: glu,
      unit: 'mmol/L',
      mealContext: glucoseMeal.value || 'FASTING',
      recordedAt,
    })
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    await saveChangedVitals()
    const merged = buildMergedContent()
    const res = await api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      '/api/c/v1/me/archive/basic',
      {
        method: 'PUT',
        body: JSON.stringify({ version: version.value, contentJson: merged }),
      },
    )
    version.value = res.data.version ?? version.value
    content.value = (res.data.contentJson as Record<string, unknown>) || merged
    await loadPage()
    showToast({ type: 'success', message: '已保存更新' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="健康信息" left-arrow @click-left="router.back()" />
    <div class="scroll">
      <van-loading v-if="loading" vertical class="boot">加载中</van-loading>
      <template v-else>
        <section class="card" aria-label="核心身体指标">
          <div class="vitals-grid">
            <label class="vital edit">
              <span class="vital-label">身高</span>
              <div class="vital-main">
                <input v-model="heightInput" class="vital-input" inputmode="decimal" placeholder="—" />
                <small>cm</small>
              </div>
            </label>
            <label class="vital edit">
              <span class="vital-label">体重</span>
              <div class="vital-main">
                <input v-model="weightInput" class="vital-input" inputmode="decimal" placeholder="—" />
                <small>kg</small>
              </div>
            </label>
            <div class="vital">
              <span class="vital-label">BMI</span>
              <div class="vital-main">
                <b>{{ bmiValue != null ? bmiValue : '—' }}</b>
              </div>
              <span v-if="bmiMeta?.label" class="bmi-badge" :class="bmiMeta.tone">{{ bmiMeta.label }}</span>
              <span v-else class="bmi-badge spacer" aria-hidden="true" />
            </div>
            <div class="vital">
              <span class="vital-label">血型</span>
              <div class="vital-main">
                <b>{{ bloodType ? `${bloodType}型` : '未填' }}</b>
              </div>
              <span class="bmi-badge spacer" aria-hidden="true" />
            </div>
          </div>

          <div class="field blood-field">
            <p class="field-label">选择血型</p>
            <div class="capsules cols-4" role="radiogroup" aria-label="血型">
              <button
                v-for="b in BLOOD_OPTIONS"
                :key="b"
                type="button"
                class="cap"
                :class="{ on: bloodType === b }"
                @click="selectBlood(b)"
              >
                {{ b }}型
              </button>
            </div>
          </div>

          <div class="metric-edit">
            <div class="field">
              <p class="field-label">
                最近血压
                <span v-if="bpPreview.badge" class="inline-badge" :class="bpPreview.tone">{{ bpPreview.badge }}</span>
              </p>
              <div class="bp-row">
                <input v-model="bpSysInput" class="num bp-num" inputmode="numeric" placeholder="收缩压" />
                <span class="slash">/</span>
                <input v-model="bpDiaInput" class="num bp-num" inputmode="numeric" placeholder="舒张压" />
                <span class="unit">mmHg</span>
              </div>
            </div>
            <div class="field">
              <p class="field-label">
                最近血糖
                <span v-if="glucosePreview.badge" class="inline-badge" :class="glucosePreview.tone">{{ glucosePreview.badge }}</span>
              </p>
              <div class="bp-row glu-row">
                <input v-model="glucoseInput" class="num glu-num" inputmode="decimal" placeholder="血糖" />
                <span class="unit">mmol/L</span>
              </div>
              <div class="capsules cols-3 meal-caps" role="radiogroup" aria-label="血糖餐次">
                <button
                  v-for="o in mealOptions"
                  :key="o.value"
                  type="button"
                  class="cap"
                  :class="{ on: glucoseMeal === o.value }"
                  @click="glucoseMeal = o.value"
                >
                  {{ o.text }}
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="card">
          <h3>现有疾病</h3>
          <div class="capsules cols-2 wrap">
            <button
              type="button"
              class="cap"
              :class="{ on: illnessNoneSelected }"
              @click="toggleIllness(PRESENT_ILLNESS_NONE)"
            >
              无
            </button>
            <button
              v-for="o in primaryIllnessChoices"
              :key="o.dictCode"
              type="button"
              class="cap"
              :class="{ on: presentIllness.includes(o.dictCode) }"
              @click="toggleIllness(o.dictCode)"
            >
              {{ illnessLabel(o.dictCode, o.dictCodeDesc) }}
            </button>
            <button
              v-for="o in visibleMoreIllness"
              :key="o.dictCode"
              type="button"
              class="cap"
              :class="{ on: presentIllness.includes(o.dictCode) }"
              @click="toggleIllness(o.dictCode)"
            >
              {{ illnessLabel(o.dictCode, o.dictCodeDesc) }}
            </button>
          </div>
          <button
            v-if="showIllnessExpand"
            type="button"
            class="expand-btn"
            @click="illnessExpanded = !illnessExpanded"
          >
            {{ illnessExpanded ? '收起 ▴' : '展开更多常见疾病 ▾' }}
          </button>
          <textarea
            v-if="!illnessNoneSelected"
            v-model="presentIllnessOther"
            class="note"
            rows="2"
            maxlength="200"
            placeholder="其他疾病补充（选填）"
          />
        </section>

        <section class="card">
          <h3>家族史</h3>
          <div class="capsules cols-2">
            <button
              type="button"
              class="cap"
              :class="{ on: familyHistoryStatus === 'none' }"
              @click="familyHistoryStatus = familyHistoryStatus === 'none' ? '' : 'none'"
            >
              无家族史
            </button>
            <button
              type="button"
              class="cap"
              :class="{ on: familyHistoryStatus === 'has' }"
              @click="familyHistoryStatus = familyHistoryStatus === 'has' ? '' : 'has'"
            >
              有家族史
            </button>
          </div>
          <textarea
            v-if="familyHistoryStatus === 'has'"
            v-model="familyHistoryText"
            class="note"
            rows="3"
            maxlength="500"
            placeholder="如：父亲高血压、母亲糖尿病…"
          />
        </section>

        <section class="card">
          <h3>既往史</h3>
          <div class="capsules cols-2">
            <button
              type="button"
              class="cap"
              :class="{ on: pastHistoryStatus === 'none' }"
              @click="pastHistoryStatus = pastHistoryStatus === 'none' ? '' : 'none'"
            >
              无既往史
            </button>
            <button
              type="button"
              class="cap"
              :class="{ on: pastHistoryStatus === 'has' }"
              @click="pastHistoryStatus = pastHistoryStatus === 'has' ? '' : 'has'"
            >
              有既往史
            </button>
          </div>
          <textarea
            v-if="pastHistoryStatus === 'has'"
            v-model="pastHistoryText"
            class="note"
            rows="3"
            maxlength="500"
            placeholder="如：2021 年胆囊切除…"
          />
        </section>

        <p class="trust">健康档案数据已与健管师工作台实时同步</p>
        <div class="scroll-spacer" aria-hidden="true" />
      </template>
    </div>

    <div v-if="!loading" class="dock">
      <div class="dock-inner">
        <button type="button" class="save-btn" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存更新' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  height: var(--hx-app-height, 100dvh);
  min-height: var(--hx-app-height, 100dvh);
  max-height: var(--hx-app-height, 100dvh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--hx-bg);
  box-sizing: border-box;
}
.scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
.scroll-spacer { height: 100px; }
.boot { padding: 40px 0; }
.card {
  margin: 12px 16px;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  box-sizing: border-box;
}
.card h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}
.vitals-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  text-align: center;
  gap: 4px;
}
.vital {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.vital-label {
  font-size: 11px;
  color: #86909c;
  font-weight: 500;
}
.vital-main {
  height: 28px;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 2px;
}
.vital-main b {
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  line-height: 28px;
}
.vital-main small {
  font-size: 11px;
  color: #86909c;
  font-weight: 600;
}
.vital-input {
  width: 100%;
  max-width: 56px;
  border: 0;
  border-bottom: 1px solid #e5e6eb;
  background: transparent;
  text-align: center;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  font-family: inherit;
  padding: 0;
  outline: none;
}
.vital-input:focus { border-bottom-color: #1677ff; }
.bmi-badge {
  height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 650;
  line-height: 16px;
  display: inline-flex;
  align-items: center;
}
.bmi-badge.ok { background: #e8ffea; color: #00b42a; }
.bmi-badge.warn { background: #fff7e8; color: #ff7d00; }
.bmi-badge.danger { background: #ffece8; color: #f53f3f; }
.bmi-badge.spacer { visibility: hidden; padding: 0; }
.field { margin-top: 14px; }
.field-label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 650;
  color: #4e5969;
  display: flex;
  align-items: center;
  gap: 8px;
}
.blood-field { margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f4f6; }
.metric-edit { margin-top: 8px; padding-top: 8px; border-top: 1px solid #f0f4f6; }
.bp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.num {
  height: 40px;
  border: 0;
  border-radius: 10px;
  background: #f2f3f5;
  padding: 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: #1d2129;
  font-family: inherit;
  outline: none;
  box-sizing: border-box;
  min-width: 0;
}
.bp-num {
  flex: 1 1 0;
  max-width: none;
}
.glu-num {
  flex: 1 1 auto;
}
.slash {
  flex: none;
  color: #86909c;
  font-weight: 700;
}
.unit {
  flex: none;
  width: 52px;
  font-size: 13px;
  color: #86909c;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1;
}
.inline-badge {
  display: inline-flex;
  height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 650;
  align-items: center;
}
.inline-badge.ok { background: #e8ffea; color: #00b42a; }
.inline-badge.warn { background: #fff7e8; color: #ff7d00; }
.inline-badge.muted { background: #f2f3f5; color: #86909c; }
.meal-caps { margin-top: 8px; }
.capsules {
  display: grid;
  gap: 8px;
}
.capsules.cols-2 { grid-template-columns: repeat(2, 1fr); }
.capsules.cols-3 { grid-template-columns: repeat(3, 1fr); }
.capsules.cols-4 { grid-template-columns: repeat(4, 1fr); }
.capsules.wrap {
  display: flex;
  flex-wrap: wrap;
}
.capsules.wrap .cap {
  width: auto;
  min-width: calc(50% - 4px);
  flex: 1 1 calc(50% - 4px);
  white-space: normal;
  height: auto;
  min-height: 36px;
  padding: 6px 8px;
}
.cap {
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  padding: 0 8px;
  cursor: pointer;
  box-sizing: border-box;
}
.cap.on {
  border-color: #1677ff;
  background: #e8f3ff;
  color: #1677ff;
}
.expand-btn {
  display: block;
  width: 100%;
  margin: 10px 0 0;
  padding: 6px 0;
  border: 0;
  background: transparent;
  color: #86909c;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  text-align: center;
  cursor: pointer;
}
.expand-btn:active {
  color: #1677ff;
}
.note {
  width: 100%;
  margin-top: 10px;
  box-sizing: border-box;
  border: 0;
  border-radius: 12px;
  background: #f2f3f5;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: #1d2129;
  font-family: inherit;
  resize: vertical;
  min-height: 64px;
  outline: none;
}
.note::placeholder { color: #c9cdd4; }
.trust {
  margin: 0;
  font-size: 11px;
  color: #86909c;
  text-align: center;
  padding: 8px 0 16px;
}
.dock {
  flex: none;
  position: relative;
  z-index: 20;
  width: 100%;
  max-width: 430px;
  align-self: center;
  padding: 12px 16px calc(20px + env(safe-area-inset-bottom, 0px));
  background: #ffffff;
  border-top: 1px solid #f2f3f5;
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.06);
  box-sizing: border-box;
}
.dock-inner {
  width: 100%;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #14b8a6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.save-btn:disabled { opacity: 0.6; cursor: default; }
.save-btn:not(:disabled):active { transform: scale(0.98); }
</style>
