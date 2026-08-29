<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, getCurrentOrgId, updateOrgSession } from '../../shared/http'
import { formatEnableStatus, formatOrgType } from '../../shared/enums'

const router = useRouter()
const loading = ref(false)
const orgs = ref<Array<{ id: string; name: string; orgType: string; status: string }>>([])
const currentOrgId = ref<string | null>(getCurrentOrgId())

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: { orgs: typeof orgs.value; currentOrgId: string | null } }>(
      '/api/b/v1/session/me',
    )
    orgs.value = res.data.orgs ?? []
    currentOrgId.value = res.data.currentOrgId != null ? String(res.data.currentOrgId) : null
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function enterOrg(org: { id: string; name: string }) {
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/b/v1/session/org', {
      method: 'PUT',
      body: JSON.stringify({ orgId: org.id }),
    })
    updateOrgSession(res.data.accessToken, org.id, org.name)
    currentOrgId.value = String(org.id)
    ElMessage.success(`已进入 ${org.name}`)
    await router.push('/workspace/patients')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '进入失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title">
      <h1>选择工作机构</h1>
      <p>选择机构后进入工作台，默认从患者管理开始</p>
    </div>
    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="org in orgs" :key="org.id" :xs="24" :sm="12" :md="8" :lg="6">
        <el-card
          class="org-card"
          shadow="hover"
          :body-style="{ padding: '16px' }"
          @click="enterOrg(org)"
        >
          <div class="name">{{ org.name }}</div>
          <div class="meta">{{ formatOrgType(org.orgType) }} · {{ formatEnableStatus(org.status) }}</div>
          <el-button type="primary" link>
            {{ currentOrgId === org.id ? '当前机构，点击进入' : '进入工作台' }}
          </el-button>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="!loading && !orgs.length" description="暂无可用机构" />
  </div>
</template>

<style scoped>
.page-title {
  margin-bottom: 16px;
}
.page-title h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}
.page-title p {
  margin: 6px 0 0;
  color: var(--admin-muted);
  font-size: 12px;
}
.org-card {
  margin-bottom: 16px;
  cursor: pointer;
  border-radius: 8px;
  transition: transform 0.15s ease;
}
.org-card:hover {
  transform: translateY(-2px);
}
.name {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}
.meta {
  font-size: 12px;
  color: var(--admin-muted);
  margin-bottom: 10px;
}
</style>
