<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
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
const loading = ref(false)
const list = ref<CareTeamListItem[]>([])
const keyword = ref('')
const staffList = ref<OrgStaffItem[]>([])

const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({
  name: '',
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})

const careManagers = computed(() =>
  staffList.value.filter((s) => s.roles?.includes(StaffRole.CARE_MANAGER)),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

const totalMembers = computed(() => list.value.reduce((sum, t) => sum + (t.memberCount || 0), 0))
const avgMembers = computed(() => {
  if (!list.value.length) return 0
  return Math.round(totalMembers.value / list.value.length)
})
const careManagerCount = computed(() => {
  const ids = new Set(
    list.value.map((t) => t.primaryCareManagerStaffId).filter(Boolean),
  )
  return ids.size || careManagers.value.length
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
  createForm.value = {
    name: '',
    primaryCareManagerStaffId: null,
    primaryDoctorStaffId: null,
  }
  createVisible.value = true
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
  if (cmd === 'detail') openDetail(row)
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
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <div class="care-teams">
    <div class="page-title">
      <div>
        <h1>健管组</h1>
        <p>
          共 <strong class="em">{{ list.length }}</strong> 个健管组
          · 在管 <strong class="em">{{ totalMembers }}</strong> 位成员
          <template v-if="list.length"> · 平均每组 {{ avgMembers }} 人</template>
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
          <div class="stat-label">在管成员</div>
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
      <div class="stat-card">
        <div class="stat-icon tone-violet">均</div>
        <div>
          <div class="stat-label">平均组规模</div>
          <div class="stat-val">{{ list.length ? avgMembers : '—' }}</div>
        </div>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索健管组名称 / 编码"
          clearable
          class="toolbar-search"
          @keyup.enter="load"
        />
        <el-button type="primary" @click="load">查询</el-button>
      </div>
    </el-card>

    <div v-loading="loading" class="team-grid">
      <article
        v-for="row in list"
        :key="row.id"
        class="team-card"
        @click="openDetail(row)"
      >
        <div class="team-head">
          <div>
            <div class="team-code">{{ row.teamCode || '—' }}</div>
            <div class="team-name">{{ row.name }}</div>
          </div>
          <el-dropdown trigger="click" @command="(cmd: string) => onCardCommand(cmd, row)">
            <button type="button" class="more-btn" aria-label="更多操作" @click.stop>
              ···
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="detail">详情</el-dropdown-item>
                <el-dropdown-item divided command="delete">
                  <span class="danger">删除</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <div class="team-leads">
          <div class="lead-avatar" :style="avatarStyle(row.primaryCareManagerName)">
            {{ avatarChar(row.primaryCareManagerName) }}
          </div>
          <div class="lead-info">
            <div class="name">{{ row.primaryCareManagerName || '未指定' }}</div>
            <div class="role">主责健管师</div>
          </div>
          <div v-if="row.primaryDoctorName" class="lead-doctor">
            <div class="lead-avatar sm" :style="avatarStyle(row.primaryDoctorName)">
              {{ avatarChar(row.primaryDoctorName) }}
            </div>
            <div class="lead-info">
              <div class="name">{{ row.primaryDoctorName }}</div>
              <div class="role">主责医生</div>
            </div>
          </div>
        </div>

        <div class="team-stats">
          <div class="t-stat">
            <div class="v">{{ row.memberCount ?? 0 }}</div>
            <div class="l">成员</div>
          </div>
          <div class="t-stat">
            <div class="v">{{ row.primaryDoctorName ? '有' : '—' }}</div>
            <div class="l">主责医生</div>
          </div>
          <div class="t-stat">
            <div class="v">—</div>
            <div class="l">依从率</div>
          </div>
        </div>

        <div class="team-foot">
          <span>创建于 {{ formatDate(row.createdAt) }}</span>
          <el-button link type="primary" @click.stop="openDetail(row)">查看详情</el-button>
        </div>
      </article>

      <el-empty
        v-if="!loading && list.length === 0"
        class="empty-span"
        description="暂无健管组，点击右上角新建"
      />
    </div>

    <el-dialog v-model="createVisible" title="新建健管组" width="520px">
      <el-form label-width="120px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="主责健管师" required>
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
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
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

.summary-row {
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

.toolbar-search {
  flex: 1;
  min-width: 220px;
  max-width: 360px;
}

.team-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
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
  padding: 18px;
  cursor: pointer;
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

.team-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 8px;
}

.team-code {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-400);
  letter-spacing: 0.6px;
}

.team-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  margin: 2px 0 0;
  line-height: 1.35;
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

.team-leads {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-top: 1px solid var(--ink-100);
  border-bottom: 1px solid var(--ink-100);
  margin-bottom: 12px;
  flex-wrap: wrap;
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

.lead-avatar.sm {
  width: 24px;
  height: 24px;
  font-size: 10px;
}

.lead-info .name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-800);
  line-height: 1.3;
}

.lead-info .role {
  font-size: 11px;
  color: var(--ink-500);
}

.lead-doctor {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
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
  background: var(--ink-50, #f8fafc);
  border-radius: 8px;
}

.t-stat .v {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
}

.t-stat .l {
  font-size: 10.5px;
  color: var(--ink-400);
  margin-top: 2px;
}

.team-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--ink-400);
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
}
</style>
