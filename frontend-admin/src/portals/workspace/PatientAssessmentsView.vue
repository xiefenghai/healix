<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import AssessmentResultDetail from '../../shared/AssessmentResultDetail.vue'
import AssessmentTrendChart from '../../shared/AssessmentTrendChart.vue'
import AssessmentQuestionnaireDrawer from '../../shared/AssessmentQuestionnaireDrawer.vue'
import AssessmentRulesDrawer from '../../shared/AssessmentRulesDrawer.vue'
import {
  type AssessmentGroup,
  type AssessmentMissing,
  type AssessmentOverview,
  type AssessmentSnapshot,
  type QuestionnaireRequest,
  formatDateTime,
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
const sending = ref(false)
const overview = ref<AssessmentOverview | null>(null)

const questionnaireOpen = ref(false)
const focusField = ref('')
const rulesOpen = ref(false)
const rulesEngine = ref('')

const historyLoading = ref(false)
const historyEngine = ref('')
const historyItems = ref<AssessmentSnapshot[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historyDetail = ref<AssessmentSnapshot | null>(null)
const pageSize = 20

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
  if (data) {
    applyOverview(data)
    void loadHistory()
    return
  }
  void load()
}

async function sendRequest() {
  const resend = pending.value?.status === 'PENDING'
  try {
    await ElMessageBox.confirm(
      resend
        ? '患者还有一份未填写的评估问卷，重新发送会作废旧问卷并重新计时 7 天。'
        : '将向患者推送评估问卷，请其补充缺失的健康信息。7 天内有效，48 小时未填会再提醒一次。',
      resend ? '重新发送问卷' : '发送评估问卷',
      { confirmButtonText: '发送', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  sending.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/assessments/questionnaire-requests`, {
      method: 'POST',
      body: JSON.stringify({}),
    })
    ElMessage.success('已发送给患者')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '发送失败')
  } finally {
    sending.value = false
  }
}

async function createArchive(g: AssessmentGroup) {
  const disease = g.latest?.suspicion?.diseaseLabel || g.diseaseLabel
  try {
    await ElMessageBox.confirm(
      `确认患者已确诊${disease}？建档后将切换为「${disease}严重等级分层」评估，并关闭疑似患病任务。`,
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

function openRules(engineCode = '') {
  rulesEngine.value = engineCode
  rulesOpen.value = true
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const q = new URLSearchParams({ page: String(historyPage.value), pageSize: String(pageSize) })
    if (historyEngine.value) q.set('engineCode', historyEngine.value)
    const res = await api<{ data: { total: number; items: AssessmentSnapshot[] } }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/history?${q}`,
    )
    historyItems.value = res.data.items
    historyTotal.value = res.data.total
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

function openRecord(row: AssessmentSnapshot) {
  historyDetail.value = row
}

watch(peopleId, () => {
  historyEngine.value = ''
  historyPage.value = 1
  historyDetail.value = null
  void load()
})
onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading" class="panel">
    <div class="head">
      <div>
        <h3>评估记录</h3>
        <p class="hint">当前结论在上方标签里。这里按时间列出每次评估，点一行查看那次的依据。</p>
      </div>
      <div class="head-actions">
        <el-button @click="openRules()">评估规则</el-button>
        <el-button :loading="sending" @click="sendRequest">
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
          已确诊，建立档案
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
      <span class="list-count">共 {{ historyTotal }} 条</span>
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

    <el-table
      v-loading="historyLoading"
      :data="historyItems"
      class="record-table"
      empty-text="暂无评估记录"
      @row-click="openRecord"
    >
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.assessedAt) }}</template>
      </el-table-column>
      <el-table-column prop="diseaseLabel" label="病种" width="96" />
      <el-table-column label="类型" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.categoryLabel || row.engineLabel }}</template>
      </el-table-column>
      <el-table-column label="结果" width="168">
        <template #default="{ row }">
          <el-tag v-if="row.levelLabel" size="small" :type="toneTagType(row.tone)" effect="light">
            {{ row.levelLabel }}
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain">数据不足</el-tag>
          <span v-if="row.score != null" class="score">{{ row.score }} 分</span>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.advice || '—' }}</template>
      </el-table-column>
      <el-table-column prop="triggerLabel" label="来源" width="110" show-overflow-tooltip />
      <el-table-column label="" width="72" align="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openRecord(row)">依据</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="historyTotal > pageSize" class="pager">
      <el-pagination
        v-model:current-page="historyPage"
        layout="prev, pager, next"
        :total="historyTotal"
        :page-size="pageSize"
        @current-change="loadHistory"
      />
    </div>

    <p v-if="overview?.lastQuestionnaire" class="foot-meta">
      最近一次问卷：{{ overview.lastQuestionnaire.portal === 'C' ? '患者自填' : '健管师填写' }} ·
      {{ formatDateTime(overview.lastQuestionnaire.submittedAt) }}
    </p>

    <AssessmentQuestionnaireDrawer
      v-model="questionnaireOpen"
      :people-id="peopleId"
      :focus-field="focusField"
      @submitted="onQuestionnaireSubmitted"
    />
    <AssessmentRulesDrawer v-model="rulesOpen" :engine-code="rulesEngine" />

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
  margin-bottom: 8px;
}
.list-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.list-filter {
  width: 220px;
}
.record-table {
  width: 100%;
}
.record-table :deep(.el-table__row) {
  cursor: pointer;
}
.score {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
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
</style>
