<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import {
  formatBaselineMetric,
  formatFollowupContactTarget,
  formatFollowupMethod,
  formatFollowupRecordType,
  formatFollowupStatus,
  formatFollowupType,
  formatLifestyleLevel,
  formatMissedDoseFrequency,
  formatMissedDoseReason,
  formatNextAction,
  formatPlanBlocker,
  formatPlanSatisfaction,
  formatSymptomDisposition,
} from '../../shared/followup-labels'

interface FollowupDetail {
  id: string
  recordType: string
  recordTypeLabel?: string
  status: string
  title?: string
  summary?: string
  content?: Record<string, unknown>
  contactChannel?: string
  completedByName?: string
  completedAt?: string
}

const props = defineProps<{
  followupId: string | null
}>()

const emit = defineEmits<{
  closed: []
}>()

const open = ref(false)
const loading = ref(false)
const detail = ref<FollowupDetail | null>(null)

const detailSection = computed(() => {
  const s = detail.value?.content?.section
  return s && typeof s === 'object' ? (s as Record<string, unknown>) : null
})

type MetricHit = Record<string, unknown>

const detailHits = computed<MetricHit[]>(() => {
  const c = detail.value?.content
  if (!c) return []
  const hits = c.hits
  if (Array.isArray(hits) && hits.length > 0) {
    return hits.filter((h): h is MetricHit => !!h && typeof h === 'object') as MetricHit[]
  }
  if (c.sourceRecordId || c.family) {
    return [c as MetricHit]
  }
  return []
})

const detailDialogWidth = computed(() => {
  if (!detail.value) return '560px'
  if (detail.value.recordType === 'METRIC_REVIEW') return '640px'
  if (contentText('followupType') === 'ROUTINE' || contentText('followupType') === 'ONBOARDING') {
    return '640px'
  }
  return '560px'
})

watch(
  () => props.followupId,
  async (id) => {
    if (!id) {
      open.value = false
      detail.value = null
      return
    }
    loading.value = true
    open.value = true
    try {
      const res = await api<{ data: FollowupDetail }>(`/api/b/v1/followups/${id}`)
      detail.value = res.data
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载随访详情失败')
      open.value = false
      emit('closed')
    } finally {
      loading.value = false
    }
  },
)

function onClosed() {
  detail.value = null
  emit('closed')
}

function formatTime(v?: string) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

function sectionText(key: string) {
  const s = detailSection.value
  if (!s) return ''
  const v = s[key]
  if (Array.isArray(v)) return v.map((x) => String(x)).join('、')
  return v == null ? '' : String(v)
}

function contentText(key: string) {
  const c = detail.value?.content
  if (!c) return ''
  const v = c[key]
  return v == null ? '' : String(v)
}

function hitLabel(hit: MetricHit) {
  if (typeof hit.summary === 'string' && hit.summary.trim()) return hit.summary
  const family = String(hit.family || '')
  if (family === 'BP') {
    return `血压 ${hit.sys ?? '-'}/${hit.dia ?? '-'} ${hit.unit || 'mmHg'}`
  }
  if (family === 'GLUCOSE') {
    return `血糖 ${hit.value ?? '-'} ${hit.unit || 'mmol/L'}`
  }
  if (family === 'HR') {
    return `心率 ${hit.value ?? '-'} ${hit.unit || 'bpm'}`
  }
  if (family === 'BMI') {
    return `BMI ${hit.bmi ?? '-'}`
  }
  return '指标异常'
}

function hitTime(hit: MetricHit) {
  const raw = hit.recordedAt
  if (typeof raw !== 'string' || !raw) return ''
  return raw.replace('T', ' ').slice(0, 16)
}

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlightMetricText(text: string) {
  return escapeHtml(text).replace(
    /(\d+(?:\.\d+)?(?:\s*\/\s*(?:\d+(?:\.\d+)?|-))?)/g,
    '<span class="metric-abnormal">$1</span>',
  )
}

function statusTagType(status?: string) {
  if (status === 'DONE') return 'success'
  if (status === 'OPEN') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'info'
}

function lifestyleTagType(level?: string) {
  if (level === 'GOOD') return 'success'
  if (level === 'FAIR') return 'warning'
  if (level === 'POOR') return 'danger'
  return 'info'
}

function satisfactionTagType(code?: string) {
  if (code === 'SATISFIED') return 'success'
  if (code === 'NEUTRAL') return 'warning'
  if (code === 'UNSATISFIED') return 'danger'
  return 'info'
}

const baselineMetricText = computed(() => {
  const raw = detailSection.value?.baselineMetrics
  if (!Array.isArray(raw) || !raw.length) return ''
  return raw
    .map((m) => {
      const item = (m || {}) as Record<string, unknown>
      const label = formatBaselineMetric(String(item.metricType || ''))
      const unit = item.unit ? String(item.unit) : ''
      return `${label} ${item.value ?? ''}${unit}`.trim()
    })
    .join('、')
})

function dispositionTagType(code?: string) {
  if (code === 'URGENT_REFERRAL') return 'danger'
  if (code === 'VISIT_CLINIC' || code === 'ADJUST_PLAN') return 'warning'
  if (code === 'OBSERVE') return 'success'
  return 'info'
}

function detailTitle() {
  if (!detail.value) return ''
  return (
    formatFollowupType(contentText('followupType')) ||
    detail.value.recordTypeLabel ||
    formatFollowupRecordType(detail.value.recordType) ||
    '随访'
  )
}
</script>

<template>
  <el-dialog
    v-model="open"
    title="随访详情"
    :width="detailDialogWidth"
    destroy-on-close
    align-center
    class="followup-detail-dialog"
    @closed="onClosed"
  >
    <div v-if="loading" class="loading">加载中…</div>
    <template v-else-if="detail">
      <div class="detail-head">
        <div class="detail-title">{{ detailTitle() }}</div>
        <el-tag :type="statusTagType(detail.status)" size="small" effect="light">
          {{ formatFollowupStatus(detail.status) }}
        </el-tag>
      </div>

      <div
        v-if="contentText('contactTarget') || detail.contactChannel || contentText('followupMethod')"
        class="detail-meta"
      >
        <div v-if="contentText('contactTarget')" class="meta-item">
          <span class="meta-label">沟通对象</span>
          <span class="meta-value">{{ formatFollowupContactTarget(contentText('contactTarget')) }}</span>
        </div>
        <div v-if="detail.contactChannel || contentText('followupMethod')" class="meta-item">
          <span class="meta-label">随访方式</span>
          <span class="meta-value">{{
            formatFollowupMethod(detail.contactChannel || contentText('followupMethod'))
          }}</span>
        </div>
      </div>

      <template v-if="contentText('followupType') === 'ONBOARDING' && detailSection">
        <div class="detail-card">
          <div class="detail-card-title">入组结果</div>
          <div class="kv-grid">
            <div class="kv-item">
              <span class="kv-label">档案写回</span>
              <span class="kv-value">{{ detailSection.archiveWritten ? '是' : '否' }}</span>
            </div>
            <div v-if="sectionText('nextAction')" class="kv-item">
              <span class="kv-label">下次动作</span>
              <span class="kv-value">{{ formatNextAction(sectionText('nextAction')) }}</span>
            </div>
            <div v-if="sectionText('diseaseCodes')" class="kv-item kv-item--full">
              <span class="kv-label">已写病种</span>
              <span class="kv-value">{{ sectionText('diseaseCodes') }}</span>
            </div>
          </div>
          <div v-if="baselineMetricText" class="text-block">
            <div class="text-block-label">基线指标</div>
            <div class="text-block-body">{{ baselineMetricText }}</div>
          </div>
          <div v-if="sectionText('archiveChecks')" class="text-block">
            <div class="text-block-label">档案核对（历史）</div>
            <div class="text-block-body">{{ sectionText('archiveChecks') }}</div>
          </div>
          <div v-if="sectionText('lifestyleNote')" class="text-block">
            <div class="text-block-label">生活习惯（历史）</div>
            <div class="text-block-body">{{ sectionText('lifestyleNote') }}</div>
          </div>
        </div>
      </template>
      <template v-else-if="contentText('followupType') === 'ROUTINE' && detailSection">
        <div class="detail-card">
          <div class="detail-card-title">随访评估</div>
          <div class="kv-grid">
            <div v-if="sectionText('lifestyleLevel')" class="kv-item">
              <span class="kv-label">习惯执行</span>
              <el-tag
                size="small"
                effect="plain"
                :type="lifestyleTagType(sectionText('lifestyleLevel'))"
              >
                {{ formatLifestyleLevel(sectionText('lifestyleLevel')) }}
              </el-tag>
            </div>
            <div v-if="sectionText('planSatisfaction')" class="kv-item">
              <span class="kv-label">方案满意度</span>
              <el-tag
                size="small"
                effect="plain"
                :type="satisfactionTagType(sectionText('planSatisfaction'))"
              >
                {{ formatPlanSatisfaction(sectionText('planSatisfaction')) }}
              </el-tag>
            </div>
          </div>
          <div v-if="sectionText('adherenceNote')" class="text-block">
            <div class="text-block-label">打卡概况</div>
            <div class="text-block-body">{{ sectionText('adherenceNote') }}</div>
          </div>
          <div v-if="sectionText('lifestyleNote')" class="text-block">
            <div class="text-block-label">生活习惯说明</div>
            <div class="text-block-body">{{ sectionText('lifestyleNote') }}</div>
          </div>
          <div v-if="sectionText('unsatisfiedReason')" class="text-block">
            <div class="text-block-label">不满意原因</div>
            <div class="text-block-body">{{ sectionText('unsatisfiedReason') }}</div>
          </div>
          <div v-if="sectionText('symptomNote')" class="text-block">
            <div class="text-block-label">症状备注</div>
            <div class="text-block-body">{{ sectionText('symptomNote') }}</div>
          </div>
        </div>
      </template>
      <template v-else-if="contentText('followupType') === 'PLAN_ADHERENCE' && detailSection">
        <div class="detail-card">
          <div class="detail-card-title">方案执行评估</div>
          <div class="kv-grid">
            <div v-if="sectionText('selfRatePct')" class="kv-item">
              <span class="kv-label">自评执行率</span>
              <span>{{ sectionText('selfRatePct') }}%</span>
            </div>
            <div v-if="sectionText('mainBlocker')" class="kv-item">
              <span class="kv-label">主要未完成原因</span>
              <el-tag size="small" effect="plain" type="warning">
                {{ formatPlanBlocker(sectionText('mainBlocker')) }}
              </el-tag>
            </div>
          </div>
          <div v-if="sectionText('blockerNote')" class="text-block">
            <div class="text-block-label">原因说明</div>
            <div class="text-block-body">{{ sectionText('blockerNote') }}</div>
          </div>
          <div v-if="sectionText('planChange')" class="text-block">
            <div class="text-block-label">拟调整项</div>
            <div class="text-block-body">{{ sectionText('planChange') }}</div>
          </div>
        </div>
      </template>
      <template v-else-if="contentText('followupType') === 'MEDICATION' && detailSection">
        <div class="detail-card">
          <div class="detail-card-title">用药评估</div>
          <div class="kv-grid">
            <div v-if="sectionText('missedDoseFrequency')" class="kv-item">
              <span class="kv-label">漏服频次</span>
              <el-tag
                size="small"
                effect="plain"
                :type="sectionText('missedDoseFrequency') === 'NONE' ? 'success' : 'warning'"
              >
                {{ formatMissedDoseFrequency(sectionText('missedDoseFrequency')) }}
              </el-tag>
            </div>
            <div v-if="sectionText('missedDoseReason')" class="kv-item">
              <span class="kv-label">漏服原因</span>
              <span>{{ formatMissedDoseReason(sectionText('missedDoseReason')) }}</span>
            </div>
            <div class="kv-item">
              <span class="kv-label">不良反应</span>
              <el-tag
                size="small"
                effect="plain"
                :type="sectionText('hasAdverseReaction') === 'true' ? 'danger' : 'success'"
              >
                {{ sectionText('hasAdverseReaction') === 'true' ? '有' : '无' }}
              </el-tag>
            </div>
            <div class="kv-item">
              <span class="kv-label">需医生调药</span>
              <el-tag
                size="small"
                effect="plain"
                :type="sectionText('needDoctorAdjust') === 'true' ? 'warning' : 'info'"
              >
                {{ sectionText('needDoctorAdjust') === 'true' ? '是' : '否' }}
              </el-tag>
            </div>
          </div>
          <div v-if="sectionText('adverseNote')" class="text-block">
            <div class="text-block-label">不良反应描述</div>
            <div class="text-block-body">{{ sectionText('adverseNote') }}</div>
          </div>
        </div>
      </template>
      <template v-else-if="contentText('followupType') === 'SYMPTOM_METRIC' && detailSection">
        <div class="detail-card">
          <div class="detail-card-title">症状与指标</div>
          <div class="kv-grid">
            <div v-if="sectionText('disposition')" class="kv-item">
              <span class="kv-label">处置结论</span>
              <el-tag
                size="small"
                effect="plain"
                :type="dispositionTagType(sectionText('disposition'))"
              >
                {{ formatSymptomDisposition(sectionText('disposition')) }}
              </el-tag>
            </div>
          </div>
          <div v-if="sectionText('symptoms')" class="text-block">
            <div class="text-block-label">症状清单</div>
            <div class="text-block-body">{{ sectionText('symptoms') }}</div>
          </div>
          <div v-if="sectionText('symptomNote')" class="text-block">
            <div class="text-block-label">症状说明</div>
            <div class="text-block-body">{{ sectionText('symptomNote') }}</div>
          </div>
          <div v-if="sectionText('retestNote')" class="text-block">
            <div class="text-block-label">复测值</div>
            <div class="text-block-body">{{ sectionText('retestNote') }}</div>
          </div>
        </div>
      </template>
      <template v-else-if="detail.recordType === 'METRIC_REVIEW'">
        <div v-if="detailHits.length" class="hits">
          <div class="hits-head">
            <span class="hits-title">异常指标</span>
            <span class="hits-count">{{ detailHits.length }} 项</span>
          </div>
          <div class="hits-list">
            <div
              v-for="(hit, idx) in detailHits"
              :key="String(hit.sourceRecordId || idx)"
              class="hit-row"
            >
              <span class="hit-main metric-text" v-html="highlightMetricText(hitLabel(hit))" />
              <span v-if="hitTime(hit)" class="hit-time">{{ hitTime(hit) }}</span>
            </div>
          </div>
        </div>
        <p v-else-if="detail.summary" class="detail-fallback">{{ detail.summary }}</p>
      </template>
      <template v-else>
        <div class="detail-card">
          <div class="detail-card-title">随访内容</div>
          <div v-if="contentText('abnormalReason')" class="text-block">
            <div class="text-block-label">异常原因</div>
            <div class="text-block-body">{{ contentText('abnormalReason') }}</div>
          </div>
          <div v-if="sectionText('content') || contentText('content')" class="text-block">
            <div class="text-block-label">沟通要点</div>
            <div class="text-block-body">{{ sectionText('content') || contentText('content') }}</div>
          </div>
          <div v-if="contentText('note')" class="text-block">
            <div class="text-block-label">说明</div>
            <div class="text-block-body">{{ contentText('note') }}</div>
          </div>
        </div>
      </template>

      <div class="detail-footer">
        <div v-if="contentText('abnormalReason') && detail.recordType === 'METRIC_REVIEW'" class="footer-row">
          <span class="footer-label">异常原因</span>
          <span class="footer-value">{{ contentText('abnormalReason') }}</span>
        </div>
        <div v-if="contentText('guidance')" class="footer-row">
          <span class="footer-label">指导建议</span>
          <span class="footer-value footer-value--multiline">{{ contentText('guidance') }}</span>
        </div>
        <div v-if="detail.content?.suggestPlanAdjust" class="footer-row">
          <span class="footer-label">调整方案</span>
          <span class="footer-value">已同步「制定方案」待办</span>
        </div>
        <div v-if="detail.completedByName" class="footer-row">
          <span class="footer-label">处理人</span>
          <span class="footer-value">{{ detail.completedByName }}</span>
        </div>
        <div v-if="detail.completedAt" class="footer-row">
          <span class="footer-label">完成时间</span>
          <span class="footer-value">{{ formatTime(detail.completedAt) }}</span>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.loading {
  padding: 24px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.detail-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.3;
}
.detail-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.meta-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.meta-value {
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.detail-fallback {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.detail-card {
  margin: 0 0 16px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.detail-card-title {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.kv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
  margin-bottom: 4px;
}
.kv-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.kv-item--full {
  grid-column: 1 / -1;
}
.kv-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kv-value {
  font-size: 14px;
  color: var(--el-text-color-primary);
  word-break: break-word;
}
.text-block {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-extra-light);
}
.text-block-label {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.text-block-body {
  font-size: 14px;
  line-height: 1.65;
  color: var(--el-text-color-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.hits {
  margin: 0 0 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid var(--el-color-danger-light-3);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  overflow: hidden;
}
.hits-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.hits-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.hits-count {
  font-size: 12px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 4px;
  padding: 0 6px;
  line-height: 20px;
}
.hits-list {
  max-height: 240px;
  overflow-y: auto;
  padding: 4px 0;
}
.hit-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.45;
}
.hit-row + .hit-row {
  border-top: 1px solid var(--el-border-color-extra-light);
}
.hit-main {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-primary);
}
.hit-time {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.metric-text :deep(.metric-abnormal) {
  color: var(--el-color-danger);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.detail-footer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 4px;
  border-top: 1px solid var(--el-border-color-extra-light);
}
.footer-row {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
  align-items: start;
  font-size: 13px;
  line-height: 1.5;
}
.footer-label {
  color: var(--el-text-color-secondary);
}
.footer-value {
  color: var(--el-text-color-primary);
  word-break: break-word;
}
.footer-value--multiline {
  white-space: pre-wrap;
  line-height: 1.6;
}
@media (max-width: 640px) {
  .detail-meta {
    grid-template-columns: 1fr;
  }
  .kv-grid {
    grid-template-columns: 1fr;
  }
  .footer-row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
