<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, getCurrentOrgId, updateOrgSession } from '../shared/http'

interface OrgSummary {
  id: string
  name: string
  orgType: string
  status: string
}

const router = useRouter()
const loading = ref(false)
const orgs = ref<OrgSummary[]>([])
const currentOrgId = ref<string | null>(getCurrentOrgId())

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: { orgs: OrgSummary[]; currentOrgId: string | null } }>(
      '/api/b/v1/session/me',
    )
    orgs.value = res.data.orgs ?? []
    currentOrgId.value = res.data.currentOrgId != null ? String(res.data.currentOrgId) : null
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function enterOrg(org: OrgSummary) {
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/b/v1/session/org', {
      method: 'PUT',
      body: JSON.stringify({ orgId: org.id }),
    })
    updateOrgSession(res.data.accessToken, org.id, org.name)
    currentOrgId.value = String(org.id)
    showToast(`已进入 ${org.name}`)
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '进入失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <header class="hx-page-head">
      <p class="eyebrow">Workspace</p>
      <h1>选择机构</h1>
      <p>进入后即可处理待办与患者沟通</p>
    </header>

    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <button
        v-for="org in orgs"
        :key="org.id"
        type="button"
        class="org hx-row"
        :class="{ current: currentOrgId === org.id }"
        @click="enterOrg(org)"
      >
        <div class="hx-avatar">{{ org.name.slice(0, 1) }}</div>
        <div class="copy">
          <div class="name">{{ org.name }}</div>
          <div class="meta hx-muted">
            {{ currentOrgId === org.id ? '当前机构 · 点击进入' : '点击进入工作台' }}
          </div>
        </div>
        <van-icon name="arrow" class="arrow" />
      </button>
      <van-empty v-if="!orgs.length" description="暂无可用机构" />
    </div>
  </div>
</template>

<style scoped>
.org.current {
  border-color: rgba(31, 143, 143, 0.35);
  background: linear-gradient(135deg, rgba(232, 246, 246, 0.95), rgba(255, 255, 255, 0.95));
}
.copy {
  flex: 1;
  min-width: 0;
}
.name {
  font-size: 16px;
  font-weight: 650;
  color: var(--hx-ink);
}
.meta {
  margin-top: 4px;
  font-size: 12px;
}
.arrow {
  color: var(--hx-muted);
  font-size: 14px;
}
</style>
