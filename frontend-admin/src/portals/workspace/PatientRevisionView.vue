<script setup lang="ts">
import { palette } from '../../shared/palette'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
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
  quote?: string
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

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const revisions = ref<RevisionBatch[]>([])
const keyword = ref('')
const searching = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

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
const obesityTypeOptions = ref<DictItem[]>([])
const obesityClassOptions = ref<DictItem[]>([])
const obesityCentralOptions = ref<DictItem[]>([])
const obesityComplicationOptions = ref<DictItem[]>([])
const obesityPriorInterventionOptions = ref<DictItem[]>([])
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
  obesityType: dictItemsToMap(obesityTypeOptions.value),
  obesityClass: dictItemsToMap(obesityClassOptions.value),
  centralObesity: dictItemsToMap(obesityCentralOptions.value),
  'obesity.complications': dictItemsToMap(obesityComplicationOptions.value),
  complications: dictItemsToMap(obesityComplicationOptions.value),
  'obesity.priorInterventions': dictItemsToMap(obesityPriorInterventionOptions.value),
  priorInterventions: dictItemsToMap(obesityPriorInterventionOptions.value),
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

function operatorName(b: RevisionBatch) {
  const name = b.operatorName?.trim()
  if (name) return name
  return formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode) || '未知'
}

function operatorRole(b: RevisionBatch) {
  return formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode)
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
    obType,
    obClass,
    obCentral,
    obCompl,
    obPrior,
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
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityClass'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityCentral'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityComplications'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=obesityPriorInterventions',
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
  obesityTypeOptions.value = obType.data ?? []
  obesityClassOptions.value = obClass.data ?? []
  obesityCentralOptions.value = obCentral.data ?? []
  obesityComplicationOptions.value = obCompl.data ?? []
  obesityPriorInterventionOptions.value = obPrior.data ?? []
  doseUnitOptions.value = doseUnit.data ?? []
  medicationUsageOptions.value = usage.data ?? []
  medicationFrequencyOptions.value = frequency.data ?? []
  metricTypeOptions.value = metricType.data ?? []
  examTypeOptions.value = examType.data ?? []
  bpContextOptions.value = bpContext.data ?? []
  mealContextOptions.value = mealContext.data ?? []
}

async function loadRevisions(opts?: { quiet?: boolean }) {
  if (!peopleId.value) return
  const quiet = !!opts?.quiet
  if (quiet) searching.value = true
  try {
    const qs = new URLSearchParams()
    const q = keyword.value.trim()
    if (q) qs.set('keyword', q)
    const suffix = qs.toString() ? `?${qs.toString()}` : ''
    const res = await api<{ data: RevisionBatch[] }>(
      `/api/b/v1/patients/${peopleId.value}/archive/revisions${suffix}`,
    )
    revisions.value = res.data ?? []
  } finally {
    if (quiet) searching.value = false
  }
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

function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(async () => {
    expandedBatchId.value = null
    try {
      await loadRevisions({ quiet: true })
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '搜索失败')
    }
  }, 300)
}

watch(peopleId, () => {
  expandedBatchId.value = null
  keyword.value = ''
  bootstrap()
})

onMounted(bootstrap)
onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <div v-loading="loading" class="revision-page">
    <div class="page-head">
      <div class="page-head-left">
        <h3 class="section-title">修订历史</h3>
        <span class="section-sub">按时间查看谁做了什么，点击横条展开字段变更</span>
      </div>
      <el-input
        v-model="keyword"
        class="rev-search"
        clearable
        placeholder="搜索操作人、模块、变更内容"
        :prefix-icon="Search"
        @input="scheduleSearch"
        @clear="scheduleSearch"
      />
    </div>

    <el-empty
      v-if="!revisions.length"
      :description="keyword.trim() ? '未找到相关修订记录' : '暂无修订记录'"
    />
    <el-timeline v-else v-loading="searching" class="rev-timeline">
      <el-timeline-item
        v-for="(b, idx) in revisions"
        :key="b.batchId"
        :timestamp="formatDateTime(b.gmtCreated)"
        placement="top"
        :color="idx === 0 ? 'var(--el-color-primary)' : palette.gray300"
      >
        <div
          class="rev-card"
          :class="{ 'is-expanded': isExpanded(b) }"
          role="button"
          tabindex="0"
          :aria-expanded="isExpanded(b)"
          @click="toggleDetail(b)"
          @keydown.enter.prevent="toggleDetail(b)"
          @keydown.space.prevent="toggleDetail(b)"
        >
          <div class="rev-card-main">
            <div class="rev-action">{{ summarizeRevisionChanges(b.items, b.bizType, b.bizKey) }}</div>
            <div class="rev-meta">
              <span class="rev-operator" :title="operatorRole(b)">
                <span class="op-ico" aria-hidden="true"></span>
                操作人：{{ operatorName(b) }}
              </span>
              <span class="rev-biz-tag">{{ formatRevisionBizLabel(b.bizType, b.bizKey) }}</span>
            </div>
          </div>
          <span class="rev-detail-link">
            <span class="rev-detail-chevron" aria-hidden="true">{{ isExpanded(b) ? '‹' : '›' }}</span>
            {{ isExpanded(b) ? '收起' : '查看详情' }}
          </span>
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
              <p v-if="item.quote" class="rev-quote">原句：{{ item.quote }}</p>
            </li>
          </ul>
        </div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.revision-page {
  width: 100%;
  max-width: none;
  padding: 4px 0 16px;
  background: transparent;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-bottom: 14px;
}

.page-head-left {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px 12px;
  min-width: 0;
}

.section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-950);
  line-height: 1.3;
}

.section-sub {
  font-size: 12px;
  color: var(--gray-400);
  line-height: 1.4;
}

.rev-search {
  width: 280px;
  max-width: 100%;
  flex: 0 0 auto;
}

.rev-timeline {
  width: 100%;
  max-width: none;
  padding: 2px 0 8px;
}

.rev-timeline :deep(.el-timeline-item) {
  padding-bottom: 16px;
}

.rev-timeline :deep(.el-timeline-item__wrapper) {
  width: 100%;
  box-sizing: border-box;
  padding-left: 24px;
  top: 0;
}

.rev-timeline :deep(.el-timeline-item__tail) {
  /* 从主标题附近的圆点向下连续延伸 */
  top: 40px;
  border-left: 0 dashed var(--gray-200);
}

.rev-timeline :deep(.el-timeline-item__node) {
  width: 11px;
  height: 11px;
  left: 0;
  /* 越过顶部时间戳，对齐卡片内主标题行 */
  top: 36px;
  border: 2px solid var(--white);
  box-shadow: 0 0 0 1px var(--gray-300);
  box-sizing: content-box;
}

.rev-timeline :deep(.el-timeline-item:first-child .el-timeline-item__node) {
  background-color: var(--el-color-primary) !important;
  box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--el-color-primary) 35%, var(--white));
}

.rev-timeline :deep(.el-timeline-item__timestamp) {
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-750);
  padding-top: 0;
}

.rev-timeline :deep(.el-timeline-item__timestamp.is-top) {
  margin-bottom: 8px;
}

.rev-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  max-width: none;
  box-sizing: border-box;
  padding: 12px 24px 12px 16px;
  background: var(--white);
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  box-shadow: none;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

.rev-card:hover {
  border-color: var(--brand-200);
  background: var(--ink-25);
}

.rev-card:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--el-color-primary) 45%, var(--white));
  outline-offset: 2px;
}

.rev-card.is-expanded {
  border-color: var(--blue-300);
  background: var(--ink-25);
}

.rev-card-main {
  min-width: 0;
  flex: 1 1 auto;
}

.rev-action {
  font-size: 15px;
  font-weight: 700;
  color: var(--gray-800);
  line-height: 1.4;
}

.rev-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}

.rev-operator {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--gray-400);
  line-height: 1.4;
}

.op-ico {
  font-size: 12px;
  line-height: 1;
  opacity: 0.85;
}

.rev-biz-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 0;
  border-radius: 999px;
  background: var(--blue-50);
  color: var(--blue-500);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
}

.rev-detail-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--gray-400);
  white-space: nowrap;
  line-height: 1.4;
  pointer-events: none;
  transition: color 0.15s;
}

.rev-detail-chevron {
  font-size: 15px;
  line-height: 1;
  transform: translateY(-0.5px);
}

.rev-card:hover .rev-detail-link,
.rev-card.is-expanded .rev-detail-link {
  color: var(--el-color-primary);
}

.rev-detail {
  margin-top: 8px;
  margin-bottom: 4px;
  max-width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  background: var(--white);
}

.detail-section-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-500);
}

.rev-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rev-item {
  padding: 10px 0;
  border-bottom: 0 solid var(--gray-200);
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
  color: var(--gray-800);
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
  color: var(--gray-400);
}

.rev-new {
  color: var(--gray-800);
  font-weight: 500;
}

.rev-arrow {
  margin: 0 8px;
  color: var(--gray-400);
}

.rev-quote {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--gray-400);
  line-height: 1.45;
}
</style>
