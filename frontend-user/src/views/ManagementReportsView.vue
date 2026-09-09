<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  formatHealthReportPeriodType,
  formatReportDate,
} from '../shared/management-report-labels'

interface Item {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  publishedAt?: string
}

const router = useRouter()
const loading = ref(false)
const items = ref<Item[]>([])

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Item[] }>('/api/c/v1/health-reports?limit=50')
    items.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="page">
    <van-nav-bar title="管理报告" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else>
      <van-empty v-if="!items.length" description="暂无已发布的管理报告" />
      <button
        v-for="row in items"
        :key="row.id"
        type="button"
        class="card"
        @click="router.push(`/management-reports/${row.id}`)"
      >
        <div class="card-top">
          <van-tag round plain type="primary">{{ formatHealthReportPeriodType(row.periodType) }}</van-tag>
          <van-icon name="arrow" class="arrow" />
        </div>
        <div class="title">{{ row.title || '管理报告' }}</div>
        <div class="meta">
          {{ formatReportDate(row.periodStart) }} ~ {{ formatReportDate(row.periodEnd) }}
        </div>
      </button>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.2), transparent 42%),
    var(--hx-bg);
  padding-bottom: 24px;
}
.loading {
  padding: 48px 0;
}
.card {
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px 0;
  padding: 14px 16px;
  text-align: left;
  border: 0;
  background: var(--hx-card);
  border-radius: var(--hx-radius);
  box-shadow: var(--hx-shadow);
  cursor: pointer;
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.arrow {
  color: var(--hx-muted);
  font-size: 14px;
}
.title {
  margin-top: 10px;
  font-size: 16px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.4;
}
.meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--hx-muted);
}
</style>
