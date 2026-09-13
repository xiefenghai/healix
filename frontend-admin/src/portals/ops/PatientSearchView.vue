<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { TABLE_COL } from '../../shared/table-columns'

const tenantId = ref('')
const keyword = ref('')
const loading = ref(false)
const list = ref<any[]>([])

async function search() {
  const tid = tenantId.value.trim()
  if (!tid) {
    ElMessage.warning('请填写租户 ID')
    return
  }
  if (!/^\d+$/.test(tid)) {
    ElMessage.warning('租户 ID 须为数字（雪花 ID 请完整粘贴）')
    return
  }
  loading.value = true
  try {
    const q = new URLSearchParams({ tenantId: tid, limit: '20' })
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    const res = await api<{ data: any[] }>(`/api/ops/v1/patients/search?${q}`)
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '检索失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>患者明文检索</h1>
        <p>Ops PHI 访问会写入审计日志；租户 ID 请用完整雪花字符串，勿用 Number 截断</p>
      </div>
    </div>
    <el-card shadow="never" class="filter-card">
      <div class="filters">
        <el-input v-model="tenantId" placeholder="租户业务 ID（雪花）" style="width: 240px" />
        <el-input v-model="keyword" placeholder="姓名关键词" style="width: 220px" />
        <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table :data="list" stripe>
        <el-table-column prop="id" label="peopleId" v-bind="TABLE_COL.bizId" />
        <el-table-column prop="displayName" label="姓名" min-width="100" />
        <el-table-column prop="tenantId" label="tenantId" v-bind="TABLE_COL.bizId" />
        <el-table-column prop="gender" label="性别" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
</style>
