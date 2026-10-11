<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import {
  dictItemsToMap,
  formatFieldPathLabel,
  formatRevisionBizLabel,
  formatRevisionOperatorLabel,
  formatRevisionValue,
  summarizeRevisionChanges,
  visibleRevisionItemsForDisplay,
  type RevisionOptionMaps,
} from '../../shared/archive-revision-labels'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
}

interface RevisionItem {
  fieldPath: string
  oldValue?: string
  newValue?: string
  oldDisplay?: string
  newDisplay?: string
}

interface RevisionBatch {
  batchId: string
  operatorType: string
  operatorId: string
  operatorName?: string
  operatorRoleCode?: string
  bizType?: string
  bizKey?: string
  gmtCreated?: string
  items?: RevisionItem[]
}

const route = useRoute()
const peopleId = computed(() => String(route.params.peopleId || ''))

const loading = ref(false)
const revisions = ref<RevisionBatch[]>([])

const illnessOptions = ref<DictItem[]>([])
const appetiteOptions = ref<DictItem[]>([])
const dietHabitOptions = ref<DictItem[]>([])
const dietTypeOptions = ref<DictItem[]>([])
const exerciseOptions = ref<DictItem[]>([])
const exerciseIntensityOptions = ref<DictItem[]>([])
const sleepOptions = ref<DictItem[]>([])
const sleepDisorderOptions = ref<DictItem[]>([])
const smokingOptions = ref<DictItem[]>([])
const drinkingOptions = ref<DictItem[]>([])
const drinkingFrequencyOptions = ref<DictItem[]>([])
const diabetesTypeOptions = ref<DictItem[]>([])
const diabetesSymptomOptions = ref<DictItem[]>([])
const diabetesEmergencyOptions = ref<DictItem[]>([])
const diabetesHypoglycemiaReactionOptions = ref<DictItem[]>([])
const hypertensionTypeOptions = ref<DictItem[]>([])
const hypertensionGradeOptions = ref<DictItem[]>([])
const hypertensionCvRiskOptions = ref<DictItem[]>([])
const hypertensionSymptomOptions = ref<DictItem[]>([])
const hypertensionEmergencyOptions = ref<DictItem[]>([])
const doseUnitOptions = ref<DictItem[]>([])
const medicationUsageOptions = ref<DictItem[]>([])
const medicationFrequencyOptions = ref<DictItem[]>([])
const metricTypeOptions = ref<DictItem[]>([])
const examTypeOptions = ref<DictItem[]>([])
const bpContextOptions = ref<DictItem[]>([])
const mealContextOptions = ref<DictItem[]>([])

const expandedBatchId = ref<string | null>(null)

const revisionOptionMaps = computed<RevisionOptionMaps>(() => ({
  presentIllness: dictItemsToMap(illnessOptions.value),
  'diet.appetite': dictItemsToMap(appetiteOptions.value),
  'diet.habit': dictItemsToMap(dietHabitOptions.value),
  'diet.type': dictItemsToMap(dietTypeOptions.value),
  'exercise.frequency': dictItemsToMap(exerciseOptions.value),
  'exercise.intensity': dictItemsToMap(exerciseIntensityOptions.value),
  'sleep.quality': dictItemsToMap(sleepOptions.value),
  'sleep.disorder': dictItemsToMap(sleepDisorderOptions.value),
  'lifestyle.smoking': dictItemsToMap(smokingOptions.value),
  'lifestyle.smoking.status': dictItemsToMap(smokingOptions.value),
  'lifestyle.drinking': dictItemsToMap(drinkingOptions.value),
  'lifestyle.drinking.status': dictItemsToMap(drinkingOptions.value),
  'lifestyle.drinking.frequency': dictItemsToMap(drinkingFrequencyOptions.value),
  diabetesType: dictItemsToMap(diabetesTypeOptions.value),
  'diabetes.symptoms': dictItemsToMap(diabetesSymptomOptions.value),
  'diabetes.emergencyComplications': dictItemsToMap(diabetesEmergencyOptions.value),
  symptoms: dictItemsToMap(diabetesSymptomOptions.value),
  emergencyComplications: dictItemsToMap(diabetesEmergencyOptions.value),
  hypoglycemiaReaction: dictItemsToMap(diabetesHypoglycemiaReactionOptions.value),
  hypertensionType: dictItemsToMap(hypertensionTypeOptions.value),
  hypertensionGrade: dictItemsToMap(hypertensionGradeOptions.value),
  cvRiskStratification: dictItemsToMap(hypertensionCvRiskOptions.value),
  'hypertension.symptoms': dictItemsToMap(hypertensionSymptomOptions.value),
  'hypertension.emergencyComplications': dictItemsToMap(hypertensionEmergencyOptions.value),
  doseUnit: dictItemsToMap(doseUnitOptions.value),
  usageMethod: dictItemsToMap(medicationUsageOptions.value),
  frequency: dictItemsToMap(medicationFrequencyOptions.value),
  metricType: dictItemsToMap(metricTypeOptions.value),
  examType: dictItemsToMap(examTypeOptions.value),
  bpContext: dictItemsToMap(bpContextOptions.value),
  mealContext: dictItemsToMap(mealContextOptions.value),
}))

function visibleRevisionItems(b: RevisionBatch) {
  return visibleRevisionItemsForDisplay(b.items, b.bizType)
}

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function operatorText(b: RevisionBatch) {
  const role = formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode)
  const name = b.operatorName?.trim()
  return name ? `${role} · ${name}` : role
}

function toggleDetail(b: RevisionBatch) {
  expandedBatchId.value = expandedBatchId.value === b.batchId ? null : b.batchId
}

function isExpanded(b: RevisionBatch) {
  return expandedBatchId.value === b.batchId
}

async function loadDicts() {
  const [
    illness,
    appetite,
    dietHabit,
    dietType,
    exercise,
    exerciseIntensity,
    sleepQ,
    sleepDisorder,
    smoking,
    drinking,
    drinkingFrequency,
    dmType,
    dmSymptoms,
    dmEmerg,
    dmHypo,
    htnType,
    htnGrade,
    htnCvRisk,
    htnSymptoms,
    htnEmerg,
    doseUnit,
    usage,
    frequency,
    metricType,
    examType,
    bpContext,
    mealContext,
  ] = await Promise.all([
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=presentIllness'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=appetiteLevel'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=dietHabit'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=dietType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=exerciseFrequency'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=exerciseIntensity'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=sleepQuality'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=sleepDisorder'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=smoking'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=drinking'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=drinkingFrequency'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=diabetesType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=diabetesSymptoms'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesEmergencyComplications',
    ),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesHypoglycemiaReaction',
    ),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionGrade'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionCvRisk'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionSymptoms'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionEmergencyComplications',
    ),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=doseUnit'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationUsage'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationFrequency'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=metricType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=examType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=bpContext'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=mealContext'),
  ])
  illnessOptions.value = illness.data ?? []
  appetiteOptions.value = appetite.data ?? []
  dietHabitOptions.value = dietHabit.data ?? []
  dietTypeOptions.value = dietType.data ?? []
  exerciseOptions.value = exercise.data ?? []
  exerciseIntensityOptions.value = exerciseIntensity.data ?? []
  sleepOptions.value = sleepQ.data ?? []
  sleepDisorderOptions.value = sleepDisorder.data ?? []
  smokingOptions.value = smoking.data ?? []
  drinkingOptions.value = drinking.data ?? []
  drinkingFrequencyOptions.value = drinkingFrequency.data ?? []
  diabetesTypeOptions.value = dmType.data ?? []
  diabetesSymptomOptions.value = dmSymptoms.data ?? []
  diabetesEmergencyOptions.value = dmEmerg.data ?? []
  diabetesHypoglycemiaReactionOptions.value = dmHypo.data ?? []
  hypertensionTypeOptions.value = htnType.data ?? []
  hypertensionGradeOptions.value = htnGrade.data ?? []
  hypertensionCvRiskOptions.value = htnCvRisk.data ?? []
  hypertensionSymptomOptions.value = htnSymptoms.data ?? []
  hypertensionEmergencyOptions.value = htnEmerg.data ?? []
  doseUnitOptions.value = doseUnit.data ?? []
  medicationUsageOptions.value = usage.data ?? []
  medicationFrequencyOptions.value = frequency.data ?? []
  metricTypeOptions.value = metricType.data ?? []
  examTypeOptions.value = examType.data ?? []
  bpContextOptions.value = bpContext.data ?? []
  mealContextOptions.value = mealContext.data ?? []
}

async function loadRevisions() {
  if (!peopleId.value) return
  const res = await api<{ data: RevisionBatch[] }>(
    `/api/b/v1/patients/${peopleId.value}/archive/revisions`,
  )
  revisions.value = res.data ?? []
}

async function bootstrap() {
  loading.value = true
  try {
    await loadDicts()
    await loadRevisions()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载修订历史失败')
  } finally {
    loading.value = false
  }
}

watch(peopleId, () => {
  expandedBatchId.value = null
  bootstrap()
})

onMounted(bootstrap)
</script>

<template>
  <div v-loading="loading" class="revision-page">
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">修订历史</span>
          <span class="section-sub">按时间查看谁做了什么，点详情看字段变更</span>
        </div>
      </template>

      <el-empty v-if="!revisions.length" description="暂无修订记录" />
      <el-timeline v-else class="rev-timeline">
        <el-timeline-item
          v-for="b in revisions"
          :key="b.batchId"
          :timestamp="formatDateTime(b.gmtCreated)"
          placement="top"
        >
          <div class="rev-block">
            <div class="rev-row">
              <div class="rev-main">
                <div class="rev-action">{{ summarizeRevisionChanges(b.items, b.bizType, b.bizKey) }}</div>
                <div class="rev-meta">
                  <span>{{ operatorText(b) }}</span>
                  <span class="rev-dot">·</span>
                  <el-tag size="small" type="info" effect="plain">{{
                    formatRevisionBizLabel(b.bizType, b.bizKey)
                  }}</el-tag>
                </div>
              </div>
              <el-button link type="primary" @click="toggleDetail(b)">
                {{ isExpanded(b) ? '收起' : '详情' }}
              </el-button>
            </div>

            <div v-if="isExpanded(b)" class="rev-detail">
              <div class="detail-section-title">字段变更</div>
              <el-empty
                v-if="!visibleRevisionItems(b).length"
                description="无字段明细"
                :image-size="48"
              />
              <ul v-else class="rev-list">
                <li
                  v-for="item in visibleRevisionItems(b)"
                  :key="item.fieldPath"
                  class="rev-item"
                >
                  <div class="rev-field">{{ formatFieldPathLabel(item.fieldPath, b.bizKey) }}</div>
                  <div class="rev-change">
                    <span class="rev-old">{{
                      formatRevisionValue(
                        item.fieldPath,
                        item.oldValue,
                        item.oldDisplay,
                        revisionOptionMaps,
                        b.bizKey,
                        b.bizType,
                      )
                    }}</span>
                    <span class="rev-arrow">→</span>
                    <span class="rev-new">{{
                      formatRevisionValue(
                        item.fieldPath,
                        item.newValue,
                        item.newDisplay,
                        revisionOptionMaps,
                        b.bizKey,
                        b.bizType,
                      )
                    }}</span>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<style scoped>
.revision-page {
  width: 100%;
}

.section-card :deep(.el-card__header) {
  padding: 14px 20px;
  background: #f8fafc;
}

.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}

.section-sub {
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.rev-timeline {
  padding: 4px 8px 8px;
}

.rev-block {
  padding-bottom: 4px;
}

.rev-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 0 6px;
}

.rev-main {
  min-width: 0;
  flex: 1;
}

.rev-action {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
  line-height: 1.4;
}

.rev-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.rev-dot {
  color: var(--admin-muted);
}

.rev-detail {
  margin-top: 8px;
  margin-bottom: 8px;
  padding: 12px 14px;
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 8px;
  background: #f8fafc;
}

.detail-section-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text-secondary);
}

.rev-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rev-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--admin-border, #e5e7eb);
}

.rev-list li:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.rev-list li:first-child {
  padding-top: 0;
}

.rev-field {
  font-size: 13px;
  color: var(--admin-text);
  font-weight: 600;
  line-height: 1.45;
}

.rev-change {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.rev-old {
  color: var(--admin-muted, #94a3b8);
}

.rev-new {
  color: var(--admin-text);
  font-weight: 500;
}

.rev-arrow {
  margin: 0 8px;
  color: var(--admin-text-secondary);
}
</style>
