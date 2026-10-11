<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, getStaffId } from '../shared/http'
import { formatDateTime, isOverdue } from '../shared/format'
import { submitWithRetry } from '../shared/retry'
import {
  EXERCISE_DISPOSITION_OPTIONS,
  FOLLOWUP_CONTACT_TARGET_OPTIONS,
  FOLLOWUP_METHOD_OPTIONS,
  LIFESTYLE_LEVEL_OPTIONS,
  NUDGE_CHANNEL_OPTIONS,
  NUDGE_RESULT_OPTIONS,
  PLAN_SATISFACTION_OPTIONS,
  buildRoutineFollowupPayload,
  formatTaskStatus,
  validateRoutineForm,
} from '../shared/labels'

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName?: string
  taskType?: string
  taskTypeLabel?: string
  summary?: string
  status?: string
  assigneeStaffId?: string | null
  dueAt?: string
  deepLink?: string
  payload?: Record<string, unknown> | null
  followups?: Array<{ id: string; status?: string; content?: Record<string, unknown> }>
}

interface OrgStaffItem {
  staffId: string
  displayName?: string
  name?: string
}

type MetricHit = Record<string, unknown>

interface ExerciseEvent {
  sessionId?: string
  symptomLabels?: string[]
  typeName?: string
  startedAt?: string
  durationMin?: number
  recordedByStaff?: boolean
  deleted?: boolean
}

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.taskId || ''))
const loading = ref(false)
const submitting = ref(false)
const savingDraft = ref(false)
const claiming = ref(false)
const releasing = ref(false)
const assigning = ref(false)
const task = ref<TaskDetail | null>(null)
const staffId = computed(() => getStaffId())
const staffList = ref<OrgStaffItem[]>([])
const showAssign = ref(false)
const showRelease = ref(false)
const releaseReason = ref('')

const isMineOpen = computed(
  () =>
    task.value?.status === 'OPEN' &&
    !!task.value.assigneeStaffId &&
    task.value.assigneeStaffId === staffId.value,
)
const canClaim = computed(() => task.value?.status === 'OPEN' && !task.value.assigneeStaffId)
const canRelease = computed(
  () => task.value?.status === 'OPEN' && !!task.value.assigneeStaffId && isMineOpen.value,
)
const canAssign = computed(() => task.value?.status === 'OPEN')
const isNudge = computed(() => task.value?.taskType === 'PLAN_NUDGE')
const isAlert = computed(() => task.value?.taskType === 'METRIC_ALERT')
const isFollowUp = computed(() => task.value?.taskType === 'FOLLOW_UP')
const isExercise = computed(() => task.value?.taskType === 'EXERCISE_ALERT')
const isTeamAssign = computed(() => task.value?.taskType === 'TEAM_ASSIGN')
const isPlanCreate = computed(() => task.value?.taskType === 'PLAN_CREATE')
const isReportReview = computed(() => task.value?.taskType === 'REPORT_REVIEW')
const isPlanReview = computed(() => task.value?.taskType === 'PLAN_REVIEW')
const canFillForm = computed(
  () => isMineOpen.value && (isNudge.value || isAlert.value || isFollowUp.value || isExercise.value),
)
const formTitle = computed(() => {
  if (isNudge.value) return '打卡跟进'
  if (isAlert.value) return '指标异常处理'
  if (isExercise.value) return '运动不适处理'
  return '定期随访'
})
const canProcessFlow = computed(
  () =>
    isMineOpen.value &&
    (isTeamAssign.value || isPlanCreate.value || isReportReview.value || isPlanReview.value),
)
const processCta = computed(() => {
  if (isTeamAssign.value) return { label: '去分配健管组', path: `/tasks/${taskId.value}/join` }
  if (isPlanCreate.value) return { label: '去制定方案', path: `/tasks/${taskId.value}/care-plan` }
  if (isReportReview.value) return { label: '去审阅报告', path: `/tasks/${taskId.value}/report` }
  if (isPlanReview.value) return { label: '去复核签署', path: `/tasks/${taskId.value}/plan-review` }
  return null
})

const alertHits = computed<MetricHit[]>(() => {
  const payload = task.value?.payload
  if (!payload) return []
  const hits = payload.hits
  if (Array.isArray(hits) && hits.length > 0) return hits as MetricHit[]
  if (payload.sourceRecordId || payload.family) return [payload]
  return []
})

const exerciseEvents = computed<ExerciseEvent[]>(() => {
  const events = task.value?.payload?.events
  return Array.isArray(events) ? (events as ExerciseEvent[]) : []
})

/** EXERCISE_ALERT */
const disposition = ref('')

/** PLAN_NUDGE */
const contactChannel = ref('PHONE')
const contactResult = ref('REACHED')
const informedCheckin = ref(true)
const patientFeedback = ref('')
const note = ref('')

/** METRIC_ALERT / FOLLOW_UP */
const contactTarget = ref('PATIENT')
const followupMethod = ref('PHONE')
const abnormalReason = ref('')
const guidance = ref('')
const adherenceNote = ref('')
const lifestyleLevel = ref('FAIR')
const planSatisfaction = ref('SATISFIED')
const lifestyleNote = ref('')
const unsatisfiedReason = ref('')

type PickerField =
  | 'contactChannel'
  | 'contactResult'
  | 'contactTarget'
  | 'followupMethod'
  | 'lifestyleLevel'
  | 'planSatisfaction'
  | 'disposition'

const showPicker = ref(false)
const picker = ref<{
  field: PickerField
  title: string
  options: ReadonlyArray<{ label: string; value: string }>
} | null>(null)

const fieldSetters: Record<PickerField, (v: string) => void> = {
  contactChannel: (v) => {
    contactChannel.value = v
  },
  contactResult: (v) => {
    contactResult.value = v
  },
  contactTarget: (v) => {
    contactTarget.value = v
  },
  followupMethod: (v) => {
    followupMethod.value = v
  },
  lifestyleLevel: (v) => {
    lifestyleLevel.value = v
  },
  planSatisfaction: (v) => {
    planSatisfaction.value = v
  },
  disposition: (v) => {
    disposition.value = v
  },
}

function openPicker(
  field: PickerField,
  title: string,
  options: ReadonlyArray<{ label: string; value: string }>,
) {
  picker.value = { field, title, options }
  showPicker.value = true
}

function pickOption(value: string) {
  if (picker.value) fieldSetters[picker.value.field](value)
  showPicker.value = false
}

function optionLabel(options: ReadonlyArray<{ label: string; value: string }>, value: string) {
  return options.find((o) => o.value === value)?.label || value
}

function hitLabel(hit: MetricHit) {
  if (typeof hit.summary === 'string' && hit.summary.trim()) return hit.summary
  const family = String(hit.family || '')
  if (family === 'BP') return `血压 ${hit.sys ?? '-'}/${hit.dia ?? '-'} ${hit.unit || 'mmHg'}`
  if (family === 'GLUCOSE') return `血糖 ${hit.value ?? '-'} ${hit.unit || 'mmol/L'}`
  if (family === 'HR') return `心率 ${hit.value ?? '-'} ${hit.unit || 'bpm'}`
  if (family === 'BMI') return `BMI ${hit.bmi ?? '-'}`
  return '指标异常'
}

function hitTime(hit: MetricHit) {
  const raw = hit.recordedAt
  if (typeof raw !== 'string' || !raw) return ''
  return raw.replace('T', ' ').slice(0, 16)
}

function hydrateFromContent(content: Record<string, unknown>) {
  if (typeof content.contactTarget === 'string') contactTarget.value = content.contactTarget
  if (typeof content.followupMethod === 'string') followupMethod.value = content.followupMethod
  if (typeof content.guidance === 'string') guidance.value = content.guidance
  if (typeof content.abnormalReason === 'string') abnormalReason.value = content.abnormalReason
  if (typeof content.contactChannel === 'string') contactChannel.value = content.contactChannel
  if (typeof content.contactResult === 'string') contactResult.value = content.contactResult
  if (typeof content.informedCheckin === 'boolean') informedCheckin.value = content.informedCheckin
  if (typeof content.patientFeedback === 'string') patientFeedback.value = content.patientFeedback
  if (typeof content.note === 'string') note.value = content.note
  const section = content.section as Record<string, unknown> | undefined
  if (section) {
    if (typeof section.adherenceNote === 'string') adherenceNote.value = section.adherenceNote
    if (typeof section.lifestyleLevel === 'string') lifestyleLevel.value = section.lifestyleLevel
    if (typeof section.planSatisfaction === 'string') planSatisfaction.value = section.planSatisfaction
    if (typeof section.lifestyleNote === 'string') lifestyleNote.value = section.lifestyleNote
    if (typeof section.unsatisfiedReason === 'string') unsatisfiedReason.value = section.unsatisfiedReason
  }
}

function buildBody(): Record<string, unknown> | null {
  if (isExercise.value) {
    if (!disposition.value) {
      showToast('请选择处理方式')
      return null
    }
    return {
      disposition: disposition.value,
      contactChannel: contactChannel.value || undefined,
      note: note.value.trim() || undefined,
    }
  }
  if (isNudge.value) {
    return {
      contactChannel: contactChannel.value,
      contactResult: contactResult.value,
      informedCheckin: contactResult.value === 'REACHED' ? informedCheckin.value : undefined,
      patientFeedback: patientFeedback.value.trim() || undefined,
      note: note.value.trim() || undefined,
    }
  }
  if (isAlert.value) {
    return {
      contactTarget: contactTarget.value,
      followupMethod: followupMethod.value,
      abnormalReason: abnormalReason.value.trim() || undefined,
      guidance: guidance.value.trim() || undefined,
    }
  }
  const err = validateRoutineForm({
    contactTarget: contactTarget.value,
    followupMethod: followupMethod.value,
    adherenceNote: adherenceNote.value,
    lifestyleLevel: lifestyleLevel.value,
    planSatisfaction: planSatisfaction.value,
    unsatisfiedReason: unsatisfiedReason.value,
  })
  if (err) {
    showToast(err)
    return null
  }
  return buildRoutineFollowupPayload({
    contactTarget: contactTarget.value,
    followupMethod: followupMethod.value,
    guidance: guidance.value,
    adherenceNote: adherenceNote.value,
    lifestyleLevel: lifestyleLevel.value,
    planSatisfaction: planSatisfaction.value,
    lifestyleNote: lifestyleNote.value,
    unsatisfiedReason: unsatisfiedReason.value,
  })
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${taskId.value}`)
    task.value = res.data
    const openFu = (res.data.followups || []).find((f) => f.status === 'OPEN' && f.content)
    if (openFu?.content && typeof openFu.content === 'object') {
      hydrateFromContent(openFu.content)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function claim() {
  claiming.value = true
  try {
    const ok = await submitWithRetry(
      () => api(`/api/b/v1/workspace/tasks/${taskId.value}/claim`, { method: 'POST' }),
      { title: '领取失败' },
    )
    if (!ok) return
    showToast('已领取')
    await load()
  } finally {
    claiming.value = false
  }
}

async function openRelease() {
  releaseReason.value = ''
  showRelease.value = true
}

async function confirmRelease() {
  const reason = releaseReason.value.trim()
  if (!reason) {
    showToast('请填写退回原因')
    return
  }
  releasing.value = true
  try {
    await api(`/api/b/v1/workspace/tasks/${taskId.value}/release`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
    showToast('已退回公共池')
    showRelease.value = false
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '退回失败')
  } finally {
    releasing.value = false
  }
}

async function openAssign() {
  try {
    const res = await api<{ data: OrgStaffItem[] }>('/api/b/v1/org-staff')
    staffList.value = res.data || []
    showAssign.value = true
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载同事失败')
  }
}

async function assignTo(staff: OrgStaffItem) {
  assigning.value = true
  try {
    await api(`/api/b/v1/workspace/tasks/${taskId.value}/assign`, {
      method: 'POST',
      body: JSON.stringify({ staffId: staff.staffId }),
    })
    showToast(`已分派给 ${staff.displayName || staff.name || '同事'}`)
    showAssign.value = false
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '分派失败')
  } finally {
    assigning.value = false
  }
}

async function saveDraft() {
  if (!task.value) return
  const body = isAlert.value
    ? {
        contactTarget: contactTarget.value,
        followupMethod: followupMethod.value,
        abnormalReason: abnormalReason.value.trim() || undefined,
        guidance: guidance.value.trim() || undefined,
      }
    : isNudge.value
      ? {
          contactChannel: contactChannel.value,
          contactResult: contactResult.value,
          informedCheckin: contactResult.value === 'REACHED' ? informedCheckin.value : undefined,
          patientFeedback: patientFeedback.value.trim() || undefined,
          note: note.value.trim() || undefined,
        }
      : buildRoutineFollowupPayload({
          contactTarget: contactTarget.value,
          followupMethod: followupMethod.value,
          guidance: guidance.value,
          adherenceNote: adherenceNote.value,
          lifestyleLevel: lifestyleLevel.value,
          planSatisfaction: planSatisfaction.value,
          lifestyleNote: lifestyleNote.value,
          unsatisfiedReason: unsatisfiedReason.value,
        })
  savingDraft.value = true
  try {
    const ok = await submitWithRetry(
      () =>
        api(`/api/b/v1/workspace/tasks/${taskId.value}/forms/draft`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      { title: '保存失败' },
    )
    if (!ok) return
    showToast('草稿已保存')
  } finally {
    savingDraft.value = false
  }
}

async function submitForm() {
  if (!task.value) return
  if (isAlert.value && !abnormalReason.value.trim()) {
    showToast('请填写异常原因说明')
    return
  }
  const body = buildBody()
  if (!body) return
  submitting.value = true
  try {
    const ok = await submitWithRetry(
      () =>
        api(`/api/b/v1/workspace/tasks/${taskId.value}/forms`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      { title: '提交失败' },
    )
    if (!ok) return
    showToast('已完成')
    await router.replace('/tasks')
  } finally {
    submitting.value = false
  }
}

function goPatient() {
  if (task.value?.peopleId) void router.push(`/patients/${task.value.peopleId}`)
}

function goProcess() {
  if (processCta.value) void router.push(processCta.value.path)
}

function staffName(s: OrgStaffItem) {
  return s.displayName || s.name || s.staffId
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="任务详情" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else-if="task">
      <section class="hero hx-surface">
        <span class="hx-chip">{{ task.taskTypeLabel }}</span>
        <h2>{{ task.summary || task.taskTypeLabel }}</h2>
        <p>
          {{ task.peopleName || '—' }}
          <button v-if="task.peopleId" type="button" class="link" @click="goPatient">查看患者</button>
        </p>
        <p class="hx-muted">
          {{ formatTaskStatus(task.status) }}
          <template v-if="task.dueAt">
            · 截止 {{ formatDateTime(task.dueAt) }}
            <span v-if="isOverdue(task.dueAt, task.status)" class="overdue">已逾期</span>
          </template>
        </p>
      </section>

      <section v-if="isAlert && alertHits.length" class="hits hx-surface">
        <h3>异常明细</h3>
        <div v-for="(h, i) in alertHits" :key="i" class="hit">
          <strong>{{ hitLabel(h) }}</strong>
          <span v-if="hitTime(h)" class="hx-muted">{{ hitTime(h) }}</span>
        </div>
      </section>

      <section v-if="isExercise && exerciseEvents.length" class="hits hx-surface">
        <h3>不适上报（{{ exerciseEvents.length }} 次）</h3>
        <div v-for="(e, i) in exerciseEvents" :key="e.sessionId || i" class="hit">
          <strong :class="{ deleted: e.deleted }">
            {{ e.typeName || '运动' }}<template v-if="e.durationMin"> · {{ e.durationMin }} 分钟</template>：{{
              (e.symptomLabels || []).join('、') || '不适'
            }}
          </strong>
          <span class="hx-muted">
            <template v-if="e.startedAt">{{ String(e.startedAt).replace('T', ' ').slice(5, 16) }}</template>
            <template v-if="e.recordedByStaff"> · 代录</template>
            <template v-if="e.deleted"> · 患者已删除该记录</template>
          </span>
        </div>
      </section>

      <div v-if="canClaim" class="actions">
        <van-button block type="primary" round :loading="claiming" @click="claim">领取任务</van-button>
      </div>

      <div v-if="canProcessFlow && processCta" class="actions">
        <van-button block type="primary" round @click="goProcess">{{ processCta.label }}</van-button>
      </div>

      <div v-if="task.status === 'OPEN' && (canRelease || canAssign)" class="actions row">
        <van-button v-if="canRelease" block round plain type="primary" @click="openRelease">
          退回公共池
        </van-button>
        <van-button v-if="canAssign" block round plain type="primary" @click="openAssign">
          转派同事
        </van-button>
      </div>

      <section v-if="canFillForm" class="form hx-surface">
        <h3>{{ formTitle }}</h3>

        <template v-if="isExercise">
          <van-cell
            title="处理方式"
            is-link
            required
            :value="disposition ? optionLabel(EXERCISE_DISPOSITION_OPTIONS, disposition) : '请选择'"
            @click="openPicker('disposition', '处理方式', EXERCISE_DISPOSITION_OPTIONS)"
          />
          <van-cell
            title="联系渠道"
            is-link
            :value="optionLabel(NUDGE_CHANNEL_OPTIONS, contactChannel)"
            @click="openPicker('contactChannel', '联系渠道', NUDGE_CHANNEL_OPTIONS)"
          />
          <van-field v-model="note" rows="2" autosize type="textarea" label="备注" maxlength="500" />
        </template>

        <template v-else-if="isNudge">
          <van-cell
            title="联系渠道"
            is-link
            :value="optionLabel(NUDGE_CHANNEL_OPTIONS, contactChannel)"
            @click="openPicker('contactChannel', '联系渠道', NUDGE_CHANNEL_OPTIONS)"
          />
          <van-cell
            title="联系结果"
            is-link
            :value="optionLabel(NUDGE_RESULT_OPTIONS, contactResult)"
            @click="openPicker('contactResult', '联系结果', NUDGE_RESULT_OPTIONS)"
          />
          <van-cell title="已告知打卡">
            <template #value>
              <van-radio-group v-model="informedCheckin" direction="horizontal">
                <van-radio :name="true">是</van-radio>
                <van-radio :name="false">否</van-radio>
              </van-radio-group>
            </template>
          </van-cell>
          <van-field v-model="patientFeedback" rows="2" autosize type="textarea" label="患者反馈" />
          <van-field v-model="note" rows="2" autosize type="textarea" label="备注" />
        </template>

        <template v-else-if="isAlert">
          <van-cell
            title="联系对象"
            is-link
            :value="optionLabel(FOLLOWUP_CONTACT_TARGET_OPTIONS, contactTarget)"
            @click="openPicker('contactTarget', '联系对象', FOLLOWUP_CONTACT_TARGET_OPTIONS)"
          />
          <van-cell
            title="随访方式"
            is-link
            :value="optionLabel(FOLLOWUP_METHOD_OPTIONS, followupMethod)"
            @click="openPicker('followupMethod', '随访方式', FOLLOWUP_METHOD_OPTIONS)"
          />
          <van-field
            v-model="abnormalReason"
            rows="3"
            autosize
            type="textarea"
            label="异常说明"
            required
            maxlength="400"
          />
          <van-field v-model="guidance" rows="2" autosize type="textarea" label="指导建议" maxlength="400" />
        </template>

        <template v-else>
          <van-cell
            title="联系对象"
            is-link
            :value="optionLabel(FOLLOWUP_CONTACT_TARGET_OPTIONS, contactTarget)"
            @click="openPicker('contactTarget', '联系对象', FOLLOWUP_CONTACT_TARGET_OPTIONS)"
          />
          <van-cell
            title="随访方式"
            is-link
            :value="optionLabel(FOLLOWUP_METHOD_OPTIONS, followupMethod)"
            @click="openPicker('followupMethod', '随访方式', FOLLOWUP_METHOD_OPTIONS)"
          />
          <van-field
            v-model="adherenceNote"
            rows="2"
            autosize
            type="textarea"
            label="打卡概况"
            required
          />
          <van-cell
            title="生活习惯"
            is-link
            :value="optionLabel(LIFESTYLE_LEVEL_OPTIONS, lifestyleLevel)"
            @click="openPicker('lifestyleLevel', '生活习惯', LIFESTYLE_LEVEL_OPTIONS)"
          />
          <van-cell
            title="方案满意度"
            is-link
            :value="optionLabel(PLAN_SATISFACTION_OPTIONS, planSatisfaction)"
            @click="openPicker('planSatisfaction', '方案满意度', PLAN_SATISFACTION_OPTIONS)"
          />
          <van-field
            v-if="planSatisfaction === 'UNSATISFIED'"
            v-model="unsatisfiedReason"
            rows="2"
            autosize
            type="textarea"
            label="不满意原因"
            required
          />
          <van-field v-model="lifestyleNote" rows="2" autosize type="textarea" label="生活备注" />
          <van-field v-model="guidance" rows="2" autosize type="textarea" label="指导建议" />
        </template>

        <div class="form-actions">
          <van-button
            v-if="!isExercise"
            block
            round
            plain
            type="primary"
            :loading="savingDraft"
            @click="saveDraft"
          >
            保存草稿
          </van-button>
          <van-button block type="primary" round :loading="submitting" @click="submitForm">
            提交并完成
          </van-button>
        </div>
      </section>
    </template>

    <van-popup v-model:show="showPicker" position="bottom" round>
      <div v-if="picker" class="picker">
        <div class="picker-title">{{ picker.title }}</div>
        <van-cell
          v-for="o in picker.options"
          :key="o.value"
          :title="o.label"
          clickable
          @click="pickOption(o.value)"
        />
      </div>
    </van-popup>

    <van-popup v-model:show="showAssign" position="bottom" round>
      <div class="picker-title">转派给同事</div>
      <van-cell
        v-for="s in staffList"
        :key="s.staffId"
        :title="staffName(s)"
        clickable
        :disabled="assigning"
        @click="assignTo(s)"
      />
      <van-empty v-if="!staffList.length" description="暂无同事" />
    </van-popup>

    <van-popup v-model:show="showRelease" position="bottom" round>
      <div class="release">
        <div class="picker-title">退回公共池</div>
        <van-field
          v-model="releaseReason"
          rows="3"
          autosize
          type="textarea"
          placeholder="请填写退回原因"
        />
        <van-button
          block
          type="primary"
          round
          :loading="releasing"
          class="release-btn"
          @click="confirmRelease"
        >
          确认退回
        </van-button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.loading {
  margin-top: 48px;
}
.hero {
  margin: 8px 16px 12px;
  padding: 16px;
}
.hero h2 {
  margin: 12px 0 8px;
  font-size: 19px;
  line-height: 1.35;
  color: var(--hx-ink);
}
.hero p {
  margin: 0 0 6px;
  font-size: 14px;
}
.link {
  margin-left: 8px;
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-weight: 600;
  padding: 0;
}
.overdue {
  color: var(--hx-danger);
  margin-left: 4px;
  font-weight: 650;
}
.hits {
  margin: 0 16px 12px;
  padding: 12px 14px;
}
.hits h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--hx-ink);
}
.hit {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 0;
  font-size: 13px;
}
.hit + .hit {
  border-top: 1px solid var(--hx-line);
}
.hit strong {
  color: var(--hx-danger);
  font-weight: 650;
}
.hit strong.deleted {
  color: var(--hx-muted, #8a97a5);
  text-decoration: line-through;
}
.actions {
  padding: 0 16px 12px;
}
.actions.row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.form {
  margin: 0 16px 24px;
  padding: 8px 0 16px;
  overflow: hidden;
}
.form h3 {
  margin: 12px 16px;
  font-size: 16px;
  color: var(--hx-ink);
}
.form-actions {
  display: grid;
  gap: 8px;
  margin: 16px;
}
.picker-title {
  padding: 16px;
  font-weight: 650;
  text-align: center;
  color: var(--hx-ink);
}
.release {
  padding-bottom: 16px;
}
.release-btn {
  margin: 8px 16px 0;
  width: calc(100% - 32px);
}
</style>
