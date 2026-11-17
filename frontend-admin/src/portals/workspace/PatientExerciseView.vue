<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Basketball, InfoFilled } from '@element-plus/icons-vue'
import { api } from '../../shared/http'

interface ExerciseFlag {
  code: string
  level: 'RED' | 'YELLOW' | 'INFO'
  message: string
}

interface ExerciseSession {
  id: string
  exerciseDate: string
  startedAt: string
  durationMin: number
  typeCode: string
  typeName: string
  category: string
  intensity: string
  avgHr?: number | null
  distanceM?: number | null
  activeMin: number
  energyKcal: number
  weightEstimated: boolean
  symptoms: string[]
  flags: ExerciseFlag[]
  level: 'GREEN' | 'YELLOW' | 'RED'
  alertTaskId?: string | null
  source: string
  note?: string | null
  recordedByStaff: boolean
  /** 健管师只能改代录的记录 */
  editable: boolean
}

interface TaskItem {
  taskId: string
  title: string
  category: string
  targetMin: number
  intensity: string
  doneMin: number
  checkinStatus?: string | null
}

interface Today {
  planState: 'ACTIVE' | 'EXPIRED' | 'NONE'
  goal?: string | null
  tasks: TaskItem[]
  contraindications: string[]
  precautions: string[]
  reviewHint?: string | null
}

interface WeekDay {
  date: string
  dueCount: number
  doneCount: number
  activeMin: number
  totalMin: number
  steps?: number | null
  level?: string | null
}

interface Week {
  weekStart: string
  weekEnd: string
  planState: string
  dueCount: number
  doneCount: number
  activeMin: number
  weeklyActiveMinTarget: number
  totalMin: number
  sessionCount: number
  hasSteps: boolean
  days: WeekDay[]
}

interface ExerciseType {
  code: string
  name: string
  category: string
  defaultIntensity: string
}

const INTENSITY_LABELS: Record<string, string> = { LIGHT: '轻松', MODERATE: '中等', VIGOROUS: '较累' }
const SYMPTOM_OPTIONS = [
  { value: 'DIZZY', label: '头晕' },
  { value: 'PALPITATION', label: '心慌' },
  { value: 'JOINT_PAIN', label: '关节疼痛' },
  { value: 'HYPO', label: '低血糖症状' },
  { value: 'CHEST_PAIN', label: '胸痛胸闷' },
  { value: 'SYNCOPE', label: '晕厥或快晕倒' },
  { value: 'SEVERE_DYSPNEA', label: '严重气短' },
]
const SYMPTOM_LABELS: Record<string, string> = Object.fromEntries(SYMPTOM_OPTIONS.map((o) => [o.value, o.label]))
const SOURCE_LABELS: Record<string, string> = {
  MANUAL: '手动',
  PLAN_QUICK: '计划打卡',
  STAFF: '健管师代录',
  SCREENSHOT_AI: '截图识别',
  TEXT_AI: '文字识别',
  DEVICE: '设备同步',
}
const PLAN_STATE_TEXT: Record<string, string> = {
  EXPIRED: '运动计划已到期，请在「管理方案」中发布新版本',
  NONE: '尚无生效的管理方案，运动计划随方案发布',
}
const WEEKDAY = ['一', '二', '三', '四', '五', '六', '日']
const LIST_DAYS = 30
const BACKFILL_DAYS = 7

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const base = computed(() => `/api/b/v1/patients/${peopleId.value}/exercise`)

interface SessionDayGroup {
  date: string
  dateLabel: string
  totalMin: number
  totalActiveMin: number
  totalKcal: number
  weightEstimated: boolean
  typeSummary: string
  hints: ExerciseFlag[]
  sessions: ExerciseSession[]
}

const today = ref<Today | null>(null)
const week = ref<Week | null>(null)
const sessions = ref<ExerciseSession[]>([])
const expandedDays = ref<Set<string>>(new Set())
const planExpanded = ref(false)
const weekDate = ref(ymd(new Date()))
const loadingTop = ref(false)
const loadingList = ref(false)

const hasPlanContent = computed(() => {
  const t = today.value
  if (!t) return false
  return !!(
    t.goal ||
    t.contraindications?.length ||
    t.precautions?.length ||
    t.reviewHint ||
    t.tasks?.length
  )
})

const types = ref<ExerciseType[]>([])
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  typeCode: '',
  startedAt: '',
  durationMin: 30 as number | undefined,
  intensity: 'MODERATE',
  symptoms: [] as string[],
  avgHr: undefined as number | undefined,
  distanceKm: undefined as number | undefined,
  note: '',
})

function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + n)
  return ymd(dt)
}

const isCurrentWeek = computed(() => !!week.value && week.value.weekEnd >= ymd(new Date()))
const CHART_TRACK_H = 120
const maxDayMin = computed(() => {
  const peak = Math.max(0, ...(week.value?.days.map((d) => d.totalMin) ?? [0]))
  return Math.max(peak, 30)
})
const weekDonePct = computed(() => {
  if (!week.value?.dueCount) return 0
  return Math.round((week.value.doneCount / week.value.dueCount) * 100)
})

function barHeightPx(totalMin: number) {
  if (!totalMin) return 0
  return Math.max(6, Math.round((totalMin / maxDayMin.value) * CHART_TRACK_H))
}

function taskPct(t: TaskItem) {
  return Math.min(100, Math.round((t.doneMin / Math.max(1, t.targetMin)) * 100))
}

function activeMinTooltip(row: ExerciseSession) {
  if (row.intensity === 'LIGHT') {
    return '强度未达标不计入（WHO：轻松强度不计活动分钟）'
  }
  if (row.intensity === 'VIGOROUS') {
    return '按 WHO 口径较累强度应计 2×时长；当前为 0，请核对记录'
  }
  if (row.intensity === 'MODERATE') {
    return '按 WHO 口径中等强度应计时长本身；当前为 0，请核对记录'
  }
  return '强度未达标不计入活动分钟'
}

function sessionDate(s: ExerciseSession) {
  return s.exerciseDate || s.startedAt.slice(0, 10)
}

const sessionDays = computed<SessionDayGroup[]>(() => {
  const map = new Map<string, ExerciseSession[]>()
  for (const s of sessions.value) {
    const date = sessionDate(s)
    const list = map.get(date)
    if (list) list.push(s)
    else map.set(date, [s])
  }
  return [...map.entries()]
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .map(([date, rows]) => {
      const ordered = [...rows].sort((a, b) => (a.startedAt < b.startedAt ? 1 : -1))
      const typeCount = new Map<string, number>()
      let totalMin = 0
      let totalActiveMin = 0
      let totalKcal = 0
      let weightEstimated = false
      const hints: ExerciseFlag[] = []
      const hintKeys = new Set<string>()
      for (const s of ordered) {
        totalMin += s.durationMin || 0
        totalActiveMin += s.activeMin || 0
        totalKcal += s.energyKcal || 0
        weightEstimated = weightEstimated || !!s.weightEstimated
        typeCount.set(s.typeName, (typeCount.get(s.typeName) || 0) + 1)
        for (const f of s.flags || []) {
          if (f.code.startsWith('SYMPTOM')) continue
          const key = `${f.code}|${f.message}`
          if (hintKeys.has(key)) continue
          hintKeys.add(key)
          hints.push(f)
        }
      }
      return {
        date,
        dateLabel: date.slice(5),
        totalMin,
        totalActiveMin,
        totalKcal: Math.round(totalKcal),
        weightEstimated,
        typeSummary: [...typeCount.entries()].map(([name, n]) => `${name} ${n}次`).join(' / '),
        hints,
        sessions: ordered,
      }
    })
})

function isDayExpanded(date: string) {
  return expandedDays.value.has(date)
}

function toggleDay(date: string) {
  const next = new Set(expandedDays.value)
  if (next.has(date)) next.delete(date)
  else next.add(date)
  expandedDays.value = next
}

function syncExpandedDays(list: ExerciseSession[]) {
  if (!list.length) {
    expandedDays.value = new Set()
    return
  }
  const latest = list
    .map(sessionDate)
    .sort((a, b) => (a < b ? 1 : -1))[0]
  expandedDays.value = new Set(latest ? [latest] : [])
}

function hintFlags(row: ExerciseSession) {
  return (row.flags || []).filter((x) => !x.code.startsWith('SYMPTOM'))
}

function fmtClock(s: string) {
  const t = s.includes('T') ? s.split('T')[1] : s.slice(11)
  return (t || '').slice(0, 5)
}

function fmtTime(s: string) {
  return s.replace('T', ' ').slice(5, 16)
}

async function loadTop() {
  loadingTop.value = true
  try {
    const [t, w] = await Promise.all([
      api<{ data: Today }>(`${base.value}/today`),
      api<{ data: Week }>(`${base.value}/week?date=${weekDate.value}`),
    ])
    today.value = t.data
    week.value = w.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动概览失败')
  } finally {
    loadingTop.value = false
  }
}

async function loadWeek() {
  try {
    const res = await api<{ data: Week }>(`${base.value}/week?date=${weekDate.value}`)
    week.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载周汇总失败')
  }
}

async function loadSessions() {
  loadingList.value = true
  const to = ymd(new Date())
  const from = addDays(to, -(LIST_DAYS - 1))
  try {
    const res = await api<{ data: ExerciseSession[] }>(`${base.value}/sessions?from=${from}&to=${to}`)
    sessions.value = res.data || []
    syncExpandedDays(sessions.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动记录失败')
  } finally {
    loadingList.value = false
  }
}

async function loadTypes() {
  if (types.value.length) return
  const res = await api<{ data: ExerciseType[] }>('/api/b/v1/exercise/types?limit=100')
  types.value = res.data || []
}

function shiftWeek(n: number) {
  weekDate.value = addDays(weekDate.value, n * 7)
  void loadWeek()
}

function goCarePlan() {
  router.push({ path: `/workspace/patients/${peopleId.value}/care-plan`, query: route.query })
}

async function openDialog(s?: ExerciseSession) {
  try {
    await loadTypes()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动类型失败')
    return
  }
  editingId.value = s?.id ?? null
  const now = new Date()
  form.typeCode = s?.typeCode ?? ''
  form.startedAt = s?.startedAt?.slice(0, 19) ?? `${ymd(now)}T${String(now.getHours()).padStart(2, '0')}:00:00`
  form.durationMin = s?.durationMin ?? 30
  form.intensity = s?.intensity ?? 'MODERATE'
  form.symptoms = s ? [...s.symptoms] : []
  form.avgHr = s?.avgHr ?? undefined
  form.distanceKm = s?.distanceM ? s.distanceM / 1000 : undefined
  form.note = s?.note ?? ''
  dialog.value = true
}

function onTypeChange(code: string) {
  const t = types.value.find((x) => x.code === code)
  if (t && !editingId.value) form.intensity = t.defaultIntensity
}

function disabledDate(d: Date) {
  const day = ymd(d)
  const now = ymd(new Date())
  return day > now || day < addDays(now, -(BACKFILL_DAYS - 1))
}

async function save() {
  if (!form.typeCode) return ElMessage.warning('请选择运动类型')
  if (!form.startedAt) return ElMessage.warning('请选择开始时间')
  if (!form.durationMin) return ElMessage.warning('请填写时长')
  saving.value = true
  try {
    const body = {
      typeCode: form.typeCode,
      startedAt: form.startedAt,
      durationMin: form.durationMin,
      intensity: form.intensity,
      symptoms: form.symptoms,
      avgHr: form.avgHr ?? null,
      distanceM: form.distanceKm ? Math.round(form.distanceKm * 1000) : null,
      note: form.note.trim() || null,
      source: 'STAFF',
    }
    const res = await api<{ data: ExerciseSession }>(
      editingId.value ? `${base.value}/sessions/${editingId.value}` : `${base.value}/sessions`,
      { method: editingId.value ? 'PUT' : 'POST', body: JSON.stringify(body) },
    )
    ElMessage.success(res.data.level === 'RED' ? '已保存，并已生成「运动不适」待办' : '已保存')
    dialog.value = false
    await Promise.all([loadTop(), loadSessions()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(s: ExerciseSession) {
  try {
    await ElMessageBox.confirm(`删除 ${fmtTime(s.startedAt)} 的「${s.typeName}」记录？`, '删除记录', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`${base.value}/sessions/${s.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await Promise.all([loadTop(), loadSessions()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function reload() {
  weekDate.value = ymd(new Date())
  void loadTop()
  void loadSessions()
}

watch(peopleId, reload)
onMounted(reload)
</script>

<template>
  <div class="exercise-page">
    <el-card v-loading="loadingTop" shadow="never" class="section-card section-card--plan">
      <template #header>
        <div class="card-head plan-card-head">
          <button type="button" class="plan-title-btn" @click="planExpanded = !planExpanded">
            <span class="plan-icon" aria-hidden="true">
              <el-icon><Basketball /></el-icon>
            </span>
            <span class="section-title">运动方案</span>
            <el-icon class="plan-chevron" :class="{ 'plan-chevron--open': planExpanded }">
              <ArrowDown />
            </el-icon>
          </button>
          <button type="button" class="plan-adjust-btn" @click="goCarePlan">调整计划（管理方案）</button>
        </div>
      </template>
      <template v-if="today && planExpanded">
        <el-alert
          v-if="today.planState !== 'ACTIVE'"
          class="plan-state-alert"
          :title="PLAN_STATE_TEXT[today.planState]"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-empty
          v-else-if="!hasPlanContent"
          :image-size="60"
          description="管理方案中尚未填写运动方案"
        />
        <div v-else class="plan-scheme">
          <p v-if="today.goal" class="section-lead">{{ today.goal }}</p>

          <div v-if="today.contraindications.length" class="info-block info-block--danger">
            <div class="info-block-title">禁忌</div>
            <ul class="info-list">
              <li v-for="(item, i) in today.contraindications" :key="'c-' + i">{{ item }}</li>
            </ul>
          </div>

          <div v-if="today.precautions.length" class="info-block info-block--caution">
            <div class="info-block-title">注意事项</div>
            <ul class="info-list">
              <li v-for="(item, i) in today.precautions" :key="'p-' + i">{{ item }}</li>
            </ul>
          </div>

          <p v-if="today.reviewHint" class="section-hint">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ today.reviewHint }}</span>
          </p>

          <div v-if="today.planState === 'ACTIVE' && today.tasks.length" class="today-progress">
            <div class="today-progress-title">今日进度</div>
            <div class="tasks">
              <div v-for="t in today.tasks" :key="t.taskId" class="task-card">
                <div class="task-head">
                  <div class="task-title-row">
                    <strong>{{ t.title }}</strong>
                    <el-tag size="small" effect="plain">{{ INTENSITY_LABELS[t.intensity] || t.intensity }}</el-tag>
                    <el-tag v-if="t.checkinStatus === 'DONE'" size="small" type="success">今日已完成</el-tag>
                  </div>
                  <span class="task-progress-num">
                    <b>{{ t.doneMin }}</b><span class="task-progress-den">/{{ t.targetMin }}</span>
                    <span class="task-progress-unit">分钟</span>
                  </span>
                </div>
                <div class="task-bar" aria-hidden="true">
                  <span class="task-bar-fill" :style="{ width: `${taskPct(t)}%` }" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </el-card>

    <el-card v-loading="loadingTop" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">
            周汇总
            <span v-if="week" class="muted">{{ week.weekStart.slice(5) }} ~ {{ week.weekEnd.slice(5) }}</span>
          </span>
          <div class="head-actions week-nav">
            <el-button-group>
              <el-button class="week-nav-btn" @click="shiftWeek(-1)">上一周</el-button>
              <el-button class="week-nav-btn" :disabled="isCurrentWeek" @click="shiftWeek(1)">下一周</el-button>
            </el-button-group>
          </div>
        </div>
      </template>
      <template v-if="week">
        <div class="week-stats">
          <div class="week-stat">
            <span class="week-stat-label">计划完成</span>
            <strong class="week-stat-value">
              {{ week.doneCount }}/{{ week.dueCount }}
              <em v-if="week.dueCount">{{ weekDonePct }}%</em>
            </strong>
          </div>
          <div class="week-stat">
            <span class="week-stat-label">活动分钟（WHO）</span>
            <strong class="week-stat-value">
              {{ week.activeMin }}
              <em v-if="week.weeklyActiveMinTarget">/ {{ week.weeklyActiveMinTarget }}</em>
            </strong>
          </div>
          <div class="week-stat">
            <span class="week-stat-label">总时长</span>
            <strong class="week-stat-value">{{ week.totalMin }}<em> 分钟</em></strong>
          </div>
          <div class="week-stat">
            <span class="week-stat-label">记录次数</span>
            <strong class="week-stat-value">{{ week.sessionCount }}</strong>
          </div>
        </div>

        <div class="week-chart">
          <div class="week-chart-vals">
            <div v-for="d in week.days" :key="`${d.date}-val`" class="week-val">
              <template v-if="d.totalMin">{{ d.totalMin }}分钟</template>
              <template v-else>—</template>
            </div>
          </div>
          <div class="week-chart-plot" :style="{ height: `${CHART_TRACK_H}px` }">
            <div class="week-chart-grid" aria-hidden="true" />
            <div class="week-chart-cols">
              <div v-for="d in week.days" :key="d.date" class="week-col">
                <div
                  v-if="d.totalMin"
                  class="week-bar"
                  :class="{
                    'week-bar--warn': d.level === 'YELLOW',
                    'week-bar--danger': d.level === 'RED',
                  }"
                  :style="{ height: `${barHeightPx(d.totalMin)}px` }"
                  :title="`${d.totalMin} 分钟 · 活动 ${d.activeMin} 分钟`"
                />
              </div>
            </div>
          </div>
          <div class="week-chart-axis">
            <div v-for="(d, i) in week.days" :key="`${d.date}-axis`" class="week-axis-cell">
              <div class="week-x">周{{ WEEKDAY[i] }}</div>
              <div class="week-due">
                <template v-if="d.dueCount">{{ d.doneCount }}/{{ d.dueCount }}</template>
                <template v-else>—</template>
              </div>
              <div v-if="week.hasSteps" class="week-steps">{{ d.steps ?? '—' }} 步</div>
            </div>
          </div>
        </div>
        <p class="chart-legend">
          柱高为当日运动总时长；下方为计划任务完成数/应完成数；橙/红色表示当日有不适提示。
        </p>
      </template>
    </el-card>

    <el-card v-loading="loadingList" shadow="never" class="section-card section-card--sessions">
      <template #header>
        <div class="card-head">
          <span class="section-title">近 {{ LIST_DAYS }} 天运动记录</span>
          <el-button type="primary" class="record-btn" @click="openDialog()">代录运动</el-button>
        </div>
      </template>

      <el-empty v-if="!sessionDays.length" :image-size="64" description="暂无运动记录" />

      <div v-else class="day-groups">
        <div
          v-for="day in sessionDays"
          :key="day.date"
          class="day-group"
          :class="{ 'day-group--open': isDayExpanded(day.date) }"
        >
          <button type="button" class="day-summary" @click="toggleDay(day.date)">
            <span class="day-toggle" aria-hidden="true">{{ isDayExpanded(day.date) ? '▾' : '▸' }}</span>
            <div class="day-summary-main">
              <strong class="day-date">{{ day.dateLabel }}</strong>
              <span class="day-types">{{ day.typeSummary }}</span>
            </div>
            <div class="day-summary-metrics">
              <span class="day-metric">
                <em>总时长</em>
                <b>{{ day.totalMin }}</b><i>分钟</i>
              </span>
              <span class="day-metric">
                <em class="th-with-tip">
                  活动分钟
                  <el-tooltip content="仅计算中高强度运动分钟数（WHO：轻松不计、中等×1、较累×2）" placement="top">
                    <span class="th-help" tabindex="0" @click.stop>?</span>
                  </el-tooltip>
                </em>
                <template v-if="day.totalActiveMin">
                  <b>{{ day.totalActiveMin }}</b><i>分钟</i>
                </template>
                <el-tooltip v-else content="强度未达标不计入" placement="top">
                  <b class="active-empty" @click.stop>-</b>
                </el-tooltip>
              </span>
              <span class="day-metric">
                <em>总消耗</em>
                <b>{{ day.totalKcal }}</b><i>kcal</i>
                <i v-if="day.weightEstimated" class="num-star">*</i>
              </span>
            </div>
            <div v-if="day.hints.length" class="day-summary-hints" @click.stop>
              <el-tooltip
                v-for="f in day.hints"
                :key="`${day.date}-${f.code}-${f.message}`"
                :content="f.message"
                placement="top"
              >
                <span class="day-hint-tag" :class="`day-hint-tag--${(f.level || 'INFO').toLowerCase()}`">
                   {{ f.message }}
                </span>
              </el-tooltip>
            </div>
          </button>

          <div v-if="isDayExpanded(day.date)" class="day-detail">
            <div class="day-detail-head">
              <span>时间</span>
              <span>运动</span>
              <span>时长</span>
              <span>强度</span>
              <span class="th-with-tip">
                活动分钟
                <el-tooltip content="仅计算中高强度运动分钟数（WHO：轻松不计、中等×1、较累×2）" placement="top">
                  <span class="th-help" tabindex="0">?</span>
                </el-tooltip>
              </span>
              <span>消耗</span>
              <span>来源</span>
              <span>操作</span>
            </div>
            <div v-for="row in day.sessions" :key="row.id" class="day-detail-row">
              <div class="day-detail-grid">
                <span class="cell-text">{{ fmtClock(row.startedAt) }}</span>
                <span class="cell-text">{{ row.typeName }}</span>
                <span class="num-cell num-cell--center">
                  <b>{{ row.durationMin }}</b><span class="num-unit">分钟</span>
                </span>
                <span class="cell-text cell-center">{{ INTENSITY_LABELS[row.intensity] || row.intensity }}</span>
                <el-tooltip
                  v-if="!row.activeMin"
                  placement="top"
                  :content="activeMinTooltip(row)"
                >
                  <span class="active-empty">-</span>
                </el-tooltip>
                <span v-else class="num-cell num-cell--center">
                  <b>{{ row.activeMin }}</b><span class="num-unit">分钟</span>
                </span>
                <span class="num-cell num-cell--center">
                  <b>{{ Math.round(row.energyKcal) }}</b>
                  <span class="num-unit">kcal</span>
                  <span v-if="row.weightEstimated" class="num-star">*</span>
                </span>
                <span class="cell-muted">{{ SOURCE_LABELS[row.source] || row.source }}</span>
                <div class="row-actions">
                  <template v-if="row.editable">
                    <el-button text type="primary" size="small" @click="openDialog(row)">编辑</el-button>
                    <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
                  </template>
                  <span v-else class="readonly-tag">患者自录</span>
                </div>
              </div>
              <div
                v-if="row.symptoms.length || hintFlags(row).length || row.note"
                class="day-detail-extra"
              >
                <el-tag v-for="s in row.symptoms" :key="s" size="small" type="danger" effect="plain">
                  {{ SYMPTOM_LABELS[s] || s }}
                </el-tag>
                <el-tooltip
                  v-for="f in hintFlags(row)"
                  :key="f.code"
                  :content="f.message"
                  placement="top"
                >
                  <span class="hint-chip" :class="`hint-chip--${(f.level || 'INFO').toLowerCase()}`">
                    <i aria-hidden="true"></i>
                    <em>{{ f.message }}</em>
                  </span>
                </el-tooltip>
                <span v-if="row.note" class="detail-note">备注：{{ row.note }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <p class="legend">
        默认展开最近一天；点击日期行可展开/收起明细。活动分钟按 WHO 口径：轻松不计、中等×1、较累×2，未计入显示「-」。*
        体重缺失按 60kg 估算消耗，仅供参考。患者自录只读，代录可编辑。
      </p>
    </el-card>

    <el-dialog v-model="dialog" :title="editingId ? '编辑运动记录' : '代录运动'" width="560px" append-to-body>
      <el-form label-width="88px">
        <el-form-item label="运动类型" required>
          <el-select v-model="form.typeCode" filterable placeholder="搜索运动" style="width: 100%" @change="onTypeChange">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-date-picker
            v-model="form.startedAt"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled-date="disabledDate"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item label="时长" required>
          <el-input-number v-model="form.durationMin" :min="1" :max="600" />
          <span class="unit">分钟</span>
        </el-form-item>
        <el-form-item label="强度" required>
          <el-radio-group v-model="form.intensity">
            <el-radio-button v-for="(label, k) in INTENSITY_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="不适症状">
          <el-checkbox-group v-model="form.symptoms">
            <el-checkbox v-for="o in SYMPTOM_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="距离 / 心率">
          <el-input-number v-model="form.distanceKm" :min="0" :max="100" :precision="2" :controls="false" placeholder="公里" />
          <el-input-number
            v-model="form.avgHr"
            :min="30"
            :max="220"
            :controls="false"
            placeholder="平均心率"
            class="ml"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.exercise-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}
.section-card {
  border: 1px solid var(--ink-125);
  border-radius: 12px;
}
.section-card :deep(.el-card__header) {
  padding: 14px 20px;
  background: transparent;
  border-bottom: none;
}
.section-card :deep(.el-card__body) {
  padding: 4px 20px 20px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text, var(--ink-800));
}
.head-actions {
  display: flex;
  gap: 8px;
}
.plan-card-head {
  width: 100%;
}
.plan-title-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.plan-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--blue-50);
  color: var(--blue-600);
  font-size: 16px;
}
.plan-chevron {
  color: var(--gray-400);
  font-size: 14px;
  transition: transform 0.15s ease;
}
.plan-chevron--open {
  transform: rotate(180deg);
}
.plan-adjust-btn {
  box-sizing: border-box;
  height: 32px;
  padding: 0 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--gray-500);
  font-size: 13px;
  font-weight: 500;
  line-height: 32px;
  cursor: pointer;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}
.plan-adjust-btn:hover {
  color: var(--ink-750);
  background: var(--gray-100);
}
.muted {
  margin-left: 6px;
  color: var(--ink-450);
  font-size: 12px;
  font-weight: 400;
}
.plan-state-alert {
  margin-bottom: 12px;
}
.plan-scheme {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.section-lead {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink-700);
}
.info-block {
  margin: 12px 0 0;
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  border-radius: 0;
  background: var(--gray-50);
  border-left: 4px solid var(--ink-400);
}
.info-block--danger {
  background: var(--rose-100);
  border-left: 4px solid var(--rose-500);
}
.info-block--danger .info-block-title {
  color: var(--rose-800);
}
.info-block--caution {
  background: var(--auto-fefce8);
  border-left: 4px solid var(--auto-eab308);
}
.info-block--caution .info-block-title {
  color: var(--auto-a16207);
}
.info-block-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.3;
}
.info-list {
  margin: 0;
  padding-left: 1.15em;
  font-size: 13px;
  line-height: 1.7;
  color: var(--gray-800);
}
.info-list li + li {
  margin-top: 4px;
}
.section-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--auto-f0f9ff);
  font-size: 13px;
  line-height: 1.6;
  color: var(--auto-0369a1);
}
.section-hint .el-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 16px;
}
.today-progress {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 0 solid var(--gray-100);
}
.today-progress-title {
  margin-bottom: 10px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-500);
}
.tasks {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.task-card {
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  background: var(--white);
}
.task-card:last-child {
  margin-bottom: 0;
}
.task-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}
.task-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.task-title-row strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-900);
  line-height: 1.4;
}
.task-progress-num {
  display: inline-flex;
  align-items: baseline;
  flex-shrink: 0;
  gap: 2px;
  white-space: nowrap;
  line-height: 1.2;
}
.task-progress-num b {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-950);
}
.task-progress-den {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-400);
}
.task-progress-unit {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 400;
  color: var(--gray-400);
}
.task-bar {
  position: relative;
  width: 100%;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--gray-100);
}
.task-bar-fill {
  display: block;
  height: 100%;
  max-width: 100%;
  border-radius: inherit;
  background: var(--blue-500);
  transition: width 0.2s ease;
}

.week-stats {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 0;
  margin-bottom: 20px;
  padding: 12px 0;
  border-bottom: 0 solid var(--gray-100);
}
.week-stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 20px;
  border-right: 0 solid var(--gray-200);
}
.week-stat:first-child {
  padding-left: 0;
}
.week-stat:last-child {
  border-right: none;
  padding-right: 0;
}
.week-stat-label {
  font-size: 12px;
  line-height: 1.2;
  color: var(--gray-400);
}
.week-stat-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink-900);
}
.week-stat-value em {
  margin-left: 4px;
  font-size: 13px;
  font-style: normal;
  font-weight: 500;
  color: var(--gray-500);
}

.week-nav :deep(.el-button-group) {
  display: inline-flex;
}
.week-nav-btn {
  height: 32px;
  padding: 0 12px;
  border-color: var(--gray-200) !important;
  background: var(--gray-50) !important;
  color: var(--gray-500) !important;
  font-weight: 500;
}
.week-nav-btn:hover,
.week-nav-btn:focus {
  background: var(--gray-100) !important;
  border-color: var(--gray-300) !important;
  color: var(--ink-750) !important;
}
.week-nav-btn.is-disabled {
  background: var(--gray-50) !important;
  color: var(--gray-300) !important;
}
.week-chart {
  box-sizing: border-box;
  width: 100%;
  max-width: none;
}
.week-chart-plot {
  position: relative;
  width: 100%;
  border-bottom: 0 solid var(--gray-200);
}
.week-chart-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: repeating-linear-gradient(
    to top,
    transparent 0,
    transparent calc(25% - 1px),
    var(--gray-100) calc(25% - 1px),
    var(--gray-100) 25%
  );
}
.week-chart-vals,
.week-chart-cols,
.week-chart-axis {
  display: flex;
  justify-content: space-between;
  width: 100%;
}
.week-chart-vals {
  margin-bottom: 6px;
}
.week-chart-cols {
  position: relative;
  z-index: 1;
  align-items: flex-end;
  height: 100%;
}
.week-val,
.week-col,
.week-axis-cell {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
}
.week-val {
  min-height: 16px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ink-500);
  white-space: nowrap;
  text-align: center;
}
.week-col {
  height: 100%;
  justify-content: flex-end;
}
.week-bar {
  width: 24px;
  max-width: 60%;
  border-radius: 4px 4px 0 0;
  background: var(--auto-14b8a6);
  transition: height 0.2s ease;
}
.week-bar--warn {
  background: var(--amber-500);
}
.week-bar--danger {
  background: var(--rose-500);
}
.week-chart-axis {
  margin-top: 8px;
}
.week-x {
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink-700);
}
.week-due {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.2;
  color: var(--gray-400);
}
.week-steps {
  margin-top: 2px;
  font-size: 11px;
  color: var(--gray-400);
}
.chart-legend {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--gray-400);
}
.legend {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--gray-400);
}
.record-btn {
  height: 36px;
  padding: 0 14px;
}
.day-groups {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.day-group {
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  background: var(--white);
  overflow: hidden;
}
.day-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
  width: 100%;
  padding: 12px 14px;
  border: none;
  background: var(--white);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;
}
.day-summary:hover {
  background: var(--ink-5);
}
.day-group--open > .day-summary {
  border-bottom: 0 solid var(--gray-100);
}
.day-toggle {
  flex-shrink: 0;
  width: 16px;
  color: var(--gray-400);
  font-size: 12px;
  line-height: 1;
}
.day-summary-main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  min-width: 160px;
  flex: 1 1 180px;
}
.day-date {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.2;
}
.day-types {
  font-size: 13px;
  color: var(--ink-500);
  line-height: 1.35;
}
.day-summary-metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 18px;
  flex: 0 1 auto;
}
.day-metric {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  white-space: nowrap;
}
.day-metric em {
  font-style: normal;
  font-size: 11px;
  color: var(--gray-400);
}
.day-metric b {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-950);
}
.day-metric i {
  font-style: normal;
  font-size: 11px;
  color: var(--gray-400);
}
.day-summary-hints {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  margin-left: auto;
  max-width: 100%;
}
.day-hint-tag {
  display: inline-block;
  max-width: 260px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--amber-100);
  color: var(--amber-550);
  font-size: 12px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.day-hint-tag--red {
  background: var(--rose-100);
  color: var(--rose-600);
}
.day-detail {
  background: var(--gray-50);
  padding: 8px 12px 12px;
}
.day-detail-head,
.day-detail-grid {
  display: grid;
  grid-template-columns: 56px minmax(72px, 1.1fr) 72px 56px 96px 88px 72px 108px;
  gap: 8px;
  align-items: center;
}
.day-detail-head {
  padding: 4px 8px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--gray-400);
}
.day-detail-row {
  margin-top: 6px;
  padding: 8px;
  border: 1px solid var(--gray-100);
  border-radius: 6px;
  background: var(--white);
}
.day-detail-row:first-of-type {
  margin-top: 0;
}
.day-detail-extra {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 0 dashed var(--gray-200);
}
.detail-note {
  font-size: 12px;
  color: var(--gray-400);
}
.th-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.th-help {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 999px;
  background: var(--gray-200);
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: help;
}
.cell-text {
  font-size: 13px;
  color: var(--gray-800);
}
.cell-center {
  text-align: center;
}
.cell-muted {
  font-size: 12px;
  color: var(--gray-400);
}
.num-cell {
  display: inline-flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 4px;
  white-space: nowrap;
}
.num-cell--center {
  justify-content: center;
  width: 100%;
}
.num-cell b {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-950);
}
.num-unit {
  font-size: 11px;
  font-weight: 400;
  color: var(--gray-400);
}
.num-star {
  font-size: 11px;
  color: var(--gray-400);
}
.active-empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.5em;
  color: var(--gray-400);
  font-size: 13px;
  font-weight: 500;
  cursor: help;
}
.hint-chip {
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--amber-100);
  font-size: 12px;
  line-height: 1.35;
  cursor: default;
}
.hint-chip i {
  flex-shrink: 0;
  font-style: normal;
  font-size: 12px;
  line-height: 1;
}
.hint-chip em {
  display: inline-block;
  max-width: 280px;
  overflow: hidden;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.hint-chip--info,
.hint-chip--yellow {
  color: var(--amber-550);
}
.hint-chip--red {
  color: var(--rose-600);
  background: var(--rose-100);
}
.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: nowrap;
  white-space: nowrap;
}
.row-actions .el-button + .el-button {
  margin-left: 2px;
}
.readonly-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-400);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}
@media (max-width: 960px) {
  .day-detail-head {
    display: none;
  }
  .day-detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.unit {
  margin-left: 8px;
  color: var(--ink-450);
}
.ml {
  margin-left: 8px;
}
</style>
