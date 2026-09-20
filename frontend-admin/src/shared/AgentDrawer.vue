<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from './http'
import { AGENT_FULL_NAME, AGENT_WELCOME } from './agent-brand'
import { postSse } from './agent-stream'
import {
  appendProgress,
  appendSkill,
  appendThinking,
  appendTool,
  finishAllActivity,
  type ActivityItem,
} from './agent-activity'
import AgentActivityPanel from './AgentActivityPanel.vue'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from './CarePlanPreviewDialog.vue'
import { saveLabOcrDraft, type LabOcrDraft } from './lab-ocr'
import { saveExamOcrDraft, type ExamOcrDraft } from './exam-ocr'
import { setAgentDraft } from './agent-draft-bus'
import { formatAssistantPlainHtml } from './agent-plain-text'

export interface AgentAction {
  type: string
  label: string
  path?: string
  peopleId?: string
  payload?: Record<string, unknown>
}

export interface AgentChatResponse {
  sessionId: string
  capability: string
  intent: string
  reply: string
  actions: AgentAction[]
  safetyFlags: Record<string, unknown>[]
  extracted?: unknown
}

export interface AgentCapability {
  code: string
  label: string
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  streamContent?: string
  activity?: ActivityItem[]
  elapsedMs?: number | null
  actions?: AgentAction[]
  streaming?: boolean
  isCarePlan?: boolean
}

const props = defineProps<{
  peopleId: string
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'care-plan-updated': []
}>()

const router = useRouter()
const sessionId = ref<string | null>(null)
const sessionStatus = ref<string>('ACTIVE')
const input = ref('')
const sending = ref(false)
const messages = ref<ChatMessage[]>([])
const capabilities = ref<AgentCapability[]>([])
const chatBodyRef = ref<HTMLElement | null>(null)
const previewVisible = ref(false)
const previewData = ref<CarePlanPreviewData | null>(null)
const ocrFileRef = ref<HTMLInputElement | null>(null)
const pendingOcrCapability = ref<'OCR_LAB' | 'OCR_EXAM' | null>(null)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyBusy = ref(false)
const historyRows = ref<SessionSummaryRow[]>([])

interface SessionSummaryRow {
  sessionId: string
  title: string
  peopleId?: string
  status: string
  preview?: string
  messageCount: number
  gmtCreated?: string
  gmtModified?: string
}

type SessionBundlePayload = {
  sessionId: string
  status?: string
  messages?: Array<{
    id: string
    role: string
    content: string
    actions?: AgentAction[]
    createdAt?: string
  }>
}

const drawerVisible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})

const isArchivedSession = computed(
  () => (sessionStatus.value || '').toUpperCase() === 'CLOSED',
)

async function loadCapabilities() {
  try {
    const res = await api<{ data: AgentCapability[] }>('/api/b/v1/agent/capabilities')
    capabilities.value = res.data
  } catch {
    capabilities.value = [
      { code: 'CARE_PLAN', label: '制定管理方案' },
      { code: 'OCR_LAB', label: '检验单识别' },
      { code: 'OCR_EXAM', label: '检查单识别' },
      { code: 'GENERAL_CHAT', label: '健康咨询' },
    ]
  }
}

function applyAgentResult(row: ChatMessage, payload: unknown) {
  const data = payload as AgentChatResponse
  const streamed = (row.streamContent || '').trim()
  if (!data?.reply && !streamed) return
  // 通用对话优先用后端 sanitize 后的 reply；管理方案等长文仍保留流式正文
  if (data.capability === 'CARE_PLAN') {
    row.content = streamed || data.reply || ''
  } else {
    row.content = (data.reply && data.reply.trim()) || streamed || ''
  }
  row.streamContent = ''
  row.actions =
    data.capability === 'CARE_PLAN'
      ? [...(data.actions || []), { type: 'PREVIEW', label: '预览方案详情' }]
      : data.actions
  row.isCarePlan = data.capability === 'CARE_PLAN'
  row.streaming = false
  finishAllActivity(row.activity)
  if (data.sessionId) {
    sessionId.value = data.sessionId
    sessionStatus.value = 'ACTIVE'
  }
  if (data.capability === 'CARE_PLAN') {
    emit('care-plan-updated')
  }
  if (data.capability === 'OCR_LAB' && data.extracted) {
    stashLabOcrDraft(data.extracted)
  }
  if (data.capability === 'OCR_EXAM' && data.extracted) {
    stashExamOcrDraft(data.extracted)
  }
}

function stashLabOcrDraft(extracted: unknown) {
  const raw = extracted as LabOcrDraft
  if (!raw?.items?.length) return
  saveLabOcrDraft({
    specimenType: raw.specimenType,
    sampledAt: typeof raw.sampledAt === 'string' ? raw.sampledAt : undefined,
    reportedAt: typeof raw.reportedAt === 'string' ? raw.reportedAt : undefined,
    note: raw.note,
    items: raw.items,
    ignoredItems: raw.ignoredItems,
    warnings: raw.warnings,
  })
}

function stashExamOcrDraft(extracted: unknown) {
  const raw = extracted as ExamOcrDraft
  if (!raw) return
  const hasFindings = raw.findings && Object.keys(raw.findings).length > 0
  if (!raw.examType && !raw.conclusion && !hasFindings) return
  saveExamOcrDraft({
    examType: raw.examType,
    examTypeName: raw.examTypeName,
    examinedAt: typeof raw.examinedAt === 'string' ? raw.examinedAt : raw.examinedAt ? String(raw.examinedAt) : undefined,
    conclusion: raw.conclusion,
    findings: raw.findings,
    ignoredFindings: raw.ignoredFindings,
    warnings: raw.warnings,
  })
}

async function sendMessage(
  message: string,
  capabilityHint?: string,
  image?: { base64: string; mimeType: string; name?: string } | null,
) {
  if (!message.trim() && !image) return
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  const userText =
    message.trim() || (capabilityHint === 'OCR_EXAM' ? '请识别这张检查单' : '请识别这张检验单')
  const isCarePlan = capabilityHint === 'CARE_PLAN'
  messages.value.push({
    role: 'user',
    content: image ? `${userText}\n[已附图片${image.name ? `：${image.name}` : ''}]` : userText,
  })
  input.value = ''
  sending.value = true

  const assistantIdx = messages.value.length
  messages.value.push({
    role: 'assistant',
    content: '',
    streamContent: '',
    activity: [],
    elapsedMs: null,
    streaming: true,
    isCarePlan,
  })
  await scrollToBottom()

  try {
    await postSse(
      '/api/b/v1/agent/chat/stream',
      {
        peopleId: props.peopleId,
        sessionId: sessionId.value,
        message: userText,
        capabilityHint: capabilityHint || null,
        imageBase64: image?.base64 || null,
        imageMimeType: image?.mimeType || null,
      },
      {
        onProgress: (msg) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          if (!row.activity) row.activity = []
          appendProgress(row.activity, msg)
          void scrollToBottom()
        },
        onTool: (event) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          if (!row.activity) row.activity = []
          appendTool(row.activity, event)
          void scrollToBottom()
        },
        onSkill: (event) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          if (!row.activity) row.activity = []
          appendSkill(row.activity, event)
          void scrollToBottom()
        },
        onThinking: (event) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          if (!row.activity) row.activity = []
          appendThinking(row.activity, event)
          void scrollToBottom()
        },
        onToken: (token) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          row.streamContent = (row.streamContent || '') + token
          void scrollToBottom()
        },
        onResult: (payload) => {
          const row = messages.value[assistantIdx]
          if (row) applyAgentResult(row, payload)
          void scrollToBottom()
        },
        onDone: (meta) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          row.streaming = false
          finishAllActivity(row.activity)
          if (meta?.elapsedMs != null) row.elapsedMs = meta.elapsedMs
        },
        onError: (msg) => ElMessage.error(msg),
      },
    )
  } catch (e) {
    messages.value.splice(assistantIdx, 1)
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
    const row = messages.value[assistantIdx]
    if (row) {
      row.streaming = false
      finishAllActivity(row.activity)
    }
    await scrollToBottom()
  }
}

function toggleActivity(msg: ChatMessage, id: string) {
  const item = msg.activity?.find((x) => x.id === id)
  if (item) item.collapsed = !item.collapsed
}

function quickAction(code: string, label: string) {
  if (code === 'OCR_LAB' || code === 'OCR_EXAM') {
    pendingOcrCapability.value = code
    ocrFileRef.value?.click()
    return
  }
  const prompts: Record<string, string> = {
    CARE_PLAN: '请为这位患者生成管理方案草稿',
    GENERAL_CHAT: '请根据患者档案给出健康管理建议',
  }
  void sendMessage(prompts[code] || label, code)
}

async function onOcrFileChange(e: Event) {
  const inputEl = e.target as HTMLInputElement
  const file = inputEl.files?.[0]
  const cap = pendingOcrCapability.value
  inputEl.value = ''
  pendingOcrCapability.value = null
  if (!file || !cap) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  const mime = file.type || 'image/jpeg'
  if (!['image/jpeg', 'image/jpg', 'image/png', 'image/webp'].includes(mime)) {
    ElMessage.warning('仅支持 JPG、PNG、WEBP 图片')
    return
  }
  try {
    const base64 = await readFileAsBase64(file)
    const prompt = cap === 'OCR_EXAM' ? '请识别这张检查单' : '请识别这张检验单'
    await sendMessage(prompt, cap, { base64, mimeType: mime, name: file.name })
  } catch {
    ElMessage.error('读取图片失败')
  }
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result || '')
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

function runAction(action: AgentAction) {
  if (action.type === 'PREVIEW') {
    void openCarePlanPreview()
    return
  }
  if (action.type === 'REFRESH' || action.type === 'FOCUS_PATIENT') {
    return
  }
  if (action.type === 'CALL_API') {
    void executeCallApi(action)
    return
  }
  if (action.type === 'OPEN_SHEET' && action.path) {
    const peopleId = action.peopleId || props.peopleId
    if (action.payload?.draftContent || action.payload?.openCreate) {
      setAgentDraft({
        peopleId,
        mode: action.path,
        draftContent: typeof action.payload.draftContent === 'string' ? action.payload.draftContent : undefined,
        openCreate: !!action.payload.openCreate,
      })
    }
    const target = sheetModeToPath(peopleId, action.path)
    drawerVisible.value = false
    router.push(target)
    return
  }
  if (action.path) {
    drawerVisible.value = false
    let path = action.path
    if (path.includes('/observations/labs') && !path.includes('ocr=')) {
      path = `${path}${path.includes('?') ? '&' : '?'}ocr=1`
    }
    if (path.includes('/observations/exams') && !path.includes('ocr=')) {
      path = `${path}${path.includes('?') ? '&' : '?'}ocr=1`
    }
    router.push(path)
  }
}

async function executeCallApi(action: AgentAction) {
  const peopleId = action.peopleId || props.peopleId
  if (!peopleId) return
  const apiKey = action.path || ''
  try {
    let receipt = ''
    if (apiKey === 'NUDGE') {
      const res = await api<{ data: { sent?: boolean; message?: string; reason?: string } }>(
        `/api/b/v1/adherence/patients/${peopleId}/nudge`,
        { method: 'POST' },
      )
      receipt = res.data?.message || (res.data?.sent ? '已发送站内提醒' : '提醒未发送')
      if (res.data?.reason === 'NO_LINKED_ACCOUNT') {
        receipt = '患者未激活 C 端账号，无法站内提醒'
      }
    } else if (apiKey === 'CREATE_FOLLOWUP') {
      const followupType =
        typeof action.payload?.followupType === 'string' ? action.payload.followupType : 'PERIODIC'
      await api('/api/b/v1/followups', {
        method: 'POST',
        body: JSON.stringify({
          peopleId,
          followupType,
          createTask: action.payload?.createTask !== false,
          completeNow: false,
        }),
      })
      receipt = '已创建随访待办'
    } else {
      ElMessage.warning('暂不支持该动作')
      return
    }
    messages.value.push({
      role: 'assistant',
      content: `回执：${receipt}`,
    })
    ElMessage.success(receipt)
    await scrollToBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '动作执行失败')
  }
}

function sheetModeToPath(peopleId: string, mode: string): string {
  const base = `/workspace/patients/${peopleId}`
  switch (mode) {
    case 'archive':
      return `${base}/archive`
    case 'followups':
      return `${base}/followups`
    case 'care-plan':
      return `${base}/care-plan`
    case 'reports':
      return `${base}/health-reports`
    case 'observations':
      return `${base}/observations/metrics`
    case 'care-chat':
      return `${base}/chat`
    case 'assessments':
      return `${base}/assessments`
    default:
      return base
  }
}

async function openCarePlanPreview() {
  try {
    const res = await api<{ data: Record<string, unknown> }>(`/api/b/v1/patients/${props.peopleId}/care-plan`)
    const b = res.data as {
      plan?: { title?: string; goalSummary?: string }
      draft?: {
        source?: string
        exercise?: CarePlanPreviewData['exercise']
        diet?: CarePlanPreviewData['diet']
        execution?: CarePlanPreviewData['execution']
      }
      activeVersion?: {
        source?: string
        versionNo?: number
        exercise?: CarePlanPreviewData['exercise']
        diet?: CarePlanPreviewData['diet']
        execution?: CarePlanPreviewData['execution']
        publishedAt?: string
      }
    }
    const src = b.draft || b.activeVersion
    previewData.value = {
      title: b.plan?.title,
      goalSummary: b.plan?.goalSummary,
      source: b.draft?.source || b.activeVersion?.source,
      versionLabel: b.draft ? '草稿' : `v${b.activeVersion?.versionNo || ''}`,
      status: b.draft ? 'DRAFT' : 'ACTIVE',
      exercise: src?.exercise,
      diet: src?.diet,
      execution: src?.execution,
      publishedAt: b.activeVersion?.publishedAt,
    }
    previewVisible.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载方案预览失败')
  }
}

async function scrollToBottom() {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

function applySessionBundle(bundle: SessionBundlePayload) {
  sessionId.value = bundle.sessionId
  sessionStatus.value = bundle.status || 'ACTIVE'
  messages.value = (bundle.messages || []).map((m) => {
    const r = (m.role || '').toLowerCase()
    return {
      role: (r === 'user' ? 'user' : 'assistant') as ChatMessage['role'],
      content: m.content,
      actions: m.actions?.length ? m.actions : undefined,
    }
  })
}

function formatSessionTime(iso?: string) {
  if (!iso) return ''
  const s = String(iso).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

function sessionStatusLabel(status?: string) {
  return (status || '').toUpperCase() === 'ACTIVE' ? '进行中' : '已归档'
}

async function restoreDrawerSession() {
  if (!props.peopleId) return
  try {
    const res = await api<{ data: SessionBundlePayload }>(
      `/api/b/v1/agent/sessions/current?peopleId=${encodeURIComponent(props.peopleId)}`,
    )
    applySessionBundle(res.data)
    if (!messages.value.length) {
      messages.value.push({ role: 'assistant', content: AGENT_WELCOME })
    }
    await scrollToBottom()
  } catch {
    if (!messages.value.length) {
      messages.value.push({ role: 'assistant', content: AGENT_WELCOME })
    }
  }
}

async function openHistory() {
  historyOpen.value = true
  await loadHistoryList()
}

async function loadHistoryList() {
  if (!props.peopleId) return
  historyLoading.value = true
  try {
    const res = await api<{ data: SessionSummaryRow[] }>(
      `/api/b/v1/agent/sessions?peopleId=${encodeURIComponent(props.peopleId)}&limit=30`,
    )
    historyRows.value = res.data || []
  } catch (e) {
    historyRows.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载历史会话失败')
  } finally {
    historyLoading.value = false
  }
}

async function openHistorySession(row: SessionSummaryRow) {
  if (historyBusy.value || sending.value) return
  historyBusy.value = true
  try {
    const res = await api<{ data: SessionBundlePayload }>(
      `/api/b/v1/agent/sessions/${encodeURIComponent(row.sessionId)}`,
    )
    applySessionBundle(res.data)
    historyOpen.value = false
    await scrollToBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开会话失败')
  } finally {
    historyBusy.value = false
  }
}

async function startNewChat() {
  if (!props.peopleId || sending.value || historyBusy.value) return
  historyBusy.value = true
  try {
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
      method: 'POST',
      body: JSON.stringify({ peopleId: props.peopleId }),
    })
    applySessionBundle(res.data)
    messages.value = [{ role: 'assistant', content: AGENT_WELCOME }]
    historyOpen.value = false
    ElMessage.success('已新建会话')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建会话失败')
  } finally {
    historyBusy.value = false
  }
}

async function resumeArchivedSession() {
  const id = sessionId.value
  if (!id || historyBusy.value) return
  historyBusy.value = true
  try {
    const res = await api<{ data: SessionBundlePayload }>(
      `/api/b/v1/agent/sessions/${encodeURIComponent(id)}/resume`,
      { method: 'POST' },
    )
    applySessionBundle(res.data)
    ElMessage.success('已继续该会话')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '继续会话失败')
  } finally {
    historyBusy.value = false
  }
}

watch(
  () => [props.visible, props.peopleId] as const,
  ([v], prev) => {
    if (!v) return
    loadCapabilities()
    const peopleChanged = !prev || prev[1] !== props.peopleId
    if (peopleChanged || messages.value.length === 0) {
      messages.value = []
      sessionId.value = null
      sessionStatus.value = 'ACTIVE'
      void restoreDrawerSession()
    }
  },
)
</script>

<template>
  <el-drawer
    v-model="drawerVisible"
    :title="AGENT_FULL_NAME"
    direction="rtl"
    size="420px"
    :append-to-body="true"
  >
    <div class="agent-drawer">
      <div class="drawer-toolbar">
        <div class="quick-actions">
          <el-button
            v-for="cap in capabilities"
            :key="cap.code"
            size="small"
            :disabled="sending || isArchivedSession"
            @click="quickAction(cap.code, cap.label)"
          >
            {{ cap.label }}
          </el-button>
        </div>
        <div class="session-actions">
          <el-button size="small" text @click="openHistory">历史</el-button>
          <el-button size="small" text :disabled="sending || historyBusy" @click="startNewChat">
            新建
          </el-button>
        </div>
      </div>

      <div v-if="isArchivedSession" class="session-banner">
        <span>历史会话</span>
        <el-button size="small" type="primary" :loading="historyBusy" @click="resumeArchivedSession">
          继续此会话
        </el-button>
      </div>

      <div ref="chatBodyRef" class="chat-body">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="chat-bubble"
          :class="msg.role"
        >
          <AgentActivityPanel
            v-if="msg.activity?.length || msg.elapsedMs"
            :items="msg.activity || []"
            :streaming="msg.streaming"
            :elapsed-ms="msg.elapsedMs"
            @toggle="(id) => toggleActivity(msg, id)"
          />

          <div v-if="msg.streamContent" class="bubble-content stream-output">
            <template v-if="msg.isCarePlan">
              <div class="stream-label">AI 生成中</div>
              <div class="plain-body" v-html="formatAssistantPlainHtml(msg.streamContent)" />
            </template>
            <div v-else class="plain-body" v-html="formatAssistantPlainHtml(msg.streamContent)" />
          </div>
          <div
            v-else-if="msg.content && msg.role === 'assistant'"
            class="bubble-content plain-body"
            v-html="formatAssistantPlainHtml(msg.content)"
          />
          <div v-else-if="msg.content" class="bubble-content">
            {{ msg.content }}
          </div>
          <div v-else-if="msg.streaming && !msg.activity?.length" class="bubble-content typing">
            思考中…
          </div>

          <div v-if="msg.actions?.length" class="bubble-actions">
            <el-button
              v-for="(act, i) in msg.actions"
              :key="i"
              type="primary"
              link
              size="small"
              @click="runAction(act)"
            >
              {{ act.label }}
            </el-button>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <el-input
          v-model="input"
          placeholder="输入消息..."
          :disabled="sending || isArchivedSession"
          @keyup.enter="sendMessage(input)"
        />
        <el-button
          type="primary"
          :loading="sending"
          :disabled="isArchivedSession"
          @click="sendMessage(input)"
        >
          发送
        </el-button>
      </div>
      <input
        ref="ocrFileRef"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        class="ocr-file-input"
        @change="onOcrFileChange"
      />
    </div>
    <CarePlanPreviewDialog v-model="previewVisible" :data="previewData" />
  </el-drawer>

  <el-drawer
    v-model="historyOpen"
    title="历史会话"
    direction="rtl"
    size="360px"
    :append-to-body="true"
  >
    <div class="history-panel" v-loading="historyLoading">
      <div class="history-toolbar">
        <span class="history-hint">当前患者会话</span>
        <el-button size="small" type="primary" :disabled="sending || historyBusy" @click="startNewChat">
          新建会话
        </el-button>
      </div>
      <el-empty
        v-if="!historyLoading && !historyRows.length"
        description="暂无历史会话"
        :image-size="56"
      />
      <button
        v-for="row in historyRows"
        :key="row.sessionId"
        type="button"
        class="history-item"
        :class="{ active: row.sessionId === sessionId }"
        :disabled="historyBusy"
        @click="openHistorySession(row)"
      >
        <div class="history-item-top">
          <strong>{{ row.title || '未命名会话' }}</strong>
          <span
            class="history-status"
            :class="(row.status || '').toUpperCase() === 'ACTIVE' ? 'on' : 'off'"
          >
            {{ sessionStatusLabel(row.status) }}
          </span>
        </div>
        <p class="history-preview">{{ row.preview || '暂无消息' }}</p>
        <div class="history-meta">
          <span>{{ formatSessionTime(row.gmtModified || row.gmtCreated) }}</span>
          <span>{{ row.messageCount || 0 }} 条</span>
        </div>
      </button>
    </div>
  </el-drawer>
</template>

<style scoped>
.agent-drawer {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
}

.drawer-toolbar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.session-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.session-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.history-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 180px;
}

.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.history-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.history-item {
  width: 100%;
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: #fff;
  padding: 10px 12px;
  cursor: pointer;
}

.history-item:hover:not(:disabled) {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.history-item.active {
  border-color: var(--el-color-primary);
}

.history-item:disabled {
  opacity: 0.65;
  cursor: wait;
}

.history-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.history-item-top strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-status {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.history-status.on {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.history-status.off {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-lighter);
}

.history-preview {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-meta {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-bubble {
  max-width: 95%;
}

.chat-bubble.user {
  align-self: flex-end;
}

.chat-bubble.assistant {
  align-self: flex-start;
}

.bubble-content {
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.plain-body {
  white-space: pre-wrap;
  word-break: break-word;
}

.plain-body :deep(.section-title) {
  font-weight: 700;
  color: var(--el-text-color-primary);
}

.plain-body :deep(.field-label) {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.stream-output {
  max-height: 240px;
  overflow: auto;
}

.stream-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.stream-hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
}

.stream-pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-bubble.user .bubble-content {
  background: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
}

.chat-bubble.assistant .bubble-content {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.bubble-actions {
  margin-top: 6px;
}

.chat-input {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.typing {
  color: var(--el-text-color-secondary);
}

.ocr-file-input {
  display: none;
}
</style>
