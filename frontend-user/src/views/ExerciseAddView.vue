<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showDialog, showToast } from 'vant'
import { api } from '../api/http'
import { addDays, ymd } from '../shared/diet'
import {
  BACKFILL_DAYS,
  CATEGORY_LABELS,
  INTENSITY_OPTIONS,
  LONG_DURATION_MIN,
  SYMPTOM_OPTIONS,
  hhmm,
  toLocalDateTime,
  type ExerciseSession,
  type ExerciseType,
  type Intensity,
} from '../shared/exercise'

const route = useRoute()
const router = useRouter()
const q = (k: string) => (typeof route.query[k] === 'string' ? (route.query[k] as string) : '')

const editId = q('id')
const taskId = q('taskId')
const today = ymd(new Date())
const now = new Date()

const loading = ref(true)
const saving = ref(false)
const readonly = ref(false)
const types = ref<ExerciseType[]>([])
const frequent = ref<ExerciseType[]>([])
const pickerOpen = ref(false)
const keyword = ref('')

const typeCode = ref(q('typeCode'))
const date = ref(today)
const time = ref(`${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`)
const durationMin = ref<number | null>(q('durationMin') ? Number(q('durationMin')) : 30)
const intensity = ref<Intensity | ''>((q('intensity') as Intensity) || '')
const avgHr = ref<string>('')
const distanceKm = ref<string>('')
const note = ref('')
const symptoms = ref<string[]>([])
const showMore = ref(false)

const DURATION_PRESETS = [10, 20, 30, 45, 60]

const dateOptions = computed(() =>
  Array.from({ length: BACKFILL_DAYS }, (_, i) => {
    const d = addDays(today, -i)
    const [, m, dd] = d.split('-')
    return { value: d, label: i === 0 ? '今天' : i === 1 ? '昨天' : `${Number(m)}月${Number(dd)}日` }
  }),
)

const selectedType = computed(() => types.value.find((t) => t.code === typeCode.value) || null)
const chipTypes = computed(() => {
  const list = [...frequent.value]
  if (selectedType.value && !list.some((t) => t.code === selectedType.value!.code)) {
    list.unshift(selectedType.value)
  }
  if (list.length < 6) {
    for (const code of ['WALKING', 'BRISK_WALKING', 'SQUARE_DANCE', 'TAICHI', 'CYCLING', 'ELASTIC_BAND']) {
      const t = types.value.find((x) => x.code === code)
      if (t && !list.some((x) => x.code === code)) list.push(t)
      if (list.length >= 6) break
    }
  }
  return list
})

const filteredGroups = computed(() => {
  const k = keyword.value.trim()
  const list = k
    ? types.value.filter((t) => t.name.includes(k) || t.aliases.some((a) => a.includes(k)))
    : types.value
  const groups: Record<string, ExerciseType[]> = {}
  for (const t of list) (groups[t.category] ??= []).push(t)
  return Object.entries(groups)
})

const hasRedSymptom = computed(() => SYMPTOM_OPTIONS.some((o) => o.red && symptoms.value.includes(o.value)))

function pickType(t: ExerciseType) {
  typeCode.value = t.code
  if (!intensity.value) intensity.value = t.defaultIntensity
  pickerOpen.value = false
}

function toggleSymptom(v: string) {
  if (readonly.value) return
  const i = symptoms.value.indexOf(v)
  if (i >= 0) symptoms.value.splice(i, 1)
  else symptoms.value.push(v)
}

async function load() {
  loading.value = true
  try {
    const [all, freq] = await Promise.all([
      api<{ data: ExerciseType[] }>('/api/c/v1/me/exercise/types?limit=50'),
      api<{ data: ExerciseType[] }>('/api/c/v1/me/exercise/types/frequent'),
    ])
    types.value = all.data
    frequent.value = freq.data
    if (typeCode.value && !all.data.some((t) => t.code === typeCode.value)) typeCode.value = ''
    if (editId) {
      const res = await api<{ data: ExerciseSession }>(`/api/c/v1/me/exercise/sessions/${editId}`)
      const s = res.data
      typeCode.value = s.typeCode
      date.value = s.exerciseDate
      time.value = hhmm(s.startedAt)
      durationMin.value = s.durationMin
      intensity.value = s.intensity
      avgHr.value = s.avgHr ? String(s.avgHr) : ''
      distanceKm.value = s.distanceM ? String(s.distanceM / 1000) : ''
      note.value = s.note || ''
      symptoms.value = [...s.symptoms]
      readonly.value = !s.editable
      showMore.value = !!(s.avgHr || s.distanceM || s.note)
    } else if (typeCode.value && !intensity.value) {
      intensity.value = selectedType.value?.defaultIntensity || 'MODERATE'
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!typeCode.value) return showToast('请选择运动类型')
  const minutes = Number(durationMin.value)
  if (!minutes || minutes < 1 || minutes > 600) return showToast('时长需在 1–600 分钟之间')
  if (!intensity.value) return showToast('请选择运动强度')
  const n = new Date()
  const nowHm = `${String(n.getHours()).padStart(2, '0')}:${String(n.getMinutes()).padStart(2, '0')}`
  if (date.value === today && time.value > nowHm) return showToast('开始时间不能晚于现在')
  if (minutes > LONG_DURATION_MIN) {
    try {
      await showConfirmDialog({ title: '确认时长', message: `单次运动 ${minutes} 分钟，超过 4 小时，确认无误吗？` })
    } catch {
      return
    }
  }
  const body = {
    typeCode: typeCode.value,
    startedAt: toLocalDateTime(date.value, time.value),
    durationMin: minutes,
    intensity: intensity.value,
    avgHr: avgHr.value ? Number(avgHr.value) : null,
    distanceM: distanceKm.value ? Math.round(Number(distanceKm.value) * 1000) : null,
    symptoms: symptoms.value,
    note: note.value.trim() || null,
    source: taskId ? 'PLAN_QUICK' : 'MANUAL',
    carePlanTaskId: taskId || null,
  }
  saving.value = true
  try {
    const res = await api<{ data: ExerciseSession }>(
      editId ? `/api/c/v1/me/exercise/sessions/${editId}` : '/api/c/v1/me/exercise/sessions',
      { method: editId ? 'PUT' : 'POST', body: JSON.stringify(body) },
    )
    const s = res.data
    const red = s.flags.find((f) => f.level === 'RED')
    if (red) {
      await showDialog({ title: '请注意安全', message: red.message, confirmButtonText: '我知道了' })
    } else {
      const warn = s.flags.find((f) => f.level === 'YELLOW') || s.flags.find((f) => f.code === 'DUPLICATE_SUSPECT')
      showToast(warn ? warn.message : '已保存')
    }
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  try {
    await showConfirmDialog({ title: '删除记录', message: '确定删除这条运动记录吗？' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/exercise/sessions/${editId}`, { method: 'DELETE' })
    showToast('已删除')
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar
      :title="editId ? (readonly ? '运动记录' : '编辑运动') : '记录运动'"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="router.back()"
    >
      <template v-if="editId && !readonly" #right>
        <span class="nav-link danger" @click="remove">删除</span>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <p v-if="readonly" class="notice">这条记录由健管师代录，不可修改。</p>

      <section class="card">
        <div class="label">运动类型</div>
        <div class="chips">
          <button
            v-for="t in chipTypes"
            :key="t.code"
            type="button"
            class="chip"
            :class="{ on: t.code === typeCode }"
            :disabled="readonly"
            @click="pickType(t)"
          >
            {{ t.name }}
          </button>
          <button v-if="!readonly" type="button" class="chip more" @click="pickerOpen = true">
            更多 <van-icon name="arrow" />
          </button>
        </div>
      </section>

      <section class="card">
        <div class="label">开始时间</div>
        <div class="row">
          <select v-model="date" class="input" :disabled="readonly">
            <option v-for="o in dateOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
          <input v-model="time" type="time" class="input" :disabled="readonly" />
        </div>
        <div class="label mt">时长（分钟）</div>
        <div class="chips">
          <button
            v-for="m in DURATION_PRESETS"
            :key="m"
            type="button"
            class="chip"
            :class="{ on: durationMin === m }"
            :disabled="readonly"
            @click="durationMin = m"
          >
            {{ m }}
          </button>
          <input
            v-model.number="durationMin"
            type="number"
            inputmode="numeric"
            min="1"
            max="600"
            class="input small"
            :disabled="readonly"
          />
        </div>
      </section>

      <section class="card">
        <div class="label">运动时的感觉</div>
        <div class="intensity">
          <button
            v-for="o in INTENSITY_OPTIONS"
            :key="o.value"
            type="button"
            class="int"
            :class="{ on: intensity === o.value }"
            :disabled="readonly"
            @click="intensity = o.value"
          >
            <strong>{{ o.label }}</strong>
            <span>{{ o.hint }}</span>
          </button>
        </div>
      </section>

      <section class="card">
        <div class="label">运动后有没有不适？</div>
        <div class="chips">
          <button
            type="button"
            class="chip"
            :class="{ on: !symptoms.length }"
            :disabled="readonly"
            @click="symptoms = []"
          >
            无不适
          </button>
          <button
            v-for="o in SYMPTOM_OPTIONS"
            :key="o.value"
            type="button"
            class="chip"
            :class="{ on: symptoms.includes(o.value), red: o.red && symptoms.includes(o.value) }"
            :disabled="readonly"
            @click="toggleSymptom(o.value)"
          >
            {{ o.label }}
          </button>
        </div>
        <p v-if="hasRedSymptom" class="flag red">
          请立即停止运动并休息；症状持续请拨打 120。保存后会通知您的健管师。
        </p>
      </section>

      <section class="card">
        <div class="label clickable" @click="showMore = !showMore">
          更多信息（选填）<van-icon :name="showMore ? 'arrow-up' : 'arrow-down'" />
        </div>
        <template v-if="showMore">
          <div class="row mt">
            <input v-model="distanceKm" type="number" inputmode="decimal" placeholder="距离（公里）" class="input" :disabled="readonly" />
            <input v-model="avgHr" type="number" inputmode="numeric" placeholder="平均心率" class="input" :disabled="readonly" />
          </div>
          <textarea v-model="note" maxlength="200" rows="2" placeholder="备注" class="input area mt" :disabled="readonly" />
        </template>
      </section>

      <div v-if="!readonly" class="actions">
        <van-button round block type="primary" :loading="saving" @click="save">保存</van-button>
      </div>
      <p class="disclaimer">消耗热量为估算值，仅供参考，不计入饮食热量预算。</p>
    </template>

    <van-popup v-model:show="pickerOpen" position="bottom" round :style="{ height: '70%' }">
      <div class="picker">
        <van-search v-model="keyword" placeholder="搜索运动，如 广场舞" shape="round" />
        <div v-for="[cat, list] in filteredGroups" :key="cat" class="group">
          <div class="group-title">{{ CATEGORY_LABELS[cat] || cat }}</div>
          <div class="chips">
            <button
              v-for="t in list"
              :key="t.code"
              type="button"
              class="chip"
              :class="{ on: t.code === typeCode }"
              @click="pickType(t)"
            >
              {{ t.name }}
            </button>
          </div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
}
:deep(.van-nav-bar) {
  background: rgba(247, 249, 252, 0.88);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 650;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.nav-link.danger {
  color: #e8743b;
  font-size: 14px;
}
.loading {
  padding: 48px 0;
}
.notice {
  margin: 12px 16px 0;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  color: #a16207;
  background: var(--hx-orange-soft);
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
}
.label {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}
.label.mt,
.mt {
  margin-top: 12px;
}
.clickable {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.chip {
  border: 1px solid #e3eaee;
  border-radius: 999px;
  padding: 6px 12px;
  background: #fff;
  color: var(--hx-text);
  font-size: 13px;
}
.chip.on {
  border-color: var(--hx-teal);
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.chip.red {
  border-color: #e8743b;
  background: #fff1ec;
  color: #c2410c;
}
.chip.more {
  color: var(--hx-muted);
}
.chip:disabled {
  opacity: 0.7;
}
.row {
  display: flex;
  gap: 10px;
}
.input {
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 10px;
  border: 1px solid #e3eaee;
  border-radius: 10px;
  background: #fff;
  font-size: 14px;
  color: var(--hx-text);
}
.input.small {
  flex: 0 0 72px;
}
.input.area {
  width: 100%;
  height: auto;
  padding: 8px 10px;
  box-sizing: border-box;
  resize: none;
}
.intensity {
  display: flex;
  gap: 8px;
}
.int {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 6px;
  border: 1px solid #e3eaee;
  border-radius: 12px;
  background: #fff;
  color: var(--hx-text);
}
.int strong {
  font-size: 15px;
}
.int span {
  font-size: 11px;
  color: var(--hx-muted);
}
.int.on {
  border-color: var(--hx-teal);
  background: var(--hx-teal-light);
}
.int.on strong {
  color: var(--hx-teal);
}
.flag {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
}
.flag.red {
  color: #c2410c;
  background: #fff1ec;
}
.actions {
  margin: 20px 16px 0;
}
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
.disclaimer {
  margin: 12px 24px 0;
  font-size: 11px;
  color: var(--hx-muted);
  text-align: center;
}
.picker {
  padding-bottom: 24px;
}
.group {
  padding: 4px 16px 8px;
}
.group-title {
  margin: 8px 0;
  font-size: 13px;
  color: var(--hx-muted);
}
</style>
