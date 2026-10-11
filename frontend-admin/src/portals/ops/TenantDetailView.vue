<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, isOpsSuperAdmin } from '../../shared/http'
import type { PageResult, StaffAccountListItem, TenantListItem } from './types'
import {
  formatRole,
  formatStaffStatus,
  formatTenantStatus,
  staffStatusTagType,
  tenantStatusTagType,
  ENABLE_STATUS_OPTIONS,
  StaffRole,
} from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'
import TenantGovernancePanel from './TenantGovernancePanel.vue'

const route = useRoute()
const router = useRouter()
const tenantId = computed(() => String(route.params.id || ''))
const canWrite = isOpsSuperAdmin()

const tenant = ref<TenantListItem | null>(null)
const activeTab = ref('overview')
const tabAudited = ref(false)

const staffKeyword = ref('')
const staffStatus = ref('')
const staffRoleCode = ref('')
const staffPage = ref(1)
const staffPageSize = ref(20)
const staffTotal = ref(0)
const staffLoading = ref(false)
const staffList = ref<StaffAccountListItem[]>([])

const registerVisible = ref(false)
const registering = ref(false)
const grantingId = ref<string | null>(null)
const registerForm = ref({
  username: '',
  password: '',
  displayName: '',
  mobile: '',
  title: '',
  grantTenantAdmin: false,
  bindDefaultOrgId: '' as string,
})

function formatTime(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

const tenantInitial = computed(() => {
  const name = tenant.value?.name?.trim()
  return name ? name.charAt(0) : '租'
})

async function copyTenantId() {
  if (!tenant.value?.tenantId) return
  try {
    await navigator.clipboard.writeText(tenant.value.tenantId)
    ElMessage.success('租户 ID 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

function goAccountsTab() {
  activeTab.value = 'accounts'
}

function isTenantAdmin(row: StaffAccountListItem) {
  return row.roles?.includes(StaffRole.TENANT_ADMIN)
}

async function loadTenant() {
  const q = new URLSearchParams({ page: '1', pageSize: '100' })
  const res = await api<{ data: PageResult<TenantListItem> }>(`/api/ops/v1/tenants?${q}`)
  const found = res.data.items.find((t) => t.tenantId === tenantId.value)
  if (!found) {
    ElMessage.error('租户不存在')
    router.replace('/ops/tenants')
    return
  }
  tenant.value = found
}

async function loadStaff() {
  staffLoading.value = true
  try {
    const q = new URLSearchParams({
      page: String(staffPage.value),
      pageSize: String(staffPageSize.value),
    })
    if (staffKeyword.value.trim()) q.set('keyword', staffKeyword.value.trim())
    if (staffStatus.value) q.set('status', staffStatus.value)
    if (staffRoleCode.value) q.set('roleCode', staffRoleCode.value)
    const res = await api<{ data: PageResult<StaffAccountListItem> }>(
      `/api/ops/v1/tenants/${tenantId.value}/staff-accounts?${q}`,
    )
    staffList.value = res.data.items ?? []
    staffTotal.value = res.data.total ?? 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载账号失败')
  } finally {
    staffLoading.value = false
  }
}

async function auditTabView() {
  if (tabAudited.value) return
  try {
    await api(`/api/ops/v1/tenants/${tenantId.value}/staff-accounts/audit-view`, { method: 'POST' })
    tabAudited.value = true
  } catch {
    /* 只读账号也可进入 Tab，审计失败不阻断 */
  }
}

function openRegister() {
  registerForm.value = {
    username: '',
    password: '',
    displayName: '',
    mobile: '',
    title: '',
    grantTenantAdmin: false,
    bindDefaultOrgId: '',
  }
  registerVisible.value = true
}

function usernamePlaceholder() {
  return tenant.value ? `${tenant.value.code}_` : ''
}

async function submitRegister() {
  if (!canWrite) return
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
    const body: Record<string, unknown> = {
      username: registerForm.value.username.trim(),
      password: registerForm.value.password,
      displayName: registerForm.value.displayName.trim(),
      mobile: registerForm.value.mobile || undefined,
      title: registerForm.value.title || undefined,
      grantTenantAdmin: registerForm.value.grantTenantAdmin,
    }
    const orgId = registerForm.value.bindDefaultOrgId
    if (orgId !== '' && orgId != null) body.bindDefaultOrgId = String(orgId)

    await api(`/api/ops/v1/tenants/${tenantId.value}/staff-accounts`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    ElMessage.success('账号已注册')
    registerVisible.value = false
    await Promise.all([loadStaff(), loadTenant()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '注册失败')
  } finally {
    registering.value = false
  }
}

async function grantTenantAdmin(row: StaffAccountListItem) {
  if (!canWrite || isTenantAdmin(row)) return
  grantingId.value = row.staffId
  try {
    await api(`/api/ops/v1/tenants/${tenantId.value}/staff/${row.staffId}/roles/tenant-admin`, {
      method: 'POST',
    })
    ElMessage.success('已设为租户管理员')
    await Promise.all([loadStaff(), loadTenant()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '授权失败')
  } finally {
    grantingId.value = null
  }
}

watch(activeTab, (tab) => {
  if (tab === 'accounts') {
    auditTabView()
    loadStaff()
  }
})

onMounted(async () => {
  try {
    await loadTenant()
    if (route.query.tab === 'accounts') {
      activeTab.value = 'accounts'
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <div v-if="tenant">
    <el-card class="tenant-hero" shadow="never">
      <el-button class="back-link" text type="primary" @click="router.push('/ops/tenants')">
        ← 返回列表
      </el-button>

      <div class="hero-body">
        <div class="tenant-avatar" aria-hidden="true">{{ tenantInitial }}</div>

        <div class="hero-main">
          <div class="hero-title-row">
            <h1 class="tenant-name">{{ tenant.name }}</h1>
            <div class="hero-badges">
              <el-tag :type="tenantStatusTagType(tenant.status)" size="small" effect="plain">
                {{ formatTenantStatus(tenant.status) }}
              </el-tag>
              <el-tag
                v-if="tenant.hasTenantAdmin"
                type="success"
                size="small"
                effect="plain"
              >
                管理员已配置
              </el-tag>
              <el-tag v-else type="danger" size="small" effect="dark">未配置管理员</el-tag>
            </div>
          </div>

          <div class="hero-meta">
            <div class="meta-item">
              <span class="meta-label">租户编码</span>
              <span class="meta-value code">{{ tenant.code }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">创建时间</span>
              <span class="meta-value">{{ formatTime(tenant.createdAt) }}</span>
            </div>
          </div>

          <div class="tenant-id-panel">
            <span class="meta-label">租户 ID</span>
            <div class="id-box">
              <code class="id-value">{{ tenant.tenantId }}</code>
              <el-button link type="primary" size="small" @click="copyTenantId">
                <el-icon><DocumentCopy /></el-icon>
                复制
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <el-alert
        v-if="!tenant.hasTenantAdmin"
        class="hero-alert"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>
          该租户尚未配置租户管理员
          <el-button link type="primary" class="alert-action" @click="goAccountsTab">
            前往账号管理 →
          </el-button>
        </template>
      </el-alert>
    </el-card>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="概览" name="overview">
        <el-card shadow="never">
          <div class="overview-grid">
            <div class="overview-card">
              <span class="overview-label">租户名称</span>
              <span class="overview-value">{{ tenant.name }}</span>
            </div>
            <div class="overview-card">
              <span class="overview-label">租户编码</span>
              <span class="overview-value code">{{ tenant.code }}</span>
            </div>
            <div class="overview-card">
              <span class="overview-label">运行状态</span>
              <span class="overview-value">
                <el-tag :type="tenantStatusTagType(tenant.status)" size="small" effect="plain">
                  {{ formatTenantStatus(tenant.status) }}
                </el-tag>
              </span>
            </div>
            <div class="overview-card">
              <span class="overview-label">租户管理员</span>
              <span class="overview-value">
                <el-tag v-if="tenant.hasTenantAdmin" type="success" size="small">已配置</el-tag>
                <el-tag v-else type="danger" size="small">未配置</el-tag>
              </span>
            </div>
            <div class="overview-card overview-card-wide">
              <span class="overview-label">创建时间</span>
              <span class="overview-value">{{ formatTime(tenant.createdAt) }}</span>
            </div>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="配额与开关" name="governance">
        <TenantGovernancePanel :tenant-id="tenantId" :can-write="canWrite" />
      </el-tab-pane>

      <el-tab-pane label="账号管理" name="accounts">
        <el-card shadow="never">
          <div class="toolbar">
            <el-input
              v-model="staffKeyword"
              placeholder="用户名 / 姓名"
              clearable
              style="width: 180px"
              @keyup.enter="staffPage = 1; loadStaff()"
            />
            <el-select v-model="staffStatus" placeholder="状态" clearable style="width: 120px">
              <el-option
                v-for="opt in ENABLE_STATUS_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-select v-model="staffRoleCode" placeholder="角色" clearable style="width: 160px">
              <el-option :label="formatRole(StaffRole.TENANT_ADMIN)" :value="StaffRole.TENANT_ADMIN" />
            </el-select>
            <el-button @click="staffPage = 1; loadStaff()">查询</el-button>
            <div class="spacer" />
            <el-button v-if="canWrite" type="primary" @click="openRegister">注册账号</el-button>
          </div>

          <el-table v-loading="staffLoading" :data="staffList" stripe>
            <el-table-column prop="username" label="用户名" min-width="140" />
            <el-table-column prop="displayName" label="姓名" min-width="120" />
            <el-table-column prop="mobile" label="手机" width="130" />
            <el-table-column label="角色" min-width="140">
              <template #default="{ row }">
                <el-tag v-for="r in row.roles" :key="r" size="small" class="role-tag">{{ formatRole(r) }}</el-tag>
                <span v-if="!row.roles?.length" class="muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="staffStatusTagType(row.status)" size="small" effect="plain">
                  {{ formatStaffStatus(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" v-bind="TABLE_COL.datetime">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column v-if="canWrite" label="操作" v-bind="TABLE_COL.actionsMd">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="isTenantAdmin(row)"
                  :loading="grantingId === row.staffId"
                  @click="grantTenantAdmin(row)"
                >
                  {{ isTenantAdmin(row) ? '已是管理员' : '设为租户管理员' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="staffPage"
              v-model:page-size="staffPageSize"
              :total="staffTotal"
              layout="total, prev, pager, next"
              @current-change="loadStaff"
              @size-change="staffPage = 1; loadStaff()"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="registerVisible" title="注册 B 端账号" width="520px">
      <el-form label-width="110px">
        <el-form-item label="用户名" required>
          <el-input v-model="registerForm.username" :placeholder="usernamePlaceholder()" />
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
        <el-form-item label="同时授权">
          <el-checkbox v-model="registerForm.grantTenantAdmin">设为租户管理员</el-checkbox>
        </el-form-item>
        <el-form-item label="绑定机构 ID">
          <el-input v-model="registerForm.bindDefaultOrgId" placeholder="可选，须为租户下已有机构" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="registering" @click="submitRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tenant-hero {
  margin-bottom: 16px;
  border: 1px solid #e8eaef;
}

.back-link {
  margin: -4px 0 12px;
  padding-left: 0;
}

.hero-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.tenant-avatar {
  flex-shrink: 0;
  width: 52px;
  height: 52px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2c7ef8 0%, #00b8a9 100%);
  color: #fff;
  font-size: 22px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-main {
  flex: 1;
  min-width: 0;
}

.hero-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  margin-bottom: 12px;
}

.tenant-name {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--admin-text);
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 20px 32px;
  margin-bottom: 14px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-label {
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.2;
}

.meta-value {
  font-size: 14px;
  color: var(--admin-text);
}

.meta-value.code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-weight: 500;
}

.tenant-id-panel {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.id-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border: 1px solid #e8eaef;
  border-radius: 6px;
  max-width: 100%;
}

.id-value {
  flex: 1;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  color: #434343;
  word-break: break-all;
  line-height: 1.5;
}

.hero-alert {
  margin-top: 16px;
}

.alert-action {
  margin-left: 8px;
  vertical-align: baseline;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.overview-card {
  padding: 14px 16px;
  background: #fafbfc;
  border: 1px solid #eef0f3;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.overview-card-wide {
  grid-column: 1 / -1;
}

.overview-label {
  font-size: 12px;
  color: var(--admin-muted);
}

.overview-value {
  font-size: 15px;
  font-weight: 500;
  color: var(--admin-text);
}

.overview-value.code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.role-tag { margin-right: 4px; }
.muted { color: var(--admin-muted); font-size: 12px; }

@media (max-width: 640px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .overview-card-wide {
    grid-column: auto;
  }
}
</style>
