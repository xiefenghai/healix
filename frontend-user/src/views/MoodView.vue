<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { addDays, ymd } from '../shared/diet'
import DailyTrendChart from '../shared/DailyTrendChart.vue'
import { lastNDays, parseYmd, type TrendBar } from '../shared/daily-trend'

interface MoodDay {
  date: string
  score?: number | null
  label?: string | null
  tags: string[]
  note?: string | null
  logged: boolean
  recordedByStaff?: boolean
  taskPresent: boolean
  checkinStatus?: string | null
  avgScore14d?: number | null
  trend: Array<{ date: string; score?: number; label?: string }>
}

const TAGS = ['平静', '焦虑', '疲惫', '愉悦', '压力大', '有动力']
const BACKFILL_DAYS = 7

const router = useRouter()
const loading = ref(true)
const busy = ref(false)
const day = ref<MoodDay | null>(null)
const today = ymd(new Date())
const selectedDate = ref(today)
const score = ref(3)
const selected = ref<string[]>([])
const note = ref('')

const dateLabel = computed(() => {
  if (selectedDate.value === today) return '今天'
  if (selectedDate.value === addDays(today, -1)) return '昨天'
  const [, m, d] = selectedDate.value.split('-')
  return `${Number(m)}月${Number(d)}日`
})

const dayOptions = computed(() => {
  const out: Array<{ date: string; label: string }> = []
  for (let i = 0; i < BACKFILL_DAYS; i++) {
    const date = addDays(today, -i)
    let label = date.slice(5)
    if (i === 0) label = '今天'
    else if (i === 1) label = '昨天'
    out.push({ date, label })
  }
  return out
})

const todayLine = computed(() => {
  if (!day.value?.logged) return `${dateLabel.value}尚未记录`
  const parts = [day.value.label, ...(day.value.tags || []).slice(0, 2)].filter(Boolean)
  return `${dateLabel.value}：${parts.join(' · ') || '已记'}`
})

const avgDisplay = computed(() => {
  const v = day.value?.avgScore14d
  if (v == null) return '—'
  return Number(v).toFixed(1)
})

const trendBars = computed<TrendBar[]>(() => {
  const d = day.value
  if (!d) return []
  const byDate = new Map((d.trend || []).map((t) => [String(t.date), t]))
  return lastNDays(14, parseYmd(d.date)).map((date) => {
    const t = byDate.get(date)
    const s = t?.score || 0
    return {
      date,
      value: s,
      tone: s >= 4 ? 'on' : s === 3 ? 'soft' : s > 0 ? 'warn' : '',
      display: s > 0 ? `${t?.label || ''} · ${s} 分` : '',
    }
  })
})

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: MoodDay }>(`/api/c/v1/me/mood/days/${selectedDate.value}`)
    day.value = res.data
    if (res.data.logged) {
      score.value = res.data.score || 3
      selected.value = [...(res.data.tags || [])]
      note.value = res.data.note || ''
    } else {
      score.value = 3
      selected.value = []
      note.value = ''
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function toggleTag(t: string) {
  const i = selected.value.indexOf(t)
  if (i >= 0) selected.value.splice(i, 1)
  else if (selected.value.length < 3) selected.value.push(t)
}

async function save() {
  busy.value = true
  try {
    const res = await api<{ data: MoodDay }>('/api/c/v1/me/mood/log', {
      method: 'POST',
      body: JSON.stringify({
        date: selectedDate.value,
        score: score.value,
        tags: selected.value,
        note: note.value || null,
      }),
    })
    day.value = res.data
    showToast({ type: 'success', message: '已记录心情' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    busy.value = false
  }
}

watch(selectedDate, load)
onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="心情记录" left-arrow fixed placeholder :border="false" @click-left="router.back()" />
    <van-loading v-if="loading && !day" vertical class="loading">加载中</van-loading>
    <template v-else-if="day">
      <div class="day-switch">
        <button
          v-for="opt in dayOptions"
          :key="opt.date"
          type="button"
          :class="{ active: selectedDate === opt.date }"
          @click="selectedDate = opt.date"
        >
          {{ opt.label }}
        </button>
      </div>

      <section class="card hero">
        <div class="hero-head">
          <h3>{{ dateLabel }}心情</h3>
          <small v-if="avgDisplay !== '—'">近 14 日均 {{ avgDisplay }}</small>
        </div>
        <div class="nums">
          <span class="big">{{ day.logged ? day.score ?? '—' : '—' }}</span>
          <span class="unit">/ 5</span>
        </div>
        <p class="sub">{{ todayLine }}</p>
      </section>

      <section v-if="day.recordedByStaff" class="card form">
        <p class="sec">{{ dateLabel }}心情由健管师代录，不可修改</p>
      </section>
      <section v-else class="card form">
        <p class="sec">记{{ dateLabel }}心情</p>
        <div class="scores">
          <button
            v-for="n in 5"
            :key="n"
            type="button"
            :class="{ on: score === n }"
            @click="score = n"
          >
            {{ n }}
          </button>
        </div>
        <div class="tags">
          <button
            v-for="t in TAGS"
            :key="t"
            type="button"
            :class="{ on: selected.includes(t) }"
            @click="toggleTag(t)"
          >
            {{ t }}
          </button>
        </div>
        <van-field v-model="note" label="备注" type="textarea" rows="2" maxlength="200" show-word-limit />
        <van-button round block type="primary" :loading="busy" @click="save">保存</van-button>
      </section>

      <section class="card trend-card">
        <div class="hero-head">
          <h3>近 14 日趋势</h3>
          <small>1–5 分</small>
        </div>
        <DailyTrendChart :bars="trendBars" color="var(--hx-mood)" :max="5" />
        <p class="trend-hint">近 14 日均 {{ avgDisplay }} 分；偏低的日子标红</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
}
.loading {
  padding: 48px 0;
}
.day-switch {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 12px 16px 0;
  -webkit-overflow-scrolling: touch;
}
.day-switch button {
  flex: none;
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: #fff;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 700;
  box-shadow: var(--hx-elev-2);
}
.day-switch button.active {
  background: var(--hx-teal);
  color: #fff;
}
.card {
  margin: 12px 16px 0;
  padding: 18px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.hero-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
}
.hero-head small {
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.nums {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.big {
  font-size: 44px;
  font-weight: 900;
  letter-spacing: -0.03em;
  line-height: 1;
}
.unit {
  font-size: 16px;
  font-weight: 700;
  color: var(--hx-n-600);
}
.sub {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.trend-hint {
  margin: 10px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.sec {
  margin: 0 0 8px;
  font-weight: 800;
  font-size: 14px;
}
.scores,
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 10px 0 14px;
}
.scores button,
.tags button {
  border: 0;
  background: var(--hx-n-100);
  color: var(--hx-n-700);
  border-radius: 12px;
  padding: 8px 14px;
  font-weight: 700;
}
.scores button {
  width: 44px;
  padding: 10px 0;
  text-align: center;
}
.scores button.on,
.tags button.on {
  background: var(--hx-mood-soft);
  color: var(--hx-mood);
  font-weight: 800;
}
</style>
