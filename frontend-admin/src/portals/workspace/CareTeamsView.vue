<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { StaffRole } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'

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

function formatTime(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
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
  <div>
    <div class="page-title">
      <div>
        <h1>健管组</h1>
        <p>当前机构：{{ getCurrentOrgName() || '-' }} · 网格化管理医护与患者</p>
      </div>
      <el-button @click="router.push('/workspace/orgs')">切换机构</el-button>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="组名 / 编码"
          clearable
          style="width: 200px"
          @keyup.enter="load"
        />
        <el-button @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建健管组</el-button>
      </div>

      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column prop="teamCode" label="编码" v-bind="TABLE_COL.bizCode" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="主责健管师" min-width="120">
          <template #default="{ row }">{{ row.primaryCareManagerName || '-' }}</template>
        </el-table-column>
        <el-table-column label="主责医生" min-width="120">
          <template #default="{ row }">{{ row.primaryDoctorName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="90" />
        <el-table-column label="创建时间" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" v-bind="TABLE_COL.actionsMd">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="danger" @click="deleteTeam(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
.page-title {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  gap: 12px;
}
.page-title h1 { margin: 0; font-size: 16px; }
.page-title p { margin: 6px 0 0; color: var(--admin-muted); font-size: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
</style>
