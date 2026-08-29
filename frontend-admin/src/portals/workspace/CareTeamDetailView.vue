<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  careTeamId?: string | null
}

interface CareTeamMember {
  id: string
  memberType: string
  staffId?: string | null
  peopleId?: string | null
  joinedAt: string
}

const route = useRoute()
const router = useRouter()
const teamId = computed(() => String(route.params.teamId || ''))

const pageLoading = ref(false)
const current = ref<CareTeamListItem | null>(null)
const staffList = ref<OrgStaffItem[]>([])
const allPatients = ref<OrgPatientListItem[]>([])
const unassignedPatients = computed(() => allPatients.value.filter((p) => !p.careTeamId))

const members = ref<CareTeamMember[]>([])
const membersLoading = ref(false)
const renaming = ref(false)
const renameName = ref('')
const primarySaving = ref(false)
const primaryForm = ref({
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})
const addingMember = ref(false)
const addStaffId = ref<string | null>(null)
const addPatientId = ref<string | null>(null)

const careManagers = computed(() =>
  staffList.value.filter((s) => s.roles?.includes(StaffRole.CARE_MANAGER)),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

const staffNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const s of staffList.value) map.set(s.staffId, s.displayName)
  return map
})

const patientNameMap = computed(() => {
  const map = new Map<number, string>()
  for (const p of allPatients.value) map.set(p.peopleId, p.displayName)
  return map
})

function formatTime(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

function memberLabel(m: CareTeamMember) {
  if (m.memberType === 'STAFF') {
    return staffNameMap.value.get(m.staffId!) ?? `员工 #${m.staffId}`
  }
  return patientNameMap.value.get(m.peopleId!) ?? `患者 #${m.peopleId}`
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

async function loadPatients() {
  const res = await api<{ data: OrgPatientListItem[] }>('/api/b/v1/org-patients')
  allPatients.value = res.data ?? []
}

async function loadTeam() {
  const res = await api<{ data: CareTeamListItem }>(`/api/b/v1/care-teams/${teamId.value}`)
  current.value = res.data
  renameName.value = res.data.name
  primaryForm.value = {
    primaryCareManagerStaffId: res.data.primaryCareManagerStaffId,
    primaryDoctorStaffId: res.data.primaryDoctorStaffId ?? null,
  }
}

async function loadMembers() {
  membersLoading.value = true
  try {
    const res = await api<{ data: CareTeamMember[] }>(`/api/b/v1/care-teams/${teamId.value}/members`)
    members.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载成员失败')
  } finally {
    membersLoading.value = false
  }
}

async function saveRename() {
  if (!current.value) return
  if (!renameName.value.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  renaming.value = true
  try {
    const res = await api<{ data: CareTeamListItem }>(`/api/b/v1/care-teams/${current.value.id}`, {
      method: 'PUT',
      body: JSON.stringify({ name: renameName.value.trim() }),
    })
    current.value = res.data
    ElMessage.success('名称已更新')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    renaming.value = false
  }
}

async function savePrimary() {
  if (!current.value) return
  if (!primaryForm.value.primaryCareManagerStaffId) {
    ElMessage.warning('主责健管师必填')
    return
  }
  primarySaving.value = true
  try {
    const res = await api<{ data: CareTeamListItem }>(`/api/b/v1/care-teams/${current.value.id}/primary`, {
      method: 'PUT',
      body: JSON.stringify({
        primaryCareManagerStaffId: primaryForm.value.primaryCareManagerStaffId,
        primaryDoctorStaffId: primaryForm.value.primaryDoctorStaffId || null,
      }),
    })
    current.value = res.data
    ElMessage.success('主责已更新')
    await loadMembers()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    primarySaving.value = false
  }
}

async function addStaffMember() {
  if (!addStaffId.value) {
    ElMessage.warning('请选择医护')
    return
  }
  addingMember.value = true
  try {
    await api(`/api/b/v1/care-teams/${teamId.value}/members`, {
      method: 'POST',
      body: JSON.stringify({ memberType: 'STAFF', staffId: addStaffId.value }),
    })
    ElMessage.success('已添加医护')
    addStaffId.value = null
    await Promise.all([loadMembers(), loadTeam()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    addingMember.value = false
  }
}

async function addPatientMember() {
  if (!addPatientId.value) {
    ElMessage.warning('请选择患者')
    return
  }
  addingMember.value = true
  try {
    await api(`/api/b/v1/care-teams/${teamId.value}/members`, {
      method: 'POST',
      body: JSON.stringify({ memberType: 'PATIENT', peopleId: addPatientId.value }),
    })
    ElMessage.success('患者已入组')
    addPatientId.value = null
    await Promise.all([loadMembers(), loadPatients(), loadTeam()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    addingMember.value = false
  }
}

async function removeMember(m: CareTeamMember) {
  try {
    await ElMessageBox.confirm(`确认移除「${memberLabel(m)}」？`, '移除成员', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/care-teams/${teamId.value}/members/${m.id}`, { method: 'DELETE' })
    ElMessage.success('已移除')
    await Promise.all([loadMembers(), loadPatients(), loadTeam()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  }
}

async function deleteTeam() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除健管组「${current.value.name}」？组成员关系将解除，机构患者归属保留。`,
      '删除健管组',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/care-teams/${current.value.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await router.push('/workspace/care-teams')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  if (!teamId.value) {
    await router.replace('/workspace/care-teams')
    return
  }
  pageLoading.value = true
  try {
    await Promise.all([loadStaff(), loadTeam(), loadMembers(), loadPatients()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    await router.replace('/workspace/care-teams')
  } finally {
    pageLoading.value = false
  }
})
</script>

<template>
  <div v-loading="pageLoading">
    <div class="page-title">
      <div>
        <div class="crumb">
          <el-button link type="primary" @click="router.push('/workspace/care-teams')">健管组</el-button>
          <span class="sep">/</span>
          <span>{{ current?.name || '详情' }}</span>
        </div>
        <h1>健管组详情</h1>
        <p>当前机构：{{ getCurrentOrgName() || '-' }} · 编码 {{ current?.teamCode || '-' }}</p>
      </div>
      <div class="actions">
        <el-button @click="router.push('/workspace/care-teams')">返回列表</el-button>
        <el-button type="danger" plain @click="deleteTeam">删除健管组</el-button>
      </div>
    </div>

    <template v-if="current">
      <el-card shadow="never" class="block">
        <h3>基本信息</h3>
        <div class="row">
          <el-input v-model="renameName" style="max-width: 360px" />
          <el-button type="primary" :loading="renaming" @click="saveRename">保存名称</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <h3>主责设置</h3>
        <el-form label-width="100px" style="max-width: 480px">
          <el-form-item label="主责健管师">
            <el-select v-model="primaryForm.primaryCareManagerStaffId" filterable style="width: 100%">
              <el-option
                v-for="s in careManagers"
                :key="s.staffId"
                :label="s.displayName"
                :value="s.staffId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="主责医生">
            <el-select v-model="primaryForm.primaryDoctorStaffId" clearable filterable style="width: 100%">
              <el-option
                v-for="s in doctors"
                :key="s.staffId"
                :label="s.displayName"
                :value="s.staffId"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="primarySaving" @click="savePrimary">保存主责</el-button>
      </el-card>

      <el-card shadow="never" class="block">
        <h3>成员（{{ members.length }}）</h3>
        <el-table v-loading="membersLoading" :data="members" stripe border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.memberType === 'STAFF' ? '医护' : '患者' }}</template>
          </el-table-column>
          <el-table-column label="姓名" min-width="140">
            <template #default="{ row }">{{ memberLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="加入时间" v-bind="TABLE_COL.datetime">
            <template #default="{ row }">{{ formatTime(row.joinedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="removeMember(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="add-row">
          <el-select v-model="addStaffId" clearable filterable placeholder="添加医护" style="width: 260px">
            <el-option
              v-for="s in staffList"
              :key="s.staffId"
              :label="s.displayName"
              :value="s.staffId"
            />
          </el-select>
          <el-button :loading="addingMember" @click="addStaffMember">添加医护</el-button>
        </div>
        <div class="add-row">
          <el-select v-model="addPatientId" clearable filterable placeholder="添加未入组患者" style="width: 260px">
            <el-option
              v-for="p in unassignedPatients"
              :key="p.peopleId"
              :label="p.displayName"
              :value="p.peopleId"
            />
          </el-select>
          <el-button :loading="addingMember" @click="addPatientMember">患者入组</el-button>
        </div>
      </el-card>
    </template>
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
.page-title h1 { margin: 8px 0 0; font-size: 16px; }
.page-title p { margin: 6px 0 0; color: var(--admin-muted); font-size: 12px; }
.crumb { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--admin-muted); }
.sep { margin: 0 2px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.block { margin-bottom: 16px; }
.block h3 { margin: 0 0 12px; font-size: 14px; }
.row { display: flex; gap: 8px; align-items: center; }
.add-row { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
</style>
