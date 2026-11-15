<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import FollowupSectionFields from './FollowupSectionFields.vue'
import FollowupRecordDetailDialog from './FollowupRecordDetailDialog.vue'
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
const router = useRouter()
const props = defineProps<{
  /** 驾驶舱抽屉等场景传入；不传则走路由 params */
  peopleId?: string
}>()
const emit = defineEmits<{
  'open-intake': [opts: { followupRecordId?: string; purpose?: string }]
}>()
const peopleId = () => String(props.peopleId || route.params.peopleId || '')
const loading = ref(false)

/** 驾驶舱抽屉内 emit；患者页同标签跳转问卷 */
function goIntake(row: FollowupItem) {
  const opts = { followupRecordId: row.id }
  emit('open-intake', opts)
  if (props.peopleId) return
  void router.push({
    path: `/workspace/patients/${peopleId()}/intake`,
    query: { followupRecordId: row.id },
  })
}
const items = ref<FollowupItem[]>([])
const createOpen = ref(false)
const followupDetailId = ref<string | null>(null)
const completingId = ref<string | null>(null)
const submitting = ref(false)

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

/** 勾选「建议调整方案」后同步制定方案待办（不强制跳转） */
function planAdjustSuccessMessage(base: string, suggested: boolean) {
  if (!suggested) return base
  return `${base}，已同步「制定方案」待办`
}

function formatTime(v?: string) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

function cardTitle(row: FollowupItem) {
  return (
    row.followupTypeLabel ||
    formatFollowupType(row.followupType) ||
    row.title ||
    row.recordTypeLabel ||
    formatFollowupRecordType(row.recordType) ||
    '随访'
  )
}

/** 电话 / 面对面等：标题旁小图标 */
function methodIcon(channel?: string) {
  switch (channel) {
    case 'PHONE':
      return '📞'
    case 'SMS':
      return '📱'
    case 'FACE_TO_FACE':
    case 'CLINIC':
    case 'HOME':
      return '💬'
    case 'ONLINE':
    case 'WECHAT':
      return '💬'
    default:
      return '📋'
  }
}

/** 状态类提示（尚未完成等）：浅灰辅文，不进正文摘要 */
function cardStatusHint(row: FollowupItem) {
  const title = cardTitle(row)
  const raw = String(row.summary || '').trim()
  if (!raw) return ''
  if (raw === `${title}（未完成）` || raw === `${title}(未完成)` || raw === '尚未完成') {
    return '尚未完成'
  }
  if (raw === `${title}（定期排期）` || raw === '定期排期') return '定期排期'
  return ''
}

/**
 * 沟通摘要：去掉与标题重复的文案；状态提示不算摘要。
 */
function cardSummary(row: FollowupItem) {
  if (cardStatusHint(row)) return ''
  const title = cardTitle(row)
  const raw = String(row.summary || '').trim()
  if (!raw) return ''
  if (raw === title) return ''
  if (raw.startsWith(`${title} · `)) return raw.slice(title.length + 3).trim()
  if (raw.startsWith(`${title}·`)) return raw.slice(title.length + 1).trim()
  if (raw.replace(/\s/g, '') === title.replace(/\s/g, '')) return ''
  return raw
}

function statusTone(status?: string) {
  if (status === 'DONE') return 'done'
  if (status === 'OPEN') return 'open'
  if (status === 'CANCELLED') return 'cancelled'
  return 'default'
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
  (t) => {
    resetSection(createForm)
    // 首诊只能先建待办，再在问卷工作台完成
    if (t === 'ONBOARDING') {
      createForm.completeNow = false
      createForm.createTask = true
    }
  },
)

async function submitCreate() {
  if (!createForm.followupType) {
    ElMessage.warning('请选择随访类型')
    return
  }
  // 首诊：只建 OPEN 随访，随后打开问卷工作台
  if (createForm.followupType === 'ONBOARDING') {
    submitting.value = true
    try {
      const res = await api<{ data: { id: string } }>('/api/b/v1/followups', {
        method: 'POST',
        body: JSON.stringify({
          peopleId: peopleId(),
          followupType: 'ONBOARDING',
          completeNow: false,
          createTask: true,
          content: { followupType: 'ONBOARDING' },
        }),
      })
      createOpen.value = false
      await load()
      notifyCockpitTasksPossiblyChanged(peopleId())
      const id = res.data?.id
      if (id) {
        emit('open-intake', { followupRecordId: id })
        if (!props.peopleId) {
          void router.push({
            path: `/workspace/patients/${peopleId()}/intake`,
            query: { followupRecordId: id },
          })
        }
        ElMessage.success('已打开首诊工作台')
      } else {
        ElMessage.success('已创建首诊待办，请点「开始首诊」')
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      submitting.value = false
    }
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
  if (row.followupType === 'ONBOARDING') {
    goIntake(row)
    return
  }
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
  if (completeForm.followupType === 'ONBOARDING') {
    ElMessage.warning('首诊请在问卷工作台提交完成')
    return
  }
  submitting.value = true
  try {
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

    <el-timeline v-if="items.length" class="fu-timeline">
      <el-timeline-item
        v-for="row in items"
        :key="row.id"
        :timestamp="formatTime(row.completedAt || row.plannedAt || row.gmtCreated)"
        placement="top"
        color="#D1D5DB"
      >
        <div
          class="card"
          role="button"
          tabindex="0"
          @click="openDetail(row)"
          @keydown.enter.prevent="openDetail(row)"
          @keydown.space.prevent="openDetail(row)"
        >
          <div class="card-main">
            <div class="card-title-row">
              <span class="method-ico" :title="formatFollowupMethod(row.contactChannel) || '随访'">
                {{ methodIcon(row.contactChannel) }}
              </span>
              <strong class="card-title">{{ cardTitle(row) }}</strong>
              <span v-if="cardStatusHint(row)" class="status-hint">{{ cardStatusHint(row) }}</span>
            </div>
            <div class="meta">
              <span v-if="row.completedByName">{{ row.completedByName }}</span>
              <span v-if="row.contactChannel">{{ formatFollowupMethod(row.contactChannel) }}</span>
            </div>
            <p v-if="cardSummary(row)" class="summary-text">{{ cardSummary(row) }}</p>
            <div
              v-if="
                row.status === 'OPEN' &&
                (row.followupType === 'ONBOARDING' || row.recordType === 'PERIODIC')
              "
              class="actions"
              @click.stop
            >
              <el-button
                v-if="row.followupType === 'ONBOARDING'"
                link
                type="primary"
                @click="goIntake(row)"
              >
                开始首诊
              </el-button>
              <el-button
                v-if="row.recordType === 'PERIODIC' && row.followupType !== 'ONBOARDING'"
                link
                type="primary"
                @click="openComplete(row)"
              >
                处理
              </el-button>
              <el-button
                v-if="row.recordType === 'PERIODIC'"
                link
                type="danger"
                @click="cancelRow(row)"
              >
                取消
              </el-button>
            </div>
          </div>
          <div class="card-aside">
            <span class="status-pill" :data-tone="statusTone(row.status)">
              {{ formatFollowupStatus(row.status) }}
            </span>
            <button type="button" class="detail-link" @click.stop="openDetail(row)">
              详情<span class="detail-chevron">›</span>
            </button>
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="暂无随访记录" />

    <el-dialog
      v-model="createOpen"
      title="新建随访"
      width="680px"
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
        <el-alert
          v-if="createForm.followupType === 'ONBOARDING'"
          type="info"
          :closable="false"
          show-icon
          title="入组/首诊在全屏工作台填写问卷完成，提交后将打开首诊页"
          class="onboarding-tip"
        />
        <template v-if="createForm.followupType !== 'ONBOARDING'">
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
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button
          v-if="createForm.completeNow && createForm.followupType !== 'ONBOARDING'"
          :loading="submitting"
          @click="saveCreateDraft"
        >
          保存
        </el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">
          {{ createForm.followupType === 'ONBOARDING' ? '创建并开始首诊' : '提交' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!completingId"
      title="处理随访"
      width="720px"
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
                v-for="o in FOLLOWUP_TYPE_OPTIONS.filter((x) => x.value !== 'ONBOARDING')"
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
.fu-timeline {
  width: 100%;
  padding-left: 2px;
}
.fu-timeline :deep(.el-timeline-item__wrapper) {
  padding-left: 22px;
}
.fu-timeline :deep(.el-timeline-item__tail) {
  border-left: 1px dashed #E5E7EB;
}
.fu-timeline :deep(.el-timeline-item__node) {
  width: 10px;
  height: 10px;
  left: 0;
  background-color: #E5E7EB !important;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #D1D5DB;
  box-sizing: content-box;
}
.fu-timeline :deep(.el-timeline-item:first-child .el-timeline-item__node) {
  background-color: var(--el-color-primary) !important;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--el-color-primary) 35%, #fff);
}
.fu-timeline :deep(.el-timeline-item__timestamp) {
  font-size: 13px;
  font-weight: 600;
  color: #4B5563;
  padding-top: 0;
}
.fu-timeline :deep(.el-timeline-item__timestamp.is-top) {
  margin-bottom: 6px;
}

.card {
  width: 100%;
  box-sizing: border-box;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.card:hover {
  border-color: #cbd5e1;
  background: #fafbfc;
}
.card:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  outline-offset: 2px;
}
.card-main {
  flex: 1;
  min-width: 0;
}
.card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.method-ico {
  flex-shrink: 0;
  font-size: 15px;
  line-height: 1;
}
.card-title {
  flex-shrink: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-hint {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 400;
  color: #9ca3af;
  line-height: 1.35;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.4;
  min-height: 17px;
}
.meta > span + span::before {
  content: '·';
  margin-right: 10px;
  color: #d1d5db;
}
.summary-text {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #4b5563;
  word-break: break-word;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}
.card-aside {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
  align-self: center;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  background: #f3f4f6;
  color: #6b7280;
}
.status-pill[data-tone='done'] {
  background: #ecfdf5;
  color: #059669;
}
.status-pill[data-tone='open'] {
  background: #fff7ed;
  color: #c2410c;
}
.status-pill[data-tone='cancelled'] {
  background: #f3f4f6;
  color: #9ca3af;
}
.detail-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 13px;
  color: #9ca3af;
  cursor: pointer;
  line-height: 1.4;
  white-space: nowrap;
}
.detail-link:hover {
  color: var(--el-color-primary);
}
.detail-chevron {
  font-size: 15px;
  line-height: 1;
  margin-left: 1px;
}
.actions {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.onboarding-tip {
  margin-bottom: 12px;
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
