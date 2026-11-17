<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatRole, StaffRole } from '../../shared/enums'
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
const patientKeyword = ref('')
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
  staffList.value.filter(
    (s) => s.roles?.includes(StaffRole.CARE_MANAGER) || s.roles?.includes(StaffRole.TENANT_ADMIN),
  ),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

const staffNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const s of staffList.value) map.set(s.staffId, s.displayName)
  return map
})

const patientNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const p of allPatients.value) map.set(p.peopleId, p.displayName)
  return map
})

const staffMembers = computed(() => members.value.filter((m) => m.memberType === 'STAFF'))

const patientMembers = computed(() => {
  const rows = members.value.filter((m) => m.memberType === 'PATIENT')
  const q = patientKeyword.value.trim().toLowerCase()
  if (!q) return rows
  return rows.filter((m) => memberLabel(m).toLowerCase().includes(q))
})

function formatJoinedParts(iso: string) {
  if (!iso) return { date: '-', time: '' }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return { date: '-', time: '' }
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
  }
}

function memberLabel(m: CareTeamMember) {
  if (m.memberType === 'STAFF') {
    return staffNameMap.value.get(m.staffId!) ?? `员工 #${m.staffId}`
  }
  return patientNameMap.value.get(m.peopleId!) ?? `患者 #${m.peopleId}`
}

/** 组成员类型：员工侧按岗位展示（健管师/医生等），不再笼统写「医护」 */
function memberTypeLabel(m: CareTeamMember) {
  if (m.memberType !== 'STAFF') return '患者'
  const staff = staffList.value.find((s) => s.staffId === m.staffId)
  const roles = staff?.roles ?? []
  if (roles.includes(StaffRole.CARE_MANAGER)) return formatRole(StaffRole.CARE_MANAGER)
  if (roles.includes(StaffRole.DOCTOR)) return formatRole(StaffRole.DOCTOR)
  if (roles.includes(StaffRole.NUTRITIONIST)) return formatRole(StaffRole.NUTRITIONIST)
  if (roles.length > 0) return formatRole(roles[0])
  return formatRole(StaffRole.CARE_MANAGER)
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
  const [unassigned, inTeam] = await Promise.all([
    api<{ data: { items: OrgPatientListItem[] } }>(
      '/api/b/v1/org-patients?unassigned=true&page=1&size=100',
    ),
    api<{ data: { items: OrgPatientListItem[] } }>(
      `/api/b/v1/org-patients?careTeamId=${encodeURIComponent(teamId.value)}&page=1&size=100`,
    ),
  ])
  const map = new Map<string, OrgPatientListItem>()
  for (const row of [...(unassigned.data?.items ?? []), ...(inTeam.data?.items ?? [])]) {
    map.set(row.peopleId, row)
  }
  allPatients.value = [...map.values()]
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
    ElMessage.warning('请选择健管师')
    return
  }
  addingMember.value = true
  try {
    await api(`/api/b/v1/care-teams/${teamId.value}/members`, {
      method: 'POST',
      body: JSON.stringify({ memberType: 'STAFF', staffId: addStaffId.value }),
    })
    ElMessage.success('已添加健管师')
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

const deleteDialogOpen = ref(false)
const deleteTarget = ref<DeleteCareTeamTarget | null>(null)

function deleteTeam() {
  if (!current.value) return
  deleteTarget.value = { id: current.value.id, name: current.value.name }
  deleteDialogOpen.value = true
}

async function onTeamDeleted() {
  await router.push('/workspace/care-teams')
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
  <div v-loading="pageLoading" class="team-detail">
    <div class="page-title">
      <div>
        <div class="crumb">
          <el-button link type="primary" @click="router.push('/workspace/care-teams')">健管组</el-button>
          <span class="sep">/</span>
          <span>{{ current?.name || '详情' }}</span>
        </div>
        <h1>健管组详情</h1>
        <p>当前机构：{{ getCurrentOrgName() || '-' }}</p>
      </div>
      <div class="actions">
        <el-button class="btn-back" @click="router.push('/workspace/care-teams')">返回列表</el-button>
        <el-button class="btn-delete" @click="deleteTeam">删除健管组</el-button>
      </div>
    </div>

    <template v-if="current">
      <section class="block">
        <h3>基本信息</h3>
        <div class="name-row">
          <el-input
            v-model="renameName"
            class="name-input"
            maxlength="40"
            show-word-limit
            placeholder="健管组名称"
            @keyup.enter="saveRename"
          />
          <el-button class="btn-secondary" :loading="renaming" @click="saveRename">保存名称</el-button>
        </div>
      </section>

      <section class="block">
        <h3>主责设置</h3>
        <div class="primary-row">
          <div class="primary-field">
            <label class="field-label">主责健管师</label>
            <el-select
              v-model="primaryForm.primaryCareManagerStaffId"
              filterable
              class="primary-select"
              placeholder="选择主责健管师"
            >
              <el-option
                v-for="s in careManagers"
                :key="s.staffId"
                :label="s.displayName"
                :value="s.staffId"
              />
            </el-select>
          </div>
          <div class="primary-field">
            <label class="field-label">主责医生</label>
            <el-select
              v-model="primaryForm.primaryDoctorStaffId"
              clearable
              filterable
              class="primary-select"
              placeholder="可稍后指定"
            >
              <el-option
                v-for="s in doctors"
                :key="s.staffId"
                :label="s.displayName"
                :value="s.staffId"
              />
            </el-select>
          </div>
          <div class="primary-action">
            <el-button type="primary" :loading="primarySaving" @click="savePrimary">保存主责</el-button>
          </div>
        </div>
      </section>

      <section class="block">
        <h3>成员（{{ members.length }}）</h3>
        <div v-loading="membersLoading" class="members-split">
          <div class="member-col">
            <div class="col-head">
              <div class="col-title">健管师 · {{ staffMembers.length }}</div>
            </div>
            <div class="table-wrap">
              <el-table
                :data="staffMembers"
                class="members-table"
                stripe
                empty-text="暂无健管师"
              >
                <el-table-column label="岗位" width="80" align="center" header-align="center">
                  <template #default="{ row }">
                    <span class="type-pill staff">{{ memberTypeLabel(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="姓名"
                  min-width="72"
                  align="left"
                  header-align="left"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">{{ memberLabel(row) }}</template>
                </el-table-column>
                <el-table-column
                  label="加入时间"
                  width="180"
                  align="right"
                  header-align="right"
                  class-name="col-joined"
                >
                  <template #default="{ row }">
                    <div class="joined-at">
                      <span class="joined-date">{{ formatJoinedParts(row.joinedAt).date }}</span>
                      <span class="joined-time">{{ formatJoinedParts(row.joinedAt).time }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="操作"
                  width="60"
                  align="center"
                  header-align="center"
                  class-name="col-action"
                >
                  <template #default="{ row }">
                    <button type="button" class="remove-btn" @click="removeMember(row)">移除</button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div class="add-row">
              <el-select
                v-model="addStaffId"
                clearable
                filterable
                placeholder="添加健管师"
                class="add-select"
              >
                <el-option
                  v-for="s in careManagers"
                  :key="s.staffId"
                  :label="s.displayName"
                  :value="s.staffId"
                />
              </el-select>
              <el-button class="btn-secondary" :loading="addingMember" @click="addStaffMember">
                添加
              </el-button>
            </div>
          </div>

          <div class="member-col">
            <div class="col-head">
              <div class="col-title">患者 · {{ patientMembers.length }}</div>
              <el-input
                v-model="patientKeyword"
                clearable
                class="patient-search"
                placeholder="搜索患者"
              />
            </div>
            <div class="table-wrap table-wrap--scroll">
              <el-table
                :data="patientMembers"
                class="members-table"
                stripe
                empty-text="暂无患者"
                :max-height="480"
              >
                <el-table-column
                  label="姓名"
                  min-width="72"
                  align="left"
                  header-align="left"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">{{ memberLabel(row) }}</template>
                </el-table-column>
                <el-table-column
                  label="加入时间"
                  width="180"
                  align="right"
                  header-align="right"
                  class-name="col-joined"
                >
                  <template #default="{ row }">
                    <div class="joined-at">
                      <span class="joined-date">{{ formatJoinedParts(row.joinedAt).date }}</span>
                      <span class="joined-time">{{ formatJoinedParts(row.joinedAt).time }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="操作"
                  width="60"
                  align="center"
                  header-align="center"
                  class-name="col-action"
                >
                  <template #default="{ row }">
                    <button type="button" class="remove-btn" @click="removeMember(row)">移除</button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div class="add-row">
              <el-select
                v-model="addPatientId"
                clearable
                filterable
                placeholder="添加未入组患者"
                class="add-select"
              >
                <el-option
                  v-for="p in unassignedPatients"
                  :key="p.peopleId"
                  :label="p.displayName"
                  :value="p.peopleId"
                />
              </el-select>
              <el-button class="btn-secondary" :loading="addingMember" @click="addPatientMember">
                入组
              </el-button>
            </div>
          </div>
        </div>
      </section>
    </template>

    <DeleteCareTeamDialog
      v-model="deleteDialogOpen"
      :team="deleteTarget"
      @deleted="onTeamDeleted"
    />
  </div>
</template>

<style scoped>
.team-detail {
  width: 100%;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--admin-muted);
}

.sep {
  margin: 0 2px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.btn-back {
  height: 32px;
  border: 1px solid var(--gray-200);
  background: var(--gray-50);
  color: var(--ink-750);
}

.btn-back:hover {
  border-color: var(--gray-300);
  background: var(--gray-100);
  color: var(--ink-950);
}

.btn-delete {
  height: 32px;
  border: none;
  background: var(--rose-100);
  color: var(--rose-600);
}

.btn-delete:hover {
  background: var(--rose-150);
  color: var(--rose-700);
}

.btn-secondary {
  height: 32px;
  border: 1px solid var(--brand-200);
  border-radius: 6px;
  background: var(--blue-50);
  color: var(--brand-800);
}

.btn-secondary:hover {
  border-color: var(--blue-300);
  background: var(--blue-100);
  color: var(--brand-900);
}

.block {
  margin-bottom: 16px;
  padding: 24px;
  background: var(--white);
  border: 1px solid var(--gray-200);
  border-radius: 8px;
}

.block h3 {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink-900, var(--ink-950));
}

.name-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.name-input {
  width: 100%;
  max-width: 400px;
}

.primary-row {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  flex-wrap: wrap;
}

.primary-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600, var(--ink-650));
  line-height: 1.2;
}

.primary-select {
  width: 240px;
}

.primary-select :deep(.el-select__wrapper) {
  min-height: 36px;
  height: 36px;
}

.primary-action {
  display: flex;
  align-items: center;
  height: 36px;
}

.primary-action :deep(.el-button) {
  height: 36px;
  padding: 0 18px;
}

.members-split {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(320px, 1.35fr);
  gap: 20px;
  align-items: start;
}

.member-col {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  background: var(--white);
}

.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  min-height: 32px;
}

.col-title {
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-800, var(--gray-800));
}

.patient-search {
  width: 200px;
  flex-shrink: 0;
}

.patient-search :deep(.el-input__wrapper) {
  height: 32px;
  min-height: 32px;
  padding: 0 10px;
  box-shadow: none !important;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: var(--white);
  transition:
    border-color 140ms,
    box-shadow 140ms;
}

.patient-search :deep(.el-input__wrapper:hover) {
  border-color: var(--gray-300);
  box-shadow: none !important;
}

.patient-search :deep(.el-input__wrapper.is-focus),
.patient-search :deep(.el-input__wrapper:focus-within) {
  border-color: var(--blue-300);
  box-shadow: 0 0 0 2px var(--ink-a12) !important;
}

.patient-search :deep(.el-input__inner) {
  height: 30px;
  line-height: 30px;
}

.table-wrap {
  min-width: 0;
}

.table-wrap--scroll {
  padding-bottom: 4px;
}

.members-table {
  width: 100%;
  --el-table-row-hover-bg-color: var(--gray-100);
  --el-table-striped-bg-color: var(--gray-50);
  --el-table-border-color: var(--gray-100);
}

/* 去掉 el-table 底部那根异常粗线 */
.members-table :deep(.el-table__inner-wrapper::before),
.members-table :deep(.el-table__border-left-patch),
.members-table :deep(.el-table--border::after) {
  display: none !important;
}

.members-table :deep(.el-table__header th) {
  height: 40px;
  background: var(--gray-50) !important;
  color: var(--gray-500);
  font-weight: 600;
  border-bottom: 0 solid var(--gray-100);
}

.members-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  overflow: visible;
  text-overflow: clip;
  line-height: 1.35;
}

.members-table :deep(.el-table__body .cell) {
  line-height: 1.35;
}

.members-table :deep(.el-table__cell) {
  padding: 8px 8px;
}

.members-table :deep(.el-table__body tr:last-child td) {
  border-bottom: none;
}

.members-table :deep(.col-joined .cell) {
  overflow: visible !important;
  text-overflow: clip !important;
  white-space: normal !important;
}

.members-table :deep(.col-action) {
  padding-left: 4px !important;
  padding-right: 4px !important;
}

.members-table :deep(.col-action .cell) {
  overflow: visible !important;
  text-overflow: clip !important;
  white-space: nowrap !important;
  padding: 0 !important;
  text-align: center;
}

.members-table :deep(.el-table__body-wrapper) {
  padding-bottom: 8px;
}

.joined-at {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  line-height: 1.25;
}

.joined-date {
  font-size: 13px;
  color: var(--ink-700, var(--ink-750));
  white-space: nowrap;
}

.joined-time {
  font-size: 12px;
  color: var(--ink-400, var(--gray-400));
  white-space: nowrap;
}

.type-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  white-space: nowrap;
}

.type-pill.staff {
  background: var(--blue-50);
  color: var(--brand-800);
}

.remove-btn {
  border: none;
  background: transparent;
  padding: 0;
  font: inherit;
  font-size: 13px;
  color: var(--gray-400);
  cursor: pointer;
  white-space: nowrap;
  transition: color 140ms;
}

.remove-btn:hover {
  color: var(--rose-600);
}

.add-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
  margin-top: 24px;
  padding: 0;
  border: none;
  box-shadow: none;
}

.add-select {
  flex: 1;
  min-width: 0;
}

.add-select :deep(.el-select__wrapper) {
  width: 100%;
}

@media (max-width: 960px) {
  .members-split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .name-input,
  .primary-select,
  .patient-search,
  .add-select {
    width: 100%;
    max-width: none;
  }

  .primary-action {
    width: 100%;
    height: auto;
  }

  .primary-action :deep(.el-button) {
    width: 100%;
  }
}
</style>
