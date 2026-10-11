<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import FollowupSectionFields from './FollowupSectionFields.vue'
import FollowupRecordDetailDialog from './FollowupRecordDetailDialog.vue'
import PatientArchiveView from './PatientArchiveView.vue'
import {
  FOLLOWUP_CONTACT_TARGET_OPTIONS,
  FOLLOWUP_METHOD_OPTIONS,
  FOLLOWUP_TYPE_OPTIONS,
  buildFollowupContentPayload,
  emptyFollowupSection,
  formatFollowupMethod,
  formatFollowupRecordType,
  formatFollowupStatus,
  formatFollowupType,
  hydrateFollowupFormFromContent,
  validateFollowupSection,
  type FollowupSection,
} from '../../shared/followup-labels'

interface FollowupItem {
  id: string
  peopleId: string
  peopleName?: string
  recordType: string
  recordTypeLabel?: string
  status: string
  title?: string
  summary?: string
  followupType?: string
  followupTypeLabel?: string
  contactChannel?: string
  completedByName?: string
  completedAt?: string
  plannedAt?: string
  gmtCreated?: string
}

const route = useRoute()
const props = defineProps<{
  /** 驾驶舱抽屉等场景传入；不传则走路由 params */
  peopleId?: string
}>()
const peopleId = () => String(props.peopleId || route.params.peopleId || '')
const loading = ref(false)
const items = ref<FollowupItem[]>([])
const createOpen = ref(false)
const followupDetailId = ref<string | null>(null)
const completingId = ref<string | null>(null)
const submitting = ref(false)
const createArchiveRef = ref<{ saveAllForFollowup: () => Promise<{ diseaseCodes: string[] }> } | null>(
  null,
)
const completeArchiveRef = ref<{
  saveAllForFollowup: () => Promise<{ diseaseCodes: string[] }>
} | null>(null)

const createForm = reactive({
  followupType: 'ROUTINE',
  completeNow: true,
  createTask: true,
  contactTarget: '',
  followupMethod: '',
  guidance: '',
  suggestPlanAdjust: false,
  section: emptyFollowupSection(),
})

const completeForm = reactive({
  followupType: 'ROUTINE',
  contactTarget: '',
  followupMethod: '',
  guidance: '',
  suggestPlanAdjust: false,
  section: emptyFollowupSection(),
})

const createDialogWide = computed(
  () => createForm.completeNow && createForm.followupType === 'ONBOARDING',
)
const completeDialogWide = computed(() => completeForm.followupType === 'ONBOARDING')

function formatTime(v?: string) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

async function applyOnboardingArchive(
  panel: { saveAllForFollowup: () => Promise<{ diseaseCodes: string[] }> } | null,
  section: FollowupSection,
) {
  if (!panel) throw new Error('档案编辑器未就绪，请稍候再试')
  const { diseaseCodes } = await panel.saveAllForFollowup()
  section.archiveWritten = true
  section.diseaseCodes = diseaseCodes
}

/** 勾选「建议调整方案」后同步制定方案待办（不强制跳转） */
function planAdjustSuccessMessage(base: string, suggested: boolean) {
  if (!suggested) return base
  return `${base}，已同步「制定方案」待办`
}

async function load() {
  if (!peopleId()) return
  loading.value = true
  try {
    const res = await api<{ data: FollowupItem[] }>(
      `/api/b/v1/patients/${peopleId()}/followups?limit=100`,
    )
    items.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetSection(target: { section: FollowupSection }) {
  target.section = emptyFollowupSection()
}

function openCreate(prefill?: { guidance?: string }) {
  createForm.followupType = 'ROUTINE'
  createForm.completeNow = true
  createForm.createTask = true
  createForm.contactTarget = ''
  createForm.followupMethod = ''
  createForm.guidance = prefill?.guidance || ''
  createForm.suggestPlanAdjust = false
  resetSection(createForm)
  createOpen.value = true
}

function consumeAgentDraft() {
  const id = peopleId()
  if (!id) return
  const draft = takeAgentDraft(id, 'followups')
  if (!draft) return
  openCreate({ guidance: draft.draftContent || '' })
}

watch(
  () => createForm.followupType,
  () => resetSection(createForm),
)

async function submitCreate() {
  if (!createForm.followupType) {
    ElMessage.warning('请选择随访类型')
    return
  }
  if (createForm.completeNow) {
    if (!createForm.contactTarget || !createForm.followupMethod) {
      ElMessage.warning('请完善沟通对象与随访方式')
      return
    }
    const err = validateFollowupSection(createForm.followupType, createForm.section)
    if (err) {
      ElMessage.warning(err)
      return
    }
  }
  submitting.value = true
  try {
    if (createForm.completeNow && createForm.followupType === 'ONBOARDING') {
      await applyOnboardingArchive(createArchiveRef.value, createForm.section)
    }
    await api('/api/b/v1/followups', {
      method: 'POST',
      body: JSON.stringify({
        peopleId: peopleId(),
        followupType: createForm.followupType,
        completeNow: createForm.completeNow,
        createTask: createForm.completeNow ? false : createForm.createTask,
        content: createForm.completeNow
          ? buildFollowupContentPayload(
              createForm.followupType,
              {
                contactTarget: createForm.contactTarget,
                followupMethod: createForm.followupMethod,
                guidance: createForm.guidance,
                suggestPlanAdjust: createForm.suggestPlanAdjust,
              },
              createForm.section,
            )
          : { followupType: createForm.followupType },
      }),
    })
    ElMessage.success(
      planAdjustSuccessMessage(
        createForm.completeNow ? '已记录随访' : '已创建未完成随访',
        createForm.completeNow && createForm.suggestPlanAdjust,
      ),
    )
    createOpen.value = false
    await load()
    notifyCockpitTasksPossiblyChanged(peopleId())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    submitting.value = false
  }
}

/** 新建弹窗「保存」：落 OPEN 草稿 + 工作台待办，不办结 */
async function saveCreateDraft() {
  if (!createForm.followupType) {
    ElMessage.warning('请选择随访类型')
    return
  }
  if (!createForm.completeNow) {
    ElMessage.info('「先建待办」请直接点提交；保存草稿用于「当场处理」中途暂存')
    return
  }
  submitting.value = true
  try {
    await api('/api/b/v1/followups', {
      method: 'POST',
      body: JSON.stringify({
        peopleId: peopleId(),
        followupType: createForm.followupType,
        completeNow: false,
        createTask: true,
        content: buildFollowupContentPayload(
          createForm.followupType,
          {
            contactTarget: createForm.contactTarget,
            followupMethod: createForm.followupMethod,
            guidance: createForm.guidance,
            suggestPlanAdjust: createForm.suggestPlanAdjust,
          },
          createForm.section,
        ),
      }),
    })
    ElMessage.success('已保存为未完成随访，可稍后在列表中继续处理')
    createOpen.value = false
    await load()
    notifyCockpitTasksPossiblyChanged(peopleId())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

function openDetail(row: FollowupItem) {
  followupDetailId.value = row.id
}

const hydratingComplete = ref(false)

async function openComplete(row: FollowupItem) {
  hydratingComplete.value = true
  completingId.value = row.id
  completeForm.followupType = row.followupType || 'ROUTINE'
  completeForm.contactTarget = ''
  completeForm.followupMethod = ''
  completeForm.guidance = ''
  completeForm.suggestPlanAdjust = false
  resetSection(completeForm)
  try {
    const res = await api<{ data: { content?: Record<string, unknown> } }>(
      `/api/b/v1/followups/${row.id}`,
    )
    if (res.data?.content) {
      const h = hydrateFollowupFormFromContent(res.data.content)
      completeForm.followupType = h.followupType
      completeForm.contactTarget = h.contactTarget
      completeForm.followupMethod = h.followupMethod
      completeForm.guidance = h.guidance
      completeForm.suggestPlanAdjust = h.suggestPlanAdjust
      completeForm.section = h.section
    }
  } catch {
    /* keep defaults */
  } finally {
    hydratingComplete.value = false
  }
}

watch(
  () => completeForm.followupType,
  () => {
    if (completingId.value && !hydratingComplete.value) resetSection(completeForm)
  },
)

async function saveCompleteDraft() {
  if (!completingId.value) return
  if (!completeForm.followupType) {
    ElMessage.warning('请选择随访类型')
    return
  }
  submitting.value = true
  try {
    await api(`/api/b/v1/followups/${completingId.value}/draft`, {
      method: 'POST',
      body: JSON.stringify(
        buildFollowupContentPayload(
          completeForm.followupType,
          {
            contactTarget: completeForm.contactTarget,
            followupMethod: completeForm.followupMethod,
            guidance: completeForm.guidance,
            suggestPlanAdjust: completeForm.suggestPlanAdjust,
          },
          completeForm.section,
        ),
      ),
    })
    ElMessage.success('已保存草稿，随访仍为未完成')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function submitComplete() {
  if (!completingId.value) return
  if (!completeForm.contactTarget || !completeForm.followupMethod) {
    ElMessage.warning('请完善沟通对象与随访方式')
    return
  }
  const err = validateFollowupSection(completeForm.followupType, completeForm.section)
  if (err) {
    ElMessage.warning(err)
    return
  }
  submitting.value = true
  try {
    if (completeForm.followupType === 'ONBOARDING') {
      await applyOnboardingArchive(completeArchiveRef.value, completeForm.section)
    }
    await api(`/api/b/v1/followups/${completingId.value}/complete`, {
      method: 'POST',
      body: JSON.stringify(
        buildFollowupContentPayload(
          completeForm.followupType,
          {
            contactTarget: completeForm.contactTarget,
            followupMethod: completeForm.followupMethod,
            guidance: completeForm.guidance,
            suggestPlanAdjust: completeForm.suggestPlanAdjust,
          },
          completeForm.section,
        ),
      ),
    })
    ElMessage.success(planAdjustSuccessMessage('已处理', completeForm.suggestPlanAdjust))
    completingId.value = null
    await load()
    notifyCockpitTasksPossiblyChanged(peopleId())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败')
  } finally {
    submitting.value = false
  }
}

async function cancelRow(row: FollowupItem) {
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', '取消随访', {
      confirmButtonText: '确定',
      cancelButtonText: '返回',
      inputPattern: /\S+/,
      inputErrorMessage: '请填写取消原因',
    })
    await api(`/api/b/v1/followups/${row.id}/cancel`, {
      method: 'POST',
      body: JSON.stringify({ reason: value }),
    })
    ElMessage.success('已取消')
    await load()
    notifyCockpitTasksPossiblyChanged(peopleId())
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

watch(
  () => props.peopleId || route.params.peopleId,
  () => void load(),
)

onMounted(async () => {
  await load()
  consumeAgentDraft()
})
</script>

<template>
  <div class="panel" v-loading="loading">
    <div class="head">
      <div>
        <h3>随访记录</h3>
        <p class="hint">含定期随访、指标异常处理、打卡跟进；新建按随访类型填写不同表单</p>
      </div>
      <el-button type="primary" @click="openCreate">新建随访</el-button>
    </div>

    <el-timeline v-if="items.length">
      <el-timeline-item
        v-for="row in items"
        :key="row.id"
        :timestamp="formatTime(row.completedAt || row.plannedAt || row.gmtCreated)"
        placement="top"
        :type="row.status === 'DONE' ? 'success' : row.status === 'OPEN' ? 'warning' : 'info'"
      >
        <div class="card">
          <div class="card-top">
            <strong>{{ row.followupTypeLabel || formatFollowupType(row.followupType) || row.recordTypeLabel || formatFollowupRecordType(row.recordType) }}</strong>
            <el-tag size="small" effect="plain">{{ formatFollowupStatus(row.status) }}</el-tag>
          </div>
          <p class="summary">{{ row.summary || row.title || '-' }}</p>
          <div class="meta">
            <span v-if="row.contactChannel">{{ formatFollowupMethod(row.contactChannel) }}</span>
            <span v-if="row.completedByName">{{ row.completedByName }}</span>
          </div>
          <div class="actions">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'OPEN' && row.recordType === 'PERIODIC'"
              link
              type="primary"
              @click="openComplete(row)"
            >
              处理
            </el-button>
            <el-button
              v-if="row.status === 'OPEN' && row.recordType === 'PERIODIC'"
              link
              type="danger"
              @click="cancelRow(row)"
            >
              取消
            </el-button>
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="暂无随访记录" />

    <el-dialog
      v-model="createOpen"
      title="新建随访"
      :width="createDialogWide ? '920px' : '680px'"
      destroy-on-close
      align-center
      class="followup-dialog"
    >
      <el-form label-position="top">
        <el-form-item label="随访类型" required>
          <el-select v-model="createForm.followupType" style="width: 100%">
            <el-option
              v-for="o in FOLLOWUP_TYPE_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="记录方式">
          <el-radio-group v-model="createForm.completeNow">
            <el-radio :value="true">当场处理</el-radio>
            <el-radio :value="false">先建待办</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!createForm.completeNow" label="同步工作台待办">
          <el-switch v-model="createForm.createTask" />
        </el-form-item>
        <template v-if="createForm.completeNow">
          <div class="form-row">
            <el-form-item label="沟通对象" required class="form-col">
              <el-select v-model="createForm.contactTarget" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="o in FOLLOWUP_CONTACT_TARGET_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="随访方式" required class="form-col">
              <el-select v-model="createForm.followupMethod" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="o in FOLLOWUP_METHOD_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
            </el-form-item>
          </div>
          <div v-if="createForm.followupType === 'ONBOARDING'" class="archive-block">
            <div class="archive-block-title">患者档案（提交时写回）</div>
            <PatientArchiveView
              ref="createArchiveRef"
              :people-id="peopleId()"
              embedded
            />
          </div>
          <FollowupSectionFields
            v-model="createForm.section"
            :followup-type="createForm.followupType"
          />
          <el-form-item label="指导建议">
            <el-input
              v-model="createForm.guidance"
              type="textarea"
              :rows="2"
              maxlength="400"
              show-word-limit
              placeholder="选填"
            />
          </el-form-item>
          <el-form-item v-if="createForm.followupType === 'ROUTINE'" label="建议调整方案">
            <div class="switch-line">
              <el-switch v-model="createForm.suggestPlanAdjust" />
              <span class="switch-hint">开启后同步工作台「制定方案」待办</span>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button
          v-if="createForm.completeNow"
          :loading="submitting"
          @click="saveCreateDraft"
        >
          保存
        </el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!completingId"
      title="处理随访"
      :width="completeDialogWide ? '920px' : '720px'"
      destroy-on-close
      align-center
      class="followup-dialog followup-process-dialog"
      @update:model-value="(v: boolean) => { if (!v) completingId = null }"
    >
      <el-form label-position="top" class="process-form">
        <div class="form-block">
          <div class="form-block-title">沟通信息</div>
          <el-form-item label="随访类型" required>
            <el-select v-model="completeForm.followupType" style="width: 100%">
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
              <el-select v-model="completeForm.contactTarget" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="o in FOLLOWUP_CONTACT_TARGET_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="随访方式" required class="form-col">
              <el-select v-model="completeForm.followupMethod" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="o in FOLLOWUP_METHOD_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
            </el-form-item>
          </div>
        </div>

        <div v-if="completeForm.followupType === 'ONBOARDING'" class="archive-block">
          <div class="archive-block-title">患者档案（提交时写回）</div>
          <PatientArchiveView
            ref="completeArchiveRef"
            :people-id="peopleId()"
            embedded
          />
        </div>

        <div class="form-block">
          <div class="form-block-title">随访内容</div>
          <FollowupSectionFields
            v-model="completeForm.section"
            :followup-type="completeForm.followupType"
          />
        </div>

        <div class="form-block form-block--last">
          <div class="form-block-title">后续建议</div>
          <el-form-item label="指导建议">
            <el-input
              v-model="completeForm.guidance"
              type="textarea"
              :rows="2"
              maxlength="400"
              show-word-limit
              resize="none"
              placeholder="选填，可写用药、生活方式或复诊建议"
            />
          </el-form-item>
          <el-form-item v-if="completeForm.followupType === 'ROUTINE'" label="建议调整方案">
            <div class="switch-line">
              <el-switch v-model="completeForm.suggestPlanAdjust" />
              <span class="switch-hint">开启后同步工作台「制定方案」待办</span>
            </div>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="completingId = null">取消</el-button>
        <el-button :loading="submitting" @click="saveCompleteDraft">保存</el-button>
        <el-button type="primary" :loading="submitting" @click="submitComplete">提交处理</el-button>
      </template>
    </el-dialog>

    <FollowupRecordDetailDialog
      :followup-id="followupDetailId"
      @closed="followupDetailId = null"
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
}
.hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.card {
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
}
.card-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.summary {
  margin: 8px 0 4px;
  font-size: 13px;
  line-height: 1.5;
}
.meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.actions {
  margin-top: 6px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.archive-block {
  margin: 8px 0 16px;
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
.form-col {
  margin-bottom: 14px;
}
.form-block {
  margin-bottom: 16px;
  padding: 12px 14px 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.form-block--last {
  margin-bottom: 0;
}
.form-block-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.process-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.process-form :deep(.el-form-item__label) {
  margin-bottom: 4px;
  line-height: 1.3;
  color: var(--el-text-color-regular);
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
</style>
