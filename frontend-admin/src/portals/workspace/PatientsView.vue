<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { MOBILE_OWNER_OPTIONS, formatMobileOwner } from '../../shared/mobile-owner'
import { TABLE_COL } from '../../shared/table-columns'
import { checkinStatus } from '../../shared/rewards'
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'

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

const IDENTITY_OPTIONS = [
  { label: '身份证', value: 'ID_CARD' },
  { label: '军官证', value: 'MILITARY_ID' },
  { label: '港澳通行证', value: 'HK_MACAU_PASS' },
  { label: '台胞证', value: 'TAIWAN_PASS' },
  { label: '其他', value: 'OTHER' },
]

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
/** 无筛选基线列表，仅用于 KPI（复用现有 org-patients，不新增 API） */
const baselineList = ref<OrgPatientListItem[]>([])
const teams = ref<CareTeamListItem[]>([])
const keyword = ref('')
const careTeamId = ref<string | ''>('')
const unassignedOnly = ref(false)
const watchedOnly = ref(false)
const adherenceFilter = ref<'' | 'PLAN_INCOMPLETE' | 'MED_INCOMPLETE' | 'STREAK_GE_3'>('')
const adherenceMode = computed(() => !!adherenceFilter.value)

const archiveVisible = ref(false)
const archiving = ref(false)
function emptyArchiveForm() {
  return {
    name: '',
    gender: '',
    birthday: '' as string,
    mobile: '',
    mobileOwner: 'SELF',
    noMobile: false,
    identityType: 'ID_CARD',
    identityValue: '',
  }
}
const archiveForm = ref(emptyArchiveForm())
const showIdentity = ref(false)

interface DuplicateCandidate {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  mobileMasked?: string | null
  mobileOwner?: string | null
  hasIdentity: boolean
  inCurrentOrg: boolean
}
const duplicates = ref<DuplicateCandidate[]>([])
const duplicateVisible = ref(false)

const teamDialogPeopleId = ref<string | null>(null)
const teamDialogPeopleName = ref<string | null>(null)
const teamDialogCurrentTeamId = ref<string | null>(null)


const totalCount = computed(() => baselineList.value.length)
const unassignedCount = computed(
  () => baselineList.value.filter((p) => !p.careTeamId).length,
)
const watchedCount = computed(
  () => baselineList.value.filter((p) => !!p.watched).length,
)
const enrolledCount = computed(() => totalCount.value - unassignedCount.value)
const monthNewCount = computed(() => {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  return baselineList.value.filter((p) => {
    if (!p.joinedAt) return false
    const d = new Date(p.joinedAt)
    return !Number.isNaN(d.getTime()) && d.getFullYear() === y && d.getMonth() === m
  }).length
})
const todayNewCount = computed(() => {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  const day = now.getDate()
  return baselineList.value.filter((p) => {
    if (!p.joinedAt) return false
    const d = new Date(p.joinedAt)
    return (
      !Number.isNaN(d.getTime())
      && d.getFullYear() === y
      && d.getMonth() === m
      && d.getDate() === day
    )
  }).length
})
const enrolledRate = computed(() => {
  if (!totalCount.value) return '—'
  return `${Math.round((enrolledCount.value / totalCount.value) * 100)}%`
})

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

/** 填了有效身份证时，性别和出生日期以证件为准 */
const archiveParsed = computed(() => {
  if (archiveForm.value.identityType !== 'ID_CARD') return null
  return parseIdCard(archiveForm.value.identityValue)
})

watch(archiveParsed, (v) => {
  if (!v) return
  archiveForm.value.gender = v.gender
  archiveForm.value.birthday = v.birthday
})

function parseIdCard(raw: string) {
  const id = (raw || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(id)) return null
  const y = id.slice(6, 10)
  const m = id.slice(10, 12)
  const d = id.slice(12, 14)
  const birthday = `${y}-${m}-${d}`
  const genderCode = Number(id.charAt(16))
  const gender = Number.isNaN(genderCode) ? 'UNKNOWN' : genderCode % 2 === 1 ? 'MALE' : 'FEMALE'
  return { birthday, gender }
}

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
  const res = await api<{ data: OrgPatientListItem[] }>('/api/b/v1/org-patients')
  baselineList.value = res.data ?? []
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    if (adherenceFilter.value) {
      const q = new URLSearchParams()
      q.set('filter', adherenceFilter.value)
      q.set('page', '1')
      q.set('size', '200')
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      const res = await api<{
        data: {
          items: Array<{
            peopleId: string
            displayName: string
            careTeamId?: string | null
            careTeamName?: string | null
          }>
        }
      }>(`/api/b/v1/adherence/patients?${q}`)
      list.value = (res.data?.items ?? []).map((row) => ({
        peopleId: row.peopleId,
        displayName: row.displayName,
        careTeamId: row.careTeamId,
        careTeamName: row.careTeamName,
      }))
    } else {
      const q = new URLSearchParams()
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (unassignedOnly.value) q.set('unassigned', 'true')
      else if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      if (watchedOnly.value) q.set('watched', 'true')
      const qs = q.toString()
      const res = await api<{ data: OrgPatientListItem[] }>(`/api/b/v1/org-patients${qs ? `?${qs}` : ''}`)
      list.value = res.data ?? []
      if (!keyword.value.trim() && !unassignedOnly.value && !watchedOnly.value && careTeamId.value === '') {
        baselineList.value = list.value
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
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
    void load()
    return
  }
  if (key === 'watched') {
    keyword.value = ''
    careTeamId.value = ''
    adherenceFilter.value = ''
    unassignedOnly.value = false
    watchedOnly.value = true
    void load()
    return
  }
  if (key === 'unassigned') {
    keyword.value = ''
    careTeamId.value = ''
    adherenceFilter.value = ''
    watchedOnly.value = false
    unassignedOnly.value = true
    void load()
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
  archiveForm.value = emptyArchiveForm()
  showIdentity.value = false
  archiveVisible.value = true
}

function validateArchive(): boolean {
  const f = archiveForm.value
  if (!f.name.trim()) {
    ElMessage.warning('请填写姓名')
    return false
  }
  const idValue = f.identityValue.trim()
  if (idValue && f.identityType === 'ID_CARD' && !parseIdCard(idValue)) {
    ElMessage.warning('身份证号格式不正确')
    return false
  }
  if (!f.gender || !f.birthday) {
    ElMessage.warning('请填写性别和出生日期')
    return false
  }
  if (!f.noMobile && !f.mobile.trim()) {
    ElMessage.warning('请填写联系电话，确实没有请勾选「暂无」')
    return false
  }
  return true
}

async function submitArchive() {
  if (!validateArchive()) return
  const f = archiveForm.value
  archiving.value = true
  try {
    const q = new URLSearchParams({ name: f.name.trim(), birthday: f.birthday })
    if (!f.noMobile && f.mobile.trim()) q.set('mobile', f.mobile.trim())
    const res = await api<{ data: DuplicateCandidate[] }>(`/api/b/v1/patients/archives/duplicates?${q}`)
    duplicates.value = res.data ?? []
  } catch {
    duplicates.value = []
  } finally {
    archiving.value = false
  }
  if (duplicates.value.length) {
    duplicateVisible.value = true
    return
  }
  await createArchive(null)
}

/** attachPeopleId：查重确认「就是此人」，复用已有档案入组本机构 */
async function createArchive(attachPeopleId: string | null) {
  const f = archiveForm.value
  const idValue = f.identityValue.trim()
  archiving.value = true
  try {
    await api('/api/b/v1/patients/archives', {
      method: 'POST',
      body: JSON.stringify({
        name: f.name.trim(),
        attachPeopleId: attachPeopleId || undefined,
        gender: f.gender,
        birthday: f.birthday,
        mobile: f.noMobile ? undefined : f.mobile.trim(),
        mobileOwner: f.noMobile ? undefined : f.mobileOwner,
        noMobile: f.noMobile,
        identityType: idValue ? f.identityType : undefined,
        identityValue: idValue ? idValue.toUpperCase() : undefined,
      }),
    })
    ElMessage.success(attachPeopleId ? '已关联已有档案' : '建档成功')
    duplicateVisible.value = false
    archiveVisible.value = false
    await Promise.all([load(), loadBaseline()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建档失败')
  } finally {
    archiving.value = false
  }
}

function duplicateLabel(d: DuplicateCandidate) {
  const parts = [formatGender(d.gender), d.birthday ? String(d.birthday).slice(0, 10) : '']
  if (d.mobileMasked) {
    const owner = d.mobileOwner && d.mobileOwner !== 'SELF' ? `（${formatMobileOwner(d.mobileOwner)}）` : ''
    parts.push(d.mobileMasked + owner)
  }
  if (d.hasIdentity) parts.push('已登记证件')
  return parts.filter(Boolean).join(' · ')
}

function openExisting(d: DuplicateCandidate) {
  duplicateVisible.value = false
  archiveVisible.value = false
  router.push({ path: `/workspace/patients/${d.peopleId}/archive`, query: { name: d.displayName } })
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
          @keyup.enter="load"
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
        <el-button type="primary" @click="load">查询</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="table-head-bar">
          <div class="table-title">
            患者列表
            <span class="count">
              · 共 {{ totalCount }} 条
              <template v-if="list.length !== totalCount"> · 当前 {{ list.length }} 条</template>
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
                  <span v-else-if="row.streakDays === 0" class="flame-tag flame-tag--out">已熄灭</span>
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
        <el-table-column label="证件" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.identityMask || '-' }}</template>
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
        <el-table-column label="操作" v-bind="TABLE_COL.actionsLg">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push({ path: `/workspace/patients/${row.peopleId}/archive`, query: { name: row.displayName } })"
            >
              详情
            </el-button>
            <el-button link type="primary" @click="openCareTeamDialog(row)">
              {{ row.careTeamId ? '变更健管组' : '加入健管组' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="archiveVisible" title="患者建档" width="560px">
      <el-form label-width="96px">
        <el-form-item label="姓名" required>
          <el-input v-model="archiveForm.name" maxlength="64" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="性别" required>
              <el-select v-model="archiveForm.gender" :disabled="!!archiveParsed" style="width: 100%">
                <el-option label="男" value="MALE" />
                <el-option label="女" value="FEMALE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出生日期" required label-width="80px">
              <el-date-picker
                v-model="archiveForm.birthday"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled="!!archiveParsed"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="联系电话" :required="!archiveForm.noMobile">
          <div class="mobile-row">
            <el-input
              v-model="archiveForm.mobile"
              :disabled="archiveForm.noMobile"
              maxlength="20"
              placeholder="手机或座机"
              class="mobile-input"
            />
            <el-select
              v-model="archiveForm.mobileOwner"
              :disabled="archiveForm.noMobile"
              class="owner-select"
              title="这个号码是谁的"
            >
              <el-option v-for="o in MOBILE_OWNER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-checkbox v-model="archiveForm.noMobile">暂无</el-checkbox>
          </div>
          <div class="form-tip">老人没有手机可填子女号码并选「子女」；夫妻共用号码各自注明归属</div>
        </el-form-item>

        <el-button v-if="!showIdentity" link type="primary" class="id-toggle" @click="showIdentity = true">
          + 补充证件信息（选填）
        </el-button>
        <template v-else>
          <el-form-item label="证件类型">
            <el-select v-model="archiveForm.identityType" style="width: 100%">
              <el-option v-for="opt in IDENTITY_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="证件号码">
            <el-input v-model="archiveForm.identityValue" placeholder="选填，可以后再补" />
            <div class="form-tip">加密保存、仅显示脱敏号码；填写身份证会自动带出性别和出生日期</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="archiveVisible = false">取消</el-button>
        <el-button type="primary" :loading="archiving" @click="submitArchive">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="duplicateVisible" title="可能是同一人" width="520px" append-to-body>
      <p class="dup-tip">
        租户内已有同名且出生日期或联系电话相同的档案。若是同一人，请直接关联，避免重复建档。
      </p>
      <div v-for="d in duplicates" :key="d.peopleId" class="dup-item">
        <div class="dup-main">
          <strong>{{ d.displayName }}</strong>
          <span class="dup-meta">{{ duplicateLabel(d) }}</span>
        </div>
        <el-button v-if="d.inCurrentOrg" size="small" @click="openExisting(d)">已在本机构，查看</el-button>
        <el-button v-else size="small" type="primary" :loading="archiving" @click="createArchive(d.peopleId)">
          就是此人
        </el-button>
      </div>
      <template #footer>
        <el-button @click="duplicateVisible = false">返回修改</el-button>
        <el-button :loading="archiving" @click="createArchive(null)">不是同一人，继续新建</el-button>
      </template>
    </el-dialog>

    <ChangeCareTeamDialog
      :people-id="teamDialogPeopleId"
      :people-name="teamDialogPeopleName"
      :current-care-team-id="teamDialogCurrentTeamId"
      @closed="closeCareTeamDialog"
      @changed="onCareTeamChanged"
    />
  </div>
</template>

<style scoped>
.mobile-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.mobile-input {
  flex: 1;
}
.owner-select {
  width: 110px;
}
.form-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.id-toggle {
  margin: 0 0 12px 96px;
}
.dup-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.dup-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 8px;
}
.dup-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dup-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
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

.list-card :deep(.el-card__header) {
  padding: 14px 18px !important;
}

.list-card :deep(.el-card__body) {
  padding-top: 0 !important;
}
</style>
