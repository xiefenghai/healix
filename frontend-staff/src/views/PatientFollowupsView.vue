<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'
import { formatFollowupMethod, formatFollowupStatus } from '../shared/labels'

interface FollowupItem {
  id: string
  peopleId: string
  recordType: string
  recordTypeLabel?: string
  status: string
  title?: string
  summary?: string
  followupType?: string
  followupTypeLabel?: string
  contactChannel?: string
  completedByName?: string
  completedAt?: string
  plannedAt?: string
  gmtCreated?: string
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const items = ref<FollowupItem[]>([])

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: FollowupItem[] }>(
      `/api/b/v1/patients/${peopleId.value}/followups?limit=100`,
    )
    items.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function canComplete(row: FollowupItem) {
  return row.status === 'OPEN' && row.recordType === 'PERIODIC'
}

function openDetail(row: FollowupItem) {
  void router.push(`/followups/${row.id}`)
}

function openComplete(row: FollowupItem, e: Event) {
  e.stopPropagation()
  void router.push(`/followups/${row.id}/complete`)
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="随访记录" left-arrow @click-left="router.back()">
      <template #right>
        <button
          type="button"
          class="nav-btn"
          @click="router.push(`/patients/${peopleId}/followups/create`)"
        >
          新建
        </button>
      </template>
    </van-nav-bar>
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!items.length" description="暂无随访" />
      <article
        v-for="row in items"
        :key="row.id"
        class="card hx-surface"
        @click="openDetail(row)"
      >
        <div class="top">
          <strong>{{ row.followupTypeLabel || row.recordTypeLabel || '随访' }}</strong>
          <span class="hx-chip" :class="{ warn: row.status === 'OPEN' }">
            {{ formatFollowupStatus(row.status) }}
          </span>
        </div>
        <p class="summary">{{ row.summary || row.title || '—' }}</p>
        <p class="meta hx-muted">
          <template v-if="row.contactChannel">{{ formatFollowupMethod(row.contactChannel) }} · </template>
          {{ formatDateTime(row.completedAt || row.plannedAt || row.gmtCreated) }}
          <template v-if="row.completedByName"> · {{ row.completedByName }}</template>
        </p>
        <van-button
          v-if="canComplete(row)"
          size="small"
          type="primary"
          round
          class="btn"
          @click="openComplete(row, $event)"
        >
          办结随访
        </van-button>
        <p v-else class="hint hx-muted">点击查看详情</p>
      </article>
    </div>
  </div>
</template>

<style scoped>
.nav-btn {
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-size: 14px;
  font-weight: 600;
}
.card {
  padding: 14px;
  cursor: pointer;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.top strong {
  color: var(--hx-ink);
}
.summary {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.4;
}
.meta {
  margin: 8px 0 0;
  font-size: 12px;
}
.btn {
  margin-top: 12px;
}
.hint {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
