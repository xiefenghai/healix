<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import AssessmentResultDetail from '../../shared/AssessmentResultDetail.vue'
import AssessmentTrendChart from '../../shared/AssessmentTrendChart.vue'
import AssessmentQuestionnaireDrawer from '../../shared/AssessmentQuestionnaireDrawer.vue'
import AssessmentRulesDrawer from '../../shared/AssessmentRulesDrawer.vue'
import AssessmentSendQuestionnaireDialog from '../../shared/AssessmentSendQuestionnaireDialog.vue'
import {
  type AssessmentGroup,
  type AssessmentHistoryCard,
  type AssessmentMissing,
  type AssessmentOverview,
  type AssessmentSnapshot,
  type QuestionnaireRequest,
  formatDateTime,
  groupAssessmentHistoryCards,
  notifyAssessmentChanged,
  pickPrimarySnapshot,
  requestStatusText,
  toneTagType,
} from '../../shared/assessment'

const route = useRoute()
const router = useRouter()
const props = defineProps<{
  peopleId?: string
}>()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const running = ref(false)
const overview = ref<AssessmentOverview | null>(null)

const questionnaireOpen = ref(false)
const sendDialogOpen = ref(false)
const focusField = ref('')
const rulesOpen = ref(false)
const rulesEngine = ref('')
const rulesSnapshot = ref<AssessmentSnapshot | null>(null)
const rulesSnapshots = ref<AssessmentSnapshot[]>([])

const historyLoading = ref(false)
const historyEngine = ref('')
const historyRaw = ref<AssessmentSnapshot[]>([])
const historyPage = ref(1)
const historyDetail = ref<AssessmentSnapshot | null>(null)
const cardBasis = ref<AssessmentHistoryCard | null>(null)
/** 按卡片分页；一次多取扁平行再合并去重 */
const pageSize = 8
const fetchSize = 120

const groups = computed(() => overview.value?.groups || [])
const pending = computed<QuestionnaireRequest | null>(() => overview.value?.pendingRequest || null)
const needsAttention = computed(() =>
  groups.value.filter((g) => {
    if (!g.applicable) return false
    if (g.latest?.suspicion) return true
    return !g.latest || g.latest.status !== 'COMPLETE' || !g.latest.levelLabel
  }),
)
const requestText = computed(() => requestStatusText(pending.value))

const historyCards = computed<AssessmentHistoryCard[]>(() =>
  groupAssessmentHistoryCards(historyRaw.value),
)
const historyTotal = computed(() => historyCards.value.length)
const pagedCards = computed(() => {
  const start = (historyPage.value - 1) * pageSize
  return historyCards.value.slice(start, start + pageSize)
})

const METRIC_FIELDS = new Set(['BLOOD_PRESSURE', 'HEART_RATE', 'GLUCOSE'])
const LAB_FIELDS = new Set(['HBA1C', 'LIPIDS', 'URIC_ACID', 'RENAL', 'LIVER'])
const ARCHIVE_FIELDS = new Set(['GENDER', 'BIRTHDAY'])

function applyOverview(data: AssessmentOverview | null | undefined) {
  if (!data) return
  overview.value = data
}

function missingText(g: AssessmentGroup) {
  const labels = (g.latest?.missing || []).map((m) => m.label).filter(Boolean)
  return labels.length ? `缺 ${labels.join('、')}` : '缺少评估必需数据'
}

function rowAdvice(row: AssessmentSnapshot) {
  return row.advice || row.categoryLabel || row.engineLabel || '—'
}

function triggerTagType(source?: string) {
  if (source === 'MANUAL') return 'warning'
  if (source === 'AUTO') return 'info'
  if (source === 'QUESTIONNAIRE') return 'success'
  return 'info'
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: AssessmentOverview }>(`/api/b/v1/patients/${peopleId.value}/assessments`)
    applyOverview(res.data)
  } catch (e) {
    ElMessage.error((e as Error).message || '加载评估失败')
  } finally {
    loading.value = false
  }
  await loadHistory()
}

async function runManual() {
  running.value = true
  try {
    const res = await api<{ data: AssessmentOverview }>(`/api/b/v1/patients/${peopleId.value}/assessments/run`, {
      method: 'POST',
    })
    applyOverview(res.data)
    historyPage.value = 1
    await loadHistory()
    notifyAssessmentChanged()
    ElMessage.success('已重新评估')
  } catch (e) {
    ElMessage.error((e as Error).message || '评估失败')
  } finally {
    running.value = false
  }
}

function openQuestionnaire(field = '') {
  focusField.value = field
  questionnaireOpen.value = true
}

function onQuestionnaireSubmitted(data: AssessmentOverview | null) {
  historyPage.value = 1
  notifyAssessmentChanged()
  if (data) {
    applyOverview(data)
    void loadHistory()
    return
  }
  void load()
}

function openSendDialog() {
  sendDialogOpen.value = true
}

async function onQuestionnaireSent() {
  await load()
}

async function createArchive(g: AssessmentGroup) {
  const disease = g.latest?.suspicion?.diseaseLabel || g.diseaseLabel
  try {
    await ElMessageBox.confirm(
      `确认患者已患${disease}？建档后将切换为「${disease}严重等级分层」评估，并关闭疑似患病任务。`,
      `建立${disease}档案`,
      { confirmButtonText: '确认建档', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const res = await api<{ data: AssessmentOverview }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/disease-archives/${g.diseaseCode}`,
      { method: 'POST' },
    )
    applyOverview(res.data)
    historyPage.value = 1
    await loadHistory()
    ElMessage.success(`已建立${disease}档案`)
  } catch (e) {
    ElMessage.error((e as Error).message || '建档失败')
  }
}

function fillMissing(m: AssessmentMissing) {
  if (m.questionnaire) {
    openQuestionnaire(m.field)
    return
  }
  const base = `/workspace/patients/${peopleId.value}`
  if (METRIC_FIELDS.has(m.field)) router.push(`${base}/observations/metrics`)
  else if (LAB_FIELDS.has(m.field)) router.push(`${base}/observations/labs`)
  else if (ARCHIVE_FIELDS.has(m.field)) router.push(`${base}/archive`)
  else if (m.fillHint) ElMessage.info(m.fillHint)
}

function openRules(
  engineCode = '',
  snapshot: AssessmentSnapshot | null = null,
  batch: AssessmentSnapshot[] = [],
) {
  rulesEngine.value = engineCode
  rulesSnapshot.value = snapshot
  rulesSnapshots.value = batch.length ? batch : snapshot ? [snapshot] : []
  rulesOpen.value = true
}

/** 卡片「查看依据」→ 打开规则抽屉并定位到优先病种 */
function openCardBasis(card: AssessmentHistoryCard) {
  const primary = pickPrimarySnapshot(card.items)
  openRules(primary?.engineCode || '', primary, card.items)
}

function openDiseaseBasis(row: AssessmentSnapshot, batch: AssessmentSnapshot[] = []) {
  openRules(row.engineCode, row, batch.length ? batch : [row])
}

function openRecord(row: AssessmentSnapshot) {
  historyDetail.value = row
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const q = new URLSearchParams({ page: '1', pageSize: String(fetchSize) })
    if (historyEngine.value) q.set('engineCode', historyEngine.value)
    const res = await api<{ data: { total: number; items: AssessmentSnapshot[] } }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/history?${q}`,
    )
    historyRaw.value = res.data.items || []
    if (
      historyPage.value > 1 &&
      (historyPage.value - 1) * pageSize >= groupAssessmentHistoryCards(historyRaw.value).length
    ) {
      historyPage.value = 1
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '加载历史失败')
  } finally {
    historyLoading.value = false
  }
}

function onEngineChange() {
  historyPage.value = 1
  void loadHistory()
}

watch(peopleId, () => {
  historyEngine.value = ''
  historyPage.value = 1
  historyDetail.value = null
  cardBasis.value = null
  rulesSnapshot.value = null
  rulesSnapshots.value = []
  void load()
})
onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading" class="panel">
    <section class="shell-card">
      <div class="head">
        <div>
          <h3>评估记录</h3>
        </div>
        <div class="head-actions">
          <el-button @click="openRules()">评估规则</el-button>
        <el-button @click="openSendDialog">
          {{ pending?.status === 'PENDING' ? '重新发送问卷' : '发问卷给患者' }}
        </el-button>
          <el-button @click="openQuestionnaire()">填写评估问卷</el-button>
          <el-button type="primary" :loading="running" @click="runManual">重新评估</el-button>
        </div>
      </div>

      <el-alert
        v-if="requestText"
        :type="pending?.status === 'EXPIRED' ? 'warning' : pending?.status === 'SUBMITTED' ? 'success' : 'info'"
        :closable="false"
        show-icon
        :title="requestText"
        class="info-alert"
      />

      <div v-if="needsAttention.length" class="attention">
        <div v-for="g in needsAttention" :key="g.diseaseCode" class="attention-row">
          <span class="attention-name">{{ g.diseaseLabel }}</span>
          <el-tag v-if="g.latest?.suspicion" size="small" type="danger" effect="plain">疑似已患病</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">待补充</el-tag>
          <span class="attention-text">{{ g.latest?.suspicion?.reason || missingText(g) }}</span>
          <el-button
            v-if="g.latest?.suspicion"
            size="small"
            type="danger"
            plain
            @click="createArchive(g)"
          >
            确认已患，建立档案
          </el-button>
          <template v-else>
            <el-button
              v-for="m in (g.latest?.missing || []).slice(0, 2)"
              :key="m.field"
              size="small"
              link
              type="primary"
              @click="fillMissing(m)"
            >
              {{ m.questionnaire ? '问卷补充' : `去补${m.label}` }}
            </el-button>
          </template>
        </div>
      </div>

      <div class="list-bar">
        <span class="list-count">共 {{ historyTotal }} 次评估</span>
        <el-select
          v-model="historyEngine"
          placeholder="全部评估"
          clearable
          class="list-filter"
          @change="onEngineChange"
        >
          <el-option v-for="g in groups" :key="g.engineCode" :label="g.engineLabel" :value="g.engineCode" />
        </el-select>
      </div>

      <div v-loading="historyLoading" class="card-list">
        <div v-if="!historyLoading && !pagedCards.length" class="card-empty">暂无评估记录</div>
        <article v-for="card in pagedCards" :key="card.key" class="snap-card">
          <div class="snap-main">
            <div class="snap-left">
              <el-tag
                v-if="card.triggerLabel"
                size="small"
                :type="triggerTagType(card.triggerSource)"
                effect="plain"
                class="snap-trigger"
              >
                {{ card.triggerLabel }}
              </el-tag>
              <time class="snap-time">{{ formatDateTime(card.assessedAt) }}</time>
            </div>

            <div class="snap-body">
              <div v-for="row in card.items" :key="row.id || `${row.engineCode}-${row.diseaseCode}`" class="snap-row">
                <button type="button" class="snap-disease" @click="openRecord(row)">
                  <span class="disease-name">{{ row.diseaseLabel }}</span>
                  <el-tag
                    v-if="row.levelLabel"
                    size="small"
                    :type="toneTagType(row.tone)"
                    effect="light"
                  >
                    {{ row.levelLabel }}
                  </el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">数据不足</el-tag>
                  <span v-if="row.score != null" class="disease-score">{{ row.score }} 分</span>
                </button>
                <p class="snap-advice" :title="rowAdvice(row)">{{ rowAdvice(row) }}</p>
                <button
                  type="button"
                  class="row-basis"
                  @click="openDiseaseBasis(row, card.items)"
                >
                  依据
                </button>
              </div>
            </div>
          </div>
          <div class="snap-footer">
            <button type="button" class="basis-link" @click="openCardBasis(card)">查看依据</button>
          </div>
        </article>
      </div>

      <div v-if="historyTotal > pageSize" class="pager">
        <el-pagination
          v-model:current-page="historyPage"
          layout="prev, pager, next"
          :total="historyTotal"
          :page-size="pageSize"
        />
      </div>

      <p v-if="overview?.lastQuestionnaire" class="foot-meta">
        最近一次问卷：{{ overview.lastQuestionnaire.portal === 'C' ? '患者自填' : '健管师填写' }} ·
        {{ formatDateTime(overview.lastQuestionnaire.submittedAt) }}
      </p>
    </section>

    <AssessmentQuestionnaireDrawer
      v-model="questionnaireOpen"
      :people-id="peopleId"
      :focus-field="focusField"
      @submitted="onQuestionnaireSubmitted"
    />
    <AssessmentSendQuestionnaireDialog
      v-model="sendDialogOpen"
      :people-id="peopleId"
      :resend="pending?.status === 'PENDING'"
      @sent="onQuestionnaireSent"
    />
    <AssessmentRulesDrawer
      v-model="rulesOpen"
      :engine-code="rulesEngine"
      :snapshot="rulesSnapshot"
      :snapshots="rulesSnapshots"
    />

    <el-dialog
      :model-value="!!cardBasis"
      :title="cardBasis ? `评估依据 · ${formatDateTime(cardBasis.assessedAt)}` : ''"
      width="min(960px, 94vw)"
      align-center
      class="assess-detail-dialog"
      @close="cardBasis = null"
    >
      <template v-if="cardBasis">
        <div v-for="row in cardBasis.items" :key="row.id" class="basis-block">
          <div class="basis-block-head">
            <div class="basis-block-title">
              <span>{{ row.diseaseLabel }}</span>
              <el-tag
                v-if="row.levelLabel"
                size="small"
                :type="toneTagType(row.tone)"
                effect="light"
              >
                {{ row.levelLabel }}
              </el-tag>
            </div>
            <el-button link type="primary" @click="openDiseaseBasis(row, cardBasis?.items || [])">规则说明</el-button>
          </div>
          <p v-if="row.advice" class="advice">{{ row.advice }}</p>
          <AssessmentResultDetail :snapshot="row" />
        </div>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!historyDetail"
      :title="historyDetail ? `${historyDetail.diseaseLabel} · ${historyDetail.levelLabel || historyDetail.engineLabel} · ${formatDateTime(historyDetail.assessedAt)}` : ''"
      width="min(960px, 94vw)"
      align-center
      class="assess-detail-dialog"
      @close="historyDetail = null"
    >
      <template v-if="historyDetail">
        <p v-if="historyDetail.advice" class="advice">{{ historyDetail.advice }}</p>
        <AssessmentTrendChart
          :people-id="peopleId"
          :engine-code="historyDetail.engineCode"
          :current-id="historyDetail.id"
        />
        <AssessmentResultDetail :snapshot="historyDetail" />
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel {
  padding: 8px 0 24px;
}
.shell-card {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}
.head h3 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
}
.hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-secondary);
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.head-actions .el-button + .el-button {
  margin-left: 0;
}
.info-alert {
  margin-bottom: 12px;
}
.attention {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}
.attention-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 6px 12px;
  border: 1px solid #fde68a;
  border-radius: 10px;
  background: #fffbeb;
}
.attention-name {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 650;
}
.attention-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.list-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.list-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.list-filter {
  width: 220px;
}
.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 80px;
}
.card-empty {
  padding: 36px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: #fafbfc;
}
.snap-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 8px;
  background: #fff;
  padding: 14px 16px 10px;
}
.snap-main {
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.snap-left {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding-top: 2px;
}
.snap-trigger {
  flex-shrink: 0;
}
.snap-time {
  font-size: 14px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
  line-height: 1.4;
}
.snap-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.snap-row {
  display: grid;
  grid-template-columns: minmax(180px, 240px) minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-width: 0;
}
.row-basis {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary, #94a3b8);
  cursor: pointer;
  white-space: nowrap;
}
.row-basis:hover {
  color: var(--el-color-primary);
}
.snap-disease {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  font: inherit;
  color: inherit;
  text-align: left;
}
.disease-name {
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
  white-space: nowrap;
}
.disease-score {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.snap-advice {
  margin: 0;
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.snap-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 2px;
  border-top: 1px dashed var(--el-border-color-extra-light, #eef2f7);
}
.basis-link {
  border: 0;
  background: transparent;
  padding: 2px 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary, #94a3b8);
  cursor: pointer;
}
.basis-link:hover {
  color: var(--el-color-primary);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.advice {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.6;
}
.foot-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 12px;
}
.basis-block + .basis-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.basis-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.basis-block-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 650;
}
@media (max-width: 860px) {
  .snap-main {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .snap-row {
    grid-template-columns: 1fr auto;
    gap: 4px 12px;
  }
  .snap-advice {
    grid-column: 1 / -1;
  }
}
</style>
