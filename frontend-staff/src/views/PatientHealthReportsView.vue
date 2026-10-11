<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatPeriodType, formatReportStatus, sliceDate } from '../shared/report-summary'

interface HealthReportListItem {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  status?: string
  publishedAt?: string
  publishedByName?: string
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const items = ref<HealthReportListItem[]>([])

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: HealthReportListItem[] }>(
      `/api/b/v1/patients/${peopleId.value}/health-reports?limit=50`,
    )
    items.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function open(row: HealthReportListItem) {
  void router.push(`/patients/${peopleId.value}/health-reports/${row.id}`)
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="健康报告" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!items.length" description="暂无报告" />
      <article
        v-for="row in items"
        :key="row.id"
        class="card hx-row"
        @click="open(row)"
      >
        <div class="body">
          <div class="top">
            <span class="hx-chip">{{ formatPeriodType(row.periodType) }}</span>
            <span class="hx-chip" :class="{ warn: row.status === 'DRAFT' }">
              {{ formatReportStatus(row.status) }}
            </span>
          </div>
          <strong>{{ row.title || '管理报告' }}</strong>
          <p class="meta hx-muted">
            {{ sliceDate(row.periodStart) }} ~ {{ sliceDate(row.periodEnd) }}
            <template v-if="row.publishedByName"> · {{ row.publishedByName }}</template>
          </p>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.card {
  align-items: stretch;
}
.body {
  flex: 1;
  min-width: 0;
}
.top {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
strong {
  display: block;
  font-size: 15px;
  color: var(--hx-ink);
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
}
</style>
