<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, isOpsSuperAdmin } from '../../shared/http'
import type { PageResult, TenantListItem } from './types'
import { formatTenantStatus, tenantStatusTagType } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'

const router = useRouter()
const canWrite = isOpsSuperAdmin()

const keyword = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const list = ref<TenantListItem[]>([])

const dialogVisible = ref(false)
const createMode = ref<'quick' | 'empty'>('quick')
const creating = ref(false)
const form = ref({
  code: '',
  name: '',
  username: '',
  password: '',
  displayName: '',
})

function formatTime(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

function openCreate(mode: 'quick' | 'empty') {
  createMode.value = mode
  form.value = { code: '', name: '', username: '', password: '', displayName: '' }
  dialogVisible.value = true
}

function suggestedUsername() {
  return form.value.code ? `${form.value.code}_admin` : ''
}

async function loadTenants() {
  loading.value = true
  try {
    const q = new URLSearchParams({
      page: String(page.value),
      pageSize: String(pageSize.value),
    })
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    const res = await api<{ data: PageResult<TenantListItem> }>(`/api/ops/v1/tenants?${q}`)
    list.value = res.data.items ?? []
    total.value = res.data.total ?? 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function createTenant() {
  if (!canWrite) {
    ElMessage.warning('当前账号只读，无法开通租户')
    return
  }
  if (!form.value.code.trim() || !form.value.name.trim()) {
    ElMessage.warning('请填写租户编码和租户名称')
    return
  }
  if (createMode.value === 'quick') {
    if (!form.value.username.trim() || !form.value.password.trim()) {
      ElMessage.warning('请填写管理员账号和密码')
      return
    }
    if (!form.value.displayName.trim()) {
      ElMessage.warning('请填写管理员姓名')
      return
    }
    if (form.value.password.length < 8) {
      ElMessage.warning('管理员密码至少 8 位')
      return
    }
  }
  creating.value = true
  try {
    const payload: Record<string, unknown> = {
      code: form.value.code.trim(),
      name: form.value.name.trim(),
    }
    if (createMode.value === 'quick') {
      payload.initialAdmin = {
        username: form.value.username.trim(),
        password: form.value.password,
        displayName: form.value.displayName.trim(),
        grantTenantAdmin: true,
      }
    }
    const res = await api<{ data: { tenantId: string } }>('/api/ops/v1/tenants', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
    ElMessage.success('租户已创建')
    dialogVisible.value = false
    await loadTenants()
    router.push(`/ops/tenants/${res.data.tenantId}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '开通失败')
  } finally {
    creating.value = false
  }
}

function goDetail(row: TenantListItem) {
  router.push(`/ops/tenants/${row.tenantId}`)
}

onMounted(() => {
  loadTenants()
})
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>租户管理</h1>
        <p>查看租户列表；未配置租户管理员时将标红提醒</p>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索租户编码 / 租户名称"
          clearable
          style="width: 240px"
          @keyup.enter="page = 1; loadTenants()"
        />
        <el-button @click="page = 1; loadTenants()">查询</el-button>
        <div class="spacer" />
        <el-button v-if="canWrite" type="primary" @click="openCreate('quick')">快捷开通</el-button>
        <el-button v-if="canWrite" @click="openCreate('empty')">仅开租户</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" stripe @row-click="goDetail">
        <el-table-column prop="tenantId" label="ID" v-bind="TABLE_COL.bizId" />
        <el-table-column prop="code" label="租户编码" min-width="140" />
        <el-table-column prop="name" label="租户名称" min-width="160" />
        <el-table-column label="管理员" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.hasTenantAdmin" type="success" size="small">已配置</el-tag>
            <el-tag v-else type="danger" size="small" effect="dark">未配置管理员</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="tenantStatusTagType(row.status)" size="small" effect="plain">
              {{ formatTenantStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" v-bind="TABLE_COL.actionsSm">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadTenants"
          @size-change="page = 1; loadTenants()"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="createMode === 'quick' ? '快捷开通（租户 + 租户管理员）' : '仅开租户'"
      width="520px"
    >
      <el-form label-width="100px">
        <el-form-item label="租户编码" required>
          <el-input v-model="form.code" placeholder="acme-health" />
        </el-form-item>
        <el-form-item label="租户名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <template v-if="createMode === 'quick'">
          <el-form-item label="管理员账号" required>
            <el-input v-model="form.username" :placeholder="suggestedUsername()" />
          </el-form-item>
          <el-form-item label="管理员密码" required>
            <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
          </el-form-item>
          <el-form-item label="管理员姓名" required>
            <el-input v-model="form.displayName" />
          </el-form-item>
        </template>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="将创建空租户，需在详情页注册账号并授予租户管理员"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTenant">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
:deep(.el-table__row) { cursor: pointer; }
</style>
