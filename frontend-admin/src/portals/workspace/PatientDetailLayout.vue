<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { confirmLeaveBasicArchive } from '../../shared/basic-archive-dirty'
import { setArchiveCompletenessRefresh } from '../../shared/archive-completeness-refresh'
import PatientBasicInfoEditor, {
  type PatientBasicInfo,
} from '../../shared/PatientBasicInfoEditor.vue'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamName?: string | null
  clientLinked?: boolean
  clientCardCount?: number
  mobile?: string | null
  address?: string | null
  educationLevel?: string | null
  maritalStatus?: string | null
  occupation?: string | null
  identityMask?: string | null
}

interface InviteView {
  id: string
  code: string
  expireAt?: string
  used: boolean
  expired: boolean
  active: boolean
}

interface ArchiveCompleteness {
  peopleId: string
  filledCount: number
  totalCount: number
  percent: number
  basicFilledCount: number
  basicTotalCount: number
}

interface AdherenceSummary {
  peopleId: string
  date: string
  windowDays: number
  percent: number | null
  rate?: number | null
  hasActivePlan: boolean
  streakDays: number
  riskLevel?: string
  medPercent?: number | null
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const patient = ref<OrgPatientListItem | null>(null)
const latestInvite = ref<InviteView | null>(null)
const completeness = ref<ArchiveCompleteness | null>(null)
const adherence = ref<AdherenceSummary | null>(null)
const issuing = ref(false)
const basicInfoEditorRef = ref<{ openEdit: () => void } | null>(null)
const revealMobile = ref(false)

const activeTab = computed(() => {
  const path = route.path
  if (path.includes('/medications')) return 'medications'
  if (path.includes('/care-plan')) return 'care-plan'
  if (path.includes('/observations')) return 'observations'
  if (path.includes('/revisions')) return 'revisions'
  if (path.includes('/adherence')) return 'adherence'
  if (path.includes('/followups')) return 'followups'
  if (path.includes('/health-reports')) return 'health-reports'
  if (path.includes('/assessments')) return 'assessments'
  if (path.includes('/chat')) return 'chat'
  return 'archive'
})

const patientAge = computed(() => {
  const birthday = patient.value?.birthday
  const birth = parseBirthdayDate(birthday)
  if (!birth) return '-'
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age -= 1
  return age >= 0 ? `${age} 岁` : '-'
})

const nameInitial = computed(() => {
  const name = patient.value?.displayName?.trim()
  return name ? name.slice(0, 1) : '患'
})

const maskedMobile = computed(() => maskMobile(patient.value?.mobile))

const mobileDisplay = computed(() => {
  const raw = patient.value?.mobile?.trim()
  if (!raw) return ''
  return revealMobile.value ? raw : maskedMobile.value
})

function parseBirthdayDate(birthday: unknown): Date | null {
  if (birthday == null || birthday === '') return null
  if (Array.isArray(birthday) && birthday.length >= 3) {
    const y = Number(birthday[0])
    const m = Number(birthday[1])
    const d = Number(birthday[2])
    if (!y || !m || !d) return null
    const birth = new Date(y, m - 1, d)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  if (typeof birthday === 'string' || typeof birthday === 'number') {
    const birth = new Date(birthday)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  return null
}

function maskMobile(mobile: string | null | undefined) {
  const raw = (mobile || '').replace(/\s+/g, '')
  if (!raw) return ''
  if (raw.length < 7) return '*'.repeat(raw.length)
  return `${raw.slice(0, 3)}****${raw.slice(-4)}`
}

const cLinkLabel = computed(() => {
  if (!patient.value) return ''
  if (patient.value.clientLinked) return `C 端已激活`
  return 'C 端未关联'
})

const completenessPercent = computed(() => completeness.value?.percent ?? 0)

const completenessTone = computed(() => {
  const p = completenessPercent.value
  if (p >= 80) return 'good'
  if (p >= 50) return 'fair'
  return 'poor'
})

const adherencePercent = computed(() => adherence.value?.percent ?? null)

const adherenceTone = computed(() => {
  const p = adherencePercent.value
  if (p == null) return 'empty'
  if (p >= 80) return 'good'
  if (p >= 60) return 'fair'
  return 'poor'
})

const adherenceDisplay = computed(() => {
  if (adherencePercent.value != null) return String(adherencePercent.value)
  return '—'
})

const adherenceHint = computed(() => {
  if (!adherence.value) return '近7日方案'
  if (!adherence.value.hasActivePlan) return '暂无执行中方案'
  if (adherence.value.percent == null) return '近7日无应打任务'
  if (adherence.value.streakDays > 0) return `连续未完成 ${adherence.value.streakDays} 天`
  return '近7日方案完成率'
})

const completenessHint = computed(() => {
  if (!completeness.value) return '档案字段'
  return `已填 ${completeness.value.filledCount}/${completeness.value.totalCount}`
})

const heroMeta = computed(() => {
  const parts: string[] = []
  if (patientAge.value && patientAge.value !== '-') parts.push(patientAge.value)
  const gender = formatGender(patient.value?.gender)
  if (gender && gender !== '-') parts.push(gender)
  if (patient.value?.identityMask) parts.push(`身份证 ${patient.value.identityMask}`)
  if (patient.value?.mobile) parts.push(`联系电话 ${mobileDisplay.value}`)
  return parts.join(' · ')
})

async function loadPatient() {
  if (!peopleId.value) return
  revealMobile.value = false
  try {
    const res = await api<{ data: OrgPatientListItem }>(`/api/b/v1/patients/${peopleId.value}`)
    patient.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载患者失败')
  }
}

async function loadCompleteness() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: ArchiveCompleteness }>(
      `/api/b/v1/patients/${peopleId.value}/archive/completeness`,
    )
    completeness.value = res.data
  } catch {
    completeness.value = null
  }
}

async function loadAdherenceSummary() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: AdherenceSummary }>(
      `/api/b/v1/patients/${peopleId.value}/adherence/summary`,
    )
    adherence.value = res.data
  } catch {
    adherence.value = null
  }
}

async function loadInvites() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: InviteView[] }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
    )
    latestInvite.value = (res.data || []).find((i) => i.active) || res.data?.[0] || null
  } catch {
    latestInvite.value = null
  }
}

async function issueCode() {
  issuing.value = true
  try {
    const res = await api<{ data: InviteView }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
      { method: 'POST', body: JSON.stringify({ validDays: 7 }) },
    )
    latestInvite.value = res.data
    try {
      await navigator.clipboard.writeText(res.data.code)
      ElMessage.success('已生成并复制激活码')
    } catch {
      ElMessage.success(`已生成激活码：${res.data.code}`)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发码失败')
  } finally {
    issuing.value = false
  }
}

async function copyCode() {
  if (!latestInvite.value?.code) return
  try {
    await navigator.clipboard.writeText(latestInvite.value.code)
    ElMessage.success('已复制激活码')
  } catch {
    ElMessage.warning(latestInvite.value.code)
  }
}

function openBasicInfoEdit() {
  basicInfoEditorRef.value?.openEdit()
}

function onBasicInfoUpdated(info: PatientBasicInfo) {
  if (!patient.value) return
  revealMobile.value = false
  patient.value = {
    ...patient.value,
    displayName: info.displayName,
    mobile: info.mobile,
    address: info.address,
    educationLevel: info.educationLevel,
    maritalStatus: info.maritalStatus,
    occupation: info.occupation,
  }
}

async function onPatientTabBeforeLeave(newName: string | number, oldName: string | number) {
  if (String(oldName) !== 'archive' || String(newName) === 'archive') return true
  return confirmLeaveBasicArchive()
}

function switchTab(name: string | number) {
  const base = `/workspace/patients/${peopleId.value}`
  const query = route.query
  if (name === 'medications') {
    router.push({ path: `${base}/medications`, query })
  } else if (name === 'care-plan') {
    router.push({ path: `${base}/care-plan`, query })
  } else if (name === 'observations') {
    router.push({ path: `${base}/observations/metrics`, query })
  } else if (name === 'revisions') {
    router.push({ path: `${base}/revisions`, query })
  } else if (name === 'adherence') {
    router.push({ path: `${base}/adherence`, query })
  } else if (name === 'followups') {
    router.push({ path: `${base}/followups`, query })
  } else if (name === 'health-reports') {
    router.push({ path: `${base}/health-reports`, query })
  } else if (name === 'assessments') {
    router.push({ path: `${base}/assessments`, query })
  } else if (name === 'chat') {
    router.push({ path: `${base}/chat`, query })
  } else {
    router.push({ path: `${base}/archive`, query })
  }
}

watch(
  peopleId,
  async () => {
    await loadPatient()
    await loadInvites()
    await Promise.all([loadCompleteness(), loadAdherenceSummary()])
  },
  { immediate: false },
)

onMounted(async () => {
  setArchiveCompletenessRefresh(() => {
    void loadCompleteness()
  })
  await loadPatient()
  await loadInvites()
  await Promise.all([loadCompleteness(), loadAdherenceSummary()])
})

onBeforeUnmount(() => {
  setArchiveCompletenessRefresh(null)
})
</script>

<template>
  <div class="patient-detail">
    <nav class="crumbs">
      <button type="button" class="crumb-link" @click="router.push('/workspace/patients')">
        患者管理
      </button>
      <svg class="crumb-sep" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <polyline points="9 18 15 12 9 6" />
      </svg>
      <strong>{{ patient?.displayName || '患者' }}</strong>
    </nav>

    <section class="patient-hero">
      <div class="hero-row">
        <div class="hero-avatar" aria-hidden="true">{{ nameInitial }}</div>

        <div class="hero-info">
          <h1 class="hero-name">
            {{ patient?.displayName || '患者' }}
            <button type="button" class="text-btn" @click="openBasicInfoEdit">编辑资料</button>
          </h1>

          <div v-if="heroMeta" class="hero-meta">
            {{ heroMeta }}
            <button
              v-if="patient?.mobile"
              type="button"
              class="text-btn sm"
              @click="revealMobile = !revealMobile"
            >
              {{ revealMobile ? '隐藏' : '显示' }}
            </button>
          </div>

          <div class="hero-tags">
            <span v-if="patient?.careTeamName" class="tag brand">
              <span class="dot" aria-hidden="true" />
              {{ patient.careTeamName }}
            </span>
            <span class="tag" :class="patient?.clientLinked ? 'teal' : 'muted'">
              <span class="dot" aria-hidden="true" />
              {{ cLinkLabel }}
            </span>
            <span
              v-if="completeness"
              class="tag"
              :class="completenessTone === 'good' ? 'teal' : completenessTone === 'fair' ? 'amber' : 'rose'"
            >
              档案完整度 {{ completenessPercent }}%
            </span>
          </div>
        </div>

        <div class="hero-actions">
          <button type="button" class="btn ghost" :disabled="issuing" @click="issueCode">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="2" />
              <line x1="9" y1="9" x2="15" y2="9" />
              <line x1="9" y1="15" x2="15" y2="15" />
            </svg>
            {{ issuing ? '生成中…' : '生成激活码' }}
          </button>
          <button
            v-if="latestInvite?.active"
            type="button"
            class="btn primary"
            @click="copyCode"
          >
            复制激活码
          </button>
        </div>
      </div>

      <div v-if="completeness || adherence" class="hero-kpis">
        <div
          v-if="completeness"
          class="hero-kpi"
          :class="completenessTone"
          :title="completenessHint"
        >
          <div class="label">档案完整度</div>
          <div class="val">
            {{ completenessPercent }}<small>%</small>
          </div>
          <div class="trend" :class="completenessTone">{{ completenessHint }}</div>
          <div class="kpi-bar" aria-hidden="true">
            <i :style="{ width: `${completenessPercent}%` }" />
          </div>
        </div>

        <div
          v-if="adherence"
          class="hero-kpi"
          :class="adherenceTone"
          :title="adherenceHint"
        >
          <div class="label">近7日依从率</div>
          <div class="val">
            {{ adherenceDisplay }}
            <small v-if="adherencePercent != null">%</small>
          </div>
          <div class="trend" :class="adherenceTone">{{ adherenceHint }}</div>
          <div class="kpi-bar" aria-hidden="true">
            <i :style="{ width: `${adherencePercent ?? 0}%` }" />
          </div>
        </div>
      </div>

      <PatientBasicInfoEditor
        v-if="peopleId"
        ref="basicInfoEditorRef"
        compact
        :people-id="peopleId"
        @updated="onBasicInfoUpdated"
      />
    </section>

    <div class="tabs-bar">
      <el-tabs
        :model-value="activeTab"
        class="detail-tabs"
        :before-leave="onPatientTabBeforeLeave"
        @tab-change="switchTab"
      >
        <el-tab-pane label="档案" name="archive" />
        <el-tab-pane label="健康数据" name="observations" />
        <el-tab-pane label="健康方案" name="care-plan" />
        <el-tab-pane label="用药" name="medications" />
        <el-tab-pane label="疾病评估" name="assessments" />
        <el-tab-pane label="随访" name="followups" />
        <el-tab-pane label="沟通" name="chat" />
        <el-tab-pane label="健康报告" name="health-reports" />
        <el-tab-pane label="依从性" name="adherence" />
        <el-tab-pane label="修订审计" name="revisions" />
      </el-tabs>
    </div>

    <router-view />
  </div>
</template>

<style scoped>
.patient-detail {
  width: 100%;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--ink-500);
}

.crumb-link {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--ink-500);
  cursor: pointer;
}

.crumb-link:hover {
  color: var(--brand-600);
}

.crumb-sep {
  width: 14px;
  height: 14px;
  opacity: 0.5;
  flex-shrink: 0;
}

.crumbs strong {
  color: var(--ink-800);
  font-weight: 600;
}

/* ── Hero ─────────────────────────────────────────── */
.patient-hero {
  position: relative;
  overflow: hidden;
  margin-bottom: 16px;
  padding: 24px;
  background: linear-gradient(135deg, #fff 0%, #f8fbff 100%);
  border: 1px solid var(--ink-200);
  border-radius: 16px;
}

.patient-hero::before {
  content: '';
  position: absolute;
  top: -60px;
  right: -40px;
  width: 240px;
  height: 240px;
  background: radial-gradient(circle, rgba(44, 126, 248, 0.08) 0%, transparent 70%);
  pointer-events: none;
}

.hero-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 20px;
}

.hero-avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #1d4ed8);
  box-shadow: 0 8px 24px -4px rgba(59, 130, 246, 0.3);
}

.hero-info {
  flex: 1;
  min-width: 0;
}

.hero-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.hero-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--ink-500);
  font-variant-numeric: tabular-nums;
}

.hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 600;
}

.tag .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.tag.brand {
  background: var(--brand-50);
  color: var(--brand-600);
}

.tag.teal {
  background: var(--teal-50);
  color: #0f766e;
}

.tag.amber {
  background: var(--amber-50, #fef6e2);
  color: #b45309;
}

.tag.rose {
  background: var(--rose-50, #fee9e7);
  color: var(--rose-500, #ef4444);
}

.tag.muted {
  background: var(--ink-100);
  color: var(--ink-500);
}

.hero-actions {
  position: relative;
  display: flex;
  flex-shrink: 0;
  gap: 10px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 180ms ease, border-color 180ms ease, transform 180ms ease;
}

.btn svg {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn.primary {
  background: var(--brand-500);
  color: #fff;
  box-shadow: 0 4px 10px -2px rgba(44, 126, 248, 0.4);
}

.btn.primary:hover:not(:disabled) {
  background: var(--brand-600);
  transform: translateY(-1px);
}

.btn.ghost {
  background: #fff;
  border-color: var(--ink-200);
  color: var(--ink-700);
}

.btn.ghost:hover:not(:disabled) {
  background: var(--ink-50);
}

.text-btn {
  border: none;
  background: none;
  padding: 0;
  color: var(--brand-600);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}

.text-btn.sm {
  font-size: 12px;
  margin-left: 2px;
}

.text-btn:hover {
  text-decoration: underline;
}

/* ── KPI strip ────────────────────────────────────── */
.hero-kpis {
  position: relative;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.hero-kpi {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  padding: 12px 14px;
}

.hero-kpi .label {
  font-size: 11.5px;
  color: var(--ink-500);
  margin-bottom: 4px;
}

.hero-kpi .val {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
}

.hero-kpi .val small {
  font-size: 11px;
  color: var(--ink-400);
  font-weight: 500;
  margin-left: 2px;
}

.hero-kpi .trend {
  font-size: 11px;
  margin-top: 3px;
  font-weight: 600;
  color: var(--ink-400);
}

.hero-kpi .trend.good {
  color: #10b981;
}

.hero-kpi .trend.fair {
  color: var(--amber-500, #f59e0b);
}

.hero-kpi .trend.poor {
  color: var(--rose-500, #ef4444);
}

.hero-kpi .trend.empty {
  color: var(--ink-400);
}

.hero-kpi.good .val {
  color: #047857;
}

.hero-kpi.fair .val {
  color: #92400e;
}

.hero-kpi.poor .val {
  color: #b91c1c;
}

.hero-kpi.empty .val {
  color: var(--ink-400);
}

.kpi-bar {
  margin-top: 8px;
  height: 4px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.kpi-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width 240ms ease;
}

.hero-kpi.good .kpi-bar i {
  background: #10b981;
}

.hero-kpi.fair .kpi-bar i {
  background: var(--amber-500, #f59e0b);
}

.hero-kpi.poor .kpi-bar i {
  background: var(--rose-500, #ef4444);
}

.hero-kpi.empty .kpi-bar i {
  background: var(--ink-300);
}

/* ── Tabs ─────────────────────────────────────────── */
.tabs-bar {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 0 6px;
  margin-bottom: 16px;
  overflow-x: auto;
}

.detail-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.detail-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

.detail-tabs :deep(.el-tabs__item) {
  height: auto;
  padding: 14px 18px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink-500);
  border-bottom: 2px solid transparent !important;
}

.detail-tabs :deep(.el-tabs__item:hover) {
  color: var(--ink-800);
}

.detail-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-600);
  font-weight: 600;
}

.detail-tabs :deep(.el-tabs__active-bar) {
  height: 2px;
  border-radius: 2px;
  background: var(--brand-500);
}

.detail-tabs :deep(.el-tabs__content) {
  display: none;
}

@media (max-width: 960px) {
  .hero-row {
    flex-wrap: wrap;
  }

  .hero-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .patient-hero {
    padding: 18px;
  }
}

@media (max-width: 720px) {
  .hero-avatar {
    width: 56px;
    height: 56px;
    font-size: 20px;
  }

  .hero-name {
    font-size: 20px;
  }

  .hero-kpis {
    grid-template-columns: 1fr;
  }
}
</style>
