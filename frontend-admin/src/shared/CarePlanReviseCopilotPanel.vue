<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Promotion } from '@element-plus/icons-vue'

export type ReviseChatRole = 'user' | 'assistant' | 'system'
export type ReviseChatTone = 'default' | 'success' | 'danger' | 'muted'

export interface ReviseChatMessage {
  id: string
  role: ReviseChatRole
  content: string
  tone?: ReviseChatTone
  at?: number
}

const props = withDefaults(
  defineProps<{
    messages: ReviseChatMessage[]
    sending?: boolean
    progress?: string
  }>(),
  { sending: false, progress: '' },
)

const emit = defineEmits<{
  send: [text: string]
}>()

const input = ref('')
const inputFocused = ref(false)
const listEl = ref<HTMLElement | null>(null)
const textareaRef = ref<{ focus?: () => void; $el?: HTMLElement } | null>(null)

const hasText = computed(() => input.value.trim().length > 0)
/** 聚焦或已输入时，发送钮“苏醒”为高亮绿 */
const sendAwake = computed(() => inputFocused.value || hasText.value)

watch(
  () => [props.messages.length, props.progress, props.sending] as const,
  async () => {
    await nextTick()
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

function onFocus() {
  inputFocused.value = true
}

function onBlur() {
  inputFocused.value = false
}

function submit() {
  if (props.sending) return
  const text = input.value.trim()
  if (!text) {
    ElMessage.info('请先输入要修改的内容')
    void focusInput()
    return
  }
  emit('send', text)
  input.value = ''
}

/** 供顶栏「AI 协助修改」聚焦输入框 */
async function focusInput() {
  await nextTick()
  const inst = textareaRef.value as unknown as {
    focus?: () => void
    textarea?: HTMLTextAreaElement
    $el?: HTMLElement
  } | null
  if (inst?.focus) {
    inst.focus()
    inputFocused.value = true
    return
  }
  const native =
    inst?.textarea ||
    (inst?.$el?.querySelector?.('textarea') as HTMLTextAreaElement | null) ||
    null
  native?.focus()
  if (native) inputFocused.value = true
}

defineExpose({ focusInput })
</script>

<template>
  <section class="revise-panel" aria-label="AI 方案修订助手">
    <header class="revise-panel__head">
      <div class="revise-panel__title-row">
        <span class="revise-panel__icon" aria-hidden="true">
          <el-icon :size="14"><MagicStick /></el-icon>
        </span>
        <h4 class="revise-panel__title">AI 方案修订助手</h4>
      </div>
      <p class="revise-panel__sub">支持局部修改，安全红线自动拦截</p>
    </header>

    <div ref="listEl" class="revise-panel__chat" aria-live="polite">
      <div
        v-for="msg in messages"
        :key="msg.id"
        class="revise-panel__bubble"
        :data-role="msg.role"
        :data-tone="msg.tone || 'default'"
      >
        <div class="revise-panel__bubble-body">{{ msg.content }}</div>
      </div>
      <div
        v-if="sending"
        class="revise-panel__bubble"
        data-role="assistant"
        data-tone="muted"
      >
        <div class="revise-panel__bubble-body">{{ progress || '正在按你的意见修订…' }}</div>
      </div>
    </div>

    <div class="revise-panel__composer">
      <el-input
        ref="textareaRef"
        v-model="input"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 4 }"
        maxlength="500"
        resize="none"
        placeholder="例如：患者不爱吃鱼，请换成其他蛋白质..."
        :disabled="sending"
        class="revise-panel__textarea"
        @focus="onFocus"
        @blur="onBlur"
        @keydown.enter.exact.prevent="submit"
      />
      <button
        type="button"
        class="revise-panel__send"
        :class="{
          'is-awake': sendAwake && !sending,
          'is-idle': !sendAwake && !sending,
        }"
        :disabled="sending"
        :aria-label="sending ? '修订中' : '发送'"
        @click="submit"
      >
        <el-icon v-if="!sending" :size="16"><Promotion /></el-icon>
        <span v-else class="revise-panel__send-spin" />
      </button>
    </div>
  </section>
</template>

<style scoped>
.revise-panel {
  display: flex;
  flex-direction: column;
  min-height: 280px;
  height: 100%;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06), 0 4px 12px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}

.revise-panel__head {
  flex: 0 0 auto;
  padding: 12px 14px 10px;
  border-bottom: 1px solid #f1f5f9;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}

.revise-panel__title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.revise-panel__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  color: #0f766e;
  background: #ccfbf1;
  flex-shrink: 0;
}

.revise-panel__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.01em;
}

.revise-panel__sub {
  margin: 6px 0 0;
  padding-left: 28px;
  font-size: 12px;
  line-height: 1.4;
  color: #94a3b8;
}

.revise-panel__chat {
  flex: 1 1 auto;
  min-height: 96px;
  overflow-y: auto;
  padding: 12px 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fff;
}

.revise-panel__bubble {
  max-width: 94%;
  display: flex;
}

.revise-panel__bubble[data-role='user'] {
  align-self: flex-end;
}

.revise-panel__bubble[data-role='assistant'],
.revise-panel__bubble[data-role='system'] {
  align-self: start;
}

.revise-panel__bubble-body {
  padding: 8px 11px;
  border-radius: 10px;
  font-size: 12.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.revise-panel__bubble[data-role='user'] .revise-panel__bubble-body {
  background: #0f766e;
  color: #fff;
  border-bottom-right-radius: 3px;
}

.revise-panel__bubble[data-role='assistant'] .revise-panel__bubble-body,
.revise-panel__bubble[data-role='system'] .revise-panel__bubble-body {
  background: #ecfdf5;
  color: #065f46;
  border: none;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
  box-shadow: none;
}

.revise-panel__bubble[data-tone='success'] .revise-panel__bubble-body {
  background: #ecfdf5;
  color: #065f46;
  border: none;
}

.revise-panel__bubble[data-tone='danger'] .revise-panel__bubble-body {
  background: #fef2f2;
  color: #b91c1c;
  border: none;
}

.revise-panel__bubble[data-tone='muted'] .revise-panel__bubble-body {
  background: #ecfdf5;
  color: #065f46;
  border: none;
}

.revise-panel__composer {
  position: relative;
  flex: 0 0 auto;
  padding: 10px 12px 12px;
  border-top: 1px solid #f1f5f9;
  background: #fff;
}

.revise-panel__textarea :deep(.el-textarea__inner) {
  padding: 10px 44px 10px 12px;
  min-height: 72px !important;
  font-size: 13px;
  line-height: 1.5;
  color: #0f172a;
  border: 1px solid #e2e8f0 !important;
  border-radius: 8px;
  box-shadow: none !important;
  outline: none !important;
  background: #f8fafc;
  resize: none;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background 0.15s ease;
}

.revise-panel__textarea :deep(.el-textarea__inner:hover) {
  border-color: #cbd5e1 !important;
}

.revise-panel__textarea :deep(.el-textarea__inner:focus),
.revise-panel__textarea.is-focus :deep(.el-textarea__inner),
.revise-panel__textarea :deep(.el-textarea.is-focus .el-textarea__inner) {
  border-color: #6ee7b7 !important;
  background: #fff !important;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.18) !important;
  outline: none !important;
}

.revise-panel__textarea :deep(.el-textarea__inner::placeholder) {
  color: #94a3b8;
}

.revise-panel__send {
  position: absolute;
  right: 20px;
  bottom: 20px;
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  color: #fff;
  cursor: pointer;
  transition:
    background 0.18s ease,
    opacity 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}

.revise-panel__send.is-idle {
  background: #cbd5e1;
  color: #f8fafc;
  box-shadow: none;
  opacity: 0.85;
}

.revise-panel__send.is-awake {
  background: #16a34a;
  color: #fff;
  box-shadow: 0 2px 8px rgba(22, 163, 74, 0.35);
}

.revise-panel__send.is-awake:hover {
  background: #15803d;
  box-shadow: 0 3px 10px rgba(22, 163, 74, 0.42);
  transform: translateY(-1px);
}

.revise-panel__send:disabled {
  background: #94a3b8;
  opacity: 0.55;
  cursor: wait;
  box-shadow: none;
  transform: none;
}

.revise-panel__send-spin {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: revise-spin 0.7s linear infinite;
}

@keyframes revise-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
