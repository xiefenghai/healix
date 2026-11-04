<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { ymd } from '../shared/diet'
import DailyTrendChart from '../shared/DailyTrendChart.vue'
import type { TrendBar } from '../shared/daily-trend'
import {
  hhmm,
  exerciseHowTo,
  INTENSITY_LABELS,
  type ExerciseFlag,
  type ExerciseSession,
  type ExerciseTaskItem,
  type ExerciseToday,
  type ExerciseWeek,
} from '../shared/exercise'

const router = useRouter()
const today = ymd(new Date())
const loading = ref(true)
const day = ref<ExerciseToday | null>(null)
const week = ref<ExerciseWeek | null>(null)

const RING_R = 54
const RING_C = 2 * Math.PI * RING_R

const planState = computed(() => day.value?.planState ?? 'NONE')
const ringPct = computed(() => {
  const w = week.value
  if (!w || !w.dueCount) return 0
  return Math.min(1, w.doneCount / w.dueCount)
})
const ringMuted = computed(() => planState.value !== 'ACTIVE' || !week.value?.dueCount)

const todaySteps = computed(() => week.value?.days.find((d) => d.date === today)?.steps ?? null)
const maxDayMin = computed(() => Math.max(30, ...(week.value?.days.map((d) => d.totalMin) ?? [0])) * 1.08)

function taskDone(t: ExerciseTaskItem) {
  return t.checkinStatus === 'DONE'
}

const weekBars = computed<TrendBar[]>(() =>
  (week.value?.days ?? []).map((d) => {
    const future = d.date > today
    const done = d.dueCount > 0 && d.doneCount >= d.dueCount
    return {
      date: d.date,
      value: future ? 0 : d.totalMin,
      tone: future ? 'future' : done ? 'on' : d.totalMin > 0 ? 'soft' : '',
      display: d.totalMin > 0 ? `${d.totalMin} 分钟${done ? ' · 计划完成' : ''}` : '',
    }
  }),
)

function flagClass(f: ExerciseFlag) {
  return f.level === 'RED' ? 'red' : f.level === 'YELLOW' ? 'yellow' : 'info'
}

async function load() {
  loading.value = true
  try {
    const [d, w] = await Promise.all([
      api<{ data: ExerciseToday }>('/api/c/v1/me/exercise/today'),
      api<{ data: ExerciseWeek }>('/api/c/v1/me/exercise/week'),
    ])
    day.value = d.data
    week.value = w.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function recordTask(t: ExerciseTaskItem) {
  router.push({
    path: '/exercise/add',
    query: {
      taskId: t.taskId,
      typeCode: t.typeCode || undefined,
      durationMin: String(Math.max(1, t.targetMin - t.doneMin)),
      intensity: t.intensity,
    },
  })
}

function openSession(s: ExerciseSession) {
  router.push({ path: '/exercise/add', query: { id: s.id } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="运动记录" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <van-loading v-if="loading && !day" vertical class="loading">加载中</van-loading>

    <template v-else-if="day && week">
      <section class="card summary">
        <div class="ring-wrap">
          <svg viewBox="0 0 132 132" class="ring">
            <circle cx="66" cy="66" :r="RING_R" class="ring-bg" />
            <circle
              cx="66"
              cy="66"
              :r="RING_R"
              class="ring-fg"
              :class="{ muted: ringMuted }"
              :stroke-dasharray="RING_C"
              :stroke-dashoffset="RING_C * (1 - ringPct)"
            />
          </svg>
          <div class="ring-text">
            <span class="ring-label">本周计划完成</span>
            <strong v-if="week.dueCount">{{ week.doneCount }}<small>/{{ week.dueCount }}</small></strong>
            <strong v-else class="muted-num">—</strong>
            <span class="ring-unit">次</span>
          </div>
        </div>
        <div class="stat-row">
          <div>
            <span>本周活跃</span>
            <strong>{{ week.activeMin }}<template v-if="week.weeklyActiveMinTarget">/{{ week.weeklyActiveMinTarget }}</template></strong>
            <em>分钟</em>
          </div>
          <div>
            <span>本周运动</span>
            <strong>{{ week.totalMin }}</strong>
            <em>分钟</em>
          </div>
          <div v-if="week.hasSteps">
            <span>今日步数</span>
            <strong>{{ todaySteps ?? '—' }}</strong>
            <em>步</em>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="sec-head">
          <strong>今日运动计划</strong>
        </div>
        <p v-if="planState === 'EXPIRED'" class="notice">运动计划已到期，请联系健管师更新。您仍可以记录运动。</p>
        <p v-else-if="planState === 'NONE'" class="notice">暂无运动计划，可以先自由记录运动。</p>
        <p v-else-if="!day.tasks.length" class="muted">今天没有运动任务，适当活动即可。</p>
        <div v-for="t in day.tasks" :key="t.taskId" class="task" :class="{ done: taskDone(t) }">
          <div class="task-main">
            <strong>{{ t.title }}</strong>
            <span v-if="exerciseHowTo(t.title)" class="howto">{{ exerciseHowTo(t.title) }}</span>
            <span class="muted">
              目标 {{ t.targetMin }} 分钟 · 强度{{ INTENSITY_LABELS[t.intensity] }} · 今日已累计 {{ t.doneMin }} 分钟
            </span>
            <div class="bar">
              <div class="bar-fill" :style="{ width: Math.min(100, (t.doneMin / t.targetMin) * 100) + '%' }" />
            </div>
          </div>
          <span v-if="taskDone(t)" class="done-tag"><van-icon name="success" /> 已完成</span>
          <button v-else type="button" class="add-btn" @click="recordTask(t)">完成了</button>
        </div>
        <div v-if="day.contraindications.length || day.precautions.length" class="tips">
          <p v-if="day.contraindications.length"><b>禁忌：</b>{{ day.contraindications.join('；') }}</p>
          <p v-if="day.precautions.length"><b>注意：</b>{{ day.precautions.join('；') }}</p>
        </div>
      </section>

      <section class="card">
        <div class="sec-head">
          <strong>今日记录</strong>
          <button type="button" class="add-btn" @click="router.push('/exercise/add')">
            <van-icon name="plus" /> 记录运动
          </button>
        </div>
        <p v-if="!day.sessions.length" class="muted">还没有记录</p>
        <div v-for="s in day.sessions" :key="s.id" class="rec" @click="openSession(s)">
          <div class="rec-main">
            <strong>{{ s.typeName }}</strong>
            <span class="muted">{{ hhmm(s.startedAt) }} · {{ s.durationMin }} 分钟 · {{ INTENSITY_LABELS[s.intensity] }}</span>
          </div>
          <span v-if="s.level !== 'GREEN'" class="dot" :class="s.level.toLowerCase()" />
          <van-icon name="arrow" class="muted" />
          <p
            v-for="f in s.flags.filter((x) => x.level !== 'INFO')"
            :key="f.code"
            class="flag small"
            :class="flagClass(f)"
          >
            {{ f.message }}
          </p>
          <p v-if="s.recordedByStaff" class="muted tiny">健管师代录</p>
        </div>
      </section>

      <section class="card">
        <div class="sec-head"><strong>本周</strong><span class="muted">分钟</span></div>
        <DailyTrendChart :bars="weekBars" color="var(--hx-orange)" :max="maxDayMin" />
        <p class="muted legend">深色表示当天计划已完成</p>
      </section>

      <p class="disclaimer">活跃分钟按世界卫生组织口径：中等强度 1 分钟计 1，较累计 2，轻松不计。</p>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
}
:deep(.van-nav-bar) {
  background: rgba(255, 250, 242, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.loading {
  padding: 48px 0;
}
.muted {
  color: var(--hx-muted);
  font-size: 12px;
}
.tiny {
  margin: 4px 0 0;
  font-size: 11px;
  width: 100%;
}
.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.summary {
  text-align: center;
}
.ring-wrap {
  position: relative;
  width: 160px;
  height: 160px;
  margin: 0 auto;
}
.ring {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ring-bg {
  fill: none;
  stroke: var(--hx-n-100);
  stroke-width: 12;
}
.ring-fg {
  fill: none;
  stroke: var(--hx-teal);
  stroke-width: 12;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.4s ease;
}
.ring-fg.muted {
  stroke: var(--hx-n-400);
}
.ring-text {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.ring-label,
.ring-unit {
  font-size: 12px;
  color: var(--hx-muted);
}
.ring-text strong {
  font-size: 30px;
  font-weight: 800;
}
.ring-text small {
  font-size: 16px;
  color: var(--hx-muted);
}
.muted-num {
  color: var(--hx-n-400);
}
.stat-row {
  display: flex;
  justify-content: center;
  gap: 28px;
  margin-top: 8px;
}
.stat-row div {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: var(--hx-muted);
}
.stat-row strong {
  font-size: 16px;
  color: var(--hx-text);
}
.stat-row em {
  font-style: normal;
  font-size: 11px;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.sec-head strong {
  font-size: 16px;
}
.sec-head .muted {
  font-size: 12px;
}
.notice {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft);
}
.task {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px dashed var(--hx-n-200);
}
.task:first-of-type {
  border-top: 0;
}
.task-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.task.done .task-main strong {
  color: var(--hx-muted);
}
.howto {
  font-size: 13px;
  line-height: 1.45;
  color: var(--hx-n-800, #3d4b58);
}
.bar {
  height: 6px;
  border-radius: 3px;
  background: var(--hx-n-100);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--hx-teal-soft);
}
.done-tag {
  font-size: 13px;
  color: var(--hx-teal);
  white-space: nowrap;
}
.add-btn {
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.tips {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-n-50);
  font-size: 12px;
  line-height: 1.6;
  color: var(--hx-text);
}
.tips p {
  margin: 0;
}
.rec {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-top: 1px dashed var(--hx-n-200);
}
.rec:first-of-type {
  border-top: 0;
}
.rec-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.dot.red {
  background: var(--hx-warn);
}
.dot.yellow {
  background: var(--hx-orange);
}
.flag {
  width: 100%;
  margin: 4px 0 0;
  padding: 6px 8px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.5;
}
.flag.red {
  color: var(--hx-danger-text);
  background: var(--hx-danger-bg);
}
.flag.yellow {
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft);
}
.legend {
  margin: 4px 0 0;
}
.disclaimer {
  margin: 16px 24px 0;
  font-size: 11px;
  color: var(--hx-muted);
  text-align: center;
}
</style>
