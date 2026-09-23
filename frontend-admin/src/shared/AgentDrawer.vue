<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from './http'
import { AGENT_FULL_NAME, AGENT_LOGO, AGENT_NAME, AGENT_WELCOME } from './agent-brand'
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
import AgentOcrReviewCard from './AgentOcrReviewCard.vue'
import AgentReportReviewCard from './AgentReportReviewCard.vue'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from './CarePlanPreviewDialog.vue'
import { setAgentDraft } from './agent-draft-bus'
import { formatAssistantPlainHtml } from './agent-plain-text'
import { callApiDoneLabel, runAgentCallApi } from './agent-call-api'
import { resolveStickyCapabilityHint } from './agent-sticky'
import {
  buildConfirmBody,
  buildOcrReview,
  buildReportReview,
  formatOcrDt,
  MED_DOSE_UNIT_FALLBACK,
  MED_FREQUENCY_FALLBACK,
  MED_USAGE_OPTIONS,
  reportStatusLine,
  type OcrReview,
  type ReportReviewPreview,
} from './agent-ocr'

export interface AgentAction {
  type: string
  label: string
  path?: string
  peopleId?: string
  payload?: Record<string, unknown>
  /** CALL_API 执行态：busy 请求中；done 已成功，禁止再点 */
  runState?: 'busy' | 'done'
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
  imageUrl?: string
  imageName?: string
  ocrReview?: OcrReview
  reportReview?: ReportReviewPreview
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
const uploading = ref(false)
const imagePreviewUrl = ref<string | null>(null)
const stickyCapability = ref<string | null>(null)
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

const MED_FREQUENCY_CODES = new Set(MED_FREQUENCY_FALLBACK.map((o) => o.value))
const MED_DOSE_UNIT_CODES = new Set(MED_DOSE_UNIT_FALLBACK.map((o) => o.value))
const MED_USAGE_CODES = new Set(MED_USAGE_OPTIONS.map((o) => o.value))
const knownMedCodes = {
  usage: MED_USAGE_CODES,
  frequency: MED_FREQUENCY_CODES,
  doseUnit: MED_DOSE_UNIT_CODES,
}

const AI_CAP_CODES = new Set(['CARE_PLAN', 'REPORT_SUMMARY', 'GENERAL_CHAT'])

const capabilityChips = computed(() => {
  const base = capabilities.value.filter((c) => AI_CAP_CODES.has(c.code))
  const chips = base.length
    ? base.map((c) => ({
        ...c,
        label:
          c.code === 'CARE_PLAN'
            ? '生成方案'
            : c.code === 'REPORT_SUMMARY'
              ? '报告点评'
              : '健康咨询',
      }))
    : [
        { code: 'CARE_PLAN', label: '生成方案' },
        { code: 'REPORT_SUMMARY', label: '报告点评' },
        { code: 'GENERAL_CHAT', label: '健康咨询' },
      ]
  const reportIdx = chips.findIndex((c) => c.code === 'REPORT_SUMMARY')
  const ocrChip = { code: 'OCR_UPLOAD', label: '单据录入' }
  if (reportIdx >= 0) chips.splice(reportIdx + 1, 0, ocrChip)
  else chips.push(ocrChip)
  return chips
})

async function loadCapabilities() {
  try {
    const res = await api<{ data: AgentCapability[] }>('/api/b/v1/agent/capabilities')
    capabilities.value = res.data
  } catch {
    capabilities.value = [
      { code: 'CARE_PLAN', label: '制定管理方案' },
      { code: 'REPORT_SUMMARY', label: '管理报告点评' },
      { code: 'GENERAL_CHAT', label: '健康咨询' },
      { code: 'OCR_LAB', label: '检验单识别' },
      { code: 'OCR_EXAM', label: '检查单识别' },
      { code: 'OCR_MED', label: '用药单识别' },
    ]
  }
}

function applyAgentResult(
  row: ChatMessage,
  payload: unknown,
  previewUrl?: string,
) {
  const data = payload as AgentChatResponse
  const streamed = (row.streamContent || '').trim()
  if (!data?.reply && !streamed && !data?.extracted) return
  if (data.capability === 'CARE_PLAN') {
    row.content = streamed || data.reply || ''
  } else if (data.capability === 'REPORT_SUMMARY' && data.extracted) {
    const preview = buildReportReview(data.extracted)
    if (preview) {
      row.reportReview = preview
      row.content = reportStatusLine(preview)
    } else {
      row.content = (data.reply && data.reply.trim()) || streamed || ''
    }
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
  if (data.capability === 'CARE_PLAN' || data.capability === 'REPORT_SUMMARY') {
    stickyCapability.value = data.capability
  } else if (
    data.capability === 'OCR_LAB' ||
    data.capability === 'OCR_EXAM' ||
    data.capability === 'OCR_MED' ||
    data.capability === 'GENERAL_CHAT'
  ) {
    stickyCapability.value = null
  }
  if (data.capability === 'CARE_PLAN') {
    emit('care-plan-updated')
  }
  if (
    (data.capability === 'OCR_LAB' ||
      data.capability === 'OCR_EXAM' ||
      data.capability === 'OCR_MED') &&
    data.extracted
  ) {
    const review = buildOcrReview(
      data.capability,
      data.extracted,
      props.peopleId,
      previewUrl,
      knownMedCodes,
    )
    if (review) row.ocrReview = review
  }
}

async function sendMessage(
  message: string,
  capabilityHint?: string | null,
  image?: { base64: string; mimeType: string; name?: string; previewUrl?: string } | null,
) {
  if (!message.trim() && !image) return
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  const stickyHint = resolveStickyCapabilityHint(stickyCapability.value, message.trim())
  if (stickyCapability.value && !capabilityHint && !stickyHint && !image) {
    stickyCapability.value = null
  }
  const effectiveHint =
    capabilityHint ||
    (!image && stickyHint ? stickyHint : null)
  const userText = message.trim() || (image ? '请识别这张单据' : '')
  const isCarePlan = effectiveHint === 'CARE_PLAN'
  messages.value.push({
    role: 'user',
    content: image ? `${userText}\n[已附图片${image.name ? `：${image.name}` : ''}]` : userText,
    imageUrl: image?.previewUrl,
    imageName: image?.name,
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
        capabilityHint: effectiveHint || null,
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
          if (row) applyAgentResult(row, payload, image?.previewUrl)
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
    uploading.value = false
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
  if (code === 'OCR_UPLOAD') {
    ocrFileRef.value?.click()
    return
  }
  const prompts: Record<string, string> = {
    CARE_PLAN: '请为这位患者生成管理方案草稿',
    REPORT_SUMMARY: '请为这位患者生成或点评管理报告',
    GENERAL_CHAT: '请根据患者档案给出健康管理建议',
  }
  void sendMessage(prompts[code] || label, code)
}

async function onOcrFileChange(e: Event) {
  const inputEl = e.target as HTMLInputElement
  const file = inputEl.files?.[0]
  inputEl.value = ''
  if (!file || uploading.value || sending.value) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  const mime = file.type || 'image/jpeg'
  if (!['image/jpeg', 'image/jpg', 'image/png', 'image/webp'].includes(mime)) {
    ElMessage.warning('仅支持 JPG、PNG、WEBP 图片')
    return
  }
  uploading.value = true
  try {
    const base64 = await readFileAsBase64(file)
    const previewUrl = URL.createObjectURL(file)
    await sendMessage('请识别这张单据', null, {
      base64,
      mimeType: mime,
      name: file.name,
      previewUrl,
    })
  } catch {
    uploading.value = false
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
  if (action.type === 'REFRESH') {
    drawerVisible.value = false
    router.push({ path: '/workspace/cockpit' })
    return
  }
  if (action.type === 'FOCUS_PATIENT' && action.peopleId) {
    drawerVisible.value = false
    router.push({ path: '/workspace/cockpit', query: { peopleId: action.peopleId } })
    return
  }
  if (action.type === 'SET_COCKPIT_TAB' && action.path) {
    drawerVisible.value = false
    const q: Record<string, string> = { tab: action.path }
    if (props.peopleId) q.peopleId = props.peopleId
    router.push({ path: '/workspace/cockpit', query: q })
    return
  }
  if (action.type === 'CALL_API') {
    void executeCallApi(action)
    return
  }
  if (action.type === 'TRIGGER_CAPABILITY' && action.path) {
    if (action.path === 'GENERAL_CHAT') {
      stickyCapability.value = null
    }
    void sendMessage(action.label, action.path)
    return
  }
  if (action.type === 'OPEN_SHEET' && action.path) {
    const peopleId = action.peopleId || props.peopleId
    if (
      action.payload?.draftContent ||
      action.payload?.openCreate ||
      action.payload?.openReview ||
      action.payload?.staffComment ||
      action.payload?.reportId
    ) {
      setAgentDraft({
        peopleId,
        mode: action.path,
        draftContent: typeof action.payload.draftContent === 'string' ? action.payload.draftContent : undefined,
        openCreate: !!action.payload.openCreate,
        reportId: typeof action.payload.reportId === 'string' ? action.payload.reportId : undefined,
        openReview: !!action.payload.openReview,
        staffComment: typeof action.payload.staffComment === 'string' ? action.payload.staffComment : undefined,
        nextFocus: typeof action.payload.nextFocus === 'string' ? action.payload.nextFocus : undefined,
        quarterAdvice:
          typeof action.payload.quarterAdvice === 'string' ? action.payload.quarterAdvice : undefined,
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
  if (action.runState === 'busy' || action.runState === 'done') return
  const peopleId = action.peopleId || props.peopleId
  if (!peopleId) return
  const apiKey = action.path || ''
  action.runState = 'busy'
  try {
    const result = await runAgentCallApi(apiKey, peopleId, action.payload)
    const receipt = result.message
    action.runState = 'done'
    const doneLabel = callApiDoneLabel(apiKey)
    if (doneLabel) action.label = doneLabel
    if (apiKey === 'PUBLISH_REPORT' || apiKey === 'PUBLISH_CARE_PLAN') {
      stickyCapability.value = null
      emit('care-plan-updated')
    }
    if (result.openSheet) {
      const target = sheetModeToPath(peopleId, result.openSheet)
      drawerVisible.value = false
      router.push(target)
    }
    messages.value.push({
      role: 'assistant',
      content: `回执：${receipt}`,
    })
    ElMessage.success(receipt)
    await scrollToBottom()
  } catch (e) {
    action.runState = undefined
    if (e === 'cancel' || (e && typeof e === 'object' && 'action' in e && (e as { action?: string }).action === 'cancel')) {
      return
    }
    ElMessage.error(e instanceof Error ? e.message : '动作执行失败')
  }
}

function openImagePreview(url?: string | null) {
  if (!url) return
  imagePreviewUrl.value = url
}

function closeImagePreview() {
  imagePreviewUrl.value = null
}

async function confirmOcr(msg: ChatMessage) {
  const review = msg.ocrReview
  if (!review || review.status !== 'pending' || !review.peopleId) return
  const kindLabel =
    review.kind === 'LAB' ? '检验报告' : review.kind === 'MED' ? '用药处方' : '检查报告'
  const timeHint =
    review.kind === 'LAB'
      ? `采样 ${formatOcrDt(review.lab?.sampledAt)}，报告 ${formatOcrDt(review.lab?.reportedAt)}`
      : review.kind === 'MED'
        ? `共 ${review.med?.items?.length ?? 0} 种药品，请核对用法、剂量与疗程`
        : `检查时间 ${formatOcrDt(review.exam?.examinedAt)}`
  const confirmTitle = review.kind === 'MED' ? '确认写入用药清单' : '确认入库'
  const confirmText =
    review.kind === 'MED'
      ? `确认将「${review.title || kindLabel}」写入患者用药清单？${timeHint}。`
      : `确认将「${review.title || kindLabel}」写入患者健康数据？请再核对时间：${timeHint}。入库后可在观测数据中查看。`
  try {
    await ElMessageBox.confirm(confirmText, confirmTitle, {
      type: 'warning',
      confirmButtonText: '确认入库',
      cancelButtonText: '再检查一下',
      distinguishCancelAndClose: true,
    })
  } catch {
    return
  }
  review.status = 'saving'
  try {
    const body = buildConfirmBody(review)
    const res = await api<{ data: { reportId: string; title: string; summary: string } }>(
      `/api/b/v1/cockpit/patients/${review.peopleId}/reports/confirm`,
      { method: 'POST', body: JSON.stringify(body) },
    )
    review.status = 'confirmed'
    review.reportId = res.data.reportId
    msg.content = res.data.summary
    if (review.kind === 'MED') {
      msg.actions = [
        {
          type: 'OPEN_SHEET',
          label: '查看用药清单',
          path: 'medications',
          peopleId: review.peopleId,
        },
      ]
    } else {
      msg.actions = [
        {
          type: 'OPEN_SHEET',
          label: '查看健康数据',
          path: 'observations',
          peopleId: review.peopleId,
        },
      ]
    }
    ElMessage.success(review.kind === 'MED' ? '已写入用药清单' : '已入库')
  } catch (e) {
    review.status = 'pending'
    ElMessage.error(e instanceof Error ? e.message : '入库失败')
  }
}

function discardOcr(msg: ChatMessage) {
  const review = msg.ocrReview
  if (!review) return
  if (review.imageUrl) URL.revokeObjectURL(review.imageUrl)
  review.status = 'discarded'
  msg.ocrReview = undefined
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
    case 'medications':
      return `${base}/medications`
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
        contextSnapshot?: { summary?: string }
      }
      activeVersion?: {
        source?: string
        versionNo?: number
        exercise?: CarePlanPreviewData['exercise']
        diet?: CarePlanPreviewData['diet']
        execution?: CarePlanPreviewData['execution']
        contextSnapshot?: { summary?: string }
        publishedAt?: string
      }
    }
    const src = b.draft || b.activeVersion
    const snapSummary =
      typeof src?.contextSnapshot?.summary === 'string' ? src.contextSnapshot.summary.trim() : ''
    previewData.value = {
      title: b.plan?.title,
      goalSummary: b.plan?.goalSummary,
      summary: snapSummary || undefined,
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
  stickyCapability.value = null
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
    direction="rtl"
    size="440px"
    :with-header="false"
    :append-to-body="true"
    class="agent-drawer-host"
  >
    <div class="agent-drawer">
      <header class="drawer-head">
        <div class="ai-avatar" aria-hidden="true">
          <img :src="AGENT_LOGO" :alt="AGENT_NAME" class="ai-logo" />
        </div>
        <div class="ai-info">
          <div class="ai-name">
            {{ AGENT_NAME }}
            <span class="beta">BETA</span>
          </div>
          <div class="ai-sub">
            <i class="pulse" />
            {{ AGENT_FULL_NAME }}
          </div>
        </div>
        <div class="head-actions">
          <button type="button" class="icon-btn" title="历史会话" @click="openHistory">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <circle cx="12" cy="12" r="9" />
              <polyline points="12 7 12 12 15.5 14" />
            </svg>
          </button>
          <button
            type="button"
            class="icon-btn"
            title="新建会话"
            :disabled="sending || historyBusy"
            @click="startNewChat"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
          <button
            type="button"
            class="icon-btn close"
            title="关闭"
            @click="drawerVisible = false"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </header>

      <div class="quick-rail" aria-label="快捷能力">
        <button
          v-for="cap in capabilityChips"
          :key="cap.code"
          type="button"
          class="quick-chip"
          :disabled="sending || uploading || isArchivedSession"
          @click="quickAction(cap.code, cap.label)"
        >
          {{ cap.label }}
        </button>
      </div>

      <div v-if="isArchivedSession" class="session-banner">
        <span>历史会话，发送前请先继续</span>
        <button
          type="button"
          class="session-resume"
          :disabled="historyBusy"
          @click="resumeArchivedSession"
        >
          继续此会话
        </button>
      </div>

      <div ref="chatBodyRef" class="chat-body">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="msg-row"
          :class="msg.role"
        >
          <div v-if="msg.role === 'assistant'" class="msg-avatar" aria-hidden="true">
            <img :src="AGENT_LOGO" :alt="AGENT_NAME" />
          </div>
          <div class="bubble" :class="msg.role">
            <div v-if="msg.role === 'assistant'" class="bubble-head">
              <span>{{ AGENT_NAME }}</span>
            </div>

            <AgentActivityPanel
              v-if="msg.activity?.length || msg.elapsedMs"
              :items="msg.activity || []"
              :streaming="msg.streaming"
              :elapsed-ms="msg.elapsedMs"
              @toggle="(id) => toggleActivity(msg, id)"
            />

            <div v-if="msg.streamContent" class="bubble-text stream-output">
              <div v-if="msg.isCarePlan" class="stream-label">AI 生成中</div>
              <div class="plain-body" v-html="formatAssistantPlainHtml(msg.streamContent)" />
            </div>
            <div
              v-else-if="msg.content && msg.role === 'assistant' && !msg.reportReview"
              class="bubble-text plain-body"
              v-html="formatAssistantPlainHtml(msg.content)"
            />
            <div v-else-if="msg.reportReview" class="bubble-text plain-body report-status">
              {{ msg.content }}
            </div>
            <div v-else-if="msg.content" class="bubble-text">
              {{ msg.content }}
            </div>
            <div
              v-else-if="msg.streaming && !msg.activity?.length"
              class="typing"
              aria-label="思考中"
            >
              <span /><span /><span />
            </div>

            <AgentReportReviewCard v-if="msg.reportReview" :preview="msg.reportReview" />

            <button
              v-if="msg.imageUrl"
              type="button"
              class="msg-image"
              :title="msg.imageName || '查看原图'"
              @click="openImagePreview(msg.imageUrl)"
            >
              <img :src="msg.imageUrl" :alt="msg.imageName || '上传原图'" />
            </button>

            <AgentOcrReviewCard
              v-if="msg.ocrReview && msg.ocrReview.status !== 'discarded'"
              :review="msg.ocrReview"
              @preview="openImagePreview"
              @confirm="confirmOcr(msg)"
              @discard="discardOcr(msg)"
            />

            <div v-if="msg.actions?.length" class="bubble-actions">
              <button
                v-for="(act, i) in msg.actions"
                :key="i"
                type="button"
                class="action-chip"
                :class="{
                  primary: i === 0 || act.type === 'CALL_API' || act.type === 'TRIGGER_CAPABILITY',
                  do: act.type === 'CALL_API' || act.type === 'TRIGGER_CAPABILITY',
                  done: act.runState === 'done',
                }"
                :disabled="act.runState === 'busy' || act.runState === 'done'"
                @click="runAction(act)"
              >
                {{ act.runState === 'busy' ? '处理中…' : act.label }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="composer">
        <div class="composer-box">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            resize="none"
            :disabled="sending || isArchivedSession"
            placeholder="问问健管智能体，或让他帮你写方案…"
            @keydown.enter.exact.prevent="sendMessage(input)"
          />
          <div class="composer-bar">
            <span class="hint">Enter 发送 · Shift+Enter 换行</span>
            <button
              type="button"
              class="send-btn"
              :disabled="sending || uploading || isArchivedSession || !input.trim()"
              @click="sendMessage(input)"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
              {{ sending ? '发送中' : '发送' }}
            </button>
          </div>
        </div>
        <p class="disclaimer">智能体建议请人工核对后再执行。</p>
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

    <el-dialog
      :model-value="!!imagePreviewUrl"
      title="原图预览"
      width="720px"
      append-to-body
      @update:model-value="(v) => !v && closeImagePreview()"
    >
      <img
        v-if="imagePreviewUrl"
        :src="imagePreviewUrl"
        alt="原图"
        style="display: block; max-width: 100%; margin: 0 auto"
      />
    </el-dialog>
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
        <button
          type="button"
          class="history-new"
          :disabled="sending || historyBusy"
          @click="startNewChat"
        >
          新建会话
        </button>
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
  height: 100%;
  background: linear-gradient(180deg, #f8fbff 0%, #f4f7fb 48%, #f8fafc 100%);
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  flex-shrink: 0;
}

.ai-avatar {
  position: relative;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #fff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.06), 0 4px 12px rgba(44, 126, 248, 0.12);
}

.ai-avatar .ai-logo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  background: #fff;
}

.ai-avatar::after {
  content: '';
  position: absolute;
  bottom: 1px;
  right: 1px;
  width: 10px;
  height: 10px;
  background: #10b981;
  border: 2px solid #fff;
  border-radius: 50%;
}

.ai-info {
  flex: 1;
  min-width: 0;
}

.ai-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 650;
  color: #0f172a;
  letter-spacing: -0.01em;
}

.beta {
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 1px 5px;
  border-radius: 4px;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.ai-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11.5px;
  color: #64748b;
}

.pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45);
  animation: pulse-dot 1.8s ease-out infinite;
}

@keyframes pulse-dot {
  0% {
    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45);
  }
  70% {
    box-shadow: 0 0 0 6px rgba(16, 185, 129, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0);
  }
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.icon-btn {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.icon-btn:hover:not(:disabled) {
  background: #f1f5f9;
  color: #0f172a;
}

.icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.icon-btn.close:hover:not(:disabled) {
  background: #fee2e2;
  color: #dc2626;
}

.icon-btn svg {
  width: 16px;
  height: 16px;
}

.quick-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 14px 12px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(15, 23, 42, 0.04);
  background: rgba(255, 255, 255, 0.55);
}

.quick-chip {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #334155;
  border-radius: 999px;
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.3;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

.quick-chip:hover:not(:disabled) {
  border-color: #93c5fd;
  color: #1d4ed8;
  background: #eff6ff;
  box-shadow: 0 1px 4px rgba(37, 99, 235, 0.08);
}

.quick-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.session-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 10px 14px 0;
  padding: 8px 12px;
  border-radius: 10px;
  background: #eff6ff;
  color: #1e3a5f;
  font-size: 12px;
  flex-shrink: 0;
}

.session-resume {
  border: 0;
  border-radius: 7px;
  padding: 5px 10px;
  background: #2563eb;
  color: #fff;
  font-size: 12px;
  font-weight: 560;
  cursor: pointer;
  white-space: nowrap;
}

.session-resume:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.msg-row {
  display: flex;
  gap: 8px;
  max-width: 100%;
}

.msg-row.user {
  justify-content: flex-end;
}

.msg-row.assistant {
  justify-content: flex-start;
}

.msg-avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
  margin-top: 2px;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.06);
}

.msg-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.bubble {
  max-width: min(420px, calc(100% - 36px));
  padding: 10px 12px;
  border-radius: 14px;
  font-size: 13.5px;
  line-height: 1.45;
}

.bubble.assistant {
  background: #fff;
  color: #0f172a;
  border: 1px solid rgba(15, 23, 42, 0.06);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  border-top-left-radius: 6px;
}

.bubble.user {
  max-width: 88%;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
  border-top-right-radius: 6px;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
}

.bubble-head {
  display: flex;
  align-items: center;
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
}

.bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.plain-body {
  white-space: pre-wrap;
  word-break: break-word;
}

.plain-body :deep(.section-title) {
  display: block;
  margin: 6px 0 2px;
  font-size: 13.5px;
  font-weight: 700;
  color: #0f172a;
}

.plain-body :deep(.section-title:first-child) {
  margin-top: 0;
}

.plain-body :deep(.field-label) {
  font-weight: 600;
  color: #1e293b;
}

.stream-output {
  max-height: 260px;
  overflow: auto;
}

.stream-label {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 6px;
}

.bubble-actions {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.action-chip {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #334155;
  border-radius: 999px;
  padding: 5px 11px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.action-chip:hover {
  border-color: #3b82f6;
  color: #1d4ed8;
  background: #eff6ff;
}

.action-chip.primary {
  background: #2563eb;
  color: #fff;
  border-color: #2563eb;
}

.action-chip.primary:hover {
  background: #1d4ed8;
  color: #fff;
}

.action-chip.do:not(.primary) {
  border-color: #93c5fd;
  color: #1d4ed8;
  background: #eff6ff;
}

.action-chip:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.action-chip.done,
.action-chip.done:hover {
  background: #f1f5f9;
  color: #64748b;
  border-color: #e2e8f0;
}

.msg-image {
  display: block;
  margin-top: 8px;
  padding: 0;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background: #f8fafc;
  cursor: zoom-in;
  line-height: 0;
}

.msg-image img {
  display: block;
  max-width: 100%;
  max-height: 160px;
  object-fit: contain;
}

.report-status {
  color: #475569;
  font-size: 13px;
}

.typing {
  display: inline-flex;
  gap: 4px;
  padding: 6px 2px;
}

.typing span {
  width: 6px;
  height: 6px;
  background: #94a3b8;
  border-radius: 50%;
  animation: bounce 1.4s infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  40% {
    transform: translateY(-5px);
    opacity: 1;
  }
}

.composer {
  flex-shrink: 0;
  padding: 12px 14px 14px;
  border-top: 1px solid rgba(15, 23, 42, 0.06);
  background: rgba(255, 255, 255, 0.94);
}

.composer-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  padding: 10px 12px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.composer-box:focus-within {
  background: #fff;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.composer-box :deep(.el-textarea__inner) {
  box-shadow: none !important;
  border: 0 !important;
  background: transparent !important;
  padding: 0 !important;
  font-size: 13.5px;
  line-height: 1.55;
  color: #0f172a;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  gap: 8px;
}

.composer-bar .hint {
  font-size: 11px;
  color: #94a3b8;
}

.send-btn {
  height: 32px;
  padding: 0 14px;
  background: #2563eb;
  color: #fff;
  border: 0;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: background 0.15s ease, opacity 0.15s ease;
}

.send-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-btn svg {
  width: 14px;
  height: 14px;
}

.disclaimer {
  margin: 8px 0 0;
  font-size: 11px;
  color: #94a3b8;
  text-align: center;
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
  color: #64748b;
}

.history-new {
  border: 0;
  border-radius: 7px;
  padding: 5px 10px;
  background: #0f172a;
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.history-new:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.history-item {
  width: 100%;
  text-align: left;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.history-item:hover:not(:disabled) {
  border-color: #93c5fd;
  background: #eff6ff;
}

.history-item.active {
  border-color: #3b82f6;
  box-shadow: 0 0 0 1px #bfdbfe;
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
  color: #64748b;
  background: #f8fafc;
  border-color: #e2e8f0;
}

.history-preview {
  margin: 6px 0 0;
  font-size: 12px;
  color: #64748b;
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
  color: #94a3b8;
}

.ocr-file-input {
  display: none;
}
</style>

<style>
.agent-drawer-host.el-drawer {
  border-radius: 16px 0 0 16px;
  overflow: hidden;
}

.agent-drawer-host .el-drawer__body {
  padding: 0;
  height: 100%;
  overflow: hidden;
}
</style>
