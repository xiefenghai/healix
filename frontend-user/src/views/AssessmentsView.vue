<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'

interface Snapshot {
  id: string
  engineCode?: string
  engineLabel?: string
  kindLabel?: string
  status?: string
  level?: string
  levelLabel?: string
  score?: number | null
  advice?: string
  assessedAt?: string
  missingFields?: string[]
}

interface Overview {
  latest?: Snapshot[]
  notices?: string[]
  disclaimer?: string
}

const router = useRouter()
const loading = ref(false)
const overview = ref<Overview | null>(null)
const expandedId = ref<string | null>(null)

const items = computed(() => overview.value?.latest || [])

function formatTime(iso?: string) {
  if (!iso) return ''
  const s = String(iso).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

function levelTone(level?: string) {
  const code = (level || '').toUpperCase()
  if (code === 'HIGH' || code === 'RED' || code === 'SEVERE') return 'danger'
  if (code === 'MEDIUM' || code === 'YELLOW' || code === 'MODERATE') return 'warning'
  if (code === 'LOW' || code === 'GREEN' || code === 'MILD') return 'success'
  return 'primary'
}

function toggle(id: string) {
  expandedId.value = expandedId.value === id ? null : id
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Overview }>('/api/c/v1/me/assessments')
    overview.value = res.data || null
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
    <van-nav-bar title="健康评估" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else>
      <p v-if="overview?.disclaimer" class="disclaimer">{{ overview.disclaimer }}</p>
      <div v-if="overview?.notices?.length" class="notices">
        <p v-for="(n, i) in overview.notices" :key="i">{{ n }}</p>
      </div>
      <van-empty v-if="!items.length" description="暂无评估结果，健管师完成评估后将在此展示" />
      <button
        v-for="row in items"
        :key="row.id"
        type="button"
        class="card"
        @click="toggle(row.id)"
      >
        <div class="card-top">
          <div class="titles">
            <strong>{{ row.engineLabel || row.engineCode || '评估' }}</strong>
            <span v-if="row.kindLabel" class="kind">{{ row.kindLabel }}</span>
          </div>
          <van-tag v-if="row.levelLabel || row.level" round :type="levelTone(row.level)">
            {{ row.levelLabel || row.level }}
          </van-tag>
        </div>
        <div class="meta">
          <span v-if="row.score != null">得分 {{ row.score }}</span>
          <span v-if="formatTime(row.assessedAt)">{{ formatTime(row.assessedAt) }}</span>
        </div>
        <p v-if="expandedId === row.id && row.advice" class="advice">{{ row.advice }}</p>
        <p
          v-else-if="expandedId === row.id && row.missingFields?.length"
          class="advice muted"
        >
          资料不全：{{ row.missingFields.join('、') }}
        </p>
        <p v-else-if="row.advice" class="advice-preview">{{ row.advice }}</p>
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
.disclaimer,
.notices {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--hx-muted);
  background: rgba(255, 255, 255, 0.85);
}
.notices p {
  margin: 0;
}
.notices p + p {
  margin-top: 6px;
}
.card {
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px 0;
  padding: 14px 16px;
  border: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--hx-shadow);
  text-align: left;
}
.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.titles {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.titles strong {
  font-size: 15px;
  color: var(--hx-text);
}
.kind {
  font-size: 12px;
  color: var(--hx-muted);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--hx-muted);
}
.advice,
.advice-preview {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.advice-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--hx-muted);
}
.advice.muted {
  color: var(--hx-muted);
}
</style>
