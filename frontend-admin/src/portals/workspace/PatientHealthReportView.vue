<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { api } from '../../shared/http'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import {
  formatHealthReportPeriodType,
  formatHealthReportStatus,
  formatReportDate,
  formatReportDateTime,
} from '../../shared/health-report-labels'
import HealthReportReviewDialog, { type ReportAiPrefill } from './HealthReportReviewDialog.vue'

interface HealthReportListItem {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  status?: string
  staffComment?: string
  publishedAt?: string
  publishedByName?: string
  generatedBy?: string
  gmtCreated?: string
}

type StatusFilter = 'ALL' | 'DRAFT' | 'PUBLISHED' | 'SKIPPED'

const route = useRoute()
const props = defineProps<{
  /** 驾驶舱抽屉等场景传入；不传则走路由 params */
  peopleId?: string
}>()
const emit = defineEmits<{
  published: []
}>()
const peopleId = () => String(props.peopleId || route.params.peopleId || '')
const loading = ref(false)
const items = ref<HealthReportListItem[]>([])
const detailId = ref<string | null>(null)
const detailMode = ref<'review' | 'readonly'>('readonly')
const aiPrefill = ref<ReportAiPrefill | null>(null)
const generating = ref(false)
const statusFilter = ref<StatusFilter>('ALL')

const counts = computed(() => {
  const all = items.value
  return {
    all: all.length,
    draft: all.filter((r) => r.status === 'DRAFT').length,
    published: all.filter((r) => r.status === 'PUBLISHED').length,
    skipped: all.filter((r) => r.status === 'SKIPPED').length,
  }
})

const filtered = computed(() => {
  if (statusFilter.value === 'ALL') return items.value
  return items.value.filter((r) => r.status === statusFilter.value)
})

const pendingDraft = computed(() => items.value.find((r) => r.status === 'DRAFT') || null)

async function load() {
  if (!peopleId()) return
  loading.value = true
  try {
    const res = await api<{ data: HealthReportListItem[] }>(
      `/api/b/v1/patients/${peopleId()}/health-reports?limit=50`,
    )
    items.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function applyAgentDraft() {
  const pid = peopleId()
  if (!pid) return
  const draft = takeAgentDraft(pid, 'reports')
  if (!draft?.openReview || !draft.reportId) return
  aiPrefill.value = {
    staffComment: draft.staffComment,
    nextFocus: draft.nextFocus,
    quarterAdvice: draft.quarterAdvice,
    note: '已从灵犀填入点评草稿，请核对后发布',
  }
  detailMode.value = 'review'
  detailId.value = draft.reportId
}

async function generate(periodType: 'WEEK' | 'MONTH' | 'QUARTER') {
  generating.value = true
  try {
    const res = await api<{ data: { id: string; status?: string } }>(
      `/api/b/v1/patients/${peopleId()}/health-reports/generate`,
      {
        method: 'POST',
        body: JSON.stringify({ periodType }),
      },
    )
    ElMessage.success('已生成/刷新草稿')
    await load()
    if (res.data?.id) {
      detailMode.value = res.data.status === 'DRAFT' ? 'review' : 'readonly'
      detailId.value = res.data.id
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    generating.value = false
  }
}

function openRow(row: HealthReportListItem) {
  aiPrefill.value = null
  detailMode.value = row.status === 'DRAFT' ? 'review' : 'readonly'
  detailId.value = row.id
}

async function voidRow(row: HealthReportListItem) {
  try {
    await ElMessageBox.confirm('作废后可重新生成本周期报告，确认？', '作废报告', { type: 'warning' })
    await api(`/api/b/v1/health-reports/${row.id}/void`, { method: 'POST' })
    ElMessage.success('已作废')
    await load()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '作废失败')
  }
}

function generatedByLabel(v?: string) {
  if (v === 'JOB') return '定时任务'
  if (v === 'MANUAL') return '手工生成'
  return ''
}

function statusTone(status?: string) {
  if (status === 'DRAFT') return 'draft'
  if (status === 'PUBLISHED') return 'published'
  if (status === 'SKIPPED') return 'skipped'
  return 'default'
}

function onDialogClosed() {
  detailId.value = null
  aiPrefill.value = null
}

onMounted(async () => {
  await load()
  applyAgentDraft()
})
watch(
  () => props.peopleId || route.params.peopleId,
  async () => {
    detailId.value = null
    aiPrefill.value = null
    await load()
    applyAgentDraft()
  },
)
</script>

<template>
  <div class="panel" v-loading="loading">
    <div class="head">
      <div>
        <h3>管理报告</h3>
        <p class="hint">
          周报按入组日滚动（错开周一高峰）；月报/三月报按自然月季。审阅发布后患者端可见。
        </p>
      </div>
      <el-dropdown :disabled="generating" @command="generate">
        <el-button type="primary" class="gen-btn" :loading="generating">
          生成报告
          <el-icon class="gen-btn-ico"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="WEEK">最近一周报（入组对齐）</el-dropdown-item>
            <el-dropdown-item command="MONTH">上月月报</el-dropdown-item>
            <el-dropdown-item command="QUARTER">上季三月报</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="summary">
      <button
        type="button"
        class="sum-card"
        :class="{ active: statusFilter === 'ALL' }"
        @click="statusFilter = 'ALL'"
      >
        <div class="sum-num">{{ counts.all }}</div>
        <div class="sum-label">全部</div>
      </button>
      <button
        type="button"
        class="sum-card"
        :class="{ active: statusFilter === 'DRAFT' }"
        @click="statusFilter = 'DRAFT'"
      >
        <div class="sum-num">{{ counts.draft }}</div>
        <div class="sum-label">待审阅</div>
      </button>
      <button
        type="button"
        class="sum-card"
        :class="{ active: statusFilter === 'PUBLISHED' }"
        @click="statusFilter = 'PUBLISHED'"
      >
        <div class="sum-num">{{ counts.published }}</div>
        <div class="sum-label">已发布</div>
      </button>
      <button
        type="button"
        class="sum-card"
        :class="{ active: statusFilter === 'SKIPPED' }"
        @click="statusFilter = 'SKIPPED'"
      >
        <div class="sum-num">{{ counts.skipped }}</div>
        <div class="sum-label">已跳过</div>
      </button>
    </div>

    <el-alert
      v-if="pendingDraft"
      class="pending"
      type="warning"
      show-icon
      :closable="false"
    >
      <template #title>
        有待审阅报告：{{ pendingDraft.title || '管理报告' }}
        <span class="pending-period">
          （{{ formatReportDate(pendingDraft.periodStart) }} ~
          {{ formatReportDate(pendingDraft.periodEnd) }}）
        </span>
      </template>
      <template #default>
        <div class="pending-actions">
          <span>可在工作台领取任务后发布，或直接在此审阅。</span>
          <el-button
            type="primary"
            size="small"
            class="pending-go"
            @click="openRow(pendingDraft)"
          >
            去审阅
          </el-button>
        </div>
      </template>
    </el-alert>

    <div v-if="!loading && !filtered.length" class="empty">
      <div class="empty-title">
        {{ statusFilter === 'ALL' ? '暂无管理报告' : `暂无${formatHealthReportStatus(statusFilter)}报告` }}
      </div>
      <p class="empty-desc">
        周期内有方案应打或用药应服日，且患者已配置主管机构后，可生成周报草稿。
      </p>
      <el-button v-if="statusFilter === 'ALL'" type="primary" plain :loading="generating" @click="generate('WEEK')">
        生成最近一周报
      </el-button>
      <el-button v-else plain @click="statusFilter = 'ALL'">查看全部</el-button>
    </div>

    <div v-else class="list">
      <article
        v-for="row in filtered"
        :key="row.id"
        class="card"
        :class="{ 'card--draft': row.status === 'DRAFT' }"
        @click="openRow(row)"
      >
        <div class="card-main">
          <div class="card-title-row">
            <h4>{{ row.title || '管理报告' }}</h4>
            <span class="rp-tag" :data-tone="statusTone(row.status)">
              {{ formatHealthReportStatus(row.status) }}
            </span>
            <span class="rp-tag" data-tone="period">
              {{ formatHealthReportPeriodType(row.periodType) }}
            </span>
          </div>
          <div class="card-meta">
            <span>
              周期 {{ formatReportDate(row.periodStart) }} ~ {{ formatReportDate(row.periodEnd) }}
            </span>
            <span v-if="row.status === 'PUBLISHED'">
              发布于 {{ formatReportDateTime(row.publishedAt) }}
              <template v-if="row.publishedByName"> · {{ row.publishedByName }}</template>
            </span>
            <span v-else-if="row.gmtCreated">创建于 {{ formatReportDateTime(row.gmtCreated) }}</span>
            <span v-if="generatedByLabel(row.generatedBy)">{{ generatedByLabel(row.generatedBy) }}</span>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <el-button
            v-if="row.status === 'DRAFT'"
            type="primary"
            size="small"
            @click="openRow(row)"
          >
            审阅发布
          </el-button>
          <el-button v-else size="small" @click="openRow(row)">查看详情</el-button>
          <button
            v-if="row.status === 'DRAFT' || row.status === 'SKIPPED'"
            type="button"
            class="void-link"
            @click="voidRow(row)"
          >
            作废
          </button>
        </div>
      </article>
    </div>

    <HealthReportReviewDialog
      :report-id="detailId"
      :mode="detailMode"
      :ai-prefill="aiPrefill"
      @closed="onDialogClosed"
      @changed="
        () => {
          void load()
          notifyCockpitTasksPossiblyChanged(peopleId())
        }
      "
      @published="emit('published')"
    />
  </div>
</template>

<style scoped>
.panel {
  box-sizing: border-box;
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 4px 0 16px;
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}
.head h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  max-width: 640px;
}
.gen-btn {
  height: auto !important;
  padding: 8px 16px !important;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}
.gen-btn-ico {
  margin: 0;
  font-size: 12px;
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  width: 100%;
  margin-bottom: 14px;
}
.sum-card {
  appearance: none;
  width: 100%;
  min-height: 76px;
  border: 1px solid #e5e7eb;
  background: #fff;
  border-radius: 8px;
  padding: 16px 24px;
  text-align: left;
  cursor: pointer;
  box-shadow: none;
  transition: border-color 0.15s, background 0.15s;
}
.sum-card:hover {
  border-color: #cbd5e1;
}
.sum-card.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: none;
}
.sum-num {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  color: #111827;
}
.sum-label {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
}

.pending {
  width: 100%;
  margin-bottom: 14px;
  padding: 12px 24px !important;
  background: rgba(254, 243, 199, 0.22) !important;
  border: 1px solid #e5e7eb !important;
  border-radius: 8px;
  box-shadow: none;
}
.pending :deep(.el-alert__title) {
  color: #b45309;
  font-weight: 600;
}
.pending-period {
  font-weight: 400;
  color: #92400e;
}
.pending-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: 13px;
  color: #78716c;
}
.pending-go {
  margin-left: 24px;
  height: 28px !important;
  padding: 0 12px !important;
  font-size: 12px;
}

.empty {
  width: 100%;
  margin-top: 8px;
  padding: 48px 24px;
  text-align: center;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: none;
}
.empty-title {
  font-size: 15px;
  font-weight: 600;
}
.empty-desc {
  margin: 8px auto 16px;
  max-width: 420px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 24px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: none;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card:hover {
  border-color: #cbd5e1;
}
.card--draft {
  border-color: #e5e7eb;
  background: #fff;
  box-shadow: inset 3px 0 0 #f59e0b;
}
.card-main {
  min-width: 0;
  flex: 1;
}
.card-title-row {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.card-title-row h4 {
  margin: 0;
  flex: 0 1 auto;
  min-width: 0;
  max-width: min(560px, 62%);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rp-tag {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  border: 0;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  background: #f3f4f6;
  color: #4b5563;
}
.rp-tag[data-tone='draft'] {
  background: #fff7ed;
  color: #c2410c;
}
.rp-tag[data-tone='published'] {
  background: #ecfdf5;
  color: #059669;
}
.rp-tag[data-tone='skipped'] {
  background: #f3f4f6;
  color: #6b7280;
}
.rp-tag[data-tone='period'] {
  background: #f3f4f6;
  color: #4b5563;
}
.card-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  font-size: 12px;
  color: #9ca3af;
}
.card-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.card-actions :deep(.el-button) {
  height: 32px;
  margin: 0;
  padding: 0 14px;
}
.void-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  margin: 0;
  padding: 0 4px;
  border: 0;
  background: transparent;
  font-size: 13px;
  color: #9ca3af;
  cursor: pointer;
  line-height: 1;
  transition: color 0.15s;
}
.void-link:hover {
  color: #ef4444;
}

@media (max-width: 900px) {
  .summary {
    grid-template-columns: repeat(2, 1fr);
  }
  .card {
    flex-direction: column;
    align-items: stretch;
  }
  .card-actions {
    justify-content: flex-end;
  }
}
</style>
