<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatRole, formatStaffStatus, ORG_CLINICAL_ROLE_OPTIONS, StaffRole, staffStatusTagType } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'

interface OrgStaffItem {
  staffId: string
  accountId: string
  username: string
  displayName: string
  mobile?: string
  title?: string
  status: string
  roles: string[]
  createdAt: string
}

const router = useRouter()
const loading = ref(false)
const list = ref<OrgStaffItem[]>([])
const keyword = ref('')
const roleCode = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({
  username: '',
  password: '',
  displayName: '',
  mobile: '',
  title: '',
  roleCode: StaffRole.CARE_MANAGER as string,
})

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

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    const q = new URLSearchParams()
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    if (roleCode.value) q.set('roleCode', roleCode.value)
    const qs = q.toString()
    const res = await api<{ data: OrgStaffItem[] }>(`/api/b/v1/org-staff${qs ? `?${qs}` : ''}`)
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.value = {
    username: '',
    password: '',
    displayName: '',
    mobile: '',
    title: '',
    roleCode: StaffRole.CARE_MANAGER,
  }
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.value.username.trim() || !createForm.value.password.trim()) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  if (!createForm.value.displayName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (createForm.value.password.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  if (!createForm.value.roleCode) {
    ElMessage.warning('请选择角色')
    return
  }
  creating.value = true
  try {
    await api('/api/b/v1/org-staff', {
      method: 'POST',
      body: JSON.stringify({
        username: createForm.value.username.trim(),
        password: createForm.value.password,
        displayName: createForm.value.displayName.trim(),
        mobile: createForm.value.mobile || undefined,
        title: createForm.value.title || undefined,
        roleCode: createForm.value.roleCode,
      }),
    })
    ElMessage.success('医护账号已创建')
    createVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

async function unbind(row: OrgStaffItem) {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.displayName}」从当前机构解绑？账号本身不会停用。`,
      '解绑医护',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/org-staff/${row.staffId}`, { method: 'DELETE' })
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解绑失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>成员管理</h1>
        <p>当前机构：{{ getCurrentOrgName() || '-' }} · 本机构健管师与医生账号</p>
      </div>
      <el-button @click="router.push('/workspace/orgs')">切换机构</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="用户名 / 姓名"
          clearable
          style="width: 180px"
          @keyup.enter="load"
        />
        <el-select v-model="roleCode" placeholder="角色" clearable style="width: 140px">
          <el-option
            v-for="opt in ORG_CLINICAL_ROLE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新开医护</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column label="角色" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" class="role-tag">{{ formatRole(r) }}</el-tag>
            <span v-if="!row.roles?.length" class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="mobile" label="手机" min-width="120">
          <template #default="{ row }">{{ row.mobile || '-' }}</template>
        </el-table-column>
        <el-table-column prop="title" label="职务" min-width="100">
          <template #default="{ row }">{{ row.title || '-' }}</template>
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
        <el-table-column label="操作" v-bind="TABLE_COL.actionsSm">
          <template #default="{ row }">
            <el-button link type="danger" @click="unbind(row)">解绑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新开医护账号" width="520px">
      <el-form label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="createForm.displayName" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="createForm.mobile" />
        </el-form-item>
        <el-form-item label="职务">
          <el-input v-model="createForm.title" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="createForm.roleCode" style="width: 100%">
            <el-option
              v-for="opt in ORG_CLINICAL_ROLE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
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
.role-tag { margin-right: 4px; }
.muted { color: var(--admin-muted); font-size: 12px; }
</style>
