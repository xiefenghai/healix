<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import { loadDictOptions, toLabelMap } from '../shared/dict'
import MedicationFeedbackSheet from '../shared/MedicationFeedbackSheet.vue'
import MedicationFormSheet from '../shared/MedicationFormSheet.vue'
import {
  formatDose,
  MED_DOSE_UNIT_LABELS,
  MED_FEEDBACK_KIND_LABELS,
  MED_FREQUENCY_LABELS,
  MED_SEVERITY_LABELS,
  MED_STOP_REASONS,
  MED_TIME_SLOT_LABELS,
  MED_USAGE_LABELS,
  medLabel,
  medSourceLabel,
  type FeedbackMode,
  type MedicationFeedback,
  type MedicationView,
} from '../shared/medication-labels'

const route = useRoute()
const router = useRouter()
const id = String(route.params.id || '')

const loading = ref(true)
const med = ref<MedicationView | null>(null)
const feedback = ref<MedicationFeedback[]>([])
const showEdit = ref(false)
const showFeedback = ref(false)
const feedbackMode = ref<FeedbackMode>('ADVERSE')
const maps = ref({
  usage: {} as Record<string, string>,
  frequency: {} as Record<string, string>,
  doseUnit: {} as Record<string, string>,
})

const active = computed(() => med.value?.status !== 'STOPPED')

const rows = computed(() => {
  const m = med.value
  if (!m) return []
  const label = (map: Record<string, string>, fallback: Record<string, string>, code?: string | null) =>
    (code && map[code]) || medLabel(fallback, code) || '—'
  const out: [string, string][] = [
    ['用法', label(maps.value.usage, MED_USAGE_LABELS, m.usageMethod)],
    ['频次', label(maps.value.frequency, MED_FREQUENCY_LABELS, m.frequency)],
    ['单次剂量', formatDose(m.doseAmount, m.doseUnit, { ...MED_DOSE_UNIT_LABELS, ...maps.value.doseUnit }) || '—'],
    [
      '服药时段',
      m.prn
        ? '需要时服用'
        : (m.suggestedSlots || []).map((s) => medLabel(MED_TIME_SLOT_LABELS, s)).join('、') +
          (m.slotsCustom ? '' : '（自动）'),
    ],
    ['服药时机', m.timingNote || '—'],
    ['服药时间', `${m.startDate || '—'} ~ ${m.stopDate || (active.value ? '长期' : '已停用')}`],
  ]
  if (m.stopReason) out.push(['停药原因', MED_STOP_REASONS.find((r) => r.code === m.stopReason)?.label || m.stopReason])
  if (m.remark) out.push(['备注', m.remark])
  return out
})

onMounted(load)

async function load() {
  try {
    const [m, f, usage, frequency, doseUnit] = await Promise.all([
      api<{ data: MedicationView }>(`/api/c/v1/me/medications/${id}`),
      api<{ data: MedicationFeedback[] }>(`/api/c/v1/me/medications/${id}/feedback`).catch(() => ({ data: [] })),
      loadDictOptions('medicationUsage').catch(() => []),
      loadDictOptions('medicationFrequency').catch(() => []),
      loadDictOptions('doseUnit').catch(() => []),
    ])
    med.value = m.data
    feedback.value = f.data || []
    maps.value = { usage: toLabelMap(usage), frequency: toLabelMap(frequency), doseUnit: toLabelMap(doseUnit) }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openFeedback(mode: FeedbackMode) {
  feedbackMode.value = mode
  showFeedback.value = true
}

async function remove() {
  try {
    await showConfirmDialog({ title: '删除这个药？', message: '服药记录会一起隐藏。', confirmButtonText: '删除' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/medications/${id}`, { method: 'DELETE' })
    showToast({ type: 'success', message: '已删除' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

function feedbackTitle(f: MedicationFeedback) {
  return f.kindLabel || MED_FEEDBACK_KIND_LABELS[f.kind] || f.kind
}

function feedbackBody(f: MedicationFeedback) {
  const parts: string[] = []
  if (f.symptomLabels?.length) parts.push(f.symptomLabels.join('、'))
  if (f.severity) parts.push(MED_SEVERITY_LABELS[f.severity] || f.severity)
  if (f.content) parts.push(f.content)
  return parts.join(' · ')
}

function fmtTime(dt?: string | null) {
  return dt ? dt.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="用药详情" left-arrow fixed placeholder :border="false" @click-left="router.back()">
      <template v-if="med" #right>
        <span class="nav-link" @click="showEdit = true">编辑</span>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else-if="med">
      <section class="card head">
        <div class="title-row">
          <strong>{{ med.drugName }}</strong>
          <span class="badge" :class="{ warn: med.source === 'PATIENT' && !med.verified }">{{ medSourceLabel(med) }}</span>
          <span v-if="!active" class="badge muted">已停用</span>
        </div>
        <p v-if="med.allergyConflict" class="conflict">
          <van-icon name="warning-o" /> 档案记录你对【{{ med.allergyConflict }}】过敏，已通知健管师，建议先咨询再服用。
        </p>
        <p v-if="med.hasAdverseReaction" class="adverse">你曾上报过这个药的不适</p>
        <dl class="rows">
          <template v-for="[k, v] in rows" :key="k">
            <dt>{{ k }}</dt>
            <dd>{{ v }}</dd>
          </template>
        </dl>
        <p v-if="!med.patientEditable" class="hint">
          医护开立的药，药名、剂量、频次由健管师调整；你可以改服药时段、时机和备注。
        </p>
      </section>

      <section v-if="active" class="actions">
        <van-button round plain type="primary" @click="openFeedback('ADVERSE')">报告不适</van-button>
        <van-button v-if="!med.patientEditable" round plain type="primary" @click="openFeedback('ADJUST')">
          申请调整
        </van-button>
        <van-button round plain type="danger" @click="openFeedback('STOP')">我已停药</van-button>
      </section>

      <section class="card">
        <p class="sec-title">反馈与健管师回复</p>
        <van-empty v-if="!feedback.length" image-size="56" description="还没有反馈" />
        <div v-for="f in feedback" :key="f.id" class="fb">
          <div class="fb-head">
            <strong>{{ feedbackTitle(f) }}</strong>
            <span class="fb-status" :class="{ done: f.status === 'HANDLED' }">
              {{ f.status === 'HANDLED' ? '已回复' : '待处理' }}
            </span>
            <span class="fb-time">{{ fmtTime(f.gmtCreated) }}</span>
          </div>
          <p v-if="feedbackBody(f)" class="fb-body">{{ feedbackBody(f) }}</p>
          <p v-if="f.reply" class="fb-reply">
            <b>{{ f.handledByName || '健管师' }}：</b>{{ f.reply }}
          </p>
        </div>
      </section>

      <div v-if="med.patientEditable" class="danger-zone">
        <button type="button" @click="remove">删除这个药</button>
      </div>
    </template>

    <MedicationFormSheet v-model:show="showEdit" :med="med" @saved="load" />
    <MedicationFeedbackSheet v-model:show="showFeedback" :mode="feedbackMode" :med="med" @done="load" />
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
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.nav-link {
  color: var(--hx-teal);
  font-size: 14px;
  font-weight: 600;
}
.loading {
  padding: 48px 0;
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.title-row strong {
  font-size: 18px;
  font-weight: 900;
}
.badge {
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.badge.warn {
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft, var(--hx-warn-bg));
}
.badge.muted {
  color: var(--hx-muted);
  background: var(--hx-n-100);
}
.conflict {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: var(--hx-r-sm);
  background: var(--hx-danger-bg);
  color: var(--hx-danger-text);
  font-size: 13px;
  line-height: 1.6;
}
.adverse {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-danger-text);
}
.rows {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px 12px;
  margin: 14px 0 0;
  font-size: 14px;
}
.rows dt {
  color: var(--hx-muted);
}
.rows dd {
  margin: 0;
  color: var(--hx-text);
}
.hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.6;
}
.actions {
  display: flex;
  gap: 10px;
  margin: 12px 16px 0;
}
.actions :deep(.van-button) {
  flex: 1;
}
.sec-title {
  margin: 0 0 8px;
  font-weight: 800;
}
.fb {
  padding: 10px 0;
  border-top: 1px solid var(--hx-n-100);
}
.fb:first-of-type {
  border-top: 0;
}
.fb-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.fb-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft, var(--hx-warn-bg));
}
.fb-status.done {
  color: var(--hx-teal-700);
  background: var(--hx-teal-light);
}
.fb-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--hx-muted);
}
.fb-body {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--hx-text);
}
.fb-reply {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-teal-50);
  font-size: 13px;
  line-height: 1.6;
}
.danger-zone {
  margin: 20px 16px 0;
  text-align: center;
}
.danger-zone button {
  border: 0;
  background: transparent;
  color: var(--hx-danger);
  font-size: 14px;
}
</style>
