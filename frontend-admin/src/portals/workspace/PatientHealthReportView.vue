<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import {
  formatHealthReportPeriodType,
  formatHealthReportStatus,
  formatReportDate,
  formatReportDateTime,
  healthReportStatusTagType,
} from '../../shared/health-report-labels'
import HealthReportReviewDialog from './HealthReportReviewDialog.vue'

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
const peopleId = () => String(props.peopleId || route.params.peopleId || '')
const loading = ref(false)
const items = ref<HealthReportListItem[]>([])
const detailId = ref<string | null>(null)
const detailMode = ref<'review' | 'readonly'>('readonly')
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

onMounted(() => void load())
watch(
  () => props.peopleId || route.params.peopleId,
  () => {
    void load()
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
        <el-button type="primary" :loading="generating">
          生成报告 <el-icon class="el-icon--right"><arrow-down /></el-icon>
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
        class="sum-card sum-card--warn"
        :class="{ active: statusFilter === 'DRAFT' }"
        @click="statusFilter = 'DRAFT'"
      >
        <div class="sum-num">{{ counts.draft }}</div>
        <div class="sum-label">待审阅</div>
      </button>
      <button
        type="button"
        class="sum-card sum-card--ok"
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
          <el-button type="warning" size="small" @click="openRow(pendingDraft)">去审阅</el-button>
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
            <el-tag size="small" effect="light" :type="healthReportStatusTagType(row.status)">
              {{ formatHealthReportStatus(row.status) }}
            </el-tag>
            <el-tag size="small" effect="plain" type="info">
              {{ formatHealthReportPeriodType(row.periodType) }}
            </el-tag>
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
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'SKIPPED'"
            size="small"
            text
            type="danger"
            @click="voidRow(row)"
          >
            作废
          </el-button>
        </div>
      </article>
    </div>

    <HealthReportReviewDialog
      :report-id="detailId"
      :mode="detailMode"
      @closed="detailId = null"
      @changed="load"
    />
  </div>
</template>

<style scoped>
.panel {
  padding: 8px 0 24px;
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
  max-width: 520px;
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.sum-card {
  appearance: none;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  border-radius: 10px;
  padding: 12px 14px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.sum-card:hover {
  border-color: var(--el-color-primary-light-5);
}
.sum-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
}
.sum-card--warn.active {
  border-color: var(--el-color-warning);
  box-shadow: 0 0 0 1px var(--el-color-warning-light-7);
  background: var(--el-color-warning-light-9);
}
.sum-card--ok.active {
  border-color: var(--el-color-success);
  box-shadow: 0 0 0 1px var(--el-color-success-light-7);
  background: var(--el-color-success-light-9);
}
.sum-num {
  font-size: 22px;
  font-weight: 650;
  line-height: 1.1;
  color: var(--el-text-color-primary);
}
.sum-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pending {
  margin-bottom: 14px;
}
.pending-period {
  font-weight: 400;
  color: var(--el-text-color-regular);
}
.pending-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: 13px;
}

.empty {
  margin-top: 8px;
  padding: 48px 24px;
  text-align: center;
  border: 1px dashed var(--el-border-color);
  border-radius: 12px;
  background: var(--el-fill-color-blank);
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
}
.card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.card:hover {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
}
.card--draft {
  border-color: var(--el-color-warning-light-5);
  background: linear-gradient(90deg, var(--el-color-warning-light-9), #fff 48%);
}
.card-main {
  min-width: 0;
  flex: 1;
}
.card-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.card-title-row h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.card-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
