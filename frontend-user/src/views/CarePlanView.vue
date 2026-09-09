<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  asFoodList,
  asStringList,
  CARE_PLAN_CHECKIN_STATUS_LABELS,
  CARE_PLAN_FREQUENCY_LABELS,
  CARE_PLAN_TASK_CATEGORY_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  formatCarePlanFoodItem,
  formatCarePlanLabel,
  formatCarePlanSource,
  formatWeeklyPlanDay,
  CARE_PLAN_EXERCISE_TYPE_LABELS,
  CARE_PLAN_INTENSITY_LABELS,
  isAiGeneratedCarePlanSource,
} from '../shared/care-plan-labels'

const router = useRouter()
const tab = ref<'today' | 'overview' | 'history'>('today')
const overviewTab = ref<'exercise' | 'diet' | 'plan' | 'checkins'>('exercise')
const loading = ref(true)
const today = ref<any>(null)
const overview = ref<any>(null)
const busyId = ref<string | null>(null)
const historyList = ref<any[]>([])
const historyLoading = ref(false)
const historyDetail = ref<any>(null)
const historyDetailLoading = ref(false)
const historyCheckins = ref<any[]>([])
const historyCheckinsLoading = ref(false)

const tabs = [
  { name: 'today' as const, title: '今日执行' },
  { name: 'overview' as const, title: '方案内容' },
  { name: 'history' as const, title: '历史方案' },
]

const planTitle = computed(() => {
  if (tab.value === 'history' && historyDetail.value) {
    return historyDetail.value.title || '历史方案'
  }
  return today.value?.planTitle || overview.value?.plan?.title || '管理方案'
})
const version = computed(() => {
  if (tab.value === 'history' && historyDetail.value) return historyDetail.value
  return overview.value?.activeVersion || null
})
const exercise = computed(() => (version.value?.exercise as Record<string, unknown>) || {})
const diet = computed(() => (version.value?.diet as Record<string, unknown>) || {})
const execution = computed(() => (version.value?.execution as Record<string, unknown>) || {})
const tasks = computed(() => {
  const fromVersion = version.value?.tasks
  if (Array.isArray(fromVersion) && fromVersion.length) return fromVersion
  return Array.isArray(execution.value.tasks) ? execution.value.tasks : []
})
const exercisePrecautions = computed(() => asStringList(exercise.value.precautions))
const exerciseContraindications = computed(() => asStringList(exercise.value.contraindications))
const dietPrinciples = computed(() => asStringList(diet.value.principles))
const dietRecommended = computed(() => asFoodList(diet.value.recommended))
const dietLimited = computed(() => asFoodList(diet.value.limited))
const dietNotes = computed(() => String(diet.value.notes || '').trim())
const dietCalorieHint = computed(() => String(diet.value.calorieHint || '').trim())
const planTasks = computed(() =>
  (tasks.value as any[]).filter((t) => t && t.enabled !== false && String(t.title || '').trim()),
)
const weeklyPlan = computed(() => (Array.isArray(exercise.value.weeklyPlan) ? exercise.value.weeklyPlan : []))
const viewingHistoryDetail = computed(() => tab.value === 'history' && !!historyDetail.value)
const overviewTabs = computed(() => {
  const base = [
    { name: 'exercise' as const, title: '运动方案' },
    { name: 'diet' as const, title: '饮食方案' },
    { name: 'plan' as const, title: '健康计划' },
  ]
  if (viewingHistoryDetail.value) {
    return [...base, { name: 'checkins' as const, title: '打卡记录' }]
  }
  return base
})
const showPlanContent = computed(
  () => tab.value === 'overview' || viewingHistoryDetail.value,
)
const weeklyPlanRows = computed(() => {
  const rows: Array<{
    key: string
    day: string
    showDay: boolean
    type: string
    intensity: string
    duration: string
    note: string
    rest: boolean
  }> = []
  weeklyPlan.value.forEach((day: any, di: number) => {
    const dayLabel = formatWeeklyPlanDay(day?.day) || '—'
    const items = Array.isArray(day?.items) ? day.items : []
    if (!items.length) {
      rows.push({
        key: `${di}-rest`,
        day: dayLabel,
        showDay: true,
        type: '休息',
        intensity: '—',
        duration: '—',
        note: '',
        rest: true,
      })
      return
    }
    items.forEach((item: unknown, ii: number) => {
      const o = item && typeof item === 'object' ? (item as Record<string, unknown>) : {}
      const type =
        formatCarePlanLabel(CARE_PLAN_EXERCISE_TYPE_LABELS, o.type != null ? String(o.type) : '') ||
        (o.title != null ? String(o.title) : '') ||
        '—'
      const intensity =
        formatCarePlanLabel(CARE_PLAN_INTENSITY_LABELS, o.intensity != null ? String(o.intensity) : '') ||
        '—'
      const duration = o.durationMin != null && o.durationMin !== '' ? `${o.durationMin} 分钟` : '—'
      const note = o.note != null ? String(o.note).trim() : ''
      rows.push({
        key: `${di}-${ii}`,
        day: dayLabel,
        showDay: ii === 0,
        type,
        intensity,
        duration,
        note,
        rest: false,
      })
    })
  })
  return rows
})
const sampleDay = computed(() => {
  const raw = diet.value.sampleDay
  if (!raw || typeof raw !== 'object') return [] as Array<{
    key: string
    label: string
    tone: string
    value: string
    items: string[]
  }>
  const o = raw as Record<string, string>
  const meals: Array<{ key: string; label: string; tone: string; aliases?: string[] }> = [
    { key: 'breakfast', label: '早餐', tone: 'sunrise' },
    { key: 'lunch', label: '午餐', tone: 'noon' },
    { key: 'dinner', label: '晚餐', tone: 'dusk' },
    { key: 'snacks', label: '加餐', tone: 'snack', aliases: ['snack'] },
  ]
  const rows: Array<{ key: string; label: string; tone: string; value: string; items: string[] }> = []
  for (const meal of meals) {
    let value = String(o[meal.key] || '').trim()
    if (!value && meal.aliases) {
      for (const alias of meal.aliases) {
        value = String(o[alias] || '').trim()
        if (value) break
      }
    }
    if (!value) continue
    const items = value
      .split(/[，,;；、]/)
      .map((s) => s.trim())
      .filter(Boolean)
    rows.push({
      key: meal.key,
      label: meal.label,
      tone: meal.tone,
      value,
      items: items.length ? items : [value],
    })
  }
  return rows
})

const todayTasks = computed(() => (Array.isArray(today.value?.tasks) ? today.value.tasks : []))
const todayTotal = computed(() => Number(today.value?.totalTasks || todayTasks.value.length || 0))
const todayDone = computed(() => {
  if (today.value?.doneTasks != null) return Number(today.value.doneTasks)
  return todayTasks.value.filter((t: any) => t.checkinStatus === 'DONE').length
})
const todayProgressPct = computed(() => {
  if (!todayTotal.value) return 0
  return Math.min(100, Math.round((todayDone.value / todayTotal.value) * 100))
})
const showAiDisclaimer = computed(() => isAiGeneratedCarePlanSource(version.value?.source as string))
const goalText = computed(() => {
  if (viewingHistoryDetail.value) {
    return (exercise.value.goal as string) || ''
  }
  return overview.value?.plan?.goalSummary || (exercise.value.goal as string) || ''
})
const hasOverview = computed(() => !!(overview.value?.plan || overview.value?.activeVersion))
const hasHistoryContent = computed(() => !!historyDetail.value)

function taskPending(task: any) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}

function formatHistoryTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(0, 16)
}

function historyStatusLabel(status?: string) {
  if (status === 'ACTIVE') return '当前生效'
  if (status === 'ARCHIVED') return '历史'
  return status || ''
}

async function loadHistoryList() {
  historyLoading.value = true
  try {
    const res = await api<{ data: any[] }>('/api/c/v1/me/care-plan/versions')
    historyList.value = res.data || []
  } catch (e) {
    historyList.value = []
    showToast(e instanceof Error ? e.message : '历史方案加载失败')
  } finally {
    historyLoading.value = false
  }
}

async function openHistory(item: any) {
  if (!item?.id) return
  historyDetailLoading.value = true
  historyCheckins.value = []
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/care-plan/versions/${item.id}`)
    historyDetail.value = {
      ...res.data,
      title: res.data?.title || item.title,
      status: item.status,
    }
    overviewTab.value = 'exercise'
    void loadHistoryCheckins(item.id, res.data?.publishedAt)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    historyDetailLoading.value = false
  }
}

async function loadHistoryCheckins(versionId: string, publishedAt?: string) {
  historyCheckinsLoading.value = true
  try {
    const params = new URLSearchParams()
    if (publishedAt) {
      const from = String(publishedAt).slice(0, 10)
      if (/^\d{4}-\d{2}-\d{2}$/.test(from)) {
        params.set('from', from)
      }
    }
    params.set('to', new Date().toISOString().slice(0, 10))
    const qs = params.toString()
    const res = await api<{ data: any[] }>(
      `/api/c/v1/me/care-plan/versions/${versionId}/checkins${qs ? `?${qs}` : ''}`,
    )
    historyCheckins.value = res.data || []
  } catch (e) {
    historyCheckins.value = []
    showToast(e instanceof Error ? e.message : '打卡记录加载失败')
  } finally {
    historyCheckinsLoading.value = false
  }
}

function closeHistoryDetail() {
  historyDetail.value = null
  historyCheckins.value = []
  overviewTab.value = 'exercise'
}

function onNavBack() {
  if (viewingHistoryDetail.value) {
    closeHistoryDetail()
    return
  }
  router.back()
}

function onTabClick(name: 'today' | 'overview' | 'history') {
  tab.value = name
  if (name !== 'history') {
    historyDetail.value = null
    historyCheckins.value = []
    if (overviewTab.value === 'checkins') {
      overviewTab.value = 'exercise'
    }
  }
}

watch(tab, (name) => {
  if (name === 'history' && !historyList.value.length && !historyLoading.value) {
    loadHistoryList()
  }
})

onMounted(async () => {
  try {
    const [t, o] = await Promise.all([
      api<{ data: any }>('/api/c/v1/me/care-plan/today'),
      api<{ data: any }>('/api/c/v1/me/care-plan'),
    ])
    today.value = t.data
    overview.value = o.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function checkin(taskId: string, status: string) {
  busyId.value = taskId
  try {
    await api(`/api/c/v1/me/care-plan/tasks/${taskId}/checkins`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    })
    showToast({ type: 'success', message: status === 'DONE' ? '已完成' : '已跳过' })
    const res = await api<{ data: any }>('/api/c/v1/me/care-plan/today')
    today.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar
      :title="planTitle"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="onNavBack"
    />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <div v-if="!viewingHistoryDetail" class="seg">
        <button
          v-for="t in tabs"
          :key="t.name"
          type="button"
          class="seg-item"
          :class="{ active: tab === t.name }"
          @click="onTabClick(t.name)"
        >
          {{ t.title }}
        </button>
      </div>

      <section v-if="showAiDisclaimer && showPlanContent" class="ai-tip">
        <div class="ai-tip-head">
          <span class="ai-tip-icon" aria-hidden="true">
            <van-icon name="warning-o" size="16" />
          </span>
          <span class="ai-tip-title">AI 生成内容 · 安全提示</span>
        </div>
        <div class="ai-tip-body">
          <p>
            本方案含 AI 辅助生成内容，仅供健康管理参考，<strong>不构成医学诊断或处方</strong>。
            如出现不适、用药疑问或需调整方案，请及时联系医生或健管师。
          </p>
        </div>
      </section>

      <!-- 今日执行 -->
      <template v-if="tab === 'today'">
        <section v-if="todayTotal" class="card progress-card">
          <div class="progress-head">
            <span>今日进度</span>
            <strong>{{ todayDone }}/{{ todayTotal }}</strong>
          </div>
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: todayProgressPct + '%' }" />
          </div>
        </section>

        <van-empty v-if="!todayTasks.length" description="今日暂无任务" image-size="64" />

        <section v-else class="card list-card">
          <div v-for="task in todayTasks" :key="task.id" class="item">
            <div class="item-main">
              <div class="item-meta-row">
                <span class="tag">
                  {{ formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, task.category) }}
                </span>
                <span class="meta">
                  {{
                    [
                      formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot),
                      formatCarePlanLabel(CARE_PLAN_FREQUENCY_LABELS, task.frequency),
                    ]
                      .filter(Boolean)
                      .join(' · ')
                  }}
                </span>
              </div>
              <strong>{{ task.title }}</strong>
              <p v-if="!taskPending(task) && task.checkinStatus" class="status">
                {{ formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, task.checkinStatus) }}
              </p>
            </div>
            <div v-if="taskPending(task)" class="item-actions">
              <van-button
                size="small"
                type="primary"
                round
                :loading="busyId === task.id"
                @click="checkin(task.id, 'DONE')"
              >
                完成
              </van-button>
              <van-button size="small" plain round :disabled="!!busyId" @click="checkin(task.id, 'SKIPPED')">
                跳过
              </van-button>
            </div>
            <div v-else class="done-mark" aria-hidden="true">
              <van-icon name="success" size="16" />
            </div>
          </div>
        </section>
      </template>

      <!-- 方案内容 / 历史详情（复用同一套展示） -->
      <template v-if="showPlanContent">
        <van-loading v-if="historyDetailLoading" vertical class="loading">加载中</van-loading>
        <van-empty
          v-else-if="tab === 'overview' && !hasOverview"
          description="暂无已发布方案"
          image-size="64"
        />
        <van-empty
          v-else-if="viewingHistoryDetail && !hasHistoryContent"
          description="方案不存在"
          image-size="64"
        />

        <template v-else-if="hasOverview || hasHistoryContent">
          <section v-if="viewingHistoryDetail" class="card history-banner">
            <div class="history-banner-row">
              <span class="tag" :class="{ active: historyDetail?.status === 'ACTIVE' }">
                {{ historyStatusLabel(historyDetail?.status) }}
              </span>
              <span v-if="formatCarePlanSource(historyDetail?.source)" class="meta">
                {{ formatCarePlanSource(historyDetail?.source) }}
              </span>
            </div>
            <p v-if="historyDetail?.publishedAt" class="meta">
              发布于 {{ formatHistoryTime(historyDetail.publishedAt) }}
            </p>
          </section>

          <section class="card summary">
            <div class="goal-block">
              <div class="goal-label">目标</div>
              <p class="goal">{{ goalText || '暂无目标说明' }}</p>
            </div>
          </section>

          <div class="sub-seg" :class="{ 'cols-4': overviewTabs.length >= 4 }">
            <button
              v-for="t in overviewTabs"
              :key="t.name"
              type="button"
              class="sub-seg-item"
              :class="{ active: overviewTab === t.name }"
              @click="overviewTab = t.name"
            >
              {{ t.title }}
            </button>
          </div>

          <!-- 运动 -->
          <section v-if="overviewTab === 'exercise'" class="card plan-panel">
            <div class="section-title">运动方案</div>
            <template
              v-if="
                exercise.goal ||
                exerciseContraindications.length ||
                exercisePrecautions.length ||
                weeklyPlan.length ||
                exercise.reviewHint
              "
            >
              <div v-if="exercise.goal" class="plan-block">
                <div class="plan-label">运动目标</div>
                <p class="plan-text">{{ exercise.goal }}</p>
              </div>

              <div v-if="exerciseContraindications.length" class="plan-block">
                <div class="plan-label warn">禁忌</div>
                <ol class="plan-ol">
                  <li v-for="(item, i) in exerciseContraindications" :key="'c-' + i">{{ item }}</li>
                </ol>
              </div>

              <div v-if="exercisePrecautions.length" class="plan-block">
                <div class="plan-label">注意事项</div>
                <ol class="plan-ol">
                  <li v-for="(item, i) in exercisePrecautions" :key="'p-' + i">{{ item }}</li>
                </ol>
              </div>

              <div v-if="exercise.reviewHint" class="plan-block plan-kv">
                <p>
                  <em>复诊提示</em>
                  <span>{{ exercise.reviewHint }}</span>
                </p>
              </div>

              <div v-if="weeklyPlanRows.length" class="plan-block">
                <div class="plan-label">周计划</div>
                <div class="table-wrap">
                  <table class="week-table">
                    <thead>
                      <tr>
                        <th>星期</th>
                        <th>类型</th>
                        <th>强度</th>
                        <th>时长</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="row in weeklyPlanRows" :key="row.key" :class="{ rest: row.rest }">
                        <td class="day">{{ row.showDay ? row.day : '' }}</td>
                        <td>
                          <div>{{ row.type }}</div>
                          <div v-if="row.note" class="note">{{ row.note }}</div>
                        </td>
                        <td>{{ row.intensity }}</td>
                        <td>{{ row.duration }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </template>
            <van-empty v-else description="暂无运动方案" image-size="56" />
          </section>

          <!-- 饮食 -->
          <section v-else-if="overviewTab === 'diet'" class="card plan-panel">
            <div class="section-title">饮食方案</div>
            <template
              v-if="
                dietPrinciples.length ||
                dietCalorieHint ||
                dietNotes ||
                dietRecommended.length ||
                dietLimited.length ||
                sampleDay.length
              "
            >
              <div v-if="dietPrinciples.length" class="plan-block">
                <div class="plan-label">饮食原则</div>
                <ol class="plan-ol">
                  <li v-for="(p, i) in dietPrinciples" :key="'pr-' + i">{{ p }}</li>
                </ol>
              </div>

              <div v-if="dietCalorieHint || dietNotes" class="plan-block plan-kv">
                <p v-if="dietCalorieHint">
                  <em>能量建议</em>
                  <span>{{ dietCalorieHint }}</span>
                </p>
                <p v-if="dietNotes">
                  <em>备注</em>
                  <span>{{ dietNotes }}</span>
                </p>
              </div>

              <div v-if="dietRecommended.length || dietLimited.length" class="plan-block">
                <div v-if="dietRecommended.length" class="plan-chip-row">
                  <em class="ok">推荐</em>
                  <div class="food-chips">
                    <span v-for="(item, i) in dietRecommended" :key="'r-' + i" class="food-chip ok">
                      {{ formatCarePlanFoodItem(item) }}
                    </span>
                  </div>
                </div>
                <div v-if="dietLimited.length" class="plan-chip-row">
                  <em class="warn">限制</em>
                  <div class="food-chips">
                    <span v-for="(item, i) in dietLimited" :key="'l-' + i" class="food-chip warn">
                      {{ formatCarePlanFoodItem(item) }}
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="sampleDay.length" class="plan-block">
                <div class="plan-label">示例一日</div>
                <div class="plan-lines">
                  <div v-for="row in sampleDay" :key="row.key" class="plan-line">
                    <strong>{{ row.label }}</strong>
                    <p>{{ row.items.join(' · ') }}</p>
                  </div>
                </div>
              </div>
            </template>
            <van-empty v-else description="暂无饮食方案" image-size="56" />
          </section>

          <!-- 计划 -->
          <section v-else-if="overviewTab === 'plan'" class="card plan-panel">
            <div class="section-title">
              健康计划
              <span v-if="execution.horizonDays" class="badge">{{ execution.horizonDays }} 天</span>
            </div>
            <van-empty v-if="!planTasks.length" description="暂无任务" image-size="56" />
            <div v-else class="plan-block">
              <div class="plan-label">执行任务</div>
              <ol class="plan-task-ol">
                <li v-for="(task, i) in planTasks" :key="task.id || i">
                  <strong>{{ task.title }}</strong>
                  <p>
                    {{
                      [
                        formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, task.category),
                        formatCarePlanLabel(CARE_PLAN_FREQUENCY_LABELS, task.frequency),
                        formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot),
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    }}
                  </p>
                </li>
              </ol>
            </div>
          </section>

          <!-- 历史版本打卡 -->
          <section v-else-if="overviewTab === 'checkins'" class="card plan-panel">
            <div class="section-title">
              打卡记录
              <span v-if="historyCheckins.length" class="badge">{{ historyCheckins.length }} 条</span>
            </div>
            <van-loading v-if="historyCheckinsLoading" vertical class="loading">加载中</van-loading>
            <van-empty
              v-else-if="!historyCheckins.length"
              description="该版本暂无打卡记录"
              image-size="56"
            />
            <div v-else class="checkin-history-list">
              <div
                v-for="row in historyCheckins"
                :key="row.id || `${row.checkinDate}-${row.taskId}-${row.timeSlot}`"
                class="checkin-history-item"
              >
                <div class="checkin-history-main">
                  <strong>{{ row.taskTitle || '任务' }}</strong>
                  <span
                    class="checkin-status"
                    :class="{
                      done: row.status === 'DONE',
                      skipped: row.status === 'SKIPPED',
                      missed: row.status === 'MISSED',
                    }"
                  >
                    {{ formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, row.status) }}
                  </span>
                </div>
                <p class="meta">
                  {{
                    [
                      row.checkinDate,
                      formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, row.taskCategory),
                      formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, row.timeSlot),
                    ]
                      .filter(Boolean)
                      .join(' · ')
                  }}
                </p>
                <p v-if="row.note" class="checkin-note">{{ row.note }}</p>
              </div>
            </div>
          </section>
        </template>
      </template>

      <!-- 历史方案列表 -->
      <template v-else-if="tab === 'history'">
        <van-loading v-if="historyLoading" vertical class="loading">加载中</van-loading>
        <van-empty v-else-if="!historyList.length" description="暂无历史方案" image-size="64" />
        <section v-else class="card list-card">
          <button
            v-for="item in historyList"
            :key="item.id"
            type="button"
            class="history-item"
            @click="openHistory(item)"
          >
            <div class="history-main">
              <div class="history-title-row">
                <strong>{{ item.title || '管理方案' }}</strong>
                <span class="tag" :class="{ active: item.status === 'ACTIVE' }">
                  {{ historyStatusLabel(item.status) }}
                </span>
              </div>
              <p v-if="item.goalSummary" class="history-summary">{{ item.goalSummary }}</p>
              <p class="meta">
                {{
                  [formatCarePlanSource(item.source), formatHistoryTime(item.createdAt)]
                    .filter(Boolean)
                    .join(' · ')
                }}
              </p>
            </div>
            <van-icon name="arrow" class="history-arrow" />
          </button>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.22), transparent 40%),
    radial-gradient(circle at 92% 4%, rgba(245, 166, 35, 0.12), transparent 34%),
    var(--hx-bg);
  padding-bottom: 28px;
}

.loading {
  padding: 48px 0;
}

:deep(.van-nav-bar) {
  background: rgba(247, 249, 252, 0.88);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 650;
  color: var(--hx-text);
  max-width: 220px;
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}

.seg {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 4px;
  margin: 8px 16px 0;
  padding: 4px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--hx-shadow);
}
.seg-item {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 550;
  line-height: 1;
  padding: 11px 0;
  border-radius: 10px;
  transition: background 0.18s ease, color 0.18s ease;
}
.seg-item.active {
  background: linear-gradient(145deg, #5cb8b8, #2b9e9e);
  color: #fff;
  box-shadow: 0 4px 12px rgba(43, 158, 158, 0.28);
}

.ai-tip {
  margin: 12px 16px 0;
  border: 1px solid #f5d9a8;
  border-radius: 14px;
  background: linear-gradient(135deg, #fff9ed 0%, #fff4e0 100%);
  overflow: hidden;
}
.ai-tip-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 6px;
  color: #8a5a12;
}
.ai-tip-icon {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #ffe7ba;
  color: #d48806;
  flex-shrink: 0;
}
.ai-tip-title {
  flex: 1;
  font-size: 13px;
  font-weight: 650;
}
.ai-tip-body {
  padding: 0 14px 12px 48px;
}
.ai-tip-body p {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: #7c5a1e;
}

.sub-seg {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin: 12px 16px 0;
}
.sub-seg.cols-4 {
  grid-template-columns: repeat(4, 1fr);
  gap: 5px;
}
.sub-seg-item {
  border: 1px solid #e4ecec;
  background: rgba(255, 255, 255, 0.86);
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 550;
  line-height: 1.2;
  padding: 10px 0;
  border-radius: 999px;
  text-align: center;
  white-space: nowrap;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.sub-seg.cols-4 .sub-seg-item {
  padding: 9px 2px;
  font-size: 12px;
  letter-spacing: -0.02em;
}
.sub-seg-item.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 650;
}

.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
}

.progress-card {
  padding: 14px 16px;
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--hx-muted);
}
.progress-head strong {
  color: var(--hx-text);
  font-variant-numeric: tabular-nums;
}
.progress-track {
  height: 8px;
  border-radius: 999px;
  background: #e8f4f4;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  transition: width 0.25s ease;
}

.list-card {
  padding: 4px 16px 8px;
}
.item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 14px 0;
  border-top: 1px solid #f0f4f6;
}
.item:first-child {
  border-top: 0;
}
.item-main {
  min-width: 0;
  flex: 1;
}
.item-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 550;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.meta {
  font-size: 12px;
  color: var(--hx-muted);
}
.item-main strong {
  display: block;
  font-size: 14px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.4;
}
.status {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.item-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}
.done-mark {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #e8f8f1;
  color: #2a9b6f;
  flex-shrink: 0;
}

.goal-block {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}
.goal-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 650;
  color: var(--hx-teal);
  letter-spacing: 0.02em;
}
.goal {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--hx-text);
  white-space: pre-wrap;
}

.section-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 700;
  color: var(--hx-text);
  display: flex;
  align-items: center;
  gap: 8px;
}
.badge {
  font-size: 12px;
  font-weight: 500;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.lead {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--hx-text);
  white-space: pre-wrap;
}
.hint-line {
  margin: 8px 0 0;
  color: var(--hx-muted);
  font-size: 13px;
  line-height: 1.5;
}
.block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f4f6;
}
.block-title {
  font-size: 12px;
  font-weight: 550;
  color: var(--hx-muted);
  margin-bottom: 8px;
}
.block ul,
.callout ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-text);
}
.callout {
  margin-top: 12px;
  padding: 12px;
  border-radius: 12px;
}
.callout.warn {
  background: #fff7ed;
}
.callout.soft {
  background: #f3fafa;
}
.callout-title {
  font-size: 12px;
  font-weight: 650;
  color: #c2410c;
  margin-bottom: 6px;
}
.callout-title.soft {
  color: var(--hx-teal);
}
.callout.soft p {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
}

.plan-panel .section-title {
  margin-bottom: 8px;
}
.plan-block + .plan-block {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f4f6;
}
.plan-label {
  font-size: 12px;
  font-weight: 650;
  color: var(--hx-muted);
  margin-bottom: 6px;
}
.plan-label.warn {
  color: #c2410c;
}
.plan-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
  white-space: pre-wrap;
}
.plan-ol,
.plan-task-ol {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
}
.plan-ol li + li,
.plan-task-ol li + li {
  margin-top: 6px;
}
.plan-task-ol strong {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.4;
}
.plan-task-ol p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.plan-kv p {
  margin: 0;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.plan-kv p + p {
  margin-top: 6px;
}
.plan-kv em,
.plan-chip-row em {
  flex-shrink: 0;
  font-style: normal;
  font-size: 12px;
  font-weight: 650;
  color: var(--hx-teal);
  min-width: 3.5em;
}
.plan-chip-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.plan-chip-row + .plan-chip-row {
  margin-top: 8px;
}
.plan-chip-row em.ok {
  color: #2a9b6f;
}
.plan-chip-row em.warn {
  color: #c2410c;
}
.food-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.food-chip {
  font-size: 12px;
  font-weight: 550;
  padding: 4px 8px;
  border-radius: 999px;
  line-height: 1.2;
}
.food-chip.ok {
  color: #2a9b6f;
  background: #e8f8f1;
}
.food-chip.warn {
  color: #c2410c;
  background: #ffedd5;
}
.plan-lines {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plan-line {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 8px;
  align-items: start;
}
.plan-line strong {
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal);
  line-height: 1.45;
}
.plan-line p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}

.row + .row {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f0f4f6;
}
.row strong {
  display: block;
  font-size: 14px;
  font-weight: 650;
  color: var(--hx-text);
}
.row p {
  margin: 4px 0 0;
  color: var(--hx-muted);
  font-size: 13px;
  line-height: 1.5;
}

.chips-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 12px;
}
.chips-label {
  font-size: 12px;
  color: var(--hx-muted);
  margin-right: 2px;
}
.chip {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  font-weight: 550;
}
.chip.ok {
  background: #e8f8f1;
  color: #2a9b6f;
}
.chip.warn {
  background: #fff7ed;
  color: #c2410c;
}

.table-wrap {
  margin-top: 4px;
  border: 1px solid #e8f0f0;
  border-radius: 12px;
  overflow: hidden;
}
.week-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}
.week-table th,
.week-table td {
  padding: 10px 8px;
  text-align: left;
  vertical-align: top;
  line-height: 1.4;
}
.week-table th {
  background: #f3fafa;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  border-bottom: 1px solid #e8f0f0;
}
.week-table td {
  border-top: 1px solid #f0f4f6;
  color: var(--hx-text);
  word-break: break-word;
}
.week-table tbody tr:first-child td {
  border-top: 0;
}
.week-table .day {
  width: 18%;
  font-weight: 650;
  color: var(--hx-teal);
}
.week-table th:nth-child(1),
.week-table td:nth-child(1) {
  width: 18%;
}
.week-table th:nth-child(2),
.week-table td:nth-child(2) {
  width: 34%;
}
.week-table th:nth-child(3),
.week-table td:nth-child(3) {
  width: 20%;
}
.week-table th:nth-child(4),
.week-table td:nth-child(4) {
  width: 28%;
}
.week-table .note {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
  line-height: 1.35;
}
.week-table tr.rest td {
  color: var(--hx-muted);
}

.history-banner {
  margin-top: 12px;
}
.history-banner-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.history-banner .meta {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  padding: 12px 0;
  color: inherit;
}
.history-item + .history-item {
  border-top: 1px solid #f0f3f3;
}
.history-main {
  flex: 1;
  min-width: 0;
}
.history-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.history-title-row strong {
  font-size: 15px;
  font-weight: 650;
}
.history-summary {
  margin: 4px 0 0;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
}
.history-item .meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.4;
}
.history-arrow {
  color: #c0c8d0;
  flex-shrink: 0;
}
.checkin-history-list {
  display: flex;
  flex-direction: column;
}
.checkin-history-item {
  padding: 12px 0;
}
.checkin-history-item + .checkin-history-item {
  border-top: 1px solid #f0f3f3;
}
.checkin-history-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.checkin-history-main strong {
  font-size: 14px;
  font-weight: 650;
}
.checkin-status {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #6b7280;
}
.checkin-status.done {
  background: #ecfdf5;
  color: #047857;
}
.checkin-status.skipped {
  background: #eff6ff;
  color: #1d4ed8;
}
.checkin-status.missed {
  background: #fef2f2;
  color: #b91c1c;
}
.checkin-history-item .meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.checkin-note {
  margin: 6px 0 0;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.45;
}
.tag.active {
  color: #2a9b6f;
  background: #e8f8f1;
}

:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
:deep(.van-button--plain) {
  color: var(--hx-muted);
  border-color: #d9e4e4;
  background: #fff;
}
:deep(.van-empty) {
  padding: 36px 0;
}
:deep(.van-empty__description) {
  color: var(--hx-muted);
  font-size: 13px;
}
</style>
