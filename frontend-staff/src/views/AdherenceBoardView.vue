<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'

type BoardFilter = '' | 'STREAK_GE_3' | 'PLAN_INCOMPLETE' | 'MED_INCOMPLETE' | 'LIFESTYLE_INACTIVE'

interface AdherenceOverview {
  date: string
  universeCount: number
  followUpCount: number
  planIncompleteCount: number
  medIncompleteCount: number
  streakGe3Count: number
  lifestyleInactiveCount?: number
}

interface AdherencePatientItem {
  peopleId: string
  displayName: string
  careTeamName?: string | null
  clientLinked?: boolean
  riskLevel?: string
  plan?: {
    streakDays?: number
    rate7d?: number | null
    todayIncomplete?: boolean
  }
  med?: {
    todayIncomplete?: boolean
  }
  lifestyle?: {
    dietDays7d?: number
    exerciseDays7d?: number
  }
}

const router = useRouter()
const loading = ref(false)
const refreshing = ref(false)
const overview = ref<AdherenceOverview | null>(null)
const items = ref<AdherencePatientItem[]>([])
const filter = ref<BoardFilter>('STREAK_GE_3')
const escalatingId = ref('')

function yesterdayYmd() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() - 1)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const date = yesterdayYmd()

const cards = computed(() => [
  { key: 'STREAK_GE_3' as const, label: '连续≥3天', value: overview.value?.streakGe3Count ?? 0 },
  { key: 'PLAN_INCOMPLETE' as const, label: '方案未完成', value: overview.value?.planIncompleteCount ?? 0 },
  { key: 'MED_INCOMPLETE' as const, label: '用药未完成', value: overview.value?.medIncompleteCount ?? 0 },
  {
    key: 'LIFESTYLE_INACTIVE' as const,
    label: '7天饮食运动无记录',
    value: overview.value?.lifestyleInactiveCount ?? 0,
  },
])

function hint(row: AdherencePatientItem) {
  if ((row.plan?.streakDays || 0) >= 3) return `连续未执行 ${row.plan?.streakDays} 天`
  if (row.plan?.todayIncomplete) return '昨日方案未完成'
  if (row.med?.todayIncomplete) return '昨日用药未完成'
  if (!row.clientLinked) return 'C 端未关联'
  const ls = row.lifestyle
  if (ls) return `7天饮食 ${ls.dietDays7d ?? 0} 天 · 运动 ${ls.exerciseDays7d ?? 0} 天`
  return '—'
}

function pct(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '—'
  return `${Math.round(Number(rate) * 100)}%`
}

async function load() {
  loading.value = true
  try {
    const oq = new URLSearchParams({ date })
    const pq = new URLSearchParams({ date, page: '1', size: '30' })
    if (filter.value) pq.set('filter', filter.value)
    const [o, p] = await Promise.all([
      api<{ data: AdherenceOverview }>(`/api/b/v1/adherence/overview?${oq}`),
      api<{ data: { items?: AdherencePatientItem[] } }>(`/api/b/v1/adherence/patients?${pq}`),
    ])
    overview.value = o.data
    items.value = p.data.items || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function onRefresh() {
  refreshing.value = true
  try {
    await load()
  } finally {
    refreshing.value = false
  }
}

function selectFilter(key: BoardFilter) {
  filter.value = filter.value === key ? '' : key
  void load()
}

async function escalate(row: AdherencePatientItem) {
  escalatingId.value = row.peopleId
  try {
    const streak = row.plan?.streakDays ?? 0
    await api(`/api/b/v1/adherence/patients/${row.peopleId}/escalate`, {
      method: 'POST',
      body: JSON.stringify({
        openFollowup: true,
        openPlanNudge: streak >= 3,
        streakDays: streak,
      }),
    })
    showToast('已催办')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '催办失败')
  } finally {
    escalatingId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="依从看板" left-arrow @click-left="router.back()" />
    <p class="sub hx-muted">
      统计日 {{ date }}（默认看昨日缺口）<template v-if="overview"> · 纳入 {{ overview.universeCount }} 人</template>
    </p>
    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <div class="cards">
        <button
          v-for="c in cards"
          :key="c.key"
          type="button"
          class="card"
          :class="{ active: filter === c.key }"
          @click="selectFilter(c.key)"
        >
          <strong>{{ c.value }}</strong>
          <span>{{ c.label }}</span>
        </button>
      </div>

      <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
      <div v-else class="hx-list">
        <van-empty v-if="!items.length" description="暂无名单" />
        <article v-for="row in items" :key="row.peopleId" class="row hx-surface">
          <div class="main" @click="router.push(`/patients/${row.peopleId}`)">
            <div class="top">
              <strong>{{ row.displayName }}</strong>
              <span class="hx-chip warn">{{ pct(row.plan?.rate7d) }}</span>
            </div>
            <p class="meta hx-muted">
              {{ row.careTeamName || '未入组' }} · {{ hint(row) }}
            </p>
          </div>
          <van-button
            size="mini"
            type="primary"
            round
            :loading="escalatingId === row.peopleId"
            @click="escalate(row)"
          >
            催办
          </van-button>
        </article>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.sub {
  margin: 0 16px 10px;
  font-size: 12px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  padding: 0 16px 12px;
}
.card {
  border: 1px solid var(--hx-line);
  background: rgba(255, 255, 255, 0.88);
  border-radius: 14px;
  padding: 12px;
  text-align: left;
}
.card.active {
  border-color: rgba(31, 143, 143, 0.35);
  background: var(--hx-teal-light);
}
.card strong {
  display: block;
  font-size: 22px;
  color: var(--hx-ink);
}
.card span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--hx-muted);
  font-weight: 600;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
}
.main {
  flex: 1;
  min-width: 0;
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.top strong {
  font-size: 15px;
  color: var(--hx-ink);
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
}
</style>
