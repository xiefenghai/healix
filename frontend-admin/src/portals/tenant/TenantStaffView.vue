<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { formatRole, formatStaffStatus, staffStatusTagType } from '../ops/labels'
import { ASSIGNABLE_STAFF_ROLE_OPTIONS, ENABLE_STATUS_OPTIONS, StaffRole, STAFF_ROLE_FILTER_OPTIONS } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'
import type { PageResult, StaffAccountListItem } from '../ops/types'

interface OrgItem {
  id: string
  name: string
  orgType: string
  status: string
}

const loading = ref(false)
const list = ref<StaffAccountListItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const roleCode = ref('')
const orgs = ref<OrgItem[]>([])

const registerVisible = ref(false)
const registering = ref(false)
const registerForm = ref({
  username: '',
  password: '',
  displayName: '',
  mobile: '',
  title: '',
  roleCode: '' as string,
  orgIds: [] as string[],
})

const roleVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref<StaffAccountListItem | null>(null)
const roleForm = ref({ roleCode: '' as string })

const orgVisible = ref(false)
const orgSaving = ref(false)
const orgTarget = ref<StaffAccountListItem | null>(null)
const orgForm = ref({ orgIds: [] as string[] })

const editVisible = ref(false)
const editSaving = ref(false)
const editTarget = ref<StaffAccountListItem | null>(null)
const editForm = ref({
  displayName: '',
  mobile: '',
  title: '',
  status: 'ACTIVE',
  password: '',
})

const orgNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const o of orgs.value) map.set(String(o.id), o.name)
  return map
})

const orgTransferData = computed(() =>
  orgs.value.map((o) => ({
    key: String(o.id),
    label: o.name,
  })),
)

function filterOrgByName(query: string, item: { label: string }) {
  return item.label.toLowerCase().includes(query.trim().toLowerCase())
}

function formatTime(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

function orgNames(ids?: Array<string | number> | null) {
  if (!ids?.length) return '-'
  return ids.map((id) => orgNameMap.value.get(String(id)) ?? String(id)).join('、')
}

async function loadOrgs() {
  const res = await api<{ data: OrgItem[] }>('/api/b/v1/orgs')
  orgs.value = res.data ?? []
}

async function loadStaff() {
  loading.value = true
  try {
    const q = new URLSearchParams({
      page: String(page.value),
      pageSize: String(pageSize.value),
    })
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    if (roleCode.value) q.set('roleCode', roleCode.value)
    const res = await api<{ data: PageResult<StaffAccountListItem> }>(`/api/b/v1/staff-accounts?${q}`)
    list.value = res.data.items ?? []
    total.value = res.data.total ?? 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openRegister() {
  registerForm.value = {
    username: '',
    password: '',
    displayName: '',
    mobile: '',
    title: '',
    roleCode: '',
    orgIds: [],
  }
  registerVisible.value = true
}

async function submitRegister() {
  if (!registerForm.value.username.trim() || !registerForm.value.password.trim()) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  if (!registerForm.value.displayName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (registerForm.value.password.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  registering.value = true
  try {
    await api('/api/b/v1/staff-accounts', {
      method: 'POST',
      body: JSON.stringify({
        username: registerForm.value.username.trim(),
        password: registerForm.value.password,
        displayName: registerForm.value.displayName.trim(),
        mobile: registerForm.value.mobile || undefined,
        title: registerForm.value.title || undefined,
        roleCode: registerForm.value.roleCode || undefined,
        orgIds: registerForm.value.orgIds,
      }),
    })
    ElMessage.success('账号已注册')
    registerVisible.value = false
    await loadStaff()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '注册失败')
  } finally {
    registering.value = false
  }
}

function openEdit(row: StaffAccountListItem) {
  editTarget.value = row
  editForm.value = {
    displayName: row.displayName ?? '',
    mobile: row.mobile ?? '',
    title: row.title ?? '',
    status: row.status || 'ACTIVE',
    password: '',
  }
  editVisible.value = true
}

async function submitEdit() {
  if (!editTarget.value) return
  if (!editForm.value.displayName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (editForm.value.password && editForm.value.password.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  editSaving.value = true
  try {
    await api(`/api/b/v1/staff/${editTarget.value.staffId}`, {
      method: 'PUT',
      body: JSON.stringify({
        displayName: editForm.value.displayName.trim(),
        mobile: editForm.value.mobile || undefined,
        title: editForm.value.title || undefined,
        status: editForm.value.status,
        password: editForm.value.password || undefined,
      }),
    })
    ElMessage.success('账号已更新')
    editVisible.value = false
    await loadStaff()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    editSaving.value = false
  }
}

function openRole(row: StaffAccountListItem) {
  roleTarget.value = row
  if (row.roles?.includes(StaffRole.CARE_MANAGER)) roleForm.value.roleCode = StaffRole.CARE_MANAGER
  else if (row.roles?.includes(StaffRole.DOCTOR)) roleForm.value.roleCode = StaffRole.DOCTOR
  else if (row.roles?.includes(StaffRole.TENANT_OPERATOR))
    roleForm.value.roleCode = StaffRole.TENANT_OPERATOR
  else roleForm.value.roleCode = ''
  roleVisible.value = true
}

async function submitRole() {
  if (!roleTarget.value) return
  roleSaving.value = true
  try {
    await api(`/api/b/v1/staff/${roleTarget.value.staffId}/roles`, {
      method: 'PUT',
      body: JSON.stringify({ roleCode: roleForm.value.roleCode || null }),
    })
    ElMessage.success('角色已更新')
    roleVisible.value = false
    await loadStaff()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    roleSaving.value = false
  }
}

function openOrgs(row: StaffAccountListItem) {
  orgTarget.value = row
  orgForm.value.orgIds = (row.orgIds ?? []).map(String)
  orgVisible.value = true
}

async function submitOrgs() {
  if (!orgTarget.value) return
  orgSaving.value = true
  try {
    await api(`/api/b/v1/staff/${orgTarget.value.staffId}/orgs`, {
      method: 'PUT',
      body: JSON.stringify({ orgIds: orgForm.value.orgIds }),
    })
    ElMessage.success('机构权限已更新')
    orgVisible.value = false
    await loadStaff()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    orgSaving.value = false
  }
}

onMounted(async () => {
  try {
    await Promise.all([loadOrgs(), loadStaff()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <div>
    <div class="page-title">
      <h1>账号与权限</h1>
      <p>注册本租户员工，分配健管师/医生/运营角色，并设置可访问机构</p>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="用户名 / 姓名"
          clearable
          style="width: 180px"
          @keyup.enter="page = 1; loadStaff()"
        />
        <el-select v-model="roleCode" placeholder="角色" clearable style="width: 140px">
          <el-option
            v-for="opt in STAFF_ROLE_FILTER_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button @click="page = 1; loadStaff()">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openRegister">注册账号</el-button>
      </div>

      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column label="角色" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" class="role-tag">{{ formatRole(r) }}</el-tag>
            <span v-if="!row.roles?.length" class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="可访问机构" min-width="180">
          <template #default="{ row }">
            <span v-if="row.roles?.includes(StaffRole.TENANT_ADMIN)" class="muted">全部机构（管理员）</span>
            <span v-else>{{ orgNames(row.orgIds) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" v-bind="TABLE_COL.status">
          <template #default="{ row }">
            <el-tag :type="staffStatusTagType(row.status)" size="small" effect="plain">
              {{ formatStaffStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" v-bind="TABLE_COL.actionsLg">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              type="primary"
              :disabled="row.roles?.includes(StaffRole.TENANT_ADMIN)"
              @click="openRole(row)"
            >
              设角色
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="row.roles?.includes(StaffRole.TENANT_ADMIN)"
              @click="openOrgs(row)"
            >
              机构权限
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadStaff"
          @size-change="page = 1; loadStaff()"
        />
      </div>
    </el-card>

    <el-dialog v-model="registerVisible" title="注册账号" width="520px">
      <el-form label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="registerForm.username" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="registerForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="registerForm.displayName" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="registerForm.mobile" />
        </el-form-item>
        <el-form-item label="职务">
          <el-input v-model="registerForm.title" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="registerForm.roleCode" clearable placeholder="可选" style="width: 100%">
            <el-option
              v-for="opt in ASSIGNABLE_STAFF_ROLE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="可访问机构">
          <el-select v-model="registerForm.orgIds" multiple clearable placeholder="可选" style="width: 100%">
            <el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="registering" @click="submitRegister">注册</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑账号" width="520px">
      <p v-if="editTarget" class="hint">用户名：{{ editTarget.username }}（不可修改）</p>
      <el-form label-width="100px">
        <el-form-item label="姓名" required>
          <el-input v-model="editForm.displayName" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="editForm.mobile" />
        </el-form-item>
        <el-form-item label="职务">
          <el-input v-model="editForm.title" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status" style="width: 100%">
            <el-option
              v-for="opt in ENABLE_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="重置密码">
          <el-input
            v-model="editForm.password"
            type="password"
            show-password
            placeholder="留空则不修改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleVisible" title="设置角色" width="420px">
      <p v-if="roleTarget" class="hint">账号：{{ roleTarget.username }}（{{ roleTarget.displayName }}）</p>
      <el-form label-width="80px">
        <el-form-item label="角色">
          <el-select v-model="roleForm.roleCode" clearable placeholder="清空则移除岗位角色" style="width: 100%">
            <el-option
              v-for="opt in ASSIGNABLE_STAFF_ROLE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="submitRole">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="orgVisible" title="分配机构" width="760px" class="org-transfer-dialog">
      <p v-if="orgTarget" class="hint">账号：{{ orgTarget.username }}（{{ orgTarget.displayName }}）</p>
      <el-transfer
        v-model="orgForm.orgIds"
        class="org-transfer"
        filterable
        :filter-method="filterOrgByName"
        filter-placeholder="请输入机构名称"
        :titles="['未分配机构', '已分配机构']"
        :data="orgTransferData"
      />
      <template #footer>
        <el-button @click="orgVisible = false">取消</el-button>
        <el-button type="primary" :loading="orgSaving" @click="submitOrgs">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title { margin-bottom: 16px; }
.page-title h1 { margin: 0; font-size: 16px; }
.page-title p { margin: 6px 0 0; color: var(--admin-muted); font-size: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.role-tag { margin-right: 4px; }
.muted { color: var(--admin-muted); font-size: 12px; }
.hint { margin: 0 0 12px; color: var(--admin-muted); font-size: 13px; }
.org-transfer-dialog :deep(.org-transfer) {
  display: flex;
  justify-content: center;
  width: 100%;
}
.org-transfer-dialog :deep(.el-transfer-panel) {
  width: 300px;
}
.org-transfer-dialog :deep(.el-transfer-panel__body) {
  height: 360px;
}
.org-transfer-dialog :deep(.el-transfer-panel__item) {
  margin-right: 0;
}
.org-transfer-dialog :deep(.el-transfer-panel__filter .el-input__inner) {
  font-size: 13px;
}
</style>
