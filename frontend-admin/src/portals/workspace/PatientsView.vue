<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { checkinStatus } from '../../shared/rewards'
import { avatarGradients } from '../../shared/palette'
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'
import CreateArchiveDrawer from './CreateArchiveDrawer.vue'
import UnassignedAssignDrawer, { type UnassignedPatientRow } from './UnassignedAssignDrawer.vue'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  identityMask?: string
  identityType?: string
  careTeamId?: string | null
  careTeamName?: string | null
  joinedAt?: string
  /** C 端就诊人是否已关联 */
  clientLinked?: boolean | null
  clientCardCount?: number | null
  diseaseLabels?: string[]
  assessmentTags?: Array<{
    engineCode?: string
    text: string
    tone?: string
    title?: string
  }>
  primaryCareManagerStaffId?: string | null
  primaryCareManagerName?: string | null
  archiveCompletenessPercent?: number | null
  watched?: boolean | null
  streakDays?: number | null
  todayActions?: number | null
  inactiveDays?: number | null
}

interface CareTeamListItem {
  id: string
  name: string
}

const AVATAR_COLORS = [...avatarGradients]

const router = useRouter()
const loading = ref(false)
const list = ref<OrgPatientListItem[]>([])
const listTotal = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 顶栏 KPI（/org-patients/stats） */
const stats = ref({
  total: 0,
  unassigned: 0,
  watched: 0,
  monthNew: 0,
  todayNew: 0,
  clientLinked: 0,
  /** 在管总数相对上月末环比 % */
  totalMomPct: null as number | null,
  lastMonthNew: 0,
})
/** 依从性概览：高风险人数 + 方案完成率 */
const adherenceOverview = ref<{
  followUpCount: number
  planRate: number | null
}>({ followUpCount: 0, planRate: null })
const teams = ref<CareTeamListItem[]>([])
const keyword = ref('')
const careTeamId = ref<string | ''>('')
const unassignedOnly = ref(false)
const watchedOnly = ref(false)
/** 病种筛选（与 DiseaseCodeLabels / 病种档案 code 对齐） */
const diseaseCode = ref('')
const DISEASE_FILTER_OPTIONS = [
  { value: 'diabetes', label: '糖尿病' },
  { value: 'hypertension', label: '高血压' },
  { value: 'obesity', label: '肥胖症' },
  { value: 'hyperlipidemia', label: '高脂血症' },
  { value: 'cad', label: '冠心病' },
  { value: 'stroke', label: '脑卒中' },
  { value: 'copd', label: '慢阻肺' },
  { value: 'ckd', label: '慢性肾病' },
  { value: 'gout', label: '痛风' },
  { value: 'osteoporosis', label: '骨质疏松' },
] as const

const archiveVisible = ref(false)

const teamDialogPeopleId = ref<string | null>(null)
const teamDialogPeopleName = ref<string | null>(null)
const teamDialogCurrentTeamId = ref<string | null>(null)

/** 列表勾选 → 批量加入健管组 */
const selectedRows = ref<OrgPatientListItem[]>([])
const tableRef = ref<{ clearSelection?: () => void } | null>(null)
const batchAssignOpen = ref(false)
const selectedCount = computed(() => selectedRows.value.length)
const canBatchAssign = computed(() => selectedCount.value > 0)
const batchAssignPatients = computed<UnassignedPatientRow[]>(() =>
  selectedRows.value.map((r) => ({
    peopleId: r.peopleId,
    displayName: r.displayName,
    diseaseLabels: r.diseaseLabels || [],
    gender: r.gender,
    careTeamId: r.careTeamId,
  })),
)

/** 仅未入组 / 当前页全无组时隐藏主责列 */
const showPrimaryManagerColumn = computed(() => {
  if (unassignedOnly.value) return false
  if (list.value.length > 0 && list.value.every((r) => !r.careTeamId)) return false
  return true
})


const totalCount = computed(() => stats.value.total)
const unassignedCount = computed(() => stats.value.unassigned)
const monthNewCount = computed(() => stats.value.monthNew)
const todayNewCount = computed(() => stats.value.todayNew)
const clientLinkedCount = computed(() => stats.value.clientLinked)
const highRiskCount = computed(() => adherenceOverview.value.followUpCount)
const planRatePct = computed(() => {
  const rate = adherenceOverview.value.planRate
  if (rate == null || Number.isNaN(rate)) return null
  return Math.round(rate * 1000) / 10
})
const clientActivationRate = computed(() => {
  if (!totalCount.value) return null
  return Math.round((clientLinkedCount.value / totalCount.value) * 100)
})
/** 本月新增 vs 上月新增：同比变化 % */
const monthNewYoYPct = computed(() => {
  const last = stats.value.lastMonthNew
  if (last <= 0) return null
  return Math.round(((monthNewCount.value - last) * 1000) / last) / 10
})
/** 表头条数：筛选结果总数（分页 total） */
const filterTotal = computed(() => listTotal.value)

function formatSignedPct(pct: number) {
  const abs = Math.abs(pct)
  const text = Number.isInteger(abs) ? String(abs) : abs.toFixed(1)
  return pct > 0 ? `+${text}%` : pct < 0 ? `-${text}%` : '0%'
}

type SummaryKey = 'all' | 'highRisk' | 'month' | 'adherence' | 'client'

/** 与设计稿 KPI 条对齐：在管 / 高风险 / 本月新增 / 平均依从率 / C 端激活 */
const summaryCards = computed(() => {
  const clearActive =
    !unassignedOnly.value
    && !watchedOnly.value
    && !diseaseCode.value
    && !careTeamId.value
    && !keyword.value.trim()

  const mom = stats.value.totalMomPct
  let totalTrend = '暂无上月对比'
  let totalTrendTone: 'up' | 'down' | 'muted' = 'muted'
  let totalShowArrow = false
  if (mom != null) {
    // 设计稿：12.5% 较上月；夸张涨幅（>|100|）去掉箭头，只留文字降噪
    const abs = Math.abs(mom)
    const pctText = Number.isInteger(abs) ? String(abs) : abs.toFixed(1)
    totalTrend = `${mom < 0 ? '-' : ''}${pctText}% 较上月`
    totalTrendTone = mom > 0 ? 'up' : mom < 0 ? 'down' : 'muted'
    totalShowArrow = mom !== 0 && abs <= 100
  } else if (monthNewCount.value > 0) {
    totalTrend = `本月新增 ${monthNewCount.value}`
    totalTrendTone = 'up'
    totalShowArrow = true
  }

  const yoy = monthNewYoYPct.value
  let monthTrend = todayNewCount.value ? `今日 +${todayNewCount.value}` : '按入机构时间'
  let monthTrendTone: 'up' | 'down' | 'muted' = todayNewCount.value ? 'up' : 'muted'
  let monthShowArrow = !!todayNewCount.value
  if (yoy != null) {
    monthTrend = `同比 ${formatSignedPct(yoy)}`
    monthTrendTone = yoy > 0 ? 'up' : yoy < 0 ? 'down' : 'muted'
    monthShowArrow = yoy !== 0 && Math.abs(yoy) <= 100
  }

  const adherenceZero = planRatePct.value == null || planRatePct.value === 0

  return [
    {
      key: 'all' as SummaryKey,
      label: '在管患者',
      value: String(totalCount.value),
      unit: '人',
      trend: totalTrend,
      trendTone: totalTrendTone,
      showArrow: totalShowArrow,
      empty: totalCount.value === 0,
      active: clearActive,
      clickable: true,
      tone: 'default' as const,
    },
    {
      key: 'highRisk' as SummaryKey,
      label: '高风险',
      value: String(highRiskCount.value),
      unit: '人',
      trend: highRiskCount.value > 0 ? '依从风险 · 点击查看' : '暂无高风险',
      trendTone: highRiskCount.value > 0 ? ('down' as const) : ('muted' as const),
      showArrow: false,
      empty: highRiskCount.value === 0,
      active: false,
      clickable: true,
      tone: highRiskCount.value > 0 ? ('rose' as const) : ('default' as const),
    },
    {
      key: 'month' as SummaryKey,
      label: '本月新增',
      value: String(monthNewCount.value),
      unit: '人',
      trend: monthTrend,
      trendTone: monthTrendTone,
      showArrow: monthShowArrow,
      empty: monthNewCount.value === 0,
      active: false,
      clickable: false,
      tone: 'default' as const,
    },
    {
      key: 'adherence' as SummaryKey,
      label: '平均依从率',
      value: planRatePct.value != null ? String(planRatePct.value) : '—',
      unit: planRatePct.value != null ? '%' : '',
      trend: adherenceZero ? '暂无打卡数据 · 点进看板' : '今日方案完成率 · 点进看板',
      trendTone: 'muted' as const,
      showArrow: false,
      empty: adherenceZero,
      active: false,
      clickable: true,
      tone: 'default' as const,
    },
    {
      key: 'client' as SummaryKey,
      label: 'C 端激活',
      value: String(clientLinkedCount.value),
      unit: '人',
      trend:
        clientActivationRate.value != null
          ? `激活率 ${clientActivationRate.value}%`
          : '暂无激活',
      trendTone: 'muted' as const,
      showArrow: false,
      empty: clientLinkedCount.value === 0,
      active: false,
      clickable: false,
      tone: 'default' as const,
    },
  ]
})

function ageFromBirthday(birthday?: string | number[]) {
  if (birthday == null || birthday === '') return '-'
  let b: Date
  if (Array.isArray(birthday) && birthday.length >= 3) {
    b = new Date(Number(birthday[0]), Number(birthday[1]) - 1, Number(birthday[2]))
  } else {
    b = new Date(birthday as string)
  }
  if (Number.isNaN(b.getTime())) return '-'
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const md = now.getMonth() - b.getMonth()
  if (md < 0 || (md === 0 && now.getDate() < b.getDate())) age -= 1
  return String(age)
}

function avatarStyle(name?: string) {
  const ch = (name || '?').charCodeAt(0) || 0
  return { background: AVATAR_COLORS[ch % AVATAR_COLORS.length] }
}

function avatarChar(name?: string) {
  const n = (name || '').trim()
  return n ? n.slice(0, 1) : '?'
}

function patientMeta(row: OrgPatientListItem) {
  const age = ageFromBirthday(row.birthday)
  const gender = formatGender(row.gender)
  const parts = [
    age !== '-' ? `${age} 岁` : null,
    gender !== '-' ? gender : null,
  ].filter(Boolean)
  return parts.join(' · ') || '-'
}

function clientStatusLabel(row: OrgPatientListItem) {
  return row.clientLinked ? 'C 端激活' : '未激活'
}

/** >70 绿 · 30–70 黄 · <30 浅橙；0 无填充 */
function archiveFillClass(percent?: number | null) {
  const p = percent ?? 0
  if (p <= 0) return 'zero'
  if (p > 70) return 'high'
  if (p >= 30) return 'mid'
  return 'low'
}

function archivePctText(percent?: number | null) {
  return `${percent ?? 0}%`
}

function archiveFillStyle(percent?: number | null) {
  const p = Math.max(0, Math.min(100, percent ?? 0))
  if (p <= 0) return { width: '0%' }
  return { width: `${p}%`, minWidth: p < 16 ? '8px' : undefined }
}

/** 评估/风险标签：统一偏红警示 */
function isRiskAssessmentTone(tone?: string) {
  return tone === 'danger' || tone === 'warning' || !tone
}

/** 跟在姓名后的状态小字：打卡中断 / N天未打卡 */
function patientStatusHint(row: OrgPatientListItem) {
  const st = checkinStatus(row)
  if (!st) {
    if (row.streakDays === 0) return '打卡中断'
    return ''
  }
  if (st.tone === 'ok') return ''
  return st.text
}

function onSelectionChange(rows: OrgPatientListItem[]) {
  selectedRows.value = Array.isArray(rows) ? [...rows] : []
}

function clearSelection() {
  selectedRows.value = []
  tableRef.value?.clearSelection?.()
}

async function openBatchAssign() {
  const rows = selectedRows.value
  if (!rows.length) {
    ElMessage.info('请先勾选要加入健管组的患者')
    return
  }
  const total = rows.length
  const alreadyInTeam = rows.filter((r) => !!r.careTeamId).length
  // 混合勾选（或含已入组）：二次确认，防止误覆盖原分组
  if (alreadyInTeam > 0) {
    try {
      await ElMessageBox.confirm(
        `您选中的 ${total} 人中，有 ${alreadyInTeam} 人已分入其他健管组，继续操作将覆盖其原有分组。确认继续吗？`,
        '确认批量加入健管组',
        {
          type: 'warning',
          confirmButtonText: '确认继续',
          cancelButtonText: '取消',
        },
      )
    } catch {
      return
    }
  }
  batchAssignOpen.value = true
}

async function onBatchAssigned() {
  clearSelection()
  await Promise.all([load(), loadBaseline()])
}

async function ensureOrg() {
  if (!getCurrentOrgId()) {
    await router.replace('/workspace/orgs')
    return false
  }
  return true
}

async function loadTeams() {
  const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
  teams.value = res.data ?? []
}

async function loadBaseline() {
  const [statsRes, adherenceRes] = await Promise.all([
    api<{
      data: {
        total: number
        unassigned: number
        watched: number
        monthNew: number
        todayNew: number
        clientLinked?: number
        totalMomPct?: number | null
        lastMonthNew?: number
      }
    }>('/api/b/v1/org-patients/stats'),
    api<{
      data: {
        followUpCount?: number
        planRate?: number | null
      }
    }>('/api/b/v1/adherence/overview').catch(() => null),
  ])
  const d = statsRes.data
  stats.value = {
    total: d?.total ?? 0,
    unassigned: d?.unassigned ?? 0,
    watched: d?.watched ?? 0,
    monthNew: d?.monthNew ?? 0,
    todayNew: d?.todayNew ?? 0,
    clientLinked: d?.clientLinked ?? 0,
    totalMomPct: d?.totalMomPct ?? null,
    lastMonthNew: d?.lastMonthNew ?? 0,
  }
  adherenceOverview.value = {
    followUpCount: adherenceRes?.data?.followUpCount ?? 0,
    planRate: adherenceRes?.data?.planRate ?? null,
  }
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    const q = new URLSearchParams()
    q.set('page', String(page.value))
    q.set('size', String(pageSize.value))
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    if (unassignedOnly.value) q.set('unassigned', 'true')
    else if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
    if (watchedOnly.value) q.set('watched', 'true')
    if (diseaseCode.value) q.set('diseaseCode', diseaseCode.value)
    const res = await api<{ data: { total: number; items: OrgPatientListItem[] } }>(
      `/api/b/v1/org-patients?${q}`,
    )
    listTotal.value = res.data?.total ?? 0
    list.value = res.data?.items ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function reloadFromFirstPage() {
  page.value = 1
  clearSelection()
  void load()
}

function onSummaryClick(key: SummaryKey) {
  // 高风险 → 依从看板「待跟进」；平均依从率 → 看板全景（不带筛选）
  if (key === 'highRisk') {
    void router.push({ path: '/workspace/adherence', query: { filter: 'FOLLOW_UP' } })
    return
  }
  if (key === 'adherence') {
    void router.push({ path: '/workspace/adherence', query: {} })
    return
  }
  if (key === 'all') {
    keyword.value = ''
    careTeamId.value = ''
    unassignedOnly.value = false
    watchedOnly.value = false
    diseaseCode.value = ''
    reloadFromFirstPage()
  }
  // 本月新增 / C 端激活：仅统计展示，列表接口暂无对应筛选
}

function onExportClick() {
  ElMessage.info('导出 Excel 即将开放')
}

watch(unassignedOnly, (v) => {
  if (v) {
    careTeamId.value = ''
    watchedOnly.value = false
  }
})

function openArchive() {
  archiveVisible.value = true
}

async function onArchiveCreated(result: { peopleId: string; displayName: string }) {
  await Promise.all([load(), loadBaseline()])
  if (result.peopleId) {
    teamDialogPeopleId.value = result.peopleId
    teamDialogPeopleName.value = result.displayName
    teamDialogCurrentTeamId.value = null
  }
}

function openCareTeamDialog(row: OrgPatientListItem) {
  teamDialogPeopleId.value = row.peopleId
  teamDialogPeopleName.value = row.displayName
  teamDialogCurrentTeamId.value = row.careTeamId || null
}

function closeCareTeamDialog() {
  teamDialogPeopleId.value = null
  teamDialogPeopleName.value = null
  teamDialogCurrentTeamId.value = null
}

async function onCareTeamChanged() {
  await Promise.all([load(), loadBaseline()])
}

const removingPeopleId = ref<string | null>(null)

async function removeOrgPatient(row: OrgPatientListItem) {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.displayName}」从本机构移除？仅解除机构关系，患者档案保留；本机构列表将不再显示。`,
      '移除患者',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  removingPeopleId.value = row.peopleId
  try {
    await api(`/api/b/v1/org-patients/${encodeURIComponent(row.peopleId)}`, { method: 'DELETE' })
    ElMessage.success('已从本机构移除')
    await Promise.all([load(), loadBaseline()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  } finally {
    removingPeopleId.value = null
  }
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  try {
    await Promise.all([loadTeams(), load(), loadBaseline()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <div class="workspace-patients">
    <div class="page-title">
      <div>
        <h1>患者管理</h1>
        <p>
          共 <strong class="em">{{ totalCount.toLocaleString('zh-CN') }}</strong> 位在管患者
          · 今日新增 {{ todayNewCount }} 位
          · {{ highRiskCount }} 位高风险预警
        </p>
      </div>
      <div class="head-actions">
        <button type="button" class="btn btn-ghost" @click="onExportClick">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          导出 Excel
        </button>
        <button type="button" class="btn btn-primary" @click="openArchive">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新建患者档案
        </button>
      </div>
    </div>

    <div v-loading="loading && !list.length" class="kpi-strip">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="kpi"
        :class="{
          'is-active': card.active,
          'is-static': !card.clickable,
          'is-empty': card.empty,
          [`tone-${card.tone}`]: true,
        }"
        :disabled="!card.clickable"
        @click="card.clickable && onSummaryClick(card.key)"
      >
        <div class="kpi-label">{{ card.label }}</div>
        <div class="kpi-val">
          {{ card.value }}
          <small v-if="card.unit">{{ card.unit }}</small>
        </div>
        <div class="kpi-trend" :class="card.trendTone">
          <svg
            v-if="card.showArrow && (card.trendTone === 'up' || card.trendTone === 'down')"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            aria-hidden="true"
          >
            <polyline v-if="card.trendTone === 'up'" points="18 15 12 9 6 15" />
            <polyline v-else points="6 9 12 15 18 9" />
          </svg>
          {{ card.trend }}
        </div>
      </button>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索姓名 / 拼音 / 手机号"
        clearable
        class="toolbar-search"
        @keyup.enter="reloadFromFirstPage"
        @clear="reloadFromFirstPage"
      />
      <el-select
        v-model="careTeamId"
        clearable
        placeholder="健管组: 全部"
        class="filter-ctl"
        style="width: 160px"
        :disabled="unassignedOnly"
        @change="reloadFromFirstPage"
      >
        <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-select
        v-model="diseaseCode"
        clearable
        placeholder="病种: 全部"
        class="filter-ctl"
        style="width: 160px"
        @change="reloadFromFirstPage"
      >
        <el-option
          v-for="opt in DISEASE_FILTER_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <label class="toggle">
        <input v-model="unassignedOnly" type="checkbox" @change="reloadFromFirstPage" />
        仅未入组
      </label>
      <label class="toggle">
        <input v-model="watchedOnly" type="checkbox" @change="reloadFromFirstPage" />
        仅重点关注
      </label>
      <el-button type="primary" class="search-btn" @click="reloadFromFirstPage">查询</el-button>
    </div>

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="table-head-bar">
          <div class="table-title">
            患者列表
            <span class="count">
              · 共 {{ filterTotal }} 条
              <template v-if="filterTotal !== totalCount"> · 在管 {{ totalCount }}</template>
            </span>
          </div>
          <button
            type="button"
            class="btn btn-ghost btn-sm"
            :class="{ ready: canBatchAssign }"
            :disabled="!canBatchAssign"
            @click="openBatchAssign"
          >
            {{
              selectedCount > 0
                ? `批量加入健管组 (${selectedCount})`
                : '批量加入健管组'
            }}
          </button>
        </div>
      </template>

      <div v-if="selectedCount > 0" class="batch-bar">
        <span>已选 <strong>{{ selectedCount }}</strong> 项</span>
        <span class="batch-dot">·</span>
        <button type="button" class="batch-link" @click="openBatchAssign">批量加入健管组</button>
        <button type="button" class="batch-close" aria-label="取消选择" @click="clearSelection">✕</button>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="list"
        row-key="peopleId"
        class="patients-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="44" class-name="col-check" />
        <el-table-column label="患者" min-width="240" width="260" class-name="col-patient">
          <template #default="{ row }">
            <div class="patient-cell">
              <div class="patient-av" :style="avatarStyle(row.displayName)">
                {{ avatarChar(row.displayName) }}
              </div>
              <div class="patient-text">
                <div class="patient-name">
                  <span class="patient-name-text" :title="row.displayName || '-'">
                    {{ row.displayName || '-' }}
                  </span>
                  <span v-if="row.watched" class="tag tag-amber tag-xs">关注</span>
                  <span
                    v-if="row.streakDays"
                    class="flame-inline"
                    :title="`连续打卡 ${row.streakDays} 天`"
                  >{{ row.streakDays }}</span>
                </div>
                <div class="patient-meta">
                  <span>{{ patientMeta(row) }}</span>
                  <template v-if="patientStatusHint(row)">
                    <span class="meta-sep"> · </span>
                    <span class="meta-alert">{{ patientStatusHint(row) }}</span>
                  </template>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="健管组" width="128" class-name="col-tag">
          <template #default="{ row }">
            <span v-if="row.careTeamName" class="tag tag-brand">{{ row.careTeamName }}</span>
            <span v-else class="tag tag-muted">未分组</span>
          </template>
        </el-table-column>
        <el-table-column label="主要病种" min-width="132" class-name="col-tag">
          <template #default="{ row }">
            <div v-if="row.diseaseLabels?.length" class="tag-wrap">
              <span
                v-for="(d, i) in row.diseaseLabels"
                :key="d"
                class="tag"
                :class="i % 2 === 0 ? 'tag-brand' : 'tag-teal'"
              >{{ d }}</span>
            </div>
            <span v-else class="muted muted-soft">暂无</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="showPrimaryManagerColumn"
          label="主责健管师"
          width="108"
          class-name="col-plain"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="plain-cell">{{ row.primaryCareManagerName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="评估" min-width="132" class-name="col-tag">
          <template #default="{ row }">
            <div v-if="row.assessmentTags?.length" class="tag-wrap">
              <span
                v-for="t in row.assessmentTags"
                :key="t.engineCode || t.text"
                class="tag"
                :class="isRiskAssessmentTone(t.tone) ? 'tag-rose' : 'tag-amber'"
                :title="t.title || t.text"
              >{{ t.text }}</span>
            </div>
            <span v-else class="muted muted-soft">—</span>
          </template>
        </el-table-column>
        <el-table-column label="档案完整度" width="128">
          <template #default="{ row }">
            <div class="progress" :class="`tone-${archiveFillClass(row.archiveCompletenessPercent)}`">
              <div class="bar">
                <div
                  class="fill"
                  :class="archiveFillClass(row.archiveCompletenessPercent)"
                  :style="archiveFillStyle(row.archiveCompletenessPercent)"
                />
              </div>
              <div class="pct">{{ archivePctText(row.archiveCompletenessPercent) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" class-name="col-tag">
          <template #default="{ row }">
            <span class="tag" :class="row.clientLinked ? 'tag-teal' : 'tag-warn'">
              {{ clientStatusLabel(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" class-name="col-actions">
          <template #default="{ row }">
            <div class="row-actions">
              <button
                type="button"
                class="act-link act-primary"
                @click="router.push({ path: `/workspace/patients/${row.peopleId}/persona`, query: { name: row.displayName } })"
              >
                详情
              </button>
              <button type="button" class="act-link" @click="openCareTeamDialog(row)">
                {{ row.careTeamId ? '变更组' : '加入组' }}
              </button>
              <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'remove' && removeOrgPatient(row)">
                <button type="button" class="act-link">更多</button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      command="remove"
                      class="danger-item"
                      :disabled="removingPeopleId === row.peopleId"
                    >
                      从本机构移除
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && list.length === 0" description="暂无患者" :image-size="72" />
      <div v-if="filterTotal > 0" class="list-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="filterTotal"
          :page-sizes="[10, 15, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="
            () => {
              clearSelection()
              void load()
            }
          "
          @size-change="
            () => {
              page = 1
              clearSelection()
              void load()
            }
          "
        />
      </div>
    </el-card>

    <CreateArchiveDrawer v-model="archiveVisible" @created="onArchiveCreated" />

    <ChangeCareTeamDialog
      :people-id="teamDialogPeopleId"
      :people-name="teamDialogPeopleName"
      :current-care-team-id="teamDialogCurrentTeamId"
      @closed="closeCareTeamDialog"
      @changed="onCareTeamChanged"
      @create="
        () => {
          closeCareTeamDialog()
          void router.push('/workspace/care-teams?create=1')
        }
      "
    />

    <UnassignedAssignDrawer
      v-model="batchAssignOpen"
      :patients="batchAssignPatients"
      select-all-on-open
      @assigned="onBatchAssigned"
      @create-team="
        () => {
          batchAssignOpen = false
          void router.push('/workspace/care-teams?create=1')
        }
      "
    />
  </div>
</template>

<style scoped>
.workspace-patients {
  width: 100%;
  min-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
  gap: 0;
  /* 底部留白：避开右下角悬浮球遮挡末行操作 */
  padding: 8px 0 88px;
  box-sizing: border-box;
}
.workspace-patients > .page-title {
  margin-bottom: 22px;
  flex-shrink: 0;
}
.workspace-patients > .kpi-strip {
  margin-bottom: 20px;
  flex-shrink: 0;
}
.workspace-patients > .toolbar {
  margin-bottom: 14px;
  flex-shrink: 0;
}
.workspace-patients > .list-card {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 420px;
}
.workspace-patients > .list-card :deep(.el-card__body) {
  flex: 1;
}

.em {
  color: var(--ink-800);
  font-weight: 600;
}

.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  align-items: center;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
  background: #fff;
  color: #475569;
}
.btn svg {
  width: 15px;
  height: 15px;
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-ghost {
  border-color: #e2e8f0;
  color: #334155;
  box-shadow: 0 1px 2px var(--ink-a04, rgba(15, 23, 42, 0.04));
}
.btn-ghost:hover:not(:disabled) {
  background: #f8fafc;
  color: #334155;
  border-color: #cbd5e1;
}
.btn-primary {
  background: #2c7ef8;
  color: #fff;
  border-color: transparent;
  box-shadow: 0 4px 10px -2px rgba(44, 126, 248, 0.4);
  font-weight: 600;
}
.btn-primary:hover:not(:disabled) {
  background: #1e63d9;
}
.btn-sm {
  height: 32px;
  font-size: 12px;
  padding: 0 12px;
}
.btn-sm:disabled {
  background: #f1f5f9;
  color: #94a3b8;
  border-color: #e2e8f0;
  opacity: 1;
}
.btn-sm.ready:not(:disabled) {
  color: #1e63d9;
  border-color: #bfdbfe;
  background: #eff6ff;
}

/* —— KPI —— */
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}
.kpi {
  margin: 0;
  padding: 16px 18px;
  text-align: left;
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}
.kpi:hover:not(:disabled) {
  border-color: #cbd5e1;
}
.kpi.is-static,
.kpi:disabled {
  cursor: default;
  opacity: 1;
}
.kpi.is-active {
  background: linear-gradient(90deg, rgba(239, 246, 255, 0.85) 0%, #fff 72%);
  box-shadow:
    0 0 0 1px rgba(37, 99, 235, 0.16),
    0 4px 14px rgba(37, 99, 235, 0.06);
}
.kpi.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #2c7ef8;
}
.kpi-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}
.kpi-val {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.5px;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.kpi-val small {
  font-size: 13px;
  color: #94a3b8;
  font-weight: 500;
  margin-left: 2px;
}
.kpi.tone-rose .kpi-val {
  color: #ef4444;
}
.kpi.is-empty .kpi-val {
  color: #94a3b8;
}
.kpi.is-empty.tone-rose .kpi-val {
  color: #fda4af;
}
.kpi-trend {
  margin-top: 4px;
  font-size: 11.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #94a3b8;
}
.kpi-trend svg {
  width: 11px;
  height: 11px;
  flex-shrink: 0;
}
.kpi-trend.up {
  color: #10b981;
}
.kpi-trend.down {
  color: #ef4444;
}
.kpi-trend.muted {
  color: #94a3b8;
  font-weight: 500;
}

/* —— Toolbar —— */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.toolbar-search {
  flex: 1;
  min-width: 220px;
}
.toolbar :deep(.el-input__wrapper),
.toolbar :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 8px;
}
.filter-ctl {
  flex-shrink: 0;
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #334155;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
.toggle input {
  width: 32px;
  height: 18px;
  appearance: none;
  background: #cbd5e1;
  border-radius: 9px;
  position: relative;
  cursor: pointer;
  transition: background 0.18s ease;
  flex-shrink: 0;
}
.toggle input:checked {
  background: #2c7ef8;
}
.toggle input::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 2px;
  width: 14px;
  height: 14px;
  background: #fff;
  border-radius: 50%;
  transition: left 0.18s ease;
}
.toggle input:checked::before {
  left: 16px;
}
.toggle input:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.search-btn {
  margin-left: auto;
}

/* —— Table card —— */
.list-card {
  border: 1px solid #e2e8f0 !important;
  border-radius: 12px !important;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  height: 100%;
}
.list-card :deep(.el-card__header) {
  padding: 14px 20px !important;
  border-bottom: 1px solid #f1f5f9;
  flex-shrink: 0;
}
.list-card :deep(.el-card__body) {
  padding: 0 !important;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.table-head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.table-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}
.table-title .count {
  color: #94a3b8;
  font-weight: 500;
  margin-left: 6px;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  background: #eaf2ff;
  border-bottom: 1px solid #e2e8f0;
  font-size: 13px;
  color: #1e63d9;
}
.batch-dot {
  color: #64748b;
}
.batch-link {
  border: none;
  background: none;
  color: #1e63d9;
  font-weight: 500;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}
.batch-link:hover {
  text-decoration: underline;
}
.batch-close {
  margin-left: auto;
  border: none;
  background: none;
  color: #64748b;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 4px;
}

.list-card :deep(.patients-table) {
  width: 100%;
  flex: 1;
  --el-table-row-hover-bg-color: #fbfcfe;
  --el-table-header-bg-color: #f8fafc;
}
.list-card :deep(.patients-table .el-table__header th.el-table__cell),
.list-card :deep(.patients-table .el-table__body td.el-table__cell) {
  text-align: left !important;
  padding-left: 16px !important;
  padding-right: 16px !important;
  vertical-align: middle !important;
}
.list-card :deep(.patients-table .el-table__header th.el-table__cell) {
  background: #f8fafc !important;
  color: #64748b !important;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.4px;
  border-bottom: 1px solid #f1f5f9 !important;
  padding-top: 12px !important;
  padding-bottom: 12px !important;
}
.list-card :deep(.patients-table .el-table__body td.el-table__cell) {
  font-size: 13px;
  padding-top: 16px !important;
  padding-bottom: 16px !important;
  border-bottom: 1px solid #f1f5f9 !important;
}
.list-card :deep(.patients-table .el-table__header th .cell),
.list-card :deep(.patients-table .el-table__body td .cell) {
  padding: 0 !important;
  text-align: left !important;
  line-height: 1.4;
}
.list-card :deep(.patients-table .el-table__body tr:hover > td.el-table__cell) {
  background: #fbfcfe !important;
  transition: background 0.2s ease;
}
.list-card :deep(.patients-table .el-table__body tr:last-child td.el-table__cell) {
  border-bottom: 0 !important;
}
.list-card :deep(th.col-patient .cell),
.list-card :deep(td.col-patient .cell),
.list-card :deep(th.col-tag .cell),
.list-card :deep(td.col-tag .cell),
.list-card :deep(th.col-plain .cell),
.list-card :deep(td.col-plain .cell),
.list-card :deep(th.col-check .cell),
.list-card :deep(td.col-check .cell) {
  display: flex;
  align-items: center;
  justify-content: flex-start;
}
.list-card :deep(th.col-actions.el-table__cell),
.list-card :deep(td.col-actions.el-table__cell) {
  padding-right: 20px !important;
}
.list-card :deep(th.col-actions .cell),
.list-card :deep(td.col-actions .cell) {
  overflow: visible;
  display: flex;
  justify-content: flex-end;
  text-align: right !important;
}
.plain-cell {
  color: #334155;
  font-size: 13px;
}

.patient-cell {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 220px;
  max-width: 100%;
  width: 100%;
}
.patient-av {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
  margin-top: 1px;
}
.patient-text {
  min-width: 0;
  flex: 1;
  overflow: hidden;
}
.patient-name {
  font-weight: 600;
  color: #1e293b;
  line-height: 1.3;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}
.patient-name-text {
  display: block;
  min-width: 0;
  flex: 1;
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.flame-inline {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: #ea580c;
  font-variant-numeric: tabular-nums;
}
.patient-meta {
  margin-top: 2px;
  font-size: 10px;
  line-height: 1.35;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.patient-meta .meta-sep {
  color: #cbd5e1;
}
.patient-meta .meta-alert {
  color: #ef4444;
  font-weight: 550;
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}
.tag-xs {
  padding: 1px 6px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.35;
  border-radius: 3px;
}
.tag-brand {
  background: #eaf2ff;
  color: #1e63d9;
}
.tag-teal {
  background: #e6faf7;
  color: #0f766e;
}
.tag-amber {
  background: #fef6e2;
  color: #b45309;
}
.tag-rose {
  background: #fee9e7;
  color: #ef4444;
}
.tag-muted {
  background: #f1f5f9;
  color: #64748b;
}
.tag-warn {
  background: #fff7ed;
  color: #ea580c;
}

.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  min-width: 0;
  width: 100%;
}
.progress .bar {
  flex: 1;
  height: 6px;
  background: #f1f5f9;
  border-radius: 999px;
  overflow: hidden;
  min-width: 52px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.18);
}
.progress .fill {
  height: 100%;
  border-radius: 999px;
  min-width: 0;
  transition: width 0.2s ease;
}
.progress .fill.high {
  background: #10b981;
}
.progress .fill.mid {
  background: #f59e0b;
}
.progress .fill.low {
  background: #fb923c;
}
.progress .fill.zero {
  display: none;
}
.progress .pct {
  font-weight: 700;
  min-width: 36px;
  text-align: right;
  color: #1e293b;
  font-variant-numeric: tabular-nums;
  font-size: 12.5px;
}
.progress.tone-zero .pct {
  color: #94a3b8;
  font-weight: 400;
}
.progress.tone-high .pct,
.progress.tone-mid .pct,
.progress.tone-low .pct {
  color: #1e293b;
  font-weight: 700;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  white-space: nowrap;
}
.act-link {
  border: none;
  background: none;
  padding: 0;
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  line-height: 1.2;
}
.act-link:hover {
  color: #334155;
}
.act-link.act-primary {
  color: #2563eb;
  font-weight: 600;
}
.act-link.act-primary:hover {
  color: #1d4ed8;
  text-decoration: underline;
}
:deep(.danger-item) {
  color: #ef4444 !important;
}

.muted {
  color: #94a3b8;
  font-size: 12px;
}
.muted-soft {
  color: #cbd5e1;
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0.02em;
  opacity: 0.85;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid #f1f5f9;
}

@media (max-width: 1280px) {
  .kpi-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 960px) {
  .kpi-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .search-btn {
    margin-left: 0;
  }
}
</style>
