<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName, getStaffId } from '../../shared/http'
import { StaffRole } from '../../shared/enums'
import { avatarGradients } from '../../shared/palette'
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

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const list = ref<CareTeamListItem[]>([])
const keyword = ref('')
/** all | unbound */
const doctorFilter = ref<'all' | 'unbound'>('all')
const staffList = ref<OrgStaffItem[]>([])
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
  const ids = new Set(
    list.value.map((t) => t.primaryCareManagerStaffId).filter(Boolean),
  )
  return ids.size || careManagers.value.length
})
const unboundDoctorCount = computed(
  () => list.value.filter((t) => !t.primaryDoctorStaffId && !t.primaryDoctorName).length,
)

const onlyUnboundDoctor = computed(() => doctorFilter.value === 'unbound')

const displayList = computed(() => {
  if (!onlyUnboundDoctor.value) return list.value
  return list.value.filter((t) => !hasDoctor(t))
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

function toggleUnboundFilter() {
  doctorFilter.value = doctorFilter.value === 'unbound' ? 'all' : 'unbound'
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
  await load()
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  try {
    await Promise.all([loadStaff(), load()])
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
          · 在管 <strong class="em">{{ totalMembers }}</strong> 位患者
          <template v-if="unboundDoctorCount">
            · <strong class="em warn">{{ unboundDoctorCount }}</strong> 组未绑主责医生
          </template>
          · {{ getCurrentOrgName() || '-' }}
        </p>
      </div>
      <div class="actions">
        <el-button @click="router.push('/workspace/orgs')">切换机构</el-button>
        <el-button type="primary" @click="openCreate">新建健管组</el-button>
      </div>
    </div>

    <div class="summary-row">
      <div class="stat-card stat-card--primary">
        <div class="stat-icon tone-brand">组</div>
        <div>
          <div class="stat-label">健管组总数</div>
          <div class="stat-val">{{ list.length }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon tone-teal">员</div>
        <div>
          <div class="stat-label">在管患者</div>
          <div class="stat-val">{{ totalMembers }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon tone-amber">师</div>
        <div>
          <div class="stat-label">主责健管师</div>
          <div class="stat-val">{{ careManagerCount }}</div>
        </div>
      </div>
      <button
        type="button"
        class="stat-card clickable"
        :class="{ active: onlyUnboundDoctor }"
        :title="onlyUnboundDoctor ? '点击取消筛选' : '点击筛选未绑医生的组'"
        @click="toggleUnboundFilter"
      >
        <div class="stat-icon" :class="unboundDoctorCount ? 'tone-rose' : 'tone-violet'">医</div>
        <div>
          <div class="stat-label">未绑主责医生</div>
          <div class="stat-val" :class="{ warn: unboundDoctorCount > 0 }">
            {{ unboundDoctorCount }}
          </div>
        </div>
      </button>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索健管组名称"
        clearable
        class="toolbar-search"
      />
      <el-select
        v-model="doctorFilter"
        class="toolbar-filter"
        placeholder="筛选"
      >
        <el-option value="all" label="全部健管组" />
        <el-option value="unbound" label="仅看未绑医生" />
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
          <div class="team-name">{{ row.name }}</div>
          <div class="head-actions" @click.stop>
            <el-dropdown trigger="click" @command="(cmd: string) => onCardCommand(cmd, row)">
              <button type="button" class="more-btn" aria-label="更多操作">⋯</button>
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
        <div class="team-meta">创建于 {{ formatDate(row.createdAt) }}</div>

        <div class="collab-row">
          <div
            class="lead-avatar"
            :style="avatarStyle(row.primaryCareManagerName)"
            :title="row.primaryCareManagerName || '主责健管师'"
          >
            {{ avatarChar(row.primaryCareManagerName) }}
          </div>
          <span class="name">{{ row.primaryCareManagerName || '未指定' }}</span>
          <span class="role-chip">主责</span>
          <template v-if="hasDoctor(row)">
            <span class="dot">·</span>
            <div
              class="lead-avatar sm"
              :style="avatarStyle(row.primaryDoctorName || undefined)"
              :title="row.primaryDoctorName || '主责医生'"
            >
              {{ avatarChar(row.primaryDoctorName || undefined) }}
            </div>
            <span class="name">{{ row.primaryDoctorName }}</span>
            <span class="role-chip muted">医生</span>
          </template>
          <template v-else>
            <span class="dot">·</span>
            <span class="no-doctor">暂无医生</span>
          </template>
        </div>

        <div class="team-tags">
          <span class="tag tag-patients">{{ row.memberCount ?? 0 }} 位患者</span>
        </div>

        <div class="team-foot">
          <span class="foot-link">查看详情 →</span>
        </div>
      </article>

      <el-empty
        v-if="!loading && displayList.length === 0"
        class="empty-span"
        :description="
          onlyUnboundDoctor && list.length
            ? '当前没有未绑医生的健管组'
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
}

.em {
  color: var(--ink-800);
  font-weight: 600;
}

.create-care-team-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.create-care-team-form :deep(.el-form-item__label) {
  font-weight: 600;
  color: var(--ink-700, var(--ink-750));
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ink-400, var(--gray-400));
}

.em.warn,
.stat-val.warn {
  color: var(--rose-600);
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--admin-card);
  border: 1px solid var(--ink-200);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
  text-align: left;
}

.stat-card.clickable {
  cursor: pointer;
  font: inherit;
  color: inherit;
  transition:
    border-color 160ms,
    box-shadow 160ms;
}

.stat-card.clickable:hover,
.stat-card.clickable.active {
  border-color: var(--rose-300);
  box-shadow: 0 0 0 3px var(--auto-rgba-2203838008);
}

.stat-icon {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
}

.stat-icon.tone-brand {
  background: var(--brand-50);
  color: var(--brand-500);
}

.stat-icon.tone-teal {
  background: var(--teal-50);
  color: var(--teal-600);
}

.stat-icon.tone-amber {
  background: var(--amber-50);
  color: var(--amber-600);
}

.stat-icon.tone-violet {
  background: var(--violet-50);
  color: var(--violet-700);
}

.stat-icon.tone-rose {
  background: var(--rose-100);
  color: var(--rose-600);
}

.stat-label {
  font-size: 12px;
  color: var(--ink-500);
  font-weight: 500;
}

.stat-val {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-900);
  margin-top: 0;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.toolbar-search {
  width: 280px;
  max-width: 100%;
  flex: 0 0 280px;
}

.toolbar-filter {
  width: 160px;
  flex: 0 0 160px;
}

.team-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 350px));
  gap: 20px;
  justify-content: start;
  min-height: 120px;
}

.empty-span {
  grid-column: 1 / -1;
  padding: 40px 0;
}

.team-card {
  width: 100%;
  max-width: 350px;
  background: var(--white);
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  padding: 14px 16px 0;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease;
}

.team-card:hover {
  border-color: var(--brand-500);
  box-shadow: 0 4px 12px var(--auto-rgba-000005);
}

.team-card:hover .foot-link {
  color: var(--brand-500);
}

.team-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.team-name {
  min-width: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.35;
  word-break: break-word;
}

.team-meta {
  margin-top: -4px;
  font-size: 12px;
  color: var(--ink-400, var(--gray-400));
  line-height: 1.4;
}

.head-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin: -4px -6px 0 0;
}

.more-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--ink-400);
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
}

.more-btn:hover {
  background: var(--gray-100);
  color: var(--ink-700);
}

.danger {
  color: var(--rose-500);
}

.collab-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
  line-height: 1.3;
}

.lead-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: var(--white);
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.lead-avatar.sm {
  width: 22px;
  height: 22px;
  font-size: 11px;
}

.collab-row .name {
  font-weight: 600;
  color: var(--ink-800);
}

.role-chip {
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  height: 18px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  color: var(--brand-800);
  background: var(--blue-50);
}

.role-chip.muted {
  color: var(--ink-500);
  background: var(--gray-100);
}

.collab-row .dot {
  color: var(--ink-300);
}

.no-doctor {
  color: var(--ink-400, var(--gray-400));
  font-size: 12px;
}

.team-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-patients {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  background: var(--blue-50);
  color: var(--brand-800);
}

.team-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: auto;
  margin-left: -16px;
  margin-right: -16px;
  padding: 10px 16px;
  border-top: 0 solid var(--gray-100);
  font-size: 12px;
}

.foot-link {
  color: var(--ink-500);
  font-weight: 500;
  transition: color 160ms;
}

@media (max-width: 1280px) {
  .summary-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .summary-row {
    grid-template-columns: 1fr;
  }

  .team-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .team-card {
    max-width: none;
  }

  .toolbar-search,
  .toolbar-filter {
    flex: 1 1 100%;
    width: 100%;
  }
}
</style>
