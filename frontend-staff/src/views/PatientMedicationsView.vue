<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { sliceDate } from '../shared/report-summary'

interface Medication {
  id: string
  drugName: string
  usageMethod?: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  startDate?: string
  stopDate?: string
  timingNote?: string
  status?: string
  remark?: string
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const filter = ref<'ACTIVE' | 'ALL'>('ACTIVE')
const items = ref<Medication[]>([])

async function load() {
  loading.value = true
  try {
    const qs = filter.value === 'ACTIVE' ? '?status=ACTIVE' : ''
    const res = await api<{ data: Medication[] }>(
      `/api/b/v1/patients/${peopleId.value}/medications${qs}`,
    )
    items.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function doseText(m: Medication) {
  const parts = [
    m.doseAmount ? `${m.doseAmount}${m.doseUnit || ''}` : '',
    m.frequency || '',
    m.usageMethod || '',
  ].filter(Boolean)
  return parts.join(' · ') || '—'
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="用药清单" left-arrow @click-left="router.back()" />
    <div class="hx-seg">
      <button type="button" :class="{ active: filter === 'ACTIVE' }" @click="filter = 'ACTIVE'; load()">
        在用
      </button>
      <button type="button" :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'; load()">
        全部
      </button>
    </div>
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!items.length" description="暂无用药" />
      <article v-for="m in items" :key="m.id" class="card hx-surface">
        <div class="top">
          <strong>{{ m.drugName }}</strong>
          <span class="hx-chip" :class="{ warn: m.status !== 'ACTIVE' }">
            {{ m.status === 'ACTIVE' ? '在用' : m.status === 'STOPPED' ? '已停' : m.status || '—' }}
          </span>
        </div>
        <p class="dose">{{ doseText(m) }}</p>
        <p v-if="m.timingNote" class="meta hx-muted">用法 {{ m.timingNote }}</p>
        <p class="meta hx-muted">
          {{ sliceDate(m.startDate) }}
          <template v-if="m.stopDate"> ~ {{ sliceDate(m.stopDate) }}</template>
        </p>
        <p v-if="m.remark" class="meta hx-muted">{{ m.remark }}</p>
      </article>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 14px;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.top strong {
  color: var(--hx-ink);
  font-size: 15px;
}
.dose {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--hx-text);
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
}
</style>
