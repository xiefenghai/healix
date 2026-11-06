<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'
import { checkinStatus } from '../../shared/rewards'
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'
import CreateArchiveDrawer from './CreateArchiveDrawer.vue'

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

const AVATAR_COLORS = [
  'linear-gradient(135deg,#3B82F6,#1D4ED8)',
  'linear-gradient(135deg,#8B5CF6,#6D28D9)',
  'linear-gradient(135deg,#F59E0B,#EA580C)',
  'linear-gradient(135deg,#EC4899,#BE185D)',
  'linear-gradient(135deg,#10B981,#0F766E)',
  'linear-gradient(135deg,#06B6D4,#0E7490)',
  'linear-gradient(135deg,#F43F5E,#9F1239)',
  'linear-gradient(135deg,#84CC16,#3F6212)',
]

const router = useRouter()
const loading = ref(false)
const list = ref<OrgPatientListItem[]>([])
const listTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
/** 顶栏 KPI（/org-patients/stats） */
const stats = ref({
  total: 0,
  unassigned: 0,
  watched: 0,
  monthNew: 0,
  todayNew: 0,
})
const teams = ref<CareTeamListItem[]>([])
const keyword = ref('')
const careTeamId = ref<string | ''>('')
const unassignedOnly = ref(false)
const watchedOnly = ref(false)
const adherenceFilter = ref<'' | 'PLAN_INCOMPLETE' | 'MED_INCOMPLETE' | 'STREAK_GE_3'>('')
const adherenceMode = computed(() => !!adherenceFilter.value)

const archiveVisible = ref(false)

const teamDialogPeopleId = ref<string | null>(null)
const teamDialogPeopleName = ref<string | null>(null)
const teamDialogCurrentTeamId = ref<string | null>(null)


const totalCount = computed(() => stats.value.total)
const unassignedCount = computed(() => stats.value.unassigned)
const watchedCount = computed(() => stats.value.watched)
const enrolledCount = computed(() => Math.max(0, totalCount.value - unassignedCount.value))
const monthNewCount = computed(() => stats.value.monthNew)
const todayNewCount = computed(() => stats.value.todayNew)
const enrolledRate = computed(() => {
  if (!totalCount.value) return '—'
  return `${Math.round((enrolledCount.value / totalCount.value) * 100)}%`
})
/** 表头条数：筛选结果总数（分页 total） */
const filterTotal = computed(() => listTotal.value)

type SummaryKey = 'all' | 'unassigned' | 'watched' | 'month' | 'adherence' | 'client'

const summaryCards = computed(() => [
  {
    key: 'all' as SummaryKey,
    label: '在管患者',
    value: String(totalCount.value),
    hint: '当前机构全部档案',
    active:
      !unassignedOnly.value
      && !watchedOnly.value
      && !adherenceFilter.value
      && !careTeamId.value
      && !keyword.value.trim(),
    clickable: true,
    danger: false,
  },
  {
    key: 'watched' as SummaryKey,
    label: '重点关注',
    value: String(watchedCount.value),
    hint: '我标记的患者',
    active: watchedOnly.value,
    clickable: true,
    danger: false,
  },
  {
    key: 'unassigned' as SummaryKey,
    label: '未入组',
    value: String(unassignedCount.value),
    hint: '待分配健管组',
    active: unassignedOnly.value,
    clickable: true,
    danger: unassignedCount.value > 0,
  },
  {
    key: 'month' as SummaryKey,
    label: '本月新增',
    value: String(monthNewCount.value),
    hint: todayNewCount.value ? `今日 +${todayNewCount.value}` : '按入机构时间统计',
    active: false,
    clickable: false,
    danger: false,
  },
  {
    key: 'adherence' as SummaryKey,
    label: '平均依从率',
    value: '—',
    hint: '数据见依从性看板',
    active: false,
    clickable: true,
    danger: false,
  },
  {
    key: 'client' as SummaryKey,
    label: '入组覆盖',
    value: enrolledRate.value,
    hint: totalCount.value ? `已入组 ${enrolledCount.value} 人` : '暂无患者',
    active: false,
    clickable: false,
    danger: false,
  },
])

function formatTime(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

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

function assessmentToneType(tone?: string): 'danger' | 'warning' | 'success' | 'info' | undefined {
  switch (tone) {
    case 'danger':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'success':
      return 'success'
    case 'info':
      return 'info'
    default:
      return undefined
  }
}

function archiveToneClass(percent?: number | null) {
  if (percent == null) return ''
  if (percent >= 80) return 'good'
  if (percent >= 50) return 'fair'
  return 'poor'
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
  const res = await api<{
    data: {
      total: number
      unassigned: number
      watched: number
      monthNew: number
      todayNew: number
    }
  }>('/api/b/v1/org-patients/stats')
  const d = res.data
  stats.value = {
    total: d?.total ?? 0,
    unassigned: d?.unassigned ?? 0,
    watched: d?.watched ?? 0,
    monthNew: d?.monthNew ?? 0,
    todayNew: d?.todayNew ?? 0,
  }
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    if (adherenceFilter.value) {
      const q = new URLSearchParams()
      q.set('filter', adherenceFilter.value)
      q.set('page', String(page.value))
      q.set('size', String(pageSize.value))
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      const res = await api<{
        data: {
          total: number
          items: Array<{
            peopleId: string
            displayName: string
            careTeamId?: string | null
            careTeamName?: string | null
          }>
        }
      }>(`/api/b/v1/adherence/patients?${q}`)
      listTotal.value = res.data?.total ?? 0
      list.value = (res.data?.items ?? []).map((row) => ({
        peopleId: row.peopleId,
        displayName: row.displayName,
        careTeamId: row.careTeamId,
        careTeamName: row.careTeamName,
      }))
    } else {
      const q = new URLSearchParams()
      q.set('page', String(page.value))
      q.set('size', String(pageSize.value))
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (unassignedOnly.value) q.set('unassigned', 'true')
      else if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      if (watchedOnly.value) q.set('watched', 'true')
      const res = await api<{ data: { total: number; items: OrgPatientListItem[] } }>(
        `/api/b/v1/org-patients?${q}`,
      )
      listTotal.value = res.data?.total ?? 0
      list.value = res.data?.items ?? []
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function reloadFromFirstPage() {
  page.value = 1
  void load()
}

function onSummaryClick(key: SummaryKey) {
  if (key === 'adherence') {
    router.push('/workspace/adherence')
    return
  }
  if (key === 'all') {
    keyword.value = ''
    careTeamId.value = ''
    unassignedOnly.value = false
    watchedOnly.value = false
    adherenceFilter.value = ''
    reloadFromFirstPage()
    return
  }
  if (key === 'watched') {
    keyword.value = ''
    careTeamId.value = ''
    adherenceFilter.value = ''
    unassignedOnly.value = false
    watchedOnly.value = true
    reloadFromFirstPage()
    return
  }
  if (key === 'unassigned') {
    keyword.value = ''
    careTeamId.value = ''
    adherenceFilter.value = ''
    watchedOnly.value = false
    unassignedOnly.value = true
    reloadFromFirstPage()
  }
}

watch(unassignedOnly, (v) => {
  if (v) {
    careTeamId.value = ''
    adherenceFilter.value = ''
    watchedOnly.value = false
  }
})

watch(watchedOnly, (v) => {
  if (v) {
    adherenceFilter.value = ''
  }
})

watch(adherenceFilter, (v) => {
  if (v) {
    unassignedOnly.value = false
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
  <div>
    <div class="page-title">
      <div>
        <h1>患者管理</h1>
        <p>
          共 <strong class="em">{{ totalCount }}</strong> 位在管患者
          · 今日新增 {{ todayNewCount }} 位
          · {{ unassignedCount }} 位未入组
          · {{ getCurrentOrgName() || '-' }}
        </p>
      </div>
      <div class="actions">
        <el-button @click="router.push('/workspace/orgs')">切换机构</el-button>
        <el-button type="primary" @click="openArchive">新建患者档案</el-button>
      </div>
    </div>

    <div class="summary-row">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="summary-card"
        :class="{
          'is-active': card.active,
          'is-danger': card.danger,
          'is-static': !card.clickable,
        }"
        :disabled="!card.clickable"
        @click="card.clickable && onSummaryClick(card.key)"
      >
        <div class="summary-value">{{ card.value }}</div>
        <div class="summary-label">{{ card.label }}</div>
        <div class="summary-hint">{{ card.hint }}</div>
      </button>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索患者姓名 / 证件号"
          clearable
          class="toolbar-search"
          @keyup.enter="reloadFromFirstPage"
        />
        <el-select
          v-model="careTeamId"
          clearable
          placeholder="健管组: 全部"
          style="width: 180px"
          :disabled="unassignedOnly"
        >
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-select
          v-model="adherenceFilter"
          clearable
          placeholder="依从性筛选"
          style="width: 180px"
          :disabled="unassignedOnly"
        >
          <el-option label="当日方案未完成" value="PLAN_INCOMPLETE" />
          <el-option label="当日用药未完成" value="MED_INCOMPLETE" />
          <el-option label="连续未执行≥3天" value="STREAK_GE_3" />
        </el-select>
        <el-checkbox v-model="unassignedOnly" :disabled="adherenceMode">仅未入组</el-checkbox>
        <el-checkbox v-model="watchedOnly" :disabled="adherenceMode">仅重点关注</el-checkbox>
        <el-button type="primary" @click="reloadFromFirstPage">查询</el-button>
      </div>
    </el-card>

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
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe class="patients-table">
        <el-table-column label="患者" min-width="160">
          <template #default="{ row }">
            <div class="patient-cell">
              <div class="patient-av" :style="avatarStyle(row.displayName)">
                {{ avatarChar(row.displayName) }}
              </div>
              <div class="patient-text">
                <div class="patient-name">
                  {{ row.displayName || '-' }}
                  <el-tag v-if="row.watched" size="small" type="warning" effect="plain" class="watch-tag">
                    关注
                  </el-tag>
                  <span
                    v-if="row.streakDays"
                    class="flame-tag"
                    :title="`连续打卡 ${row.streakDays} 天`"
                  >🔥{{ row.streakDays }}</span>
                  <span v-else-if="row.streakDays === 0" class="flame-tag flame-tag--out" title="打卡已中断">打卡中断</span>
                </div>
                <div class="patient-meta">{{ patientMeta(row) }}</div>
                <div
                  v-if="checkinStatus(row)"
                  class="checkin-status"
                  :class="`checkin-status--${checkinStatus(row)!.tone}`"
                >{{ checkinStatus(row)!.text }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="病种档案" min-width="140">
          <template #default="{ row }">
            <div v-if="row.diseaseLabels?.length" class="tag-wrap">
              <el-tag
                v-for="d in row.diseaseLabels"
                :key="d"
                size="small"
                effect="light"
                type="warning"
              >
                {{ d }}
              </el-tag>
            </div>
            <span v-else class="muted">暂无</span>
          </template>
        </el-table-column>
        <el-table-column label="评估标签" min-width="200">
          <template #default="{ row }">
            <div v-if="row.assessmentTags?.length" class="tag-wrap">
              <el-tag
                v-for="t in row.assessmentTags"
                :key="t.engineCode || t.text"
                size="small"
                effect="plain"
                :type="assessmentToneType(t.tone)"
                :title="t.title || t.text"
              >
                {{ t.text }}
              </el-tag>
            </div>
            <span v-else class="muted">暂无</span>
          </template>
        </el-table-column>
        <el-table-column label="主责健管师" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.primaryCareManagerName || '-' }}</template>
        </el-table-column>
        <el-table-column label="健管组" min-width="120">
          <template #default="{ row }">
            <el-tag v-if="row.careTeamName" size="small" effect="light" type="primary">
              {{ row.careTeamName }}
            </el-tag>
            <el-tag v-else size="small" effect="plain" type="info">未入组</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="档案完整度" width="120">
          <template #default="{ row }">
            <div
              class="archive-cell"
              :class="archiveToneClass(row.archiveCompletenessPercent)"
            >
              <span class="archive-val">
                {{
                  row.archiveCompletenessPercent != null
                    ? `${row.archiveCompletenessPercent}%`
                    : '—'
                }}
              </span>
              <div class="archive-bar" aria-hidden="true">
                <i :style="{ width: `${row.archiveCompletenessPercent ?? 0}%` }" />
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="入机构时间" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.joinedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push({ path: `/workspace/patients/${row.peopleId}/persona`, query: { name: row.displayName } })"
            >
              详情
            </el-button>
            <el-button link type="primary" @click="openCareTeamDialog(row)">
              {{ row.careTeamId ? '变更健管组' : '加入健管组' }}
            </el-button>
            <el-button
              link
              type="danger"
              :loading="removingPeopleId === row.peopleId"
              @click="removeOrgPatient(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && list.length === 0" description="暂无患者" :image-size="72" />
      <div v-if="filterTotal > 0" class="list-pagination">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :total="filterTotal"
          layout="total, prev, pager, next"
          background
          @current-change="
            (p: number) => {
              page = p
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
  </div>
</template>

<style scoped>
.em {
  color: var(--ink-800);
  font-weight: 600;
}

.summary-card.is-danger .summary-value {
  color: var(--rose-500);
}

.summary-card.is-static {
  cursor: default;
}

.summary-card.is-static:hover {
  transform: none;
  box-shadow: var(--admin-shadow);
}

.summary-card:disabled {
  opacity: 1;
  cursor: default;
}

.toolbar-search {
  flex: 1;
  min-width: 220px;
  max-width: 360px;
}

.table-head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
}

.table-title .count {
  color: var(--ink-400);
  font-weight: 500;
  margin-left: 4px;
}

.patient-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
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
}

.patient-text {
  min-width: 0;
}

.patient-name {
  font-weight: 600;
  color: var(--ink-800);
  line-height: 1.3;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.watch-tag {
  font-weight: 500;
}
.flame-tag {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 999px;
  background: #fff1e3;
  color: #e0632a;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.flame-tag--out {
  background: #f0f2f5;
  color: #a0a8b3;
  font-weight: 600;
}
.checkin-status {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.3;
}
.checkin-status--ok {
  color: #0b7a66;
}
.checkin-status--idle {
  color: #a0a8b3;
}
.checkin-status--warn {
  color: #d03050;
}

.patient-meta {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-400);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.muted {
  color: var(--ink-400);
  font-size: 12px;
}

.archive-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.archive-val {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--ink-800);
  line-height: 1.2;
}

.archive-bar {
  height: 4px;
  border-radius: 999px;
  background: #e8eef5;
  overflow: hidden;
}

.archive-bar > i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #94a3b8;
}

.archive-cell.good .archive-val {
  color: #059669;
}

.archive-cell.good .archive-bar > i {
  background: #10b981;
}

.archive-cell.fair .archive-val {
  color: #d97706;
}

.archive-cell.fair .archive-bar > i {
  background: #f59e0b;
}

.archive-cell.poor .archive-val {
  color: #e11d48;
}

.archive-cell.poor .archive-bar > i {
  background: #f43f5e;
}

.hint {
  margin: 0 0 12px;
  color: var(--admin-muted);
  font-size: 13px;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding: 0 4px 4px;
}

.list-card :deep(.el-card__header) {
  padding: 14px 18px !important;
}

.list-card :deep(.el-card__body) {
  padding-top: 0 !important;
}
</style>
