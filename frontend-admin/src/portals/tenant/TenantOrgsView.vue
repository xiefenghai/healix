<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import {
  ENABLE_STATUS_OPTIONS,
  EnableStatus,
  formatEnableStatus,
  formatOrgType,
  ORG_TYPE_OPTIONS,
  OrgType,
} from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'

interface OrgItem {
  id: string
  orgCode: string
  name: string
  orgType: string
  status: string
}

const orgs = ref<OrgItem[]>([])
const name = ref('')
const orgType = ref(OrgType.GENERAL_HOSPITAL)
const loading = ref(false)
const invite = ref<any>(null)

const editVisible = ref(false)
const editSaving = ref(false)
const editForm = ref({
  id: '' as string,
  orgCode: '',
  name: '',
  orgType: OrgType.GENERAL_HOSPITAL as string,
  status: EnableStatus.ACTIVE as string,
})

async function load() {
  const res = await api<{ data: OrgItem[] }>('/api/b/v1/orgs')
  orgs.value = res.data ?? []
}

async function createOrg() {
  if (!name.value.trim()) {
    ElMessage.warning('请填写机构名称')
    return
  }
  loading.value = true
  try {
    await api('/api/b/v1/orgs', {
      method: 'POST',
      body: JSON.stringify({ name: name.value.trim(), orgType: orgType.value }),
    })
    name.value = ''
    ElMessage.success('机构已创建')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row: OrgItem) {
  editForm.value = {
    id: row.id,
    orgCode: row.orgCode,
    name: row.name,
    orgType: row.orgType || OrgType.GENERAL_HOSPITAL,
    status: row.status || EnableStatus.ACTIVE,
  }
  editVisible.value = true
}

async function submitEdit() {
  if (!editForm.value.name.trim()) {
    ElMessage.warning('请填写机构名称')
    return
  }
  editSaving.value = true
  try {
    await api(`/api/b/v1/orgs/${editForm.value.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name: editForm.value.name.trim(),
        orgType: editForm.value.orgType,
        status: editForm.value.status,
      }),
    })
    ElMessage.success('机构已更新')
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    editSaving.value = false
  }
}

async function createInvite(orgId: string) {
  try {
    const res = await api<{ data: any }>(`/api/b/v1/orgs/${orgId}/invites`, {
      method: 'POST',
      body: JSON.stringify({ validDays: 7 }),
    })
    invite.value = res.data
    ElMessage.success(`邀请码：${res.data.code}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  }
}

onMounted(() => {
  load().catch((e) => ElMessage.error(e instanceof Error ? e.message : '加载失败'))
})
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>机构管理</h1>
        <p>创建机构，并为机构生成患者邀请码</p>
      </div>
    </div>
    <el-card shadow="never" class="filter-card">
      <div class="filters">
        <el-input v-model="name" placeholder="新机构名称" style="width: 220px" />
        <el-select v-model="orgType" style="width: 180px">
          <el-option
            v-for="opt in ORG_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button type="primary" :loading="loading" @click="createOrg">创建机构</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table :data="orgs" stripe>
        <el-table-column prop="id" label="ID" v-bind="TABLE_COL.bizId" />
        <el-table-column prop="orgCode" label="机构编码" v-bind="TABLE_COL.bizCode" />
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">{{ formatOrgType(row.orgType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === EnableStatus.ACTIVE ? 'success' : 'info'" size="small">
              {{ formatEnableStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="createInvite(row.id)">生成邀请码</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="invite" class="mt" type="success" :closable="false" show-icon :title="`邀请码 ${invite.code}`" />
    </el-card>

    <el-dialog v-model="editVisible" title="编辑机构" width="480px">
      <el-form label-width="90px">
        <el-form-item label="机构编码">
          <el-input :model-value="editForm.orgCode" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="editForm.orgType" style="width: 100%">
            <el-option
              v-for="opt in ORG_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
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
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
.mb { margin-bottom: 16px; }
.mt { margin-top: 12px; }
</style>
