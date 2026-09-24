<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import FollowupSectionFields from './FollowupSectionFields.vue'
import PatientArchiveView from './PatientArchiveView.vue'
import {
  FOLLOWUP_CONTACT_TARGET_OPTIONS,
  FOLLOWUP_METHOD_OPTIONS,
  FOLLOWUP_TYPE_OPTIONS,
  buildFollowupContentPayload,
  emptyFollowupSection,
  hydrateFollowupFormFromContent,
  validateFollowupSection,
  type FollowupSection,
} from '../../shared/followup-labels'

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName: string
  taskType: string
  taskTypeLabel: string
  summary?: string
  deepLink?: string
  payload?: Record<string, unknown>
  followups?: Array<{
    id: string
    status?: string
    content?: Record<string, unknown>
  }>
}

type MetricHit = Record<string, unknown>

const props = defineProps<{ taskId: string | null }>()
const emit = defineEmits<{ closed: []; submitted: []; saved: [] }>()

const loading = ref(false)
const submitting = ref(false)
const saving = ref(false)
const task = ref<TaskDetail | null>(null)
const archiveRef = ref<{ saveAllForFollowup: () => Promise<{ diseaseCodes: string[] }> } | null>(null)

const contactChannel = ref('PHONE')
const contactResult = ref('REACHED')
const informedCheckin = ref(true)
const patientFeedback = ref('')
const note = ref('')
/** 指标异常 / 定期随访 */
const contactTarget = ref('')
const followupMethod = ref('')
const abnormalReason = ref('')
const guidance = ref('')
const followupType = ref('ROUTINE')
const followupSection = ref<FollowupSection>(emptyFollowupSection())
const suggestPlanAdjust = ref(false)

const open = computed({
  get: () => !!props.taskId,
  set: (v: boolean) => {
    if (!v) emit('closed')
  },
})

const isNudge = computed(() => task.value?.taskType === 'PLAN_NUDGE')
const isAlert = computed(() => task.value?.taskType === 'METRIC_ALERT')
const isFollowUp = computed(() => task.value?.taskType === 'FOLLOW_UP')
const isOnboardingFollowUp = computed(() => isFollowUp.value && followupType.value === 'ONBOARDING')
const dialogTitle = computed(() => {
  if (isAlert.value) return '指标异常处理'
  if (isFollowUp.value) return '定期随访'
  return task.value?.taskTypeLabel || '填单'
})
const peopleId = computed(() => String(task.value?.peopleId || ''))

const alertHits = computed<MetricHit[]>(() => {
  const payload = task.value?.payload
  if (!payload) return []
  const hits = payload.hits
  if (Array.isArray(hits) && hits.length > 0) {
    return hits as MetricHit[]
  }
  if (payload.sourceRecordId || payload.family) {
    return [payload as MetricHit]
  }
  return []
})

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

watch(
  () => props.taskId,
  async (id) => {
    task.value = null
    contactChannel.value = 'PHONE'
    contactResult.value = 'REACHED'
    informedCheckin.value = true
    patientFeedback.value = ''
    note.value = ''
    contactTarget.value = ''
    followupMethod.value = ''
    abnormalReason.value = ''
    guidance.value = ''
    followupType.value = 'ROUTINE'
    followupSection.value = emptyFollowupSection()
    suggestPlanAdjust.value = false
    if (!id) return
    loading.value = true
    try {
      const res = await api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${id}`)
      task.value = res.data
      const payloadType = res.data.payload?.followupType
      if (typeof payloadType === 'string' && payloadType) {
        followupType.value = payloadType
      }
      // 回填 OPEN 草稿（定期随访 / 指标异常 / 打卡跟进）
      if (
        res.data.taskType === 'FOLLOW_UP' ||
        res.data.taskType === 'METRIC_ALERT' ||
        res.data.taskType === 'PLAN_NUDGE'
      ) {
        const openDraft = (res.data.followups || []).find((f) => f.status === 'OPEN' && f.content)
        if (openDraft?.content) {
          const c = openDraft.content
          if (res.data.taskType === 'FOLLOW_UP') {
            const h = hydrateFollowupFormFromContent(c)
            followupType.value = h.followupType
            contactTarget.value = h.contactTarget
            followupMethod.value = h.followupMethod
            guidance.value = h.guidance
            suggestPlanAdjust.value = h.suggestPlanAdjust
            followupSection.value = h.section
          } else if (res.data.taskType === 'METRIC_ALERT') {
            if (typeof c.contactTarget === 'string') contactTarget.value = c.contactTarget
            if (typeof c.followupMethod === 'string') followupMethod.value = c.followupMethod
            if (typeof c.abnormalReason === 'string') abnormalReason.value = c.abnormalReason
            if (typeof c.guidance === 'string') guidance.value = c.guidance
          } else {
            if (typeof c.contactChannel === 'string') contactChannel.value = c.contactChannel
            if (typeof c.contactResult === 'string') contactResult.value = c.contactResult
            if (typeof c.informedCheckin === 'boolean') informedCheckin.value = c.informedCheckin
            if (typeof c.patientFeedback === 'string') patientFeedback.value = c.patientFeedback
            if (typeof c.note === 'string') note.value = c.note
          }
        }
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载任务失败')
      emit('closed')
    } finally {
      loading.value = false
    }
  },
)

function buildFollowUpBody() {
  return buildFollowupContentPayload(
    followupType.value,
    {
      contactTarget: contactTarget.value,
      followupMethod: followupMethod.value,
      guidance: guidance.value,
      suggestPlanAdjust: suggestPlanAdjust.value,
    },
    followupSection.value,
  )
}

function buildAlertBody() {
  return {
    contactTarget: contactTarget.value,
    followupMethod: followupMethod.value,
    abnormalReason: abnormalReason.value.trim() || undefined,
    guidance: guidance.value.trim() || undefined,
  }
}

function buildNudgeBody() {
  return {
    contactChannel: contactChannel.value,
    contactResult: contactResult.value,
    informedCheckin: contactResult.value === 'REACHED' ? informedCheckin.value : undefined,
    patientFeedback: patientFeedback.value.trim() || undefined,
    note: note.value.trim() || undefined,
  }
}

/** 保存草稿：宽松校验，不写档案、不关任务 */
async function saveDraft() {
  if (!props.taskId || !task.value) return
  if (!isFollowUp.value && !isAlert.value && !isNudge.value) return
  if (isFollowUp.value && !followupType.value) {
    ElMessage.warning('请选择随访类型')
    return
  }
  saving.value = true
  try {
    const body = isFollowUp.value
      ? buildFollowUpBody()
      : isAlert.value
        ? buildAlertBody()
        : buildNudgeBody()
    await api(`/api/b/v1/workspace/tasks/${props.taskId}/forms/draft`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    ElMessage.success('已保存草稿，任务仍为未完成')
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (!props.taskId || !task.value) return
  if (isAlert.value) {
    if (!contactTarget.value) {
      ElMessage.warning('请选择沟通对象')
      return
    }
    if (!followupMethod.value) {
      ElMessage.warning('请选择随访方式')
      return
    }
    const reason = abnormalReason.value.trim()
    if (!reason) {
      ElMessage.warning('请填写异常原因')
      return
    }
    if (reason.length > 400) {
      ElMessage.warning('异常原因不能超过400字')
      return
    }
    if (guidance.value.trim().length > 400) {
      ElMessage.warning('指导建议不能超过400字')
      return
    }
  }
  if (isFollowUp.value) {
    if (!followupType.value) {
      ElMessage.warning('请选择随访类型')
      return
    }
    if (!contactTarget.value || !followupMethod.value) {
      ElMessage.warning('请完善沟通对象与随访方式')
      return
    }
    const err = validateFollowupSection(followupType.value, followupSection.value)
    if (err) {
      ElMessage.warning(err)
      return
    }
    if (guidance.value.trim().length > 400) {
      ElMessage.warning('指导建议不能超过400字')
      return
    }
  }
  submitting.value = true
  try {
    let body: Record<string, unknown>
    if (isNudge.value) {
      body = buildNudgeBody()
    } else if (isFollowUp.value) {
      if (followupType.value === 'ONBOARDING') {
        if (!archiveRef.value) throw new Error('档案编辑器未就绪，请稍候再试')
        const { diseaseCodes } = await archiveRef.value.saveAllForFollowup()
        followupSection.value = {
          ...followupSection.value,
          archiveWritten: true,
          diseaseCodes,
        }
      }
      body = buildFollowUpBody()
    } else {
      body = {
        contactTarget: contactTarget.value,
        followupMethod: followupMethod.value,
        abnormalReason: abnormalReason.value.trim(),
        guidance: guidance.value.trim() || undefined,
      }
    }
    await api(`/api/b/v1/workspace/tasks/${props.taskId}/forms`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    ElMessage.success(
      isFollowUp.value && suggestPlanAdjust.value
        ? '已提交，并已同步「制定方案」待办'
        : '已提交',
    )
    emit('submitted')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="open"
    :title="dialogTitle"
    :width="isOnboardingFollowUp ? '920px' : isAlert ? '640px' : '560px'"
    destroy-on-close
    align-center
  >
    <div v-loading="loading" class="dialog-body">
      <div class="patient-bar">
        <span class="patient-name">{{ task?.peopleName || '-' }}</span>
        <template v-if="isAlert">
          <span class="patient-sep">·</span>
          <span class="patient-summary metric-text" v-html="highlightMetricText(task?.summary || '-')" />
        </template>
        <template v-else-if="task?.summary">
          <span class="patient-sep">·</span>
          <span class="patient-summary">{{ task.summary }}</span>
        </template>
      </div>

      <div v-if="isAlert && alertHits.length" class="hits">
        <div class="hits-head">
          <span class="hits-title">异常明细</span>
          <span class="hits-count">{{ alertHits.length }} 项</span>
          <span class="hits-hint">提交后一并关闭</span>
        </div>
        <div class="hits-list">
          <div
            v-for="(hit, idx) in alertHits"
            :key="String(hit.sourceRecordId || idx)"
            class="hit-row"
          >
            <span class="hit-main metric-text" v-html="highlightMetricText(hitLabel(hit))" />
            <span v-if="hitTime(hit)" class="hit-time">{{ hitTime(hit) }}</span>
          </div>
        </div>
      </div>

      <el-form v-if="isNudge" label-width="96px" class="task-form">
        <el-form-item label="联系渠道" required>
          <el-select v-model="contactChannel" style="width: 100%">
            <el-option label="电话" value="PHONE" />
            <el-option label="企微" value="WECOM" />
            <el-option label="当面" value="IN_PERSON" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系结果" required>
          <el-radio-group v-model="contactResult">
            <el-radio value="REACHED">已接通</el-radio>
            <el-radio value="UNREACHED">未接通</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="contactResult === 'REACHED'" label="已告知打卡" required>
          <el-switch v-model="informedCheckin" />
        </el-form-item>
        <el-form-item label="患者反馈">
          <el-input v-model="patientFeedback" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>

      <el-form v-else-if="isAlert" label-position="top" class="task-form alert-form">
        <div class="form-row">
          <el-form-item label="沟通对象" required class="form-col">
            <el-select v-model="contactTarget" placeholder="请选择" style="width: 100%">
              <el-option
                v-for="o in FOLLOWUP_CONTACT_TARGET_OPTIONS"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="随访方式" required class="form-col">
            <el-select v-model="followupMethod" placeholder="请选择" style="width: 100%">
              <el-option
                v-for="o in FOLLOWUP_METHOD_OPTIONS"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="异常原因" required>
          <el-input
            v-model="abnormalReason"
            type="textarea"
            :rows="3"
            maxlength="400"
            show-word-limit
            placeholder="请填写异常原因"
          />
        </el-form-item>
        <el-form-item label="指导建议">
          <el-input
            v-model="guidance"
            type="textarea"
            :rows="3"
            maxlength="400"
            show-word-limit
            placeholder="选填，可写用药、生活方式或复诊建议"
          />
        </el-form-item>
      </el-form>

      <el-form v-else-if="isFollowUp" label-position="top" class="task-form alert-form">
        <el-form-item label="随访类型" required>
          <el-select
            v-model="followupType"
            style="width: 100%"
            @change="followupSection = emptyFollowupSection()"
          >
            <el-option
              v-for="o in FOLLOWUP_TYPE_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="沟通对象" required class="form-col">
            <el-select v-model="contactTarget" placeholder="请选择" style="width: 100%">
              <el-option
                v-for="o in FOLLOWUP_CONTACT_TARGET_OPTIONS"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="随访方式" required class="form-col">
            <el-select v-model="followupMethod" placeholder="请选择" style="width: 100%">
              <el-option
                v-for="o in FOLLOWUP_METHOD_OPTIONS"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
          </el-form-item>
        </div>
        <div v-if="followupType === 'ONBOARDING' && peopleId" class="archive-block">
          <div class="archive-block-title">患者档案（提交时写回）</div>
          <PatientArchiveView ref="archiveRef" :people-id="peopleId" embedded />
        </div>
        <FollowupSectionFields v-model="followupSection" :followup-type="followupType" />
        <el-form-item label="指导建议">
          <el-input
            v-model="guidance"
            type="textarea"
            :rows="3"
            maxlength="400"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item v-if="followupType === 'ROUTINE'" label="建议调整方案">
          <div class="switch-line">
            <el-switch v-model="suggestPlanAdjust" />
            <span class="switch-hint">开启后同步工作台「制定方案」待办</span>
          </div>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button
        v-if="isFollowUp || isAlert || isNudge"
        :loading="saving"
        :disabled="submitting"
        @click="saveDraft"
      >
        保存
      </el-button>
      <el-button type="primary" :loading="submitting" :disabled="saving" @click="submit">
        提交
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-body {
  min-height: 80px;
}
.patient-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  margin: 0 0 14px;
  font-size: 14px;
  line-height: 1.4;
}
.patient-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.patient-sep {
  color: var(--el-text-color-placeholder);
}
.patient-summary {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.hits {
  margin: 0 0 18px;
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
.hits-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hits-list {
  max-height: 168px;
  overflow-y: auto;
  padding: 4px 0;
}
.hit-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 12px;
  font-size: 13px;
  line-height: 1.45;
}
.hit-row:hover {
  background: var(--el-fill-color-lighter);
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
.task-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.alert-form :deep(.el-form-item__label) {
  margin-bottom: 4px;
  line-height: 1.3;
  color: var(--el-text-color-regular);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.form-col {
  margin-bottom: 14px;
}
.archive-block {
  margin: 4px 0 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}
.archive-block-title {
  margin-bottom: 10px;
  font-weight: 600;
  font-size: 14px;
}
.switch-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
}
.switch-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.metric-text :deep(.metric-abnormal) {
  color: var(--el-color-danger);
  font-weight: 600;
}
@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
  .hits-hint {
    display: none;
  }
}
</style>
