<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CarePlanAiDisclaimer from './CarePlanAiDisclaimer.vue'
import {
  formatCarePlanFrequency,
  formatCarePlanTaskCategory,
  formatCarePlanTimeSlot,
  isAiGeneratedCarePlanSource,
} from './care-plan-labels'

export interface CarePlanPreviewCheckin {
  id?: string
  checkinDate?: string
  taskTitle?: string
  taskCategory?: string
  timeSlot?: string
  status?: string
  note?: string
}

export interface CarePlanPreviewData {
  title?: string
  goalSummary?: string
  /** 方案总结（AI/人工撰写的阶段总结） */
  summary?: string
  source?: string
  versionLabel?: string
  status?: string
  exercise?: {
    goal?: string
    precautions?: string[]
    contraindications?: string[]
    reviewHint?: string
    weeklyPlan?: Array<{ day: string; items: unknown[] }>
  }
  diet?: {
    principles?: string[]
    calorieHint?: string
    notes?: string
    recommended?: Array<{ label?: string; code?: string }>
    limited?: Array<{ label?: string; code?: string }>
    sampleDay?: Record<string, string>
  }
  execution?: {
    horizonDays?: number
    tasks?: Array<{ title: string; category?: string; frequency?: string; timeSlot?: string; enabled?: boolean }>
  }
  publishedAt?: string
  /** 已发布版本才有；草稿不传该字段则不展示打卡区块 */
  checkins?: CarePlanPreviewCheckin[]
  checkinsLoading?: boolean
}

const props = defineProps<{
  modelValue: boolean
  data: CarePlanPreviewData | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const activeSections = ref<string[]>(['summary', 'exercise', 'diet', 'execution', 'checkins'])

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      activeSections.value = ['summary', 'exercise', 'diet', 'execution', 'checkins']
    }
  },
)

const showCheckinsSection = computed(
  () => props.data != null && (props.data.checkins !== undefined || props.data.checkinsLoading),
)

function checkinStatusLabel(status?: string) {
  if (status === 'DONE') return '已完成'
  if (status === 'SKIPPED') return '已跳过'
  if (status === 'MISSED') return '未打卡'
  return status || '-'
}

function checkinStatusTagType(status?: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'DONE') return 'success'
  if (status === 'SKIPPED') return 'info'
  if (status === 'MISSED') return 'danger'
  return 'warning'
}

const isAiSource = computed(() => isAiGeneratedCarePlanSource(props.data?.source))

function sourceLabel(source?: string) {
  if (source === 'LLM' || source === 'LLM_THEN_EDIT') return 'AI 生成'
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || '-'
}

function statusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || '-'
}

function statusTagType(status?: string): 'success' | 'warning' | 'info' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    width="760px"
    destroy-on-close
    class="care-plan-preview-dialog"
    :show-close="true"
  >
    <template #header>
      <div class="dialog-header">
        <span class="dialog-title">方案详情</span>
        <div v-if="data" class="dialog-tags">
          <el-tag size="small" :type="statusTagType(data.status)" effect="light" round>
            {{ statusLabel(data.status) }}
          </el-tag>
          <el-tag
            v-if="isAiSource"
            size="small"
            effect="dark"
            round
            class="ai-source-tag"
          >
            AI 生成
          </el-tag>
          <el-tag v-else size="small" effect="plain" round>
            {{ sourceLabel(data.source) }}
          </el-tag>
        </div>
      </div>
    </template>

    <template v-if="data">
      <div class="summary-card">
        <h3 class="plan-title">{{ data.title || '管理方案' }}</h3>
        <div class="plan-meta">
          <span v-if="data.versionLabel" class="meta-item">
            <el-icon><Collection /></el-icon>
            {{ data.versionLabel }}
          </span>
          <span v-if="data.publishedAt" class="meta-item">
            <el-icon><Clock /></el-icon>
            {{ formatDateTime(data.publishedAt) }}
          </span>
        </div>
        <p v-if="data.goalSummary" class="goal-summary">{{ data.goalSummary }}</p>
      </div>

      <CarePlanAiDisclaimer v-if="isAiSource" class="ai-disclaimer-block" />

      <el-collapse v-model="activeSections" class="plan-sections">
        <el-collapse-item name="summary">
          <template #title>
            <div class="section-head section-head--summary">
              <span class="section-icon"><el-icon><Document /></el-icon></span>
              <span class="section-name">方案总结</span>
            </div>
          </template>
          <div class="section-body">
            <p v-if="data.summary" class="section-lead">{{ data.summary }}</p>
            <p v-else class="section-lead muted">（未填写）</p>
          </div>
        </el-collapse-item>

        <el-collapse-item name="exercise">
          <template #title>
            <div class="section-head section-head--exercise">
              <span class="section-icon"><el-icon><Basketball /></el-icon></span>
              <span class="section-name">运动方案</span>
            </div>
          </template>
          <div class="section-body">
            <p class="section-lead">{{ data.exercise?.goal || '（未填写）' }}</p>
            <div v-if="data.exercise?.contraindications?.length" class="info-block info-block--warn">
              <div class="info-block-title">禁忌</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.contraindications" :key="'c-' + i">{{ item }}</li>
              </ul>
            </div>
            <div v-if="data.exercise?.precautions?.length" class="info-block">
              <div class="info-block-title">注意事项</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.precautions" :key="'p-' + i">{{ item }}</li>
              </ul>
            </div>
            <p v-if="data.exercise?.reviewHint" class="section-hint">
              <el-icon><InfoFilled /></el-icon>
              {{ data.exercise.reviewHint }}
            </p>
          </div>
        </el-collapse-item>

        <el-collapse-item name="diet">
          <template #title>
            <div class="section-head section-head--diet">
              <span class="section-icon"><el-icon><Dish /></el-icon></span>
              <span class="section-name">饮食方案</span>
            </div>
          </template>
          <div class="section-body">
            <p v-if="data.diet?.principles?.length" class="section-lead">
              {{ data.diet.principles.join('；') }}
            </p>
            <p v-else class="section-lead muted">（未填写）</p>
            <p v-if="data.diet?.calorieHint" class="section-hint">{{ data.diet.calorieHint }}</p>
            <p v-if="data.diet?.notes" class="section-note">{{ data.diet.notes }}</p>
            <div v-if="data.diet?.recommended?.length" class="tag-group">
              <span class="tag-group-label">推荐</span>
              <el-tag
                v-for="(item, i) in data.diet.recommended"
                :key="'r-' + i"
                size="small"
                type="success"
                effect="light"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>
            <div v-if="data.diet?.limited?.length" class="tag-group">
              <span class="tag-group-label">限制</span>
              <el-tag
                v-for="(item, i) in data.diet.limited"
                :key="'l-' + i"
                size="small"
                type="warning"
                effect="light"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item name="execution">
          <template #title>
            <div class="section-head section-head--execution">
              <span class="section-icon"><el-icon><Calendar /></el-icon></span>
              <span class="section-name">执行计划</span>
              <span v-if="data.execution?.horizonDays" class="section-badge">
                {{ data.execution.horizonDays }} 天
              </span>
            </div>
          </template>
          <div class="section-body">
            <el-table
              v-if="data.execution?.tasks?.length"
              :data="data.execution.tasks"
              size="small"
              stripe
              class="task-table"
            >
              <el-table-column prop="title" label="任务" min-width="180" show-overflow-tooltip />
              <el-table-column label="类别" width="96">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ formatCarePlanTaskCategory(row.category) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="频率" width="100">
                <template #default="{ row }">{{ formatCarePlanFrequency(row.frequency) }}</template>
              </el-table-column>
              <el-table-column label="时段" width="100">
                <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
              </el-table-column>
            </el-table>
            <p v-else class="section-lead muted">（暂无任务）</p>
          </div>
        </el-collapse-item>

        <el-collapse-item v-if="showCheckinsSection" name="checkins">
          <template #title>
            <div class="section-head section-head--checkins">
              <span class="section-icon"><el-icon><CircleCheck /></el-icon></span>
              <span class="section-name">患者打卡</span>
              <span v-if="data.checkins?.length" class="section-badge">
                {{ data.checkins.length }} 条
              </span>
            </div>
          </template>
          <div v-loading="data.checkinsLoading" class="section-body">
            <el-table
              v-if="data.checkins?.length"
              :data="data.checkins"
              size="small"
              stripe
              class="task-table"
              max-height="320"
            >
              <el-table-column prop="checkinDate" label="日期" width="120" />
              <el-table-column prop="taskTitle" label="任务" min-width="140" show-overflow-tooltip />
              <el-table-column label="类别" width="88">
                <template #default="{ row }">
                  {{ formatCarePlanTaskCategory(row.taskCategory) }}
                </template>
              </el-table-column>
              <el-table-column label="时段" width="88">
                <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <el-tag size="small" :type="checkinStatusTagType(row.status)">
                    {{ checkinStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="note" label="备注" min-width="100" show-overflow-tooltip />
            </el-table>
            <p v-else-if="!data.checkinsLoading" class="section-lead muted">该版本暂无打卡记录</p>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 28px;
}

.dialog-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--admin-text, #1f2937);
}

.dialog-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ai-source-tag {
  border: none;
  background: linear-gradient(135deg, #7c3aed 0%, #6366f1 100%);
}

.summary-card {
  padding: 16px 18px;
  margin-bottom: 14px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid var(--admin-border, #e5e7eb);
}

.plan-title {
  margin: 0 0 10px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--admin-text, #111827);
}

.plan-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 10px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--admin-text-secondary, #6b7280);
}

.goal-summary {
  margin: 0;
  padding-top: 10px;
  border-top: 1px dashed var(--admin-border, #e5e7eb);
  font-size: 13px;
  line-height: 1.7;
  color: var(--admin-text-secondary, #4b5563);
  white-space: pre-wrap;
}

.ai-disclaimer-block {
  margin-bottom: 14px;
}

.plan-sections {
  border: none;
}

.plan-sections :deep(.el-collapse-item) {
  margin-bottom: 10px;
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
}

.plan-sections :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 48px;
  padding: 0 14px;
  border: none;
  background: #fafbfc;
  line-height: 1.4;
}

.plan-sections :deep(.el-collapse-item__wrap) {
  border: none;
}

.plan-sections :deep(.el-collapse-item__content) {
  padding: 0;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  font-weight: 600;
  font-size: 14px;
}

.section-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  flex-shrink: 0;
}

.section-head--summary .section-icon {
  background: #ede9fe;
  color: #7c3aed;
}

.section-head--exercise .section-icon {
  background: #e0f2fe;
  color: #0284c7;
}

.section-head--diet .section-icon {
  background: #dcfce7;
  color: #16a34a;
}

.section-head--execution .section-icon {
  background: #ede9fe;
  color: #7c3aed;
}

.section-head--checkins .section-icon {
  background: var(--brand-50);
  color: var(--brand-600);
}

.section-name {
  flex: 1;
}

.section-badge {
  margin-right: 8px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  background: #ede9fe;
  color: #6d28d9;
}

.section-body {
  padding: 14px 16px 16px;
}

.section-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--admin-text, #374151);
}

.section-lead.muted {
  color: var(--admin-text-secondary, #9ca3af);
}

.section-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f0f9ff;
  font-size: 13px;
  line-height: 1.6;
  color: #0369a1;
}

.section-note {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--admin-text-secondary, #6b7280);
}

.info-block {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f9fafb;
  border-left: 3px solid #94a3b8;
}

.info-block--warn {
  background: #fff7ed;
  border-left-color: #f97316;
}

.info-block-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--admin-text-secondary, #64748b);
}

.info-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.65;
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.tag-group-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--admin-text-secondary, #6b7280);
}

.task-table {
  width: 100%;
}
</style>

<style>
.care-plan-preview-dialog .el-dialog__header {
  margin-right: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border, #e5e7eb);
}

.care-plan-preview-dialog .el-dialog__body {
  padding-top: 16px;
  max-height: min(72vh, 720px);
  overflow-y: auto;
}
</style>
