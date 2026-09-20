<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import {
  CDRS_APPLICABLE,
  CDRS_GUIDELINE,
  CDRS_HIGH_RISK_FACTORS,
  CDRS_SCORE_TABLE,
  CDRS_SUPPLEMENT_NOTE,
} from '../../shared/cdrs-rules'
import {
  HTN_APPLICABLE,
  HTN_BP_GRADE_TABLE,
  HTN_CV_HIGH_RULES,
  HTN_GUIDELINE,
  HTN_MAJOR_RISK_FACTORS,
  HTN_SUSCEPTIBLE_FACTORS,
} from '../../shared/hypertension-rules'
import {
  OBESITY_APPLICABLE,
  OBESITY_BMI_TABLE,
  OBESITY_GUIDELINE,
  OBESITY_NOTES,
  OBESITY_SEVERITY_TABLE,
  OBESITY_WAIST_TABLE,
} from '../../shared/obesity-rules'
import {
  DM_LABEL_ADVICE,
  DM_LABEL_GUIDELINE,
  DM_LABEL_PRIORITY,
  DM_LABEL_RED_RULES,
  DM_LABEL_TARGET_GROUPS,
  dmLabelTone,
} from '../../shared/diabetes-control-label-rules'

interface Guideline {
  name?: string
  version?: string
  publishedYear?: number
}

interface Item {
  code?: string
  label?: string
  inputValue?: string
  points?: number | null
  rationale?: string
}

interface AvailableEngine {
  engineCode: string
  engineLabel?: string
  kind?: string
  kindLabel?: string
  rulePackVersion?: string
  guidelineName?: string
}

interface Snapshot {
  id: string
  engineCode: string
  engineLabel?: string
  kind?: string
  kindLabel?: string
  status: string
  level?: string
  levelLabel?: string
  score?: number | null
  advice?: string
  items?: Item[]
  missingFields?: string[]
  extras?: Record<string, unknown>
  guideline?: Guideline
  rulePackVersion?: string
  assessedAt?: string
}

interface Overview {
  latest?: Snapshot[]
  availableEngines?: AvailableEngine[]
  cdrsHiddenReason?: string | null
  notices?: string[]
  disclaimer?: string
}

interface SuppHit {
  code?: string
  label?: string
  detail?: string
}

interface SuppRisk {
  title?: string
  hits?: SuppHit[]
  hitCount?: number
  unchecked?: string[]
  advice?: string
}

interface Dimension {
  code?: string
  label?: string
  value?: string
  hint?: string
}

const ENGINE_ORDER = [
  { code: 'CDRS', shortTitle: '糖尿病风险', fallbackLabel: '中国糖尿病风险评分（CDRS）' },
  { code: 'HYPERTENSION_RISK', shortTitle: '高血压风险', fallbackLabel: '高血压风险评估' },
  { code: 'OBESITY_SCREEN', shortTitle: '肥胖评估', fallbackLabel: '肥胖筛查（BMI/腰围）' },
] as const

const CONTROL_ENGINE = {
  code: 'DIABETES_CONTROL_LABEL',
  shortTitle: '血糖控制分标',
  fallbackLabel: '糖尿病血糖控制分标',
} as const

const route = useRoute()
const router = useRouter()
const peopleId = () => String(route.params.peopleId || '')

const loading = ref(false)
const running = ref(false)
const overview = ref<Overview | null>(null)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyEngine = ref('')
const historyItems = ref<Snapshot[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const expandedCode = ref<string>('')
const rulesOpen = ref<string[]>([])

const latestByEngine = computed(() => {
  const map = new Map<string, Snapshot>()
  for (const row of overview.value?.latest || []) {
    if (row.engineCode === 'CHINA_PAR') continue
    map.set(row.engineCode, row)
  }
  return map
})

const unavailableReason = computed(() => {
  const map = new Map<string, string>()
  for (const n of overview.value?.notices || []) {
    if (n.includes('糖尿病')) map.set('CDRS', n)
    if (n.includes('高血压')) map.set('HYPERTENSION_RISK', n)
    if (n.includes('肥胖')) map.set('OBESITY_SCREEN', n)
  }
  if (overview.value?.cdrsHiddenReason && !map.has('CDRS')) {
    map.set('CDRS', overview.value.cdrsHiddenReason)
  }
  return map
})

const cards = computed(() => {
  const engines = overview.value?.availableEngines || []
  return ENGINE_ORDER.map((meta) => {
    const available = engines.some((e) => e.engineCode === meta.code)
    const engineMeta = engines.find((e) => e.engineCode === meta.code)
    const latest = latestByEngine.value.get(meta.code) || null
    return {
      engineCode: meta.code,
      shortTitle: meta.shortTitle,
      engineLabel: engineMeta?.engineLabel || latest?.engineLabel || meta.fallbackLabel,
      available,
      latest,
      blockedReason: available ? '' : unavailableReason.value.get(meta.code) || '当前不适用',
    }
  })
})

const controlCard = computed(() => {
  const engines = overview.value?.availableEngines || []
  const available = engines.some((e) => e.engineCode === CONTROL_ENGINE.code)
  const engineMeta = engines.find((e) => e.engineCode === CONTROL_ENGINE.code)
  const latest = latestByEngine.value.get(CONTROL_ENGINE.code) || null
  if (!available && !latest) return null
  return {
    engineCode: CONTROL_ENGINE.code,
    shortTitle: CONTROL_ENGINE.shortTitle,
    engineLabel: engineMeta?.engineLabel || latest?.engineLabel || CONTROL_ENGINE.fallbackLabel,
    available,
    latest,
    blockedReason: available ? '' : '仅确诊糖尿病患者适用',
  }
})

const assessedCount = computed(() => {
  const riskDone = cards.value.filter((c) => c.latest && c.latest.status !== 'INCOMPLETE').length
  const controlDone =
    controlCard.value?.latest && controlCard.value.latest.status !== 'INCOMPLETE' ? 1 : 0
  return riskDone + controlDone
})

const totalCardCount = computed(
  () => cards.value.length + (controlCard.value ? 1 : 0),
)

function dimensionsOf(latest: Snapshot | null): Dimension[] {
  const raw = latest?.extras?.dimensions
  if (!Array.isArray(raw)) return []
  return raw as Dimension[]
}

function supplementalRisk(latest: Snapshot | null): SuppRisk | null {
  const raw = latest?.extras?.supplementalRisk
  if (!raw || typeof raw !== 'object') return null
  return raw as SuppRisk
}

function susceptibleDetail(latest: Snapshot | null): SuppRisk | null {
  const raw = latest?.extras?.susceptibleDetail
  if (!raw || typeof raw !== 'object') return null
  return raw as SuppRisk
}

function majorFactors(latest: Snapshot | null): SuppRisk | null {
  const raw = latest?.extras?.majorRiskFactors
  if (!raw || typeof raw !== 'object') return null
  return raw as SuppRisk
}

function scoreDisplay(code: string, latest: Snapshot | null) {
  if (latest?.score == null) return ''
  if (code === 'CDRS') return `总分 ${latest.score}`
  if (code === 'OBESITY_SCREEN') return `BMI ${latest.score}`
  if (code === 'HYPERTENSION_RISK') return `SBP ${latest.score}`
  return String(latest.score)
}

function primaryBadges(card: (typeof cards.value)[number]): string[] {
  const latest = card.latest
  if (!latest || latest.status === 'INCOMPLETE') return []
  const badges: string[] = []
  if (card.engineCode === 'CDRS') {
    if (latest.extras?.factorHighRisk === true && latest.extras?.scoreHighRisk !== true) {
      badges.push('高危因素命中')
    }
    if (latest.extras?.scoreHighRisk === true) badges.push('建议 OGTT')
  }
  if (card.engineCode === 'HYPERTENSION_RISK') {
    if (latest.extras?.cvRiskHigh === true) badges.push('心脑血管高危')
    else if (latest.extras?.susceptible === true) badges.push('易患人群')
  }
  if (card.engineCode === 'OBESITY_SCREEN') {
    if (latest.extras?.diagnosisLabel) badges.push(String(latest.extras.diagnosisLabel))
    if (latest.extras?.centralObesity === true) badges.push('中心性肥胖')
  }
  return badges
}

function levelTagType(level?: string) {
  if (!level) return 'info'
  if (level === 'RED') return 'danger'
  if (level === 'YELLOW') return 'warning'
  if (level === 'GREEN' || level === 'NEAR_GREEN') return 'success'
  if (level === 'NONE') return 'info'
  if (
    level === 'HIGH'
    || level === 'GRADE_3'
    || level === 'GRADE_2'
    || level.includes('SEVERE')
    || level === 'EXTREME_OBESITY'
  )
    return 'danger'
  if (
    level === 'MID'
    || level === 'GRADE_1'
    || level === 'PREHYPERTENSION'
    || level === 'OVERWEIGHT'
    || level === 'MILD_OBESITY'
    || level === 'MODERATE_OBESITY'
  )
    return 'warning'
  return 'success'
}

function controlMetricLine(latest: Snapshot | null) {
  const metrics = (latest?.extras?.metrics || {}) as Record<
    string,
    { value?: number | string | null; presentInWindow?: boolean }
  >
  const targets = (latest?.extras?.targets || {}) as Record<string, number | string>
  const rows: string[] = []
  const a = metrics.a1c
  const b = metrics.fbg
  const c = metrics.pbg
  if (a?.presentInWindow && a.value != null) rows.push(`A1c ${a.value}% / <${targets.a1c ?? '—'}%`)
  if (b?.presentInWindow && b.value != null) rows.push(`空腹 ${b.value} / <${targets.fbg ?? '—'}`)
  if (c?.presentInWindow && c.value != null) rows.push(`非空腹 ${c.value} / <${targets.pbg ?? '—'}`)
  return rows
}

function statusLabel(status?: string) {
  if (status === 'COMPLETE') return '完整'
  if (status === 'INCOMPLETE') return '缺项'
  return status || '-'
}

function formatTime(v?: string) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

function formatValue(v?: string | number | null) {
  if (v == null || v === '') return '—'
  return String(v).replace(/(\d+\.\d+)/g, (num) => {
    const n = Number(num)
    if (Number.isNaN(n)) return num
    return String(parseFloat(n.toFixed(2)))
  })
}

function missingLabel(code: string) {
  const map: Record<string, string> = {
    GENDER: '性别',
    BIRTHDAY: '生日',
    HEIGHT_WEIGHT: '身高/体重',
    WAIST: '腰围',
    BLOOD_PRESSURE_SYS: '收缩压',
    BLOOD_PRESSURE_DIA: '舒张压',
    FAMILY_HISTORY: '家族史',
  }
  return map[code] || code
}

function goFill(field: string) {
  const base = `/workspace/patients/${peopleId()}`
  if (
    field === 'GENDER'
    || field === 'BIRTHDAY'
    || field === 'FAMILY_HISTORY'
    || field === 'SMOKE'
    || field === 'FH_ASCVD'
  ) {
    router.push(`${base}/archive`)
    return
  }
  if (field === 'TC' || field === 'HDL') {
    router.push(`${base}/observations/labs`)
    return
  }
  router.push(`${base}/observations/metrics`)
}

function toggleExpand(code: string) {
  expandedCode.value = expandedCode.value === code ? '' : code
  rulesOpen.value = []
}

async function loadOverview() {
  if (!peopleId()) return
  loading.value = true
  try {
    const res = await api<{ data: Overview }>(`/api/b/v1/patients/${peopleId()}/assessments`)
    overview.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function runAll() {
  running.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId()}/assessments/run`, {
      method: 'POST',
      body: JSON.stringify({}),
    })
    ElMessage.success('评估完成')
    await loadOverview()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评估失败')
  } finally {
    running.value = false
  }
}

async function runOne(engineCode: string) {
  running.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId()}/assessments/run`, {
      method: 'POST',
      body: JSON.stringify({ engineCode }),
    })
    ElMessage.success('评估完成')
    await loadOverview()
    expandedCode.value = engineCode
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评估失败')
  } finally {
    running.value = false
  }
}

async function openHistory(engineCode?: string) {
  historyEngine.value = engineCode || ''
  historyPage.value = 1
  historyOpen.value = true
  await loadHistory()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const q = new URLSearchParams({
      page: String(historyPage.value),
      size: '20',
    })
    if (historyEngine.value) q.set('engineCode', historyEngine.value)
    const res = await api<{ data: { items: Snapshot[]; total: number } }>(
      `/api/b/v1/patients/${peopleId()}/assessments/history?${q}`,
    )
    historyItems.value = (res.data?.items || []).filter((h) => h.engineCode !== 'CHINA_PAR')
    historyTotal.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载历史失败')
  } finally {
    historyLoading.value = false
  }
}

onMounted(loadOverview)
</script>

<template>
  <div class="panel" v-loading="loading">
    <div class="head">
      <div>
        <h3>疾病评估</h3>
        <p class="hint">
          已确诊糖尿病看<strong>血糖控制分标</strong>；未确诊看糖尿病 / 高血压 / 肥胖
          <strong>发病风险</strong>。结论供健管参考，不构成诊断。
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="openHistory()">历史</el-button>
        <el-button type="primary" :loading="running" @click="runAll">立即评估</el-button>
      </div>
    </div>

    <el-alert
      v-for="(n, i) in overview?.notices || []"
      :key="'n-' + i"
      class="info-alert"
      type="info"
      :closable="false"
      :title="n"
      show-icon
    />

    <!-- 控制分标（确诊糖尿病） -->
    <section v-if="controlCard" class="summary control-summary">
      <div class="summary-head">
        <strong>血糖控制分标</strong>
        <span class="summary-meta">与发病风险评分相互独立</span>
      </div>
      <button
        type="button"
        class="summary-card control-card"
        :class="[
          dmLabelTone(controlCard.latest?.level),
          { active: expandedCode === controlCard.engineCode },
        ]"
        @click="toggleExpand(controlCard.engineCode)"
      >
        <div class="summary-top">
          <span class="summary-title">{{ controlCard.shortTitle }}</span>
          <el-tag
            v-if="controlCard.latest"
            size="small"
            effect="dark"
            :type="levelTagType(controlCard.latest.level)"
          >
            {{ controlCard.latest.levelLabel || controlCard.latest.level }}
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain">未评估</el-tag>
        </div>
        <div v-if="controlCard.latest" class="summary-main">
          <p class="summary-advice">
            {{ controlCard.latest.advice || DM_LABEL_ADVICE[controlCard.latest.level || ''] || '—' }}
          </p>
          <div v-if="controlMetricLine(controlCard.latest).length" class="summary-dims">
            <span v-for="line in controlMetricLine(controlCard.latest)" :key="line">{{ line }}</span>
          </div>
        </div>
        <div v-else class="summary-main muted">点击「评估」生成控制分标</div>
        <div class="summary-foot">
          <span>{{ controlCard.latest?.assessedAt ? formatTime(controlCard.latest.assessedAt) : '—' }}</span>
          <span>{{ expandedCode === controlCard.engineCode ? '收起明细' : '查看明细' }}</span>
        </div>
      </button>
    </section>

    <!-- 一眼总览 -->
    <section class="summary">
      <div class="summary-head">
        <strong>发病风险评估</strong>
        <span class="summary-meta">
          已完成 {{ assessedCount }} / {{ totalCardCount }}
        </span>
      </div>
      <div class="summary-grid">
        <button
          v-for="card in cards"
          :key="card.engineCode"
          type="button"
          class="summary-card"
          :class="{
            active: expandedCode === card.engineCode,
            blocked: !card.available && !card.latest,
            incomplete: card.latest?.status === 'INCOMPLETE',
            danger: levelTagType(card.latest?.level) === 'danger',
            warn: levelTagType(card.latest?.level) === 'warning',
          }"
          @click="toggleExpand(card.engineCode)"
        >
          <div class="summary-top">
            <span class="summary-title">{{ card.shortTitle }}</span>
            <el-tag
              v-if="card.latest"
              size="small"
              effect="dark"
              :type="card.latest.status === 'INCOMPLETE' ? 'info' : levelTagType(card.latest.level)"
            >
              {{
                card.latest.status === 'INCOMPLETE'
                  ? '缺项'
                  : card.latest.levelLabel || card.latest.level
              }}
            </el-tag>
            <el-tag v-else-if="!card.available" size="small" type="info" effect="plain">不适用</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">未评估</el-tag>
          </div>

          <div v-if="card.latest && card.latest.status !== 'INCOMPLETE'" class="summary-main">
            <span v-if="scoreDisplay(card.engineCode, card.latest)" class="summary-score">
              {{ scoreDisplay(card.engineCode, card.latest) }}
            </span>
            <div v-if="dimensionsOf(card.latest).length" class="summary-dims">
              <span v-for="d in dimensionsOf(card.latest).slice(0, 3)" :key="d.code || d.label">
                {{ d.label }} · <b>{{ d.value }}</b>
              </span>
            </div>
            <div v-else-if="primaryBadges(card).length" class="summary-badges">
              <span v-for="b in primaryBadges(card)" :key="b">{{ b }}</span>
            </div>
            <p v-if="card.latest.advice" class="summary-advice">{{ card.latest.advice }}</p>
          </div>

          <div v-else-if="card.latest?.status === 'INCOMPLETE'" class="summary-main muted">
            缺项：{{ (card.latest.missingFields || []).map(missingLabel).join('、') || '请补齐数据' }}
          </div>
          <div v-else-if="!card.available" class="summary-main muted">{{ card.blockedReason }}</div>
          <div v-else class="summary-main muted">点击下方「评估」或顶部「立即评估」生成结果</div>

          <div class="summary-foot">
            <span>{{ card.latest?.assessedAt ? formatTime(card.latest.assessedAt) : '—' }}</span>
            <span>{{ expandedCode === card.engineCode ? '收起明细' : '查看明细' }}</span>
          </div>
        </button>
      </div>
    </section>

    <!-- 明细（仅展开一项） -->
    <section v-if="expandedCode" class="detail-panel">
      <template v-if="controlCard && expandedCode === controlCard.engineCode">
        <div class="detail-inner">
          <div class="detail-head">
            <div>
              <h4>{{ controlCard.engineLabel }}</h4>
              <p class="muted">
                {{ controlCard.latest?.rulePackVersion || DM_LABEL_GUIDELINE }}
                <template v-if="controlCard.latest?.assessedAt">
                  · 评估于 {{ formatTime(controlCard.latest.assessedAt) }}
                </template>
              </p>
            </div>
            <div class="detail-actions">
              <el-button text type="primary" @click="openHistory(controlCard.engineCode)">历史</el-button>
              <el-button
                size="small"
                type="primary"
                :disabled="!controlCard.available"
                :loading="running"
                @click="runOne(controlCard.engineCode)"
              >
                {{ controlCard.latest ? '重新评估' : '评估' }}
              </el-button>
            </div>
          </div>

          <template v-if="controlCard.latest">
            <div class="block">
              <div class="block-title">判定明细</div>
              <el-table :data="controlCard.latest.items || []" size="small" stripe>
                <el-table-column prop="label" label="分项" min-width="110" />
                <el-table-column label="取值" min-width="140">
                  <template #default="{ row }">{{ formatValue(row.inputValue) }}</template>
                </el-table-column>
                <el-table-column prop="rationale" label="依据" min-width="180" show-overflow-tooltip />
              </el-table>
            </div>
            <p class="block-text">{{ controlCard.latest.advice }}</p>
          </template>

          <el-collapse v-model="rulesOpen" class="rule-collapse">
            <el-collapse-item title="查看血糖控制分标规则" name="dm-label">
              <p class="rule-lead">{{ DM_LABEL_GUIDELINE }}</p>
              <p class="rule-note">{{ DM_LABEL_PRIORITY }}</p>
              <p class="rule-note"><strong>红标（命中任一）</strong></p>
              <ul class="rule-factors">
                <li v-for="r in DM_LABEL_RED_RULES" :key="r">{{ r }}</li>
              </ul>
              <p class="rule-note"><strong>目标值分组</strong></p>
              <table class="rule-table">
                <thead>
                  <tr>
                    <th>分组</th>
                    <th>A1c</th>
                    <th>空腹</th>
                    <th>非空腹</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="g in DM_LABEL_TARGET_GROUPS" :key="g.code">
                    <td>{{ g.title }}</td>
                    <td>{{ g.a1c }}</td>
                    <td>{{ g.fbg }}</td>
                    <td>{{ g.pbg }}</td>
                  </tr>
                </tbody>
              </table>
            </el-collapse-item>
          </el-collapse>
        </div>
      </template>

      <template v-for="card in cards" :key="'d-' + card.engineCode">
        <div v-if="expandedCode === card.engineCode" class="detail-inner">
          <div class="detail-head">
            <div>
              <h4>{{ card.engineLabel }}</h4>
              <p class="muted">
                {{ card.latest?.rulePackVersion || '—' }}
                <template v-if="card.latest?.assessedAt">
                  · 评估于 {{ formatTime(card.latest.assessedAt) }}
                </template>
              </p>
            </div>
            <div class="detail-actions">
              <el-button text type="primary" @click="openHistory(card.engineCode)">历史</el-button>
              <el-button
                size="small"
                type="primary"
                :disabled="!card.available"
                :loading="running"
                @click="runOne(card.engineCode)"
              >
                {{ card.latest ? '重新评估' : '评估' }}
              </el-button>
            </div>
          </div>

          <template v-if="card.latest">
            <div
              v-if="card.latest.status === 'INCOMPLETE' && card.latest.missingFields?.length"
              class="missing"
            >
              <span class="missing-title">缺项</span>
              <el-button
                v-for="f in card.latest.missingFields"
                :key="f"
                size="small"
                @click="goFill(f)"
              >
                去补录 · {{ missingLabel(f) }}
              </el-button>
            </div>

            <div v-if="card.latest.items?.length" class="block">
              <div class="block-title">
                {{
                  card.engineCode === 'CDRS' ? '算分明细' : '判定明细'
                }}
              </div>
              <el-table :data="card.latest.items" size="small" stripe>
                <el-table-column prop="label" label="分项" min-width="110" />
                <el-table-column label="取值" min-width="140">
                  <template #default="{ row }">{{ formatValue(row.inputValue) }}</template>
                </el-table-column>
                <el-table-column prop="points" label="得分" width="72" align="center">
                  <template #default="{ row }">{{ row.points ?? '—' }}</template>
                </el-table-column>
                <el-table-column prop="rationale" label="依据" min-width="160" show-overflow-tooltip />
              </el-table>
            </div>

            <div
              v-if="card.engineCode === 'CDRS' && supplementalRisk(card.latest)"
              class="block"
            >
              <div class="block-title">高危人群定义命中</div>
              <p class="block-text">{{ supplementalRisk(card.latest)?.advice }}</p>
              <ul v-if="supplementalRisk(card.latest)?.hits?.length" class="hit-list">
                <li v-for="h in supplementalRisk(card.latest)!.hits" :key="h.code">
                  <strong>{{ h.label }}</strong>
                  <span v-if="h.detail"> · {{ h.detail }}</span>
                </li>
              </ul>
            </div>

            <div
              v-if="card.engineCode === 'HYPERTENSION_RISK' && susceptibleDetail(card.latest)"
              class="block"
            >
              <div class="block-title">易患因素</div>
              <p class="block-text">{{ susceptibleDetail(card.latest)?.advice }}</p>
              <ul v-if="susceptibleDetail(card.latest)?.hits?.length" class="hit-list">
                <li v-for="h in susceptibleDetail(card.latest)!.hits" :key="h.code">
                  <strong>{{ h.label }}</strong>
                  <span v-if="h.detail"> · {{ h.detail }}</span>
                </li>
              </ul>
            </div>

            <div
              v-if="card.engineCode === 'HYPERTENSION_RISK' && majorFactors(card.latest)"
              class="block"
            >
              <div class="block-title">
                主要危险因素（{{ majorFactors(card.latest)?.hitCount ?? 0 }}）
              </div>
              <ul v-if="majorFactors(card.latest)?.hits?.length" class="hit-list">
                <li v-for="h in majorFactors(card.latest)!.hits" :key="h.code">
                  <strong>{{ h.label }}</strong>
                  <span v-if="h.detail"> · {{ h.detail }}</span>
                </li>
              </ul>
              <p v-else class="block-text">暂未命中</p>
            </div>

            <el-collapse v-model="rulesOpen" class="rule-collapse">
              <el-collapse-item
                v-if="card.engineCode === 'CDRS'"
                title="查看 CDRS 评分规则"
                name="cdrs"
              >
                <p class="rule-lead">{{ CDRS_GUIDELINE }} · {{ CDRS_APPLICABLE }}</p>
                <div class="rule-tables">
                  <div v-for="block in CDRS_SCORE_TABLE" :key="block.indicator" class="rule-block">
                    <div class="rule-indicator">{{ block.indicator }}</div>
                    <table class="rule-table">
                      <thead>
                        <tr>
                          <th>分段</th>
                          <th>分值</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="row in block.rows" :key="row.band">
                          <td>{{ row.band }}</td>
                          <td class="pts">{{ row.points }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>
                <p class="rule-note">{{ CDRS_SUPPLEMENT_NOTE }}</p>
                <ul class="rule-factors">
                  <li v-for="f in CDRS_HIGH_RISK_FACTORS" :key="f">{{ f }}</li>
                </ul>
              </el-collapse-item>

              <el-collapse-item
                v-if="card.engineCode === 'HYPERTENSION_RISK'"
                title="查看高血压评估规则"
                name="htn"
              >
                <p class="rule-lead">{{ HTN_GUIDELINE }} · {{ HTN_APPLICABLE }}</p>
                <table class="rule-table">
                  <thead>
                    <tr>
                      <th>分级</th>
                      <th>收缩压</th>
                      <th>舒张压</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in HTN_BP_GRADE_TABLE" :key="row.grade">
                      <td>{{ row.grade }}</td>
                      <td>{{ row.sbp }}</td>
                      <td>{{ row.dbp }}</td>
                    </tr>
                  </tbody>
                </table>
                <p class="rule-note"><strong>易患：</strong>命中任一因素</p>
                <ul class="rule-factors">
                  <li v-for="f in HTN_SUSCEPTIBLE_FACTORS" :key="f">{{ f }}</li>
                </ul>
                <p class="rule-note"><strong>心脑血管高危：</strong></p>
                <ul class="rule-factors">
                  <li v-for="f in HTN_CV_HIGH_RULES" :key="f">{{ f }}</li>
                </ul>
                <ul class="rule-factors">
                  <li v-for="f in HTN_MAJOR_RISK_FACTORS" :key="f">{{ f }}</li>
                </ul>
              </el-collapse-item>

              <el-collapse-item
                v-if="card.engineCode === 'OBESITY_SCREEN'"
                title="查看肥胖评估规则"
                name="obesity"
              >
                <p class="rule-lead">{{ OBESITY_GUIDELINE }} · {{ OBESITY_APPLICABLE }}</p>
                <table class="rule-table">
                  <thead>
                    <tr>
                      <th>分类</th>
                      <th>BMI</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in OBESITY_BMI_TABLE" :key="row.category">
                      <td>{{ row.category }}</td>
                      <td>{{ row.range }}</td>
                    </tr>
                  </tbody>
                </table>
                <table class="rule-table">
                  <thead>
                    <tr>
                      <th>严重度</th>
                      <th>BMI</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in OBESITY_SEVERITY_TABLE" :key="row.category">
                      <td>{{ row.category }}</td>
                      <td>{{ row.range }}</td>
                    </tr>
                  </tbody>
                </table>
                <table class="rule-table">
                  <thead>
                    <tr>
                      <th>腰围</th>
                      <th>男</th>
                      <th>女</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in OBESITY_WAIST_TABLE" :key="row.item">
                      <td>{{ row.item }}</td>
                      <td>{{ row.male }}</td>
                      <td>{{ row.female }}</td>
                    </tr>
                  </tbody>
                </table>
                <ul class="rule-factors">
                  <li v-for="n in OBESITY_NOTES" :key="n">{{ n }}</li>
                </ul>
              </el-collapse-item>
            </el-collapse>
          </template>

          <div v-else class="empty-detail">
            <p v-if="!card.available">{{ card.blockedReason }}</p>
            <p v-else>尚未评估，点击右上角「评估」生成结果。</p>
          </div>
        </div>
      </template>
    </section>

    <p class="disclaimer">
      {{
        overview?.disclaimer
          || '本模块结论基于公开临床指南规则生成，属于健康管理辅助信息，不构成诊断或治疗建议，不替代执业医师判断。'
      }}
    </p>

    <el-drawer v-model="historyOpen" title="评估历史" size="480px">
      <div class="hist-toolbar">
        <el-select
          v-model="historyEngine"
          clearable
          placeholder="全部引擎"
          style="width: 220px"
          @change="
            () => {
              historyPage = 1
              loadHistory()
            }
          "
        >
          <el-option label="糖尿病 CDRS" value="CDRS" />
          <el-option label="血糖控制分标" value="DIABETES_CONTROL_LABEL" />
          <el-option label="高血压" value="HYPERTENSION_RISK" />
          <el-option label="肥胖" value="OBESITY_SCREEN" />
        </el-select>
      </div>
      <div v-loading="historyLoading" class="hist-list">
        <div v-for="h in historyItems" :key="h.id" class="hist-item">
          <div class="hist-top">
            <strong>{{ h.engineLabel || h.engineCode }}</strong>
            <el-tag size="small" :type="h.status === 'INCOMPLETE' ? 'info' : levelTagType(h.level)">
              {{ h.status === 'INCOMPLETE' ? '缺项' : h.levelLabel || h.level || '-' }}
            </el-tag>
          </div>
          <div class="hist-meta">
            {{ formatTime(h.assessedAt) }}
            <span v-if="h.score != null"> · {{ h.score }}</span>
          </div>
          <p v-if="h.advice" class="hist-advice">{{ h.advice }}</p>
        </div>
        <el-empty v-if="!historyLoading && !historyItems.length" description="暂无历史" />
      </div>
      <div v-if="historyTotal > 20" class="hist-pager">
        <el-pagination
          v-model:current-page="historyPage"
          layout="prev, pager, next"
          :page-size="20"
          :total="historyTotal"
          @current-change="loadHistory"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.panel {
  padding: 8px 0 24px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}
.head h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-secondary);
  max-width: 720px;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.info-alert {
  margin-bottom: 10px;
}
.summary {
  margin-bottom: 14px;
}
.summary-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.summary-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.summary-card {
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  background: #fff;
  border-radius: 12px;
  padding: 14px 14px 10px;
  cursor: pointer;
  min-height: 168px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.summary-card:hover {
  border-color: var(--el-color-primary-light-5);
}
.summary-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
}
.summary-card.danger {
  background: linear-gradient(180deg, #fff5f5 0%, #fff 48%);
}
.summary-card.warn {
  background: linear-gradient(180deg, #fffbeb 0%, #fff 48%);
}
.control-summary .summary-grid {
  grid-template-columns: 1fr;
}
.control-card {
  width: 100%;
  min-height: 140px;
}
.control-card.red {
  background: linear-gradient(180deg, #fff1f2 0%, #fff 55%);
  border-color: #fecaca;
}
.control-card.yellow {
  background: linear-gradient(180deg, #fffbeb 0%, #fff 55%);
  border-color: #fde68a;
}
.control-card.green {
  background: linear-gradient(180deg, #ecfdf5 0%, #fff 55%);
  border-color: #a7f3d0;
}
.control-card.near {
  background: linear-gradient(180deg, #f0fdf4 0%, #fff 55%);
  border-color: #bbf7d0;
}
.control-card.none {
  background: linear-gradient(180deg, #f8fafc 0%, #fff 55%);
}
.summary-card.blocked {
  opacity: 0.85;
}
.summary-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.summary-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-right: auto;
}
.summary-main {
  flex: 1;
  min-height: 0;
}
.summary-score {
  display: inline-block;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin-bottom: 6px;
}
.summary-dims,
.summary-badges {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.summary-dims b {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.summary-badges span {
  display: inline-block;
  width: fit-content;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--el-fill-color);
  font-size: 11px;
}
.summary-advice {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.summary-foot {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  border-top: 1px dashed var(--el-border-color-extra-light);
  padding-top: 8px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}
.detail-panel {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 16px 18px 12px;
  margin-bottom: 14px;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.detail-head h4 {
  margin: 0 0 4px;
  font-size: 15px;
}
.detail-actions {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.missing {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.missing-title {
  font-size: 13px;
  font-weight: 600;
}
.block {
  margin-bottom: 14px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.block-text {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.hit-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
}
.rule-collapse {
  margin-top: 4px;
}
.rule-lead,
.rule-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.rule-tables {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
  margin: 8px 0;
}
.rule-indicator {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
}
.rule-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  margin-bottom: 10px;
}
.rule-table th,
.rule-table td {
  border-top: 1px solid var(--el-border-color-extra-light);
  padding: 4px 8px;
  text-align: left;
}
.rule-table .pts {
  font-weight: 600;
  width: 40px;
}
.rule-factors {
  margin: 0 0 8px;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.55;
}
.empty-detail {
  padding: 12px 0;
}
.disclaimer {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.hist-toolbar {
  margin-bottom: 12px;
}
.hist-item {
  border-bottom: 1px solid var(--el-border-color-extra-light);
  padding: 10px 0;
}
.hist-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.hist-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hist-advice {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.hist-pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
@media (max-width: 960px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .head {
    flex-direction: column;
  }
}
</style>
