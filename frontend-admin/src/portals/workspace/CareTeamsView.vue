<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { api, getCurrentOrgId, getStaffId } from '../../shared/http'
import { StaffRole } from '../../shared/enums'
import { avatarGradients } from '../../shared/palette'
import {
  formatPlanRatePct,
  planRateBarWidth,
} from '../../shared/adherence-rate'
import DeleteCareTeamDialog, { type DeleteCareTeamTarget } from './DeleteCareTeamDialog.vue'

interface CareTeamListItem {
  id: string
  teamCode: string
  name: string
  primaryCareManagerStaffId: string
  primaryCareManagerName?: string
  primaryDoctorStaffId?: string | null
  primaryDoctorName?: string | null
  memberCount: number
  status: string
  createdAt: string
}

interface OrgStaffItem {
  staffId: string
  displayName: string
  roles: string[]
}

interface AdherenceTeamSlice {
  careTeamId?: string | null
  careTeamName: string
  planRate?: number | null
}

interface AdherenceOverview {
  planRate?: number | null
  teams?: AdherenceTeamSlice[]
}

type SortKey = 'members' | 'created' | 'name'
type DoctorFilter = 'all' | 'unbound'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const list = ref<CareTeamListItem[]>([])
const keyword = ref('')
const doctorFilter = ref<DoctorFilter>('all')
const managerFilter = ref<string | ''>('')
const sortKey = ref<SortKey>('members')
const staffList = ref<OrgStaffItem[]>([])
const overview = ref<AdherenceOverview | null>(null)
const searchReady = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = ref({
  name: '',
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})

const isCreatePrimarySelf = computed(() => {
  const selfId = getStaffId()
  return !!selfId && createForm.value.primaryCareManagerStaffId === selfId
})

const createRules = {
  name: [
    { required: true, whitespace: true, message: '请填写健管组名称', trigger: 'blur' },
    {
      validator: (_: unknown, value: string, callback: (e?: Error) => void) => {
        const nameKey = (value || '').trim().toLowerCase()
        if (
          nameKey &&
          list.value.some((t) => (t.name || '').trim().toLowerCase() === nameKey)
        ) {
          callback(new Error('机构内已存在同名健管组，请换一个名称'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  primaryCareManagerStaffId: [
    { required: true, message: '请选择主责健管师', trigger: 'change' },
  ],
}

const careManagers = computed(() =>
  staffList.value.filter(
    (s) => s.roles?.includes(StaffRole.CARE_MANAGER) || s.roles?.includes(StaffRole.TENANT_ADMIN),
  ),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

const totalMembers = computed(() => list.value.reduce((sum, t) => sum + (t.memberCount || 0), 0))
const careManagerCount = computed(() => {
  const ids = new Set(list.value.map((t) => t.primaryCareManagerStaffId).filter(Boolean))
  return ids.size || careManagers.value.length
})
const unboundDoctorCount = computed(
  () => list.value.filter((t) => !t.primaryDoctorStaffId && !t.primaryDoctorName).length,
)
const avgPerTeam = computed(() => {
  if (!list.value.length) return null
  return Math.round(totalMembers.value / list.value.length)
})
const orgPlanRate = computed(() => overview.value?.planRate ?? null)

const planRateByTeamId = computed(() => {
  const map = new Map<string, number | null>()
  for (const t of overview.value?.teams || []) {
    if (t.careTeamId) map.set(t.careTeamId, t.planRate ?? null)
  }
  return map
})

/** 工具栏「主责健管师」选项：来自当前列表去重 */
const managerOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const t of list.value) {
    const id = t.primaryCareManagerStaffId
    if (!id || seen.has(id)) continue
    seen.set(id, t.primaryCareManagerName || id)
  }
  return [...seen.entries()]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh'))
})

const onlyUnboundDoctor = computed(() => doctorFilter.value === 'unbound')

const displayList = computed(() => {
  let rows = [...list.value]
  if (onlyUnboundDoctor.value) {
    rows = rows.filter((t) => !hasDoctor(t))
  }
  if (managerFilter.value) {
    rows = rows.filter((t) => t.primaryCareManagerStaffId === managerFilter.value)
  }
  rows.sort((a, b) => {
    if (sortKey.value === 'members') {
      const d = (b.memberCount || 0) - (a.memberCount || 0)
      if (d !== 0) return d
    } else if (sortKey.value === 'created') {
      const d = String(b.createdAt || '').localeCompare(String(a.createdAt || ''))
      if (d !== 0) return d
    } else {
      const d = (a.name || '').localeCompare(b.name || '', 'zh')
      if (d !== 0) return d
    }
    return (a.teamCode || '').localeCompare(b.teamCode || '', 'zh')
  })
  return rows
})

const AVATAR_GRADIENTS = [...avatarGradients]

function avatarChar(name?: string) {
  const t = (name || '').trim()
  return t ? t.slice(0, 1) : '?'
}

function avatarStyle(name?: string) {
  const t = (name || '').trim()
  let h = 0
  for (let i = 0; i < t.length; i++) h = (h + t.charCodeAt(i) * (i + 1)) % AVATAR_GRADIENTS.length
  return { background: AVATAR_GRADIENTS[h] }
}

function formatDate(iso: string) {
  if (!iso) return '—'
  return iso.slice(0, 10)
}

function hasDoctor(row: CareTeamListItem) {
  return !!(row.primaryDoctorStaffId || row.primaryDoctorName)
}

function teamPlanRate(row: CareTeamListItem): number | null {
  const v = planRateByTeamId.value.get(row.id)
  return v === undefined ? null : v
}

function formatRate(rate?: number | null) {
  return formatPlanRatePct(rate)
}

function isHighAdherence(row: CareTeamListItem) {
  const r = teamPlanRate(row)
  return r != null && r >= 0.8
}

async function ensureOrg() {
  if (!getCurrentOrgId()) {
    await router.replace('/workspace/orgs')
    return false
  }
  return true
}

async function loadStaff() {
  const res = await api<{ data: OrgStaffItem[] }>('/api/b/v1/org-staff')
  staffList.value = res.data ?? []
}

async function loadOverview() {
  try {
    const res = await api<{ data: AdherenceOverview }>('/api/b/v1/adherence/overview')
    overview.value = res.data
  } catch {
    overview.value = null
  }
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    const q = keyword.value.trim() ? `?keyword=${encodeURIComponent(keyword.value.trim())}` : ''
    const res = await api<{ data: CareTeamListItem[] }>(`/api/b/v1/care-teams${q}`)
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  const selfId = getStaffId()
  const selfIsCm =
    !!selfId &&
    careManagers.value.some((s) => s.staffId === selfId)
  createForm.value = {
    name: '',
    primaryCareManagerStaffId: selfIsCm ? selfId : null,
    primaryDoctorStaffId: null,
  }
  createVisible.value = true
}

/** 驾驶舱冷启动：/workspace/care-teams?create=1 自动打开新建并预填自己为主责 */
async function maybeOpenCreateFromQuery() {
  if (route.query.create !== '1' && route.query.create !== 'true') return
  openCreate()
  const nextQuery = { ...route.query }
  delete nextQuery.create
  await router.replace({ path: route.path, query: nextQuery })
}

async function submitCreate() {
  try {
    await createFormRef.value?.validate()
  } catch {
    return
  }
  creating.value = true
  try {
    const res = await api<{ data: CareTeamListItem }>('/api/b/v1/care-teams', {
      method: 'POST',
      body: JSON.stringify({
        name: createForm.value.name.trim(),
        primaryCareManagerStaffId: createForm.value.primaryCareManagerStaffId,
        primaryDoctorStaffId: createForm.value.primaryDoctorStaffId || undefined,
      }),
    })
    ElMessage.success('健管组已创建')
    createVisible.value = false
    await router.push(`/workspace/care-teams/${res.data.id}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

function openDetail(row: CareTeamListItem) {
  router.push(`/workspace/care-teams/${row.id}`)
}

function onCardCommand(cmd: string, row: CareTeamListItem) {
  if (cmd === 'detail' || cmd === 'members') openDetail(row)
  else if (cmd === 'delete') openDeleteTeam(row)
}

const deleteDialogOpen = ref(false)
const deleteTarget = ref<DeleteCareTeamTarget | null>(null)

function openDeleteTeam(row: CareTeamListItem) {
  deleteTarget.value = { id: row.id, name: row.name }
  deleteDialogOpen.value = true
}

async function onTeamDeleted() {
  await Promise.all([load(), loadOverview()])
}

function onExportClick() {
  ElMessage.info('导出即将开放')
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  try {
    await Promise.all([loadStaff(), load(), loadOverview()])
    await maybeOpenCreateFromQuery()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    searchReady.value = true
  }
})

watch(
  () => route.query.create,
  (v) => {
    if (v === '1' || v === 'true') void maybeOpenCreateFromQuery()
  },
)

watch(keyword, () => {
  if (!searchReady.value) return
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void load()
  }, 320)
})
</script>

<template>
  <div class="care-teams">
    <div class="page-title">
      <div>
        <h1>健管组</h1>
        <p>
          共 <strong class="em">{{ list.length }}</strong> 个健管组
          · 在管 <strong class="em">{{ totalMembers.toLocaleString('zh-CN') }}</strong> 位患者
          · 平均每组
          <strong class="em">{{ avgPerTeam == null ? '—' : avgPerTeam }}</strong>
          人
          <template v-if="unboundDoctorCount">
            · <strong class="em warn">{{ unboundDoctorCount }}</strong> 组未绑主责医生
          </template>
        </p>
      </div>
      <div class="head-actions">
        <button type="button" class="btn btn-ghost" @click="onExportClick">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          导出
        </button>
        <button type="button" class="btn btn-primary" @click="openCreate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新建健管组
        </button>
      </div>
    </div>

    <section class="stat-grid">
      <div class="stat">
        <div class="stat-icon tone-brand" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="9" cy="7" r="4" />
            <path d="M3 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2" />
          </svg>
        </div>
        <div>
          <div class="stat-label">健管组总数</div>
          <div class="stat-val">{{ list.length }}</div>
        </div>
      </div>
      <div class="stat">
        <div class="stat-icon tone-teal" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
          </svg>
        </div>
        <div>
          <div class="stat-label">在管患者</div>
          <div class="stat-val">{{ totalMembers.toLocaleString('zh-CN') }}</div>
        </div>
      </div>
      <div class="stat">
        <div class="stat-icon tone-amber" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="7" r="4" />
            <path d="M5 21v-2a7 7 0 0 1 14 0v2" />
          </svg>
        </div>
        <div>
          <div class="stat-label">主责健管师</div>
          <div class="stat-val">{{ careManagerCount }}</div>
        </div>
      </div>
      <div class="stat">
        <div class="stat-icon tone-violet" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
          </svg>
        </div>
        <div>
          <div class="stat-label">平均依从率</div>
          <div class="stat-val">{{ formatRate(orgPlanRate) }}</div>
        </div>
      </div>
    </section>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索健管组名称 / 编码"
        clearable
        class="toolbar-search"
      />
      <el-select
        v-model="managerFilter"
        clearable
        placeholder="主责健管师: 全部"
        class="filter-ctl"
        style="width: 160px"
      >
        <el-option
          v-for="m in managerOptions"
          :key="m.id"
          :label="m.name"
          :value="m.id"
        />
      </el-select>
      <el-select v-model="sortKey" class="filter-ctl" style="width: 140px">
        <el-option value="members" label="排序: 成员数" />
        <el-option value="created" label="排序: 创建时间" />
        <el-option value="name" label="排序: 名称" />
      </el-select>
      <el-select v-model="doctorFilter" class="filter-ctl" style="width: 150px">
        <el-option value="all" label="全部健管组" />
        <el-option value="unbound" label="仅未绑医生" />
      </el-select>
    </div>

    <div v-loading="loading" class="team-grid">
      <article
        v-for="row in displayList"
        :key="row.id"
        class="team-card"
        @click="openDetail(row)"
      >
        <div class="team-head">
          <div class="team-head-text">
            <div class="team-name">{{ row.name }}</div>
          </div>
          <div class="card-more" @click.stop>
            <el-dropdown trigger="click" @command="(cmd: string) => onCardCommand(cmd, row)">
              <button type="button" class="more-btn" aria-label="更多操作">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <circle cx="12" cy="5" r="1.5" />
                  <circle cx="12" cy="12" r="1.5" />
                  <circle cx="12" cy="19" r="1.5" />
                </svg>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="detail">查看详情</el-dropdown-item>
                  <el-dropdown-item command="members">成员管理</el-dropdown-item>
                  <el-dropdown-item divided command="delete">
                    <span class="danger">删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>

        <div class="team-leads">
          <div
            class="lead-avatar"
            :style="avatarStyle(row.primaryCareManagerName)"
            :title="row.primaryCareManagerName || '主责健管师'"
          >
            {{ avatarChar(row.primaryCareManagerName) }}
          </div>
          <div class="lead-info">
            <div class="name">{{ row.primaryCareManagerName || '未指定' }}</div>
            <div class="role">主责健管师</div>
          </div>
          <div class="lead-stack">
            <template v-if="hasDoctor(row)">
              <div
                class="av"
                :style="avatarStyle(row.primaryDoctorName || undefined)"
                :title="`主责医生 · ${row.primaryDoctorName}`"
              >
                {{ avatarChar(row.primaryDoctorName || undefined) }}
              </div>
            </template>
            <div v-else class="av more-av" title="未绑主责医生">医</div>
          </div>
        </div>

        <div class="team-tags">
          <span v-if="!hasDoctor(row)" class="tag rose">未绑医生</span>
          <span v-if="isHighAdherence(row)" class="tag teal">高依从</span>
        </div>

        <div class="team-stats">
          <div class="t-stat">
            <div class="v">{{ row.memberCount ?? 0 }}</div>
            <div class="l">成员</div>
          </div>
          <div class="t-stat">
            <div class="v">{{ formatRate(teamPlanRate(row)) }}</div>
            <div class="l">依从率</div>
          </div>
          <div class="t-stat">
            <div class="v" :class="{ warn: !hasDoctor(row) }">
              {{ hasDoctor(row) ? '已绑' : '未绑' }}
            </div>
            <div class="l">医生</div>
          </div>
        </div>

        <div class="team-foot">
          <span>创建于 {{ formatDate(row.createdAt) }}</span>
          <div v-if="teamPlanRate(row) != null" class="progress-mini">
            <div class="bar">
              <div
                class="fill"
                :style="{ width: `${planRateBarWidth(teamPlanRate(row))}%` }"
              />
            </div>
            <span>{{ formatRate(teamPlanRate(row)) }}</span>
          </div>
        </div>
      </article>

      <el-empty
        v-if="!loading && displayList.length === 0"
        class="empty-span"
        :description="
          onlyUnboundDoctor && list.length
            ? '当前没有未绑医生的健管组'
            : managerFilter && list.length
              ? '当前筛选下没有健管组'
              : '暂无健管组，点击右上角新建'
        "
      />
    </div>

    <el-dialog
      v-model="createVisible"
      title="新建健管组"
      width="480px"
      destroy-on-close
      append-to-body
      class="create-care-team-dialog"
      @closed="createFormRef?.clearValidate()"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        class="create-care-team-form"
        @submit.prevent
      >
        <el-form-item label="健管组名称" prop="name" required>
          <el-input
            v-model="createForm.name"
            maxlength="40"
            show-word-limit
            placeholder="例如：减重管理一组"
            @keyup.enter="submitCreate"
          />
        </el-form-item>
        <el-form-item prop="primaryCareManagerStaffId" required>
          <template #label>
            <span>主责健管师</span>
          </template>
          <el-select
            v-model="createForm.primaryCareManagerStaffId"
            filterable
            placeholder="选择主责健管师"
            style="width: 100%"
          >
            <el-option
              v-for="s in careManagers"
              :key="s.staffId"
              :label="s.displayName"
              :value="s.staffId"
            />
          </el-select>
          <p v-if="isCreatePrimarySelf" class="field-hint">已默认选中当前登录账号</p>
        </el-form-item>
        <el-form-item label="主责医生（可选）">
          <el-select
            v-model="createForm.primaryDoctorStaffId"
            clearable
            filterable
            placeholder="可稍后指定"
            style="width: 100%"
          >
            <el-option
              v-for="s in doctors"
              :key="s.staffId"
              :label="s.displayName"
              :value="s.staffId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确定创建</el-button>
      </template>
    </el-dialog>

    <DeleteCareTeamDialog
      v-model="deleteDialogOpen"
      :team="deleteTarget"
      @deleted="onTeamDeleted"
    />
  </div>
</template>

<style scoped>
.care-teams {
  width: 100%;
  padding-bottom: 24px;
}

.em {
  color: #1e293b;
  font-weight: 600;
}

.em.warn,
.t-stat .v.warn {
  color: #e11d48;
}

.head-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
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
  font-family: inherit;
  background: none;
  transition: 180ms cubic-bezier(0.4, 0, 0.2, 1);
}

.btn svg {
  width: 15px;
  height: 15px;
}

.btn-ghost {
  background: #fff;
  color: #334155;
  border-color: #e2e8f0;
}

.btn-ghost:hover {
  background: #f8fafc;
}

.btn-primary {
  background: #2c7ef8;
  color: #fff;
  box-shadow: 0 4px 10px -2px rgba(44, 126, 248, 0.4);
}

.btn-primary:hover {
  background: #1e63d9;
  transform: translateY(-1px);
}

/* —— Stats —— */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.stat {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.stat-icon svg {
  width: 20px;
  height: 20px;
}

.stat-icon.tone-brand {
  background: #eaf2ff;
  color: #2c7ef8;
}
.stat-icon.tone-teal {
  background: #e6faf7;
  color: #0f766e;
}
.stat-icon.tone-amber {
  background: #fef6e2;
  color: #b45309;
}
.stat-icon.tone-violet {
  background: #f1ecfe;
  color: #6d28d9;
}

.stat-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.stat-val {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
  margin-top: 2px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
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
  margin-bottom: 14px;
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

/* —— Cards —— */
.team-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  min-height: 120px;
}

.empty-span {
  grid-column: 1 / -1;
  padding: 40px 0;
}

.team-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 18px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  transition: 180ms cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.team-card:hover {
  border-color: #2c7ef8;
  box-shadow: 0 4px 16px -4px rgba(44, 126, 248, 0.12);
  transform: translateY(-2px);
}

.team-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.team-head-text {
  min-width: 0;
}

.team-name {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
  word-break: break-word;
}

.card-more {
  flex-shrink: 0;
}

.more-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: grid;
  place-items: center;
  padding: 0;
}

.more-btn svg {
  width: 16px;
  height: 16px;
}

.more-btn:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.danger {
  color: #ef4444;
}

.team-leads {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-top: 1px solid #f1f5f9;
  border-bottom: 1px solid #f1f5f9;
  margin-bottom: 12px;
}

.lead-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.lead-info {
  min-width: 0;
  flex: 1;
}

.lead-info .name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.3;
}

.lead-info .role {
  font-size: 11px;
  color: #64748b;
}

.lead-stack {
  display: flex;
  margin-left: auto;
  flex-shrink: 0;
}

.lead-stack .av {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid #fff;
  margin-left: -6px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
}

.lead-stack .av:first-child {
  margin-left: 0;
}

.lead-stack .more-av {
  background: #f1f5f9;
  color: #64748b;
}

.team-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 12px;
  min-height: 0;
}

.team-tags:empty {
  display: none;
  margin: 0;
}

.tag {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}

.tag.teal {
  background: #e6faf7;
  color: #0f766e;
}

.tag.rose {
  background: #fee9e7;
  color: #ef4444;
}

.team-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.t-stat {
  text-align: center;
  padding: 8px 0;
  background: #f8fafc;
  border-radius: 8px;
}

.t-stat .v {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.t-stat .l {
  font-size: 10.5px;
  color: #94a3b8;
  margin-top: 2px;
}

.team-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: auto;
  gap: 8px;
}

.progress-mini {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: #64748b;
}

.progress-mini .bar {
  width: 60px;
  height: 4px;
  background: #f1f5f9;
  border-radius: 2px;
  overflow: hidden;
}

.progress-mini .fill {
  height: 100%;
  background: linear-gradient(90deg, #2c7ef8, #00b8a9);
  border-radius: inherit;
}

.create-care-team-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.create-care-team-form :deep(.el-form-item__label) {
  font-weight: 600;
  color: #334155;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: #94a3b8;
}

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .team-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .stat-grid,
  .team-grid {
    grid-template-columns: 1fr;
  }

  .toolbar-search,
  .filter-ctl {
    flex: 1 1 100%;
    width: 100% !important;
  }
}
</style>
