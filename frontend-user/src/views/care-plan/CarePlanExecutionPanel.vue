<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  CARE_PLAN_FREQUENCY_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  carePlanCategoryTone,
  carePlanTaskCategoryLabel,
  formatCarePlanLabel,
} from '../../shared/care-plan-labels'
import { parseGoalMetrics } from '../../shared/care-plan-goal-metrics'

const props = defineProps<{
  goalText?: string
  planTasks?: any[]
}>()

type FilterKey = 'all' | 'exercise' | 'diet' | 'monitor' | 'other'

const filter = ref<FilterKey>('all')

const tasks = computed(() => (Array.isArray(props.planTasks) ? props.planTasks : []))

const filterTabs = computed(() => {
  const counts = { exercise: 0, diet: 0, monitor: 0, other: 0 }
  for (const t of tasks.value) counts[bucketOf(t)] += 1
  return [
    { key: 'all' as const, label: '全部', count: tasks.value.length },
    { key: 'exercise' as const, label: '运动', count: counts.exercise },
    { key: 'diet' as const, label: '饮食', count: counts.diet },
    { key: 'monitor' as const, label: '监测', count: counts.monitor },
    { key: 'other' as const, label: '其它', count: counts.other },
  ].filter((t) => t.key === 'all' || t.count > 0)
})

const filteredTasks = computed(() => {
  if (filter.value === 'all') return tasks.value
  return tasks.value.filter((t) => bucketOf(t) === filter.value)
})

const goalMetrics = computed(() => parseGoalMetrics(props.goalText || ''))
const hasGoalMetrics = computed(() => goalMetrics.value.length >= 2)
/** 无法拆成网格时的兜底短句 */
const goalFallback = computed(() => {
  const raw = String(props.goalText || '').trim()
  if (!raw) return ''
  const parts = raw.split(/[。；;！!？?\n]+/).map((s) => s.trim()).filter(Boolean)
  return parts.find((p) => /\d/.test(p)) || parts[0] || raw
})

type IconTone =
  | 'bp'
  | 'weight'
  | 'glucose'
  | 'stand'
  | 'exercise'
  | 'diet'
  | 'water'
  | 'sleep'
  | 'mood'
  | 'med'
  | 'other'

/** 按 metric / taskCode / 标题识别图标，避免多类任务共用灰色文档占位 */
function iconOf(task: any): IconTone {
  const ref = String(task?.metricRef || '').toUpperCase()
  const code = String(task?.taskCode || '').toUpperCase()
  const title = String(task?.title || '')

  if (ref === 'WEIGHT' || code === 'WEIGHT_LOG' || /体重|体脂/.test(title)) return 'weight'
  if (
    ref === 'BLOOD_PRESSURE' ||
    ref.startsWith('BLOOD_PRESSURE') ||
    code === 'BP_HOME' ||
    /血压/.test(title)
  ) {
    return 'bp'
  }
  if (ref === 'BLOOD_GLUCOSE' || code === 'GLUCOSE_CHECK' || /血糖|糖化/.test(title)) {
    return 'glucose'
  }
  if (
    /SEDENTARY|STANDING_BREAK|SIT_BREAK|STAND_BREAK/.test(code) ||
    /久坐|起身|站立活动|每小时.*起/.test(title)
  ) {
    return 'stand'
  }

  const tone = carePlanCategoryTone(task?.category, task?.taskCode)
  if (tone === 'exercise') return 'exercise'
  if (tone === 'diet') return 'diet'
  if (tone === 'water') return 'water'
  if (tone === 'sleep') return 'sleep'
  if (tone === 'mood') return 'mood'
  if (tone === 'med') return 'med'
  return 'other'
}

/** 筛选桶：监测含血压/血糖/体重；久坐起身归运动；其余进「其它」 */
function bucketOf(task: any): Exclude<FilterKey, 'all'> {
  const icon = iconOf(task)
  if (icon === 'bp' || icon === 'weight' || icon === 'glucose') return 'monitor'
  if (icon === 'exercise' || icon === 'stand') return 'exercise'
  if (icon === 'diet') return 'diet'
  return 'other'
}

function subOf(task: any) {
  return [
    carePlanTaskCategoryLabel(task),
    formatCarePlanLabel(CARE_PLAN_FREQUENCY_LABELS, task?.frequency),
    formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task?.timeSlot),
  ]
    .filter(Boolean)
    .join(' · ')
}

function setFilter(key: FilterKey) {
  filter.value = key
}

watch(filterTabs, (tabs) => {
  if (!tabs.some((t) => t.key === filter.value)) filter.value = 'all'
})
</script>

<template>
  <div class="exec-panel">
    <section class="goal-card">
      <div class="goal-title">
        <span class="goal-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
            <circle cx="12" cy="12" r="5" stroke="currentColor" stroke-width="1.6" />
            <circle cx="12" cy="12" r="1.8" fill="currentColor" />
          </svg>
        </span>
        <span class="label">目标说明</span>
      </div>

      <div v-if="hasGoalMetrics" class="goal-grid">
        <article v-for="m in goalMetrics" :key="m.key" class="g-cell" :class="m.key">
          <span class="g-label">{{ m.label }}</span>
          <p class="g-value">
            <b>{{ m.value }}</b>
            <em v-if="m.unit">{{ m.unit }}</em>
          </p>
          <span class="g-hint">{{ m.hint || '\u00a0' }}</span>
        </article>
      </div>
      <p v-else class="goal-fallback">{{ goalFallback || '暂无目标说明' }}</p>
    </section>

    <section class="list-card">
      <div class="list-head">
        <strong>任务清单</strong>
        <span>{{ filteredTasks.length }} 项</span>
      </div>

      <div v-if="tasks.length" class="filter-tabs" role="tablist" aria-label="按类别筛选">
        <button
          v-for="tab in filterTabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="ftab"
          :class="{ on: filter === tab.key }"
          :aria-selected="filter === tab.key"
          @click="setFilter(tab.key)"
        >
          {{ tab.label }}
          <em>{{ tab.count }}</em>
        </button>
      </div>

      <van-empty v-if="!tasks.length" description="暂无执行任务" image-size="56" />
      <van-empty
        v-else-if="!filteredTasks.length"
        description="该分类暂无任务"
        image-size="48"
      />
      <ul v-else class="task-list">
        <li v-for="(task, i) in filteredTasks" :key="task.id || i" class="task-row">
          <span class="tico" :class="iconOf(task)" aria-hidden="true">
            <!-- 血压 / 心电 -->
            <svg v-if="iconOf(task) === 'bp'" viewBox="0 0 24 24" fill="none">
              <path
                d="M3.5 12h3.2l1.8-4.5 2.6 9 2.2-6.2H20.5"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <!-- 体重秤 -->
            <svg v-else-if="iconOf(task) === 'weight'" viewBox="0 0 24 24" fill="none">
              <rect x="4.5" y="5" width="15" height="14" rx="2.5" stroke="currentColor" stroke-width="1.7" />
              <circle cx="12" cy="12" r="3.2" stroke="currentColor" stroke-width="1.7" />
              <path d="M12 12l2.2-1.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
            <!-- 血糖水滴 -->
            <svg v-else-if="iconOf(task) === 'glucose'" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 3.2C12 3.2 6.5 10 6.5 14.2a5.5 5.5 0 0011 0C17.5 10 12 3.2 12 3.2z" />
            </svg>
            <!-- 久坐起身 / 站立 -->
            <svg v-else-if="iconOf(task) === 'stand'" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="4.5" r="2.2" stroke="currentColor" stroke-width="1.7" />
              <path
                d="M12 8.2v6.2M8.5 21.5L12 14.4l3.5 7.1M7.5 11.5h9"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <svg v-else-if="iconOf(task) === 'exercise'" viewBox="0 0 24 24" fill="currentColor">
              <circle cx="13.5" cy="3.5" r="2.2" />
              <path
                d="M9.85 19.2l1.05-4.55 2.15 2.15V22h2.1v-7.35l-2.2-2.2.55-2.85c1.35 1.45 3.35 2.4 5.55 2.4v-2.1c-1.9 0-3.5-.95-4.35-2.35l-1-1.55c-.35-.55-.95-.95-1.65-.95-.3 0-.55.05-.8.15l-5.25 2.2v4.85h2.1v-3.45l1.85-.75-1.7 8.35-4.85-1-.4 2.05 6.95 1.45z"
              />
            </svg>
            <svg v-else-if="iconOf(task) === 'diet'" viewBox="0 0 24 24" fill="none">
              <path d="M4 11h16v1.5a6 6 0 01-6 6h-4a6 6 0 01-6-6V11z" stroke="currentColor" stroke-width="1.7" />
              <path d="M7 11V8.5M12 11V7M17 11V8.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
              <path d="M5 19h14" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
            <svg v-else-if="iconOf(task) === 'water'" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 3.2C12 3.2 6.5 10 6.5 14.2a5.5 5.5 0 0011 0C17.5 10 12 3.2 12 3.2z" />
            </svg>
            <svg v-else-if="iconOf(task) === 'sleep'" viewBox="0 0 24 24" fill="currentColor">
              <path d="M14.5 4.2A7.8 7.8 0 004.8 15.2 7.9 7.9 0 0016.5 19a7.8 7.8 0 00-2-14.8z" />
            </svg>
            <svg v-else-if="iconOf(task) === 'mood'" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.7" />
              <circle cx="9" cy="10.5" r="1.1" fill="currentColor" />
              <circle cx="15" cy="10.5" r="1.1" fill="currentColor" />
              <path d="M8.5 14.2c1.1 1.4 2.6 2.1 3.5 2.1s2.4-.7 3.5-2.1" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
            <svg v-else-if="iconOf(task) === 'med'" viewBox="0 0 24 24" fill="none">
              <rect x="7.2" y="3.5" width="9.6" height="17" rx="4.8" stroke="currentColor" stroke-width="1.7" />
              <path d="M7.2 12h9.6" stroke="currentColor" stroke-width="1.7" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none">
              <rect x="5" y="4" width="14" height="16" rx="2.5" stroke="currentColor" stroke-width="1.7" />
              <path d="M8.5 9h7M8.5 12.5h7M8.5 16h4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
          </span>
          <div class="tcopy">
            <strong>{{ task.title }}</strong>
            <p v-if="subOf(task)">{{ subOf(task) }}</p>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.exec-panel {
  padding: 12px 16px calc(48px + env(safe-area-inset-bottom, 0px));
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.goal-card,
.list-card {
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.02);
  padding: 16px;
}
.goal-card {
  background: #f8fafc;
  box-shadow: none;
  border: 0;
  padding: 14px 14px 12px;
}
.goal-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
}
.goal-ico {
  flex: none;
  width: 18px;
  height: 18px;
  color: #0284c7;
  display: grid;
  place-items: center;
}
.goal-ico svg {
  width: 16px;
  height: 16px;
  display: block;
}
.label {
  font-size: 12px;
  font-weight: 700;
  color: #94a3b8;
}
.goal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  align-items: stretch;
}
.g-cell {
  height: 100%;
  min-height: 92px;
  box-sizing: border-box;
  padding: 12px 10px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.03);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.g-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  line-height: 1.2;
}
.g-value {
  margin: 6px 0 0;
  display: flex;
  align-items: baseline;
  gap: 3px;
  line-height: 1.1;
}
.g-value b {
  font-size: 20px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.03em;
}
.g-value em {
  font-style: normal;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
}
.g-hint {
  display: block;
  margin-top: auto;
  padding-top: 6px;
  font-size: 10px;
  font-weight: 500;
  color: #94a3b8;
  line-height: 1.35;
  min-height: 1.35em;
}
.g-cell.weight .g-value b {
  color: #0d9488;
}
.g-cell.bp .g-value b {
  color: #0284c7;
}
.g-cell.waist .g-value b {
  color: #7c3aed;
}
.g-cell.exercise .g-value b {
  color: #059669;
}
.g-cell.glucose .g-value b {
  color: #ea580c;
}
.goal-fallback {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.7;
  color: #334155;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.list-head strong {
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
}
.list-head span {
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
}
.filter-tabs {
  display: flex;
  flex-wrap: nowrap; /* 窄屏禁止换行，改为横滑 */
  flex-shrink: 0;
  gap: 8px;
  margin: 0 -16px 8px;
  padding: 0 16px 4px;
  overflow-x: auto;
  overflow-y: hidden;
  white-space: nowrap;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.filter-tabs::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}
.ftab {
  flex: 0 0 auto;
  flex-shrink: 0;
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  line-height: 1.2;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;
}
.ftab em {
  font-style: normal;
  font-weight: 600;
  opacity: 0.75;
}
.ftab.on {
  background: #ecfdf5;
  color: #059669;
}
.ftab.on em {
  opacity: 1;
}
.task-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.task-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid #f8fafc;
}
.task-row:last-child {
  border-bottom: 0;
  padding-bottom: 4px;
}
.tico {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #f1f5f9;
  color: #64748b;
}
.tico svg {
  width: 20px;
  height: 20px;
  display: block;
}
.tico.exercise {
  background: #ecfdf5;
  color: #059669;
}
.tico.diet {
  background: #fff7ed;
  color: #ea580c;
}
.tico.water,
.tico.glucose {
  background: #eff6ff;
  color: #2563eb;
}
.tico.bp {
  background: #e0f2fe;
  color: #0284c7;
}
.tico.weight {
  background: #ecfeff;
  color: #0d9488;
}
.tico.stand {
  background: #fff7ed;
  color: #ea580c;
}
.tico.sleep {
  background: #eef2ff;
  color: #6366f1;
}
.tico.mood {
  background: #fdf2f8;
  color: #db2777;
}
.tico.med {
  background: #f5f3ff;
  color: #7c3aed;
}
.tcopy {
  flex: 1;
  min-width: 0;
}
.tcopy strong {
  display: block;
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
}
.tcopy p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: #94a3b8;
}
:deep(.van-empty) {
  padding: 28px 0;
}
</style>
