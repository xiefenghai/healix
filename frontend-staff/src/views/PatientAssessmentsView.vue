<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'

interface Item {
  code?: string
  label?: string
  inputValue?: string
  points?: number | null
  rationale?: string
}

interface Snapshot {
  id: string
  engineCode: string
  engineLabel?: string
  kindLabel?: string
  status: string
  level?: string
  levelLabel?: string
  score?: number | null
  advice?: string
  items?: Item[]
  missingFields?: string[]
  assessedAt?: string
}

interface Overview {
  latest?: Snapshot[]
  disclaimer?: string
  notices?: string[]
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const overview = ref<Overview | null>(null)
const expanded = ref('')

const latest = computed(() =>
  (overview.value?.latest || []).filter((s) => s.engineCode !== 'CHINA_PAR'),
)

function levelTone(level?: string) {
  if (!level) return ''
  const u = level.toUpperCase()
  if (u.includes('HIGH') || u.includes('高') || u === 'RED') return 'danger'
  if (u.includes('MED') || u.includes('中') || u === 'YELLOW' || u === 'ORANGE') return 'warn'
  return ''
}

function toggle(id: string) {
  expanded.value = expanded.value === id ? '' : id
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Overview }>(`/api/b/v1/patients/${peopleId.value}/assessments`)
    overview.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="疾病评估" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!latest.length" description="暂无评估结果" />
      <article
        v-for="s in latest"
        :key="s.id"
        class="card hx-surface"
        @click="toggle(s.id)"
      >
        <div class="top">
          <div>
            <strong>{{ s.engineLabel || s.engineCode }}</strong>
            <p v-if="s.kindLabel" class="hx-muted">{{ s.kindLabel }}</p>
          </div>
          <span class="hx-chip" :class="levelTone(s.level || s.levelLabel)">
            {{ s.levelLabel || s.level || s.status || '—' }}
          </span>
        </div>
        <p v-if="s.score != null" class="score">得分 {{ s.score }}</p>
        <p v-if="s.advice" class="advice">{{ s.advice }}</p>
        <p class="meta hx-muted">{{ formatDateTime(s.assessedAt) || '—' }}</p>
        <div v-if="expanded === s.id && s.items?.length" class="items">
          <div v-for="(it, idx) in s.items" :key="it.code || idx" class="item">
            <span>{{ it.label || it.code || '—' }}</span>
            <em>{{ it.inputValue || (it.points != null ? `${it.points}分` : '—') }}</em>
          </div>
        </div>
        <p v-if="expanded === s.id && s.missingFields?.length" class="miss hx-muted">
          缺失：{{ s.missingFields.join('、') }}
        </p>
      </article>
      <p v-if="overview?.disclaimer" class="tip hx-muted">{{ overview.disclaimer }}</p>
      <p v-for="(n, i) in overview?.notices || []" :key="i" class="tip hx-muted">{{ n }}</p>
      <p class="tip hx-muted">只读；重新评估请使用电脑端。</p>
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
  gap: 10px;
  align-items: flex-start;
}
.top strong {
  font-size: 15px;
  color: var(--hx-ink);
}
.top p {
  margin: 4px 0 0;
  font-size: 12px;
}
.score {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--hx-teal-deep);
}
.advice {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.45;
  color: var(--hx-text);
}
.meta {
  margin: 8px 0 0;
  font-size: 12px;
}
.items {
  margin-top: 10px;
  border-top: 1px solid var(--hx-line);
  padding-top: 8px;
}
.item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
  font-size: 12px;
  color: var(--hx-text);
}
.item em {
  font-style: normal;
  color: var(--hx-ink);
  font-weight: 600;
}
.miss {
  margin: 8px 0 0;
  font-size: 12px;
}
.tip {
  margin: 8px 4px 0;
  font-size: 12px;
  line-height: 1.5;
}
</style>
