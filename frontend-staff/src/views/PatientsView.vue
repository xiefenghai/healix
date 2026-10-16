<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { ageFromBirthday, formatGender } from '../shared/format'
import { checkinStatus } from '../shared/rewards'

type FilterTab = 'urgent' | 'watch' | 'mine' | 'all'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamName?: string | null
  diseaseLabels?: string[]
  assessmentTags?: Array<{ text: string; tone?: string }>
  archiveCompletenessPercent?: number | null
  watched?: boolean | null
  clientLinked?: boolean
  streakDays?: number | null
  todayActions?: number | null
  inactiveDays?: number | null
}

interface PriorityCard {
  peopleId: string
  displayName: string
  topReason?: string
  badges?: string[]
  taskTypeLabels?: string[]
  taskIds?: string[]
  openTaskCount?: number
  overdueTaskCount?: number
  planRate7d?: number | null
  riskLevel?: string
}

const router = useRouter()
const loading = ref(false)
const refreshing = ref(false)
const keyword = ref('')
const filter = ref<FilterTab>('urgent')
const items = ref<OrgPatientListItem[]>([])
const priorityCards = ref<PriorityCard[]>([])

const tabs: Array<{ name: FilterTab; title: string }> = [
  { name: 'urgent', title: '优先' },
  { name: 'watch', title: '关注' },
  { name: 'mine', title: '我的' },
  { name: 'all', title: '全部' },
]

const usePriorityList = () => filter.value === 'urgent' || filter.value === 'mine'

async function load() {
  loading.value = true
  try {
    if (filter.value === 'urgent') {
      const res = await api<{ data: PriorityCard[] }>('/api/b/v1/cockpit/priority?tab=urgent')
      priorityCards.value = res.data || []
      items.value = []
    } else if (filter.value === 'mine') {
      const qs = new URLSearchParams()
      if (keyword.value.trim()) qs.set('keyword', keyword.value.trim())
      const res = await api<{ data: PriorityCard[] }>(
        `/api/b/v1/cockpit/my-patients${qs.toString() ? `?${qs}` : ''}`,
      )
      priorityCards.value = res.data || []
      items.value = []
    } else {
      const qs = new URLSearchParams()
      if (keyword.value.trim()) qs.set('keyword', keyword.value.trim())
      if (filter.value === 'watch') qs.set('watched', 'true')
      const res = await api<{ data: OrgPatientListItem[] }>(
        `/api/b/v1/org-patients${qs.toString() ? `?${qs}` : ''}`,
      )
      items.value = res.data || []
      priorityCards.value = []
    }
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

function meta(row: OrgPatientListItem) {
  const age = ageFromBirthday(row.birthday)
  const gender = formatGender(row.gender)
  return [age != null ? `${age}岁` : '', gender].filter(Boolean).join(' · ')
}

function riskLabel(code?: string) {
  if (code === 'HIGH') return '高风险'
  if (code === 'MEDIUM') return '中风险'
  if (code === 'LOW') return '低风险'
  return ''
}

function openPatient(peopleId: string, e?: Event) {
  e?.stopPropagation()
  void router.push(`/patients/${peopleId}`)
}

/** 优先/我的：有待办则进首个任务，否则进患者摘要 */
function openPriority(row: PriorityCard) {
  const first = row.taskIds?.[0]
  if (first) void router.push(`/tasks/${first}`)
  else void router.push(`/patients/${row.peopleId}`)
}

watch(filter, () => void load())
onMounted(load)
</script>

<template>
  <div class="hx-page">
    <header class="hx-page-head">
      <p class="eyebrow">Patients</p>
      <h1>患者</h1>
      <p>优先名单点卡片进任务，右侧可看摘要</p>
    </header>

    <div class="hx-seg" role="tablist">
      <button
        v-for="t in tabs"
        :key="t.name"
        type="button"
        role="tab"
        :class="{ active: filter === t.name }"
        @click="filter = t.name"
      >
        {{ t.title }}
      </button>
    </div>

    <van-search
      v-if="filter !== 'urgent'"
      v-model="keyword"
      placeholder="搜索姓名"
      shape="round"
      class="search"
      @search="load"
      @clear="load"
    />

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-loading v-if="loading && !refreshing" class="hx-loading" vertical>加载中</van-loading>

      <div v-else-if="usePriorityList()" class="hx-list">
        <van-empty v-if="!priorityCards.length" description="暂无患者" />
        <article
          v-for="row in priorityCards"
          :key="row.peopleId"
          class="patient hx-row"
          @click="openPriority(row)"
        >
          <div class="hx-avatar">{{ (row.displayName || '?').slice(0, 1) }}</div>
          <div class="body">
            <div class="top">
              <strong>{{ row.displayName }}</strong>
              <span v-if="riskLabel(row.riskLevel)" class="hx-chip warn">{{
                riskLabel(row.riskLevel)
              }}</span>
            </div>
            <p class="meta hx-muted">{{ row.topReason || '需关注' }}</p>
            <div v-if="row.taskTypeLabels?.length" class="chips">
              <span v-for="d in row.taskTypeLabels.slice(0, 3)" :key="d" class="hx-chip">{{
                d
              }}</span>
            </div>
            <div class="foot">
              <span class="hx-muted">
                待办 {{ row.openTaskCount || 0 }}
                <template v-if="row.overdueTaskCount"> · 逾期 {{ row.overdueTaskCount }}</template>
                <template v-if="row.planRate7d != null">
                  · 依从 {{ Math.round(row.planRate7d * 100) }}%
                </template>
              </span>
              <button type="button" class="link" @click="openPatient(row.peopleId, $event)">
                摘要
              </button>
            </div>
          </div>
        </article>
      </div>

      <div v-else class="hx-list">
        <van-empty v-if="!items.length" description="暂无患者" />
        <article
          v-for="row in items"
          :key="row.peopleId"
          class="patient hx-row"
          @click="openPatient(row.peopleId)"
        >
          <div class="hx-avatar">{{ (row.displayName || '?').slice(0, 1) }}</div>
          <div class="body">
            <div class="top">
              <strong>{{ row.displayName }}</strong>
              <span v-if="row.watched" class="hx-chip warn">关注</span>
              <span v-if="row.streakDays" class="flame">🔥{{ row.streakDays }}</span>
              <span v-else-if="row.streakDays === 0" class="flame flame--out">已熄灭</span>
              <span
                v-if="checkinStatus(row)"
                class="checkin"
                :class="`checkin--${checkinStatus(row)!.tone}`"
              >{{ checkinStatus(row)!.text }}</span>
            </div>
            <p class="meta hx-muted">{{ meta(row) || '—' }}</p>
            <div v-if="row.diseaseLabels?.length" class="chips">
              <span v-for="d in row.diseaseLabels.slice(0, 2)" :key="d" class="hx-chip">{{
                d
              }}</span>
            </div>
            <div class="foot hx-muted">
              <span>{{ row.careTeamName || '未入组' }}</span>
              <span v-if="row.archiveCompletenessPercent != null">
                档案 {{ row.archiveCompletenessPercent }}%
              </span>
            </div>
          </div>
        </article>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.search {
  padding: 0 16px 4px !important;
}
.body {
  flex: 1;
  min-width: 0;
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.top strong {
  font-size: 16px;
  color: var(--hx-ink);
}
.flame {
  padding: 0 6px;
  border-radius: 999px;
  background: #fff1e3;
  color: #e0632a;
  font-size: 12px;
  font-weight: 700;
}
.flame--out {
  background: #f0f3f5;
  color: #a0aab4;
  font-weight: 600;
}
.checkin {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 700;
}
.checkin--ok {
  background: #e3f6ef;
  color: #0b7a66;
}
.checkin--idle {
  background: #f0f3f5;
  color: #8a96a3;
}
.checkin--warn {
  background: #fde7ea;
  color: #c8324a;
}
.meta {
  margin: 4px 0 0;
  font-size: 13px;
}
.chips {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.foot {
  margin-top: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.link {
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-size: 12px;
  font-weight: 700;
  padding: 0;
}
</style>
