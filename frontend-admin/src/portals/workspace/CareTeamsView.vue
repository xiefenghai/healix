<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName, getStaffId } from '../../shared/http'
import { StaffRole } from '../../shared/enums'

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
const createForm = ref({
  name: '',
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})

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

const AVATAR_GRADIENTS = [
  'linear-gradient(135deg,#3B82F6,#1D4ED8)',
  'linear-gradient(135deg,#8B5CF6,#6D28D9)',
  'linear-gradient(135deg,#F59E0B,#EA580C)',
  'linear-gradient(135deg,#10B981,#0F766E)',
  'linear-gradient(135deg,#EC4899,#BE185D)',
  'linear-gradient(135deg,#06B6D4,#0E7490)',
]

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
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请填写健管组名称')
    return
  }
  if (!createForm.value.primaryCareManagerStaffId) {
    ElMessage.warning('请选择主责健管师')
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
  else if (cmd === 'delete') void deleteTeam(row)
}

async function deleteTeam(row: CareTeamListItem) {
  try {
    await ElMessageBox.confirm(
      `确认删除健管组「${row.name}」？组成员关系将解除，机构患者归属保留。`,
      '删除健管组',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/care-teams/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
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
      <div class="stat-card">
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

    <el-card shadow="never" class="filter-card">
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
    </el-card>

    <div v-loading="loading" class="team-grid">
      <article
        v-for="row in displayList"
        :key="row.id"
        class="team-card"
        @click="openDetail(row)"
      >
        <div class="team-head">
          <div class="team-title">
            <div class="team-name">{{ row.name }}</div>
            <div class="team-meta">创建于 {{ formatDate(row.createdAt) }}</div>
          </div>
          <div class="head-actions" @click.stop>
            <el-dropdown trigger="click" @command="(cmd: string) => onCardCommand(cmd, row)">
              <button type="button" class="more-btn" aria-label="更多操作">···</button>
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

        <div class="collab-row">
          <div class="avatar-stack">
            <div
              class="lead-avatar"
              :style="avatarStyle(row.primaryCareManagerName)"
              :title="row.primaryCareManagerName || '主责健管师'"
            >
              {{ avatarChar(row.primaryCareManagerName) }}
            </div>
            <div
              v-if="hasDoctor(row)"
              class="lead-avatar"
              :style="avatarStyle(row.primaryDoctorName || undefined)"
              :title="row.primaryDoctorName || '主责医生'"
            >
              {{ avatarChar(row.primaryDoctorName || undefined) }}
            </div>
          </div>
          <div class="collab-text">
            <span class="label">主责</span>
            <span class="name">{{ row.primaryCareManagerName || '未指定' }}</span>
            <template v-if="hasDoctor(row)">
              <span class="dot">·</span>
              <span class="name">{{ row.primaryDoctorName }}</span>
            </template>
            <template v-else>
              <span class="dot">·</span>
              <span class="muted">暂无医生</span>
            </template>
          </div>
        </div>

        <div class="team-tags">
          <span class="tag">{{ row.memberCount ?? 0 }} 位患者</span>
        </div>

        <div class="team-foot">
          <span class="foot-hint">点击查看详情</span>
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

    <el-dialog v-model="createVisible" title="新建健管组" width="520px">
      <el-form label-width="120px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="40" show-word-limit placeholder="例如：减重管理一组" />
        </el-form-item>
        <el-form-item required>
          <template #label>
            <span>主责健管师</span>
            <span
              v-if="createForm.primaryCareManagerStaffId && createForm.primaryCareManagerStaffId === getStaffId()"
              class="cm-default-hint"
            >（默认：当前登录账号）</span>
          </template>
          <el-select v-model="createForm.primaryCareManagerStaffId" filterable style="width: 100%">
            <el-option
              v-for="s in careManagers"
              :key="s.staffId"
              :label="s.displayName"
              :value="s.staffId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="主责医生">
          <el-select v-model="createForm.primaryDoctorStaffId" clearable filterable style="width: 100%">
            <el-option v-for="s in doctors" :key="s.staffId" :label="s.displayName" :value="s.staffId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确定创建</el-button>
      </template>
    </el-dialog>
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

.cm-default-hint {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-400, #9ca3af);
}

.em.warn,
.stat-val.warn {
  color: #dc2626;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
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
  border-color: #fca5a5;
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.08);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 15px;
  font-weight: 700;
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

.stat-icon.tone-rose {
  background: #fef2f2;
  color: #dc2626;
}

.stat-label {
  font-size: 12px;
  color: var(--ink-500);
  font-weight: 500;
}

.stat-val {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-900);
  margin-top: 2px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-search {
  width: 300px;
  max-width: 100%;
  flex: 0 1 300px;
}

.toolbar-filter {
  width: 160px;
}

.team-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  min-height: 120px;
}

.empty-span {
  grid-column: 1 / -1;
  padding: 48px 0;
}

.team-card {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 16px 16px 14px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition:
    border-color 180ms,
    box-shadow 180ms,
    transform 180ms;
}

.team-card:hover {
  border-color: var(--brand-500);
  box-shadow: 0 4px 16px -4px rgba(44, 126, 248, 0.12);
  transform: translateY(-2px);
}

.team-card:hover .foot-link {
  color: var(--brand-500, #2c7ef8);
}

.team-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.team-title {
  min-width: 0;
}

.team-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.35;
  word-break: break-word;
}

.team-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.head-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.more-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--ink-400);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  letter-spacing: 1px;
}

.more-btn:hover {
  background: var(--ink-100);
  color: var(--ink-700);
}

.danger {
  color: var(--rose-500, #ef4444);
}

.collab-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.avatar-stack {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.avatar-stack .lead-avatar + .lead-avatar {
  margin-left: -8px;
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
  border: 2px solid #fff;
  box-sizing: border-box;
}

.collab-text {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  min-width: 0;
  font-size: 13px;
  line-height: 1.35;
  color: var(--ink-700);
}

.collab-text .label {
  color: var(--ink-500);
  font-size: 12px;
}

.collab-text .name {
  font-weight: 600;
  color: var(--ink-800);
}

.collab-text .dot {
  color: var(--ink-300);
}

.collab-text .muted {
  color: var(--ink-400);
  font-size: 12px;
}

.team-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  background: var(--ink-50, #f8fafc);
  color: var(--ink-700);
  border: 1px solid var(--ink-100);
}

.team-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 2px;
  font-size: 12px;
}

.foot-hint {
  color: var(--ink-300);
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

  .team-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .summary-row,
  .team-grid {
    grid-template-columns: 1fr;
  }

  .toolbar-search {
    flex: 1 1 100%;
    width: 100%;
  }
}
</style>
