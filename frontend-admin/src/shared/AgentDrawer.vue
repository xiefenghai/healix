<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from './http'
import { AGENT_FULL_NAME, AGENT_WELCOME } from './agent-brand'
import { postSse, type StreamSkillEvent, type StreamToolEvent } from './agent-stream'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from './CarePlanPreviewDialog.vue'

export interface AgentAction {
  type: string
  label: string
  path?: string
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

type TimelineKind = 'progress' | 'tool' | 'skill'

interface TimelineItem {
  kind: TimelineKind
  title: string
  detail?: string
  status?: 'running' | 'done'
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  streamContent?: string
  timeline?: TimelineItem[]
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
const input = ref('')
const sending = ref(false)
const messages = ref<ChatMessage[]>([])
const capabilities = ref<AgentCapability[]>([])
const chatBodyRef = ref<HTMLElement | null>(null)
const previewVisible = ref(false)
const previewData = ref<CarePlanPreviewData | null>(null)

const drawerVisible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})

async function loadCapabilities() {
  try {
    const res = await api<{ data: AgentCapability[] }>('/api/b/v1/agent/capabilities')
    capabilities.value = res.data
  } catch {
    capabilities.value = [
      { code: 'CARE_PLAN', label: '制定管理方案' },
      { code: 'GENERAL_CHAT', label: '健康咨询' },
    ]
  }
}

function ensureTimeline(row: ChatMessage): TimelineItem[] {
  if (!row.timeline) row.timeline = []
  return row.timeline
}

function appendProgress(row: ChatMessage, msg: string) {
  if (!msg.trim()) return
  const timeline = ensureTimeline(row)
  const last = timeline[timeline.length - 1]
  if (!last || last.kind !== 'progress' || last.title !== msg) {
    timeline.push({ kind: 'progress', title: msg, status: 'running' })
  }
  markPreviousDone(timeline)
}

function appendTool(row: ChatMessage, event: StreamToolEvent) {
  const timeline = ensureTimeline(row)
  const status = event.status === 'done' ? 'done' : 'running'
  const existing = timeline.find((item) => item.kind === 'tool' && item.title === event.name)
  if (existing) {
    existing.detail = event.detail
    existing.status = status
  } else {
    timeline.push({
      kind: 'tool',
      title: event.name,
      detail: event.detail,
      status,
    })
  }
  markPreviousDone(timeline, event.name)
}

function appendSkill(row: ChatMessage, event: StreamSkillEvent) {
  const timeline = ensureTimeline(row)
  timeline.push({
    kind: 'skill',
    title: `Skill: ${event.name}`,
    detail: event.detail,
    status: 'done',
  })
}

function markPreviousDone(timeline: TimelineItem[], exceptTitle?: string) {
  for (let i = 0; i < timeline.length - 1; i++) {
    const item = timeline[i]
    if (item.status === 'running' && item.title !== exceptTitle) {
      item.status = 'done'
    }
  }
}

function applyAgentResult(row: ChatMessage, payload: unknown) {
  const data = payload as AgentChatResponse
  if (!data?.reply) return
  row.streamContent = ''
  row.content = data.reply
  row.actions =
    data.capability === 'CARE_PLAN'
      ? [...(data.actions || []), { type: 'PREVIEW', label: '预览方案详情' }]
      : data.actions
  row.isCarePlan = data.capability === 'CARE_PLAN'
  row.streaming = false
  if (row.timeline) {
    row.timeline.forEach((item) => {
      item.status = 'done'
    })
  }
  if (data.sessionId) {
    sessionId.value = data.sessionId
  }
  if (data.capability === 'CARE_PLAN') {
    emit('care-plan-updated')
  }
}

async function sendMessage(message: string, capabilityHint?: string) {
  if (!message.trim()) return
  const userText = message.trim()
  const isCarePlan = capabilityHint === 'CARE_PLAN'
  messages.value.push({ role: 'user', content: userText })
  input.value = ''
  sending.value = true

  const assistantIdx = messages.value.length
  messages.value.push({
    role: 'assistant',
    content: '',
    streamContent: '',
    timeline: isCarePlan ? [] : undefined,
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
      },
      {
        onProgress: (msg) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          appendProgress(row, msg)
          void scrollToBottom()
        },
        onTool: (event) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          appendTool(row, event)
          void scrollToBottom()
        },
        onSkill: (event) => {
          const row = messages.value[assistantIdx]
          if (!row) return
          appendSkill(row, event)
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
        onDone: () => {
          const row = messages.value[assistantIdx]
          if (row) row.streaming = false
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
    if (row) row.streaming = false
    await scrollToBottom()
  }
}

function quickAction(code: string, label: string) {
  const prompts: Record<string, string> = {
    CARE_PLAN: '请为这位患者生成管理方案草稿',
    GENERAL_CHAT: '请根据患者档案给出健康管理建议',
  }
  sendMessage(prompts[code] || label, code)
}

function runAction(action: AgentAction) {
  if (action.type === 'PREVIEW') {
    void openCarePlanPreview()
    return
  }
  if (action.path) {
    drawerVisible.value = false
    router.push(action.path)
  }
}

async function openCarePlanPreview() {
  try {
    const res = await api<{ data: Record<string, unknown> }>(`/api/b/v1/patients/${props.peopleId}/care-plan`)
    const b = res.data as {
      plan?: { title?: string; goalSummary?: string }
      draft?: { source?: string; exercise?: CarePlanPreviewData['exercise']; diet?: CarePlanPreviewData['diet']; execution?: CarePlanPreviewData['execution'] }
      activeVersion?: { source?: string; versionNo?: number; exercise?: CarePlanPreviewData['exercise']; diet?: CarePlanPreviewData['diet']; execution?: CarePlanPreviewData['execution']; publishedAt?: string }
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

function timelineIcon(item: TimelineItem): string {
  if (item.kind === 'skill') return 'S'
  if (item.kind === 'tool') return 'T'
  return '·'
}

async function scrollToBottom() {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      loadCapabilities()
      if (messages.value.length === 0) {
        messages.value.push({
          role: 'assistant',
          content: AGENT_WELCOME,
        })
      }
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
      <div class="quick-actions">
        <el-button
          v-for="cap in capabilities"
          :key="cap.code"
          size="small"
          :disabled="sending"
          @click="quickAction(cap.code, cap.label)"
        >
          {{ cap.label }}
        </el-button>
      </div>

      <div ref="chatBodyRef" class="chat-body">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="chat-bubble"
          :class="msg.role"
        >
          <div v-if="msg.timeline?.length" class="progress-timeline">
            <div
              v-for="(item, stepIdx) in msg.timeline"
              :key="stepIdx"
              class="progress-step"
              :class="{
                active: msg.streaming && item.status === 'running',
                done: item.status === 'done' || (!msg.streaming && stepIdx < msg.timeline!.length - 1),
                tool: item.kind === 'tool',
                skill: item.kind === 'skill',
              }"
            >
              <span class="progress-dot">{{ timelineIcon(item) }}</span>
              <div class="progress-body">
                <span class="progress-text">{{ item.title }}</span>
                <span v-if="item.detail" class="progress-detail">{{ item.detail }}</span>
              </div>
            </div>
          </div>

          <div v-if="msg.streamContent" class="bubble-content stream-output">
            <div v-if="msg.isCarePlan" class="stream-label">AI 生成中</div>
            <pre v-if="msg.isCarePlan" class="stream-pre">{{ msg.streamContent }}</pre>
            <template v-else>{{ msg.streamContent }}</template>
          </div>
          <div v-else-if="msg.content" class="bubble-content">
            {{ msg.content }}
          </div>
          <div v-else-if="msg.streaming && !msg.timeline?.length" class="bubble-content typing">
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
          :disabled="sending"
          @keyup.enter="sendMessage(input)"
        />
        <el-button type="primary" :loading="sending" @click="sendMessage(input)">发送</el-button>
      </div>
    </div>
    <CarePlanPreviewDialog v-model="previewVisible" :data="previewData" />
  </el-drawer>
</template>

<style scoped>
.agent-drawer {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
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

.progress-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
}

.progress-step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  animation: step-in 0.25s ease-out;
}

@keyframes step-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.progress-step.active {
  color: var(--el-color-primary);
}

.progress-step.done {
  color: var(--el-text-color-regular);
}

.progress-step.tool .progress-dot {
  background: var(--el-color-warning-light-3);
  color: var(--el-color-warning);
}

.progress-step.skill .progress-dot {
  background: var(--el-color-success-light-3);
  color: var(--el-color-success);
}

.progress-dot {
  width: 18px;
  height: 18px;
  margin-top: 2px;
  border-radius: 4px;
  background: var(--el-border-color-lighter);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}

.progress-step.active .progress-dot {
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
}

.progress-step.done .progress-dot {
  background: var(--el-color-success-light-8);
  color: var(--el-color-success);
}

.progress-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.progress-text {
  line-height: 1.5;
  font-weight: 500;
}

.progress-detail {
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
  word-break: break-word;
}

.bubble-content {
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
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
</style>
