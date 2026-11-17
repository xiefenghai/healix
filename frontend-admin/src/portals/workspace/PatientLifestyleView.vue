<script setup lang="ts">
import { palette } from '../../shared/palette'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'

interface WaterEntry {
  id: string
  amountMl: number
  source?: string
  createdAt?: string
  recordedByStaff?: boolean
}

interface WaterDay {
  date: string
  intakeMl: number
  targetMl?: number | null
  reached: boolean
  taskPresent: boolean
  checkinStatus?: string | null
  entries: WaterEntry[]
  avgIntakeMl14d?: number | null
  reachedDays14d?: number | null
  trend: Array<{ date: string; intakeMl: number; targetMl?: number | null; reached: boolean }>
}

interface SleepSegment {
  id: string
  segment?: string
  durationMin?: number | null
  quality?: string | null
  bedtime?: string | null
  wakeTime?: string | null
  note?: string | null
  recordedByStaff?: boolean
}

interface SleepDay {
  date: string
  durationMin?: number | null
  quality?: string | null
  bedtime?: string | null
  wakeTime?: string | null
  note?: string | null
  logged: boolean
  taskPresent: boolean
  checkinStatus?: string | null
  avgDurationMin14d?: number | null
  segments?: SleepSegment[]
  trend: Array<{ date: string; durationMin?: number; quality?: string }>
}

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

const QUALITY_LABELS: Record<string, string> = {
  GOOD: '好',
  FAIR: '一般',
  POOR: '差',
}

const CHECKIN_LABELS: Record<string, string> = {
  DONE: '已打卡',
  SKIPPED: '已跳过',
  MISSED: '未完成',
}

const MOOD_LABELS: Record<number, string> = { 1: '很低落', 2: '偏低', 3: '平稳', 4: '不错', 5: '很好' }
const MOOD_TAGS = ['平静', '焦虑', '疲惫', '愉悦', '压力大', '有动力']
const STAFF_BACKFILL_DAYS = 7

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const selectedDate = ref(ymd(new Date()))
const loading = ref(false)
const water = ref<WaterDay | null>(null)
const sleep = ref<SleepDay | null>(null)
const mood = ref<MoodDay | null>(null)

function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function disabledDate(d: Date) {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return d.getTime() > today.getTime()
}

function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + n)
  return ymd(dt)
}

const base = computed(() => `/api/b/v1/patients/${peopleId.value}`)
/** 代录仅限最近 7 天（与运动一致） */
const canProxy = computed(() => selectedDate.value >= addDays(ymd(new Date()), -(STAFF_BACKFILL_DAYS - 1)))

function hoursText(min?: number | null) {
  if (min == null) return '—'
  return (min / 60).toFixed(1)
}

function checkinText(status?: string | null, taskPresent?: boolean) {
  if (!taskPresent) return '无对应方案任务'
  if (!status) return '待打卡'
  return CHECKIN_LABELS[status] || status
}

function maxOf(...groups: Array<number | number[]>) {
  const flat: number[] = []
  for (const g of groups) {
    if (Array.isArray(g)) flat.push(...g)
    else flat.push(g)
  }
  return Math.max(1, ...flat.filter((n) => n > 0))
}

type SparkPoint = {
  date: string
  value: number
  label: string
  isToday: boolean
  hasData: boolean
}

function last7Dates(end: string) {
  return Array.from({ length: 7 }, (_, i) => addDays(end, -(6 - i)))
}

function barHeightPct(value: number, max: number, hasData: boolean) {
  if (!hasData || value <= 0) return 0
  // 按真实比例映射，最低约 12%，避免 1/5 分时柱子过矮
  return Math.max(12, Math.min(100, Math.round((value / Math.max(max, 1)) * 100)))
}

const waterSpark = computed<SparkPoint[]>(() => {
  const end = selectedDate.value
  const byDate = new Map((water.value?.trend || []).map((t) => [String(t.date).slice(0, 10), t]))
  return last7Dates(end).map((date) => {
    const t = byDate.get(date)
    const value = t?.intakeMl || 0
    return {
      date,
      value,
      label: `${date.slice(5)}：${value} ml`,
      isToday: date === end,
      hasData: value > 0,
    }
  })
})

const sleepSpark = computed<SparkPoint[]>(() => {
  const end = selectedDate.value
  const byDate = new Map((sleep.value?.trend || []).map((t) => [String(t.date).slice(0, 10), t]))
  return last7Dates(end).map((date) => {
    const t = byDate.get(date)
    const value = t?.durationMin || 0
    return {
      date,
      value,
      label: `${date.slice(5)}：${hoursText(value || null)} 小时`,
      isToday: date === end,
      hasData: value > 0,
    }
  })
})

const moodSpark = computed<SparkPoint[]>(() => {
  const end = selectedDate.value
  const byDate = new Map((mood.value?.trend || []).map((t) => [String(t.date).slice(0, 10), t]))
  return last7Dates(end).map((date) => {
    const t = byDate.get(date)
    const value = t?.score || 0
    const name = value ? MOOD_LABELS[value] || '' : '未记'
    return {
      date,
      value,
      label: `${date.slice(5)}：${value ? `${value} 分（${name}）` : '未记'}`,
      isToday: date === end,
      hasData: value > 0,
    }
  })
})

const waterMax = computed(() =>
  maxOf(
    waterSpark.value.map((t) => t.value),
    water.value?.targetMl || 0,
  ),
)
const sleepMax = computed(() => maxOf(sleepSpark.value.map((t) => t.value), 480))
const moodMax = computed(() => 5)

function checkinTone(status?: string | null, taskPresent?: boolean) {
  if (!taskPresent) return 'muted'
  if (status === 'DONE') return 'done'
  if (status === 'SKIPPED' || status === 'MISSED') return 'warn'
  return 'pending'
}

/** 心情柱：浅蓝→琥珀，避免医疗场景的大面积红色 */
function moodBarColor(score: number, isToday: boolean) {
  const stops = [
    [palette.brand200, palette.blue300],
    [palette.blue300, palette.brand400],
    [palette.sky200, palette.sky400],
    [palette.amber200, palette.amber400],
    [palette.orange200, palette.orange400],
  ]
  const pair = stops[Math.max(0, Math.min(4, (score || 1) - 1))]
  if (isToday) return `linear-gradient(180deg, ${pair[0]} 0%, ${pair[1]} 100%)`
  return `linear-gradient(180deg, ${palette.ink200} 0%, ${palette.ink300} 100%)`
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const d = selectedDate.value
    const [w, s, m] = await Promise.all([
      api<{ data: WaterDay }>(`${base.value}/water/days/${d}`),
      api<{ data: SleepDay }>(`${base.value}/sleep/days/${d}`),
      api<{ data: MoodDay }>(`${base.value}/mood/days/${d}`),
    ])
    water.value = w.data
    sleep.value = s.data
    mood.value = m.data
  } catch (e) {
    water.value = null
    sleep.value = null
    mood.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function confirmDelete(message: string) {
  try {
    await ElMessageBox.confirm(message, '删除记录', { type: 'warning' })
    return true
  } catch {
    return false
  }
}

function errText(e: unknown, fallback: string) {
  return e instanceof Error ? e.message : fallback
}

// ---------------------------------------------------------------- 饮水

const waterDialog = ref(false)
const waterSaving = ref(false)
const waterForm = reactive({ id: '' as string, amountMl: 250 as number | undefined, time: '' })

function nowHm() {
  const n = new Date()
  return `${String(n.getHours()).padStart(2, '0')}:${String(n.getMinutes()).padStart(2, '0')}`
}

function openWater(e?: WaterEntry) {
  waterForm.id = e?.id ?? ''
  waterForm.amountMl = e?.amountMl ?? 250
  waterForm.time = e?.createdAt?.slice(11, 16) || (selectedDate.value === ymd(new Date()) ? nowHm() : '12:00')
  waterDialog.value = true
}

async function saveWater() {
  if (!waterForm.amountMl) return ElMessage.warning('请填写饮水量')
  waterSaving.value = true
  try {
    const body = JSON.stringify({
      date: selectedDate.value,
      amountMl: waterForm.amountMl,
      recordedAt: waterForm.time ? `${selectedDate.value}T${waterForm.time}:00` : null,
    })
    const res = await api<{ data: WaterDay }>(
      waterForm.id ? `${base.value}/water/intake/${waterForm.id}` : `${base.value}/water/intake`,
      { method: waterForm.id ? 'PUT' : 'POST', body },
    )
    water.value = res.data
    waterDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errText(e, '保存失败'))
  } finally {
    waterSaving.value = false
  }
}

async function removeWater(e: WaterEntry) {
  if (!(await confirmDelete(`删除 ${e.createdAt?.slice(11, 16) || ''} 的 ${e.amountMl} ml 饮水记录？`))) return
  try {
    const res = await api<{ data: WaterDay }>(`${base.value}/water/intake/${e.id}`, { method: 'DELETE' })
    water.value = res.data
    ElMessage.success('已删除')
  } catch (err) {
    ElMessage.error(errText(err, '删除失败'))
  }
}

// ---------------------------------------------------------------- 睡眠

const sleepDialog = ref(false)
const sleepSaving = ref(false)
const sleepForm = reactive({
  id: '' as string,
  segment: 'MAIN',
  hours: 7 as number | undefined,
  minutes: 0 as number | undefined,
  quality: 'FAIR',
  bedtime: '' as string,
  wakeTime: '' as string,
  note: '',
})
const mainSegmentTaken = computed(() => (sleep.value?.segments || []).some((s) => (s.segment || 'MAIN') === 'MAIN'))

function openSleep(s?: SleepSegment) {
  const total = s?.durationMin ?? (mainSegmentTaken.value ? 60 : 420)
  sleepForm.id = s?.id ?? ''
  sleepForm.segment = s?.segment ?? (mainSegmentTaken.value ? 'NAP' : 'MAIN')
  sleepForm.hours = Math.floor(total / 60)
  sleepForm.minutes = total % 60
  sleepForm.quality = s?.quality ?? 'FAIR'
  sleepForm.bedtime = s?.bedtime ?? ''
  sleepForm.wakeTime = s?.wakeTime ?? ''
  sleepForm.note = s?.note ?? ''
  sleepDialog.value = true
}

async function saveSleep() {
  const durationMin = (sleepForm.hours || 0) * 60 + (sleepForm.minutes || 0)
  if (!durationMin) return ElMessage.warning('请填写睡眠时长')
  sleepSaving.value = true
  try {
    const res = await api<{ data: SleepDay }>(`${base.value}/sleep/log`, {
      method: 'POST',
      body: JSON.stringify({
        id: sleepForm.id || null,
        date: selectedDate.value,
        segment: sleepForm.segment,
        durationMin,
        quality: sleepForm.quality,
        bedtime: sleepForm.bedtime || null,
        wakeTime: sleepForm.wakeTime || null,
        note: sleepForm.note.trim() || null,
      }),
    })
    sleep.value = res.data
    sleepDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errText(e, '保存失败'))
  } finally {
    sleepSaving.value = false
  }
}

async function removeSleep(s: SleepSegment) {
  if (!(await confirmDelete(`删除这条${s.segment === 'NAP' ? '午睡' : '主睡眠'}记录？`))) return
  try {
    const res = await api<{ data: SleepDay }>(`${base.value}/sleep/segments/${s.id}`, { method: 'DELETE' })
    sleep.value = res.data
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(errText(e, '删除失败'))
  }
}

// ---------------------------------------------------------------- 心情

const moodDialog = ref(false)
const moodSaving = ref(false)
const moodForm = reactive({ score: 3, tags: [] as string[], note: '' })

function openMood() {
  const m = mood.value
  moodForm.score = m?.logged && m.score ? m.score : 3
  moodForm.tags = m?.logged ? [...(m.tags || [])] : []
  moodForm.note = m?.logged ? m.note || '' : ''
  moodDialog.value = true
}

async function saveMood() {
  moodSaving.value = true
  try {
    const res = await api<{ data: MoodDay }>(`${base.value}/mood/log`, {
      method: 'POST',
      body: JSON.stringify({
        date: selectedDate.value,
        score: moodForm.score,
        tags: moodForm.tags,
        note: moodForm.note.trim() || null,
      }),
    })
    mood.value = res.data
    moodDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errText(e, '保存失败'))
  } finally {
    moodSaving.value = false
  }
}

async function removeMood() {
  if (!(await confirmDelete(`删除 ${selectedDate.value} 的心情记录？`))) return
  try {
    const res = await api<{ data: MoodDay }>(`${base.value}/mood/days/${selectedDate.value}`, { method: 'DELETE' })
    mood.value = res.data
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(errText(e, '删除失败'))
  }
}

watch([peopleId, selectedDate], load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="lifestyle-page">
    <div class="toolbar">
      <div>
        <h3 class="title">生活习惯</h3>
        <p class="sub">饮水 · 睡眠 · 心情。可代录最近 {{ STAFF_BACKFILL_DAYS }} 天；只能修改代录的记录，患者自录只读；代录不计激励</p>
      </div>
      <el-date-picker
        v-model="selectedDate"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        :disabled-date="disabledDate"
        size="default"
      />
    </div>

    <div class="grid">
      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-head">
            <span>饮水</span>
            <div class="head-right">
              <span
                class="status-pill"
                :data-tone="checkinTone(water?.checkinStatus, water?.taskPresent)"
              >
                {{ checkinText(water?.checkinStatus, water?.taskPresent) }}
              </span>
              <button v-if="canProxy" type="button" class="proxy-link" @click="openWater()">代录</button>
            </div>
          </div>
        </template>
        <div class="card-body">
          <div class="hero">
            <strong>{{ water?.intakeMl ?? 0 }}</strong>
            <span v-if="water?.targetMl"> / {{ water.targetMl }} <em>ml</em></span>
            <span v-else> <em>ml</em></span>
          </div>
          <p class="meta">
            近 14 日有记日均
            <b class="meta-num">{{ water?.avgIntakeMl14d ?? '—' }}</b>
            <em class="meta-unit">ml</em>
            ·
            <em class="meta-label">达标</em>
            <b class="meta-num">{{ water?.reachedDays14d ?? 0 }}</b>
            <em class="meta-unit">天</em>
          </p>
          <div class="spark">
            <div
              v-if="waterSpark.some((t) => t.hasData)"
              class="spark-bars"
              aria-label="近7日饮水趋势"
            >
              <div v-for="t in waterSpark" :key="t.date" class="spark-slot" :title="t.label">
                <div class="spark-track">
                  <span
                    v-if="t.hasData"
                    class="spark-bar"
                    :class="t.isToday ? 'spark-bar--today' : 'spark-bar--hist'"
                    :style="{ height: `${barHeightPct(t.value, waterMax, true)}%` }"
                  />
                </div>
                <i class="spark-dot" :class="{ 'spark-dot--on': t.hasData }" aria-hidden="true" />
              </div>
            </div>
            <p v-if="!water?.entries?.length" class="empty-inline">当日无饮水记录</p>
          </div>
          <div v-if="water?.entries?.length" class="rows">
            <div v-for="e in water.entries" :key="e.id" class="row">
              <span>+{{ e.amountMl }} ml</span>
              <span class="row-right">
                <span class="muted">{{ e.createdAt?.slice(11) || '' }}</span>
                <el-tag v-if="e.recordedByStaff" size="small" type="warning" effect="plain">代录</el-tag>
                <template v-if="e.recordedByStaff && canProxy">
                  <el-button size="small" type="primary" link @click="openWater(e)">编辑</el-button>
                  <el-button size="small" type="danger" link @click="removeWater(e)">删除</el-button>
                </template>
              </span>
            </div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-head">
            <span>睡眠</span>
            <div class="head-right">
              <span
                class="status-pill"
                :data-tone="checkinTone(sleep?.checkinStatus, sleep?.taskPresent)"
              >
                {{ checkinText(sleep?.checkinStatus, sleep?.taskPresent) }}
              </span>
              <button v-if="canProxy" type="button" class="proxy-link" @click="openSleep()">代录</button>
            </div>
          </div>
        </template>
        <div class="card-body">
          <div class="hero">
            <strong>{{ hoursText(sleep?.logged ? sleep.durationMin : sleep?.avgDurationMin14d) }}</strong>
            <span> <em>小时</em></span>
          </div>
          <p class="meta">
            <template v-if="sleep?.logged">
              合计
              <b class="meta-num">{{ hoursText(sleep.durationMin) }}</b>
              <em class="meta-unit">小时</em>
              ·
              <em class="meta-label">质量</em>
              <b class="meta-num">{{ QUALITY_LABELS[sleep.quality || ''] || sleep.quality || '—' }}</b>
            </template>
            <template v-else>
              <em class="meta-label">当日未记</em>
              · 近 14 日均
              <b class="meta-num">{{ hoursText(sleep?.avgDurationMin14d) }}</b>
              <em class="meta-unit">小时</em>
            </template>
          </p>
          <div class="spark">
            <div
              v-if="sleepSpark.some((t) => t.hasData)"
              class="spark-bars"
              aria-label="近7日睡眠趋势"
            >
              <div v-for="t in sleepSpark" :key="t.date" class="spark-slot" :title="t.label">
                <div class="spark-track">
                  <span
                    v-if="t.hasData"
                    class="spark-bar"
                    :class="t.isToday ? 'spark-bar--today' : 'spark-bar--hist'"
                    :style="{ height: `${barHeightPct(t.value, sleepMax, true)}%` }"
                  />
                </div>
                <i class="spark-dot" :class="{ 'spark-dot--on': t.hasData }" aria-hidden="true" />
              </div>
            </div>
            <p v-if="!sleep?.logged" class="empty-inline">当日无睡眠记录</p>
          </div>
          <div v-if="sleep?.segments?.length" class="seg-list">
            <div v-for="s in sleep.segments" :key="s.id" class="seg-row">
              <span>{{ s.segment === 'NAP' ? '午睡' : '主睡眠' }}</span>
              <strong>{{ hoursText(s.durationMin) }}h</strong>
              <em v-if="s.bedtime || s.wakeTime">{{ s.bedtime || '—' }}–{{ s.wakeTime || '—' }}</em>
              <span class="row-right">
                <el-tag v-if="s.recordedByStaff" size="small" type="warning" effect="plain">代录</el-tag>
                <template v-if="s.recordedByStaff && canProxy">
                  <el-button size="small" type="primary" link @click="openSleep(s)">编辑</el-button>
                  <el-button size="small" type="danger" link @click="removeSleep(s)">删除</el-button>
                </template>
              </span>
            </div>
          </div>
          <p v-if="sleep?.note" class="note">备注：{{ sleep.note }}</p>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-head">
            <span>心情</span>
            <div class="head-right">
              <span
                class="status-pill"
                :data-tone="checkinTone(mood?.checkinStatus, mood?.taskPresent)"
              >
                {{ checkinText(mood?.checkinStatus, mood?.taskPresent) }}
              </span>
              <template v-if="canProxy">
                <button v-if="!mood?.logged" type="button" class="proxy-link" @click="openMood()">代录</button>
                <template v-else-if="mood.recordedByStaff">
                  <button type="button" class="proxy-link" @click="openMood()">编辑</button>
                  <button type="button" class="proxy-link proxy-link--danger" @click="removeMood()">删除</button>
                </template>
              </template>
            </div>
          </div>
        </template>
        <div class="card-body">
          <div class="hero">
            <strong>{{ mood?.logged ? mood.score ?? '—' : mood?.avgScore14d ?? '—' }}</strong>
            <span> / 5</span>
          </div>
          <p class="meta">
            <template v-if="mood?.logged">
              <el-tag v-if="mood.recordedByStaff" size="small" type="warning" effect="plain">代录</el-tag>
              <b class="meta-num">{{ mood.label || '已记' }}</b>
              <template v-if="mood.tags?.length">
                · <em class="meta-label">{{ mood.tags.join('、') }}</em>
              </template>
            </template>
            <template v-else>
              <em class="meta-label">当日未记</em>
              · 近 14 日均
              <b class="meta-num">{{ mood?.avgScore14d ?? '—' }}</b>
              <em class="meta-unit">分</em>
            </template>
          </p>
          <div class="spark">
            <div
              v-if="moodSpark.some((t) => t.hasData)"
              class="spark-bars"
              aria-label="近7日心情趋势"
            >
              <div v-for="t in moodSpark" :key="t.date" class="spark-slot" :title="t.label">
                <div class="spark-track">
                  <span
                    v-if="t.hasData"
                    class="spark-bar spark-bar--mood"
                    :class="t.isToday ? 'spark-bar--today' : 'spark-bar--hist'"
                    :style="{
                      height: `${barHeightPct(t.value, moodMax, true)}%`,
                      background: moodBarColor(t.value, t.isToday),
                    }"
                  />
                </div>
                <i class="spark-dot" :class="{ 'spark-dot--on': t.hasData }" aria-hidden="true" />
              </div>
            </div>
            <p v-if="!mood?.logged" class="empty-inline">当日无心情记录</p>
          </div>
          <p v-if="mood?.note" class="note">备注：{{ mood.note }}</p>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="waterDialog" :title="waterForm.id ? '编辑饮水记录' : `代录饮水 · ${selectedDate}`" width="420px" append-to-body>
      <el-form label-width="72px">
        <el-form-item label="饮水量" required>
          <el-input-number v-model="waterForm.amountMl" :min="50" :max="2000" :step="50" />
          <span class="unit">ml</span>
        </el-form-item>
        <el-form-item label="时间">
          <el-time-select v-model="waterForm.time" start="00:00" step="00:15" end="23:45" placeholder="选择时间" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="waterDialog = false">取消</el-button>
        <el-button type="primary" :loading="waterSaving" @click="saveWater">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sleepDialog" :title="sleepForm.id ? '编辑睡眠记录' : `代录睡眠 · ${selectedDate}`" width="480px" append-to-body>
      <el-form label-width="72px">
        <el-form-item label="类型">
          <el-radio-group v-model="sleepForm.segment" :disabled="!!sleepForm.id">
            <el-radio-button value="MAIN" :disabled="mainSegmentTaken && !sleepForm.id">主睡眠</el-radio-button>
            <el-radio-button value="NAP">午睡</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="时长" required>
          <el-input-number v-model="sleepForm.hours" :min="0" :max="16" :controls="false" class="num-sm" />
          <span class="unit">小时</span>
          <el-input-number v-model="sleepForm.minutes" :min="0" :max="59" :step="5" :controls="false" class="num-sm ml" />
          <span class="unit">分钟</span>
        </el-form-item>
        <el-form-item label="质量">
          <el-radio-group v-model="sleepForm.quality">
            <el-radio-button v-for="(label, k) in QUALITY_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="入睡/起床">
          <el-time-select v-model="sleepForm.bedtime" start="00:00" step="00:15" end="23:45" placeholder="入睡" class="time-sm" />
          <el-time-select v-model="sleepForm.wakeTime" start="00:00" step="00:15" end="23:45" placeholder="起床" class="time-sm ml" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="sleepForm.note" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sleepDialog = false">取消</el-button>
        <el-button type="primary" :loading="sleepSaving" @click="saveSleep">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moodDialog" :title="`${mood?.logged ? '编辑' : '代录'}心情 · ${selectedDate}`" width="460px" append-to-body>
      <el-form label-width="72px">
        <el-form-item label="心情" required>
          <el-radio-group v-model="moodForm.score">
            <el-radio-button v-for="n in 5" :key="n" :value="n">{{ n }} {{ MOOD_LABELS[n] }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标签">
          <el-checkbox-group v-model="moodForm.tags">
            <el-checkbox v-for="t in MOOD_TAGS" :key="t" :value="t">{{ t }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="moodForm.note" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moodDialog = false">取消</el-button>
        <el-button type="primary" :loading="moodSaving" @click="saveMood">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lifestyle-page {
  display: grid;
  gap: 16px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}
.sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
@media (max-width: 1100px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
.block {
  border-radius: 12px;
  border: 1px solid var(--ink-125);
}
.block :deep(.el-card__header) {
  padding: 12px 16px 8px;
  border-bottom: none;
}
.block :deep(.el-card__body) {
  padding: 0 16px 14px;
}
.card-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 28px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 28px;
}
.head-right {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  height: 28px;
  line-height: 28px;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  color: var(--gray-400);
  font-size: 12px;
  font-weight: 400;
  line-height: 28px;
}
.status-pill[data-tone='done'] {
  color: var(--green-600);
}
.status-pill[data-tone='warn'] {
  color: var(--amber-700);
}
.status-pill[data-tone='muted'],
.status-pill[data-tone='pending'] {
  color: var(--gray-400);
}
.proxy-link {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--blue-600);
  font-size: 13px;
  font-weight: 500;
  line-height: 28px;
  cursor: pointer;
}
.proxy-link:hover {
  text-decoration: underline;
}
.proxy-link--danger {
  color: var(--rose-600);
}
.hero {
  display: flex;
  align-items: baseline;
  gap: 4px;
  min-height: 36px;
  color: var(--ink-500);
}
.hero strong {
  font-size: 30px;
  font-weight: 800;
  color: var(--ink-900);
  letter-spacing: -0.02em;
  line-height: 1;
}
.hero em {
  font-style: normal;
  font-size: 12px;
  color: var(--gray-400);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: flex-start;
  gap: 4px;
  min-height: 20px;
  margin: 8px 0 0;
  padding: 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--gray-400);
  text-align: left;
}
.meta-num {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-750);
}
.meta-unit,
.meta-label {
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: var(--gray-400);
}
.spark {
  margin-top: 12px;
}
.spark-bars {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 6px;
  height: 56px;
  width: 100%;
}
.spark-slot {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
  max-width: 36px;
  height: 100%;
}
.spark-track {
  display: flex;
  flex: 1 1 auto;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
  min-height: 0;
}
.spark-bar {
  display: block;
  width: 12px;
  border-radius: 4px 4px 2px 2px;
  transition: height 0.15s ease;
}
.spark-bar--hist {
  background: var(--ink-400);
}
.spark-bar--today {
  background: var(--teal-700);
}
.spark-dot {
  display: block;
  width: 4px;
  height: 4px;
  margin-top: 4px;
  border-radius: 999px;
  background: var(--gray-200);
  flex-shrink: 0;
}
.spark-dot--on {
  background: var(--ink-400);
}
.empty-inline {
  margin: 10px 0 0;
  padding: 8px 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--gray-400);
  text-align: center;
}
.rows {
  margin-top: 10px;
  border-top: 0 solid var(--gray-100);
}
.row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 0 solid var(--el-border-color-extra-light);
  font-size: 13px;
  font-weight: 600;
}
.row:last-child {
  border-bottom: 0;
}
.muted {
  color: var(--el-text-color-secondary);
  font-weight: 500;
}
.note {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.seg-list {
  margin-top: 10px;
  border-top: 0 solid var(--el-border-color-lighter);
}
.seg-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 0 solid var(--el-border-color-extra-light);
  font-size: 13px;
}
.seg-row:last-child {
  border-bottom: 0;
}
.seg-row strong {
  font-weight: 700;
}
.seg-row em {
  font-style: normal;
  color: var(--el-text-color-secondary);
}
.row-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.row-right .el-button + .el-button {
  margin-left: 0;
}
.unit {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
}
.ml {
  margin-left: 10px;
}
.num-sm {
  width: 72px;
}
.time-sm {
  width: 130px;
}
</style>
