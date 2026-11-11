<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from './http'
import { type Question, type QuestionnaireView } from './assessment'

const props = defineProps<{
  modelValue: boolean
  peopleId: string
  /** 是否为重新发送（作废旧链接） */
  resend?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'sent'): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

type IssueResult = {
  id?: string
  url?: string
  copyText?: string
  expiresAt?: string
}

const loading = ref(false)
const generating = ref(false)
const copying = ref(false)
const previewOpen = ref(false)

const patientName = ref('患者')
const questionnaireTitle = ref('极简健康评估')
const totalCount = ref(0)
const firstQuestion = ref<Question | null>(null)
const linkUrl = ref('')
const copyText = ref('')
const generateError = ref('')

const questionnaireLabel = computed(() => {
  const title = questionnaireTitle.value || '极简健康评估'
  const n = totalCount.value
  return n > 0 ? `${title} (共 ${n} 题)` : title
})

const dialogTitle = computed(() => (props.resend ? '重新发送问卷' : '发送评估问卷'))

const canCopy = computed(() => !!linkUrl.value && !generating.value && !loading.value)

async function loadMeta() {
  if (!props.peopleId) return
  const [patientRes, qRes] = await Promise.all([
    api<{ data: { displayName?: string } }>(`/api/b/v1/patients/${props.peopleId}`),
    api<{ data: QuestionnaireView }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/questionnaire`,
    ),
  ])
  patientName.value = patientRes.data?.displayName?.trim() || '患者'
  totalCount.value = qRes.data?.totalCount || 0
  questionnaireTitle.value = '极简健康评估'
  const questions = (qRes.data?.sections || []).flatMap((s) => s.questions || [])
  firstQuestion.value = questions.find((q) => q.missing) || questions[0] || null
}

async function generateLink() {
  if (!props.peopleId) return
  generating.value = true
  generateError.value = ''
  linkUrl.value = ''
  copyText.value = ''
  try {
    const res = await api<{ data: IssueResult }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/questionnaire-requests`,
      {
        method: 'POST',
        body: JSON.stringify({ channel: 'COPY' }),
      },
    )
    linkUrl.value = res.data?.url?.trim() || ''
    copyText.value = res.data?.copyText?.trim() || ''
    if (!linkUrl.value) {
      throw new Error('未返回填写链接')
    }
    if (!copyText.value) {
      copyText.value = `请填写健康评估问卷：\n${linkUrl.value}`
    }
    emit('sent')
  } catch (e) {
    generateError.value = (e as Error).message || '生成链接失败'
    ElMessage.error(generateError.value)
  } finally {
    generating.value = false
  }
}

async function openAndPrepare() {
  loading.value = true
  generateError.value = ''
  linkUrl.value = ''
  copyText.value = ''
  try {
    await loadMeta()
    await generateLink()
  } catch (e) {
    ElMessage.error((e as Error).message || '加载发送信息失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (v) => {
    if (!v) {
      previewOpen.value = false
      return
    }
    void openAndPrepare()
  },
)

function openPreview() {
  if (!firstQuestion.value) {
    ElMessage.info('当前没有可预览的题目')
    return
  }
  previewOpen.value = true
}

async function writeClipboard(text: string) {
  if (!text) throw new Error('无可复制内容')
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', 'true')
  ta.style.position = 'fixed'
  ta.style.left = '-9999px'
  document.body.appendChild(ta)
  ta.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(ta)
  if (!ok) throw new Error('复制失败')
}

async function copyScriptAndLink() {
  if (!canCopy.value || copying.value) return
  copying.value = true
  try {
    await writeClipboard(copyText.value || linkUrl.value)
    ElMessage.success('✅ 复制成功，请去微信粘贴给患者')
  } catch (e) {
    ElMessage.error((e as Error).message || '复制失败')
  } finally {
    copying.value = false
  }
}

async function copyLinkOnly() {
  if (!canCopy.value || copying.value) return
  copying.value = true
  try {
    await writeClipboard(linkUrl.value)
    ElMessage.success('✅ 链接已复制')
  } catch (e) {
    ElMessage.error((e as Error).message || '复制失败')
  } finally {
    copying.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="open"
    :title="dialogTitle"
    width="520px"
    align-center
    destroy-on-close
    class="send-q-dialog"
  >
    <div v-loading="loading || generating" class="send-body">
      <p v-if="resend" class="danger-hint">
        ⚠️ 重新生成后，患者之前收到的旧链接将立即失效作废。
      </p>
      <p v-else class="info-hint">生成专属填写链接后，请复制发给患者。链接有效期 7 天。</p>

      <div class="meta-card">
        <div class="meta-row">
          <span class="meta-label">目标问卷</span>
          <div class="meta-value questionnaire">
            <span class="q-name">{{ questionnaireLabel }}</span>
            <button type="button" class="preview-btn" @click="openPreview">👁️ 预览问卷</button>
          </div>
        </div>
        <div class="meta-row">
          <span class="meta-label">接收患者</span>
          <span class="meta-value">{{ patientName }}</span>
        </div>
      </div>

      <div class="link-block">
        <div class="link-head">
          <span class="link-label">专属链接</span>
          <span class="link-expire">（链接有效期 7 天）</span>
        </div>
        <el-input
          :model-value="linkUrl"
          readonly
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="正在生成专属链接…"
          class="link-input"
        />
        <p v-if="generateError" class="gen-error">{{ generateError }}</p>
      </div>
    </div>

    <template #footer>
      <div class="footer-row">
        <el-button @click="open = false">取消</el-button>
        <div class="footer-actions">
          <el-button :disabled="!canCopy" :loading="copying" @click="copyLinkOnly">
            仅复制链接
          </el-button>
          <el-button
            type="primary"
            :disabled="!canCopy"
            :loading="copying"
            @click="copyScriptAndLink"
          >
            📋 一键复制话术及链接
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="previewOpen"
    title="问卷预览 · 第 1 题"
    width="420px"
    append-to-body
    align-center
    class="preview-q-dialog"
  >
    <div v-if="firstQuestion" class="preview-card">
      <div class="preview-badge">患者端所见（示意）</div>
      <div class="preview-q-text">{{ firstQuestion.text }}</div>
      <div v-if="firstQuestion.type === 'NUMBER'" class="preview-ctrl">
        <el-input disabled placeholder="请输入数值" />
        <span v-if="firstQuestion.unit" class="preview-unit">{{ firstQuestion.unit }}</span>
      </div>
      <div v-else-if="firstQuestion.type === 'MULTI'" class="preview-opts">
        <el-checkbox
          v-for="o in firstQuestion.options"
          :key="o.code"
          disabled
          :label="o.label"
        >
          {{ o.label }}
        </el-checkbox>
      </div>
      <div v-else class="preview-opts">
        <el-radio-group disabled>
          <el-radio v-for="o in firstQuestion.options" :key="o.code" :value="o.code">
            {{ o.label }}
          </el-radio>
        </el-radio-group>
      </div>
      <p class="preview-note">仅预览第一题界面，实际题目会按患者缺失字段动态生成。</p>
    </div>
    <el-empty v-else description="暂无可预览题目" />
  </el-dialog>
</template>

<style scoped>
.send-body {
  min-height: 140px;
}
.danger-hint {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  color: #c2410c;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}
.info-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.meta-card {
  padding: 14px 16px;
  border-radius: 10px;
  background: #f5f7fa;
  border: 1px solid #eef2f7;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.meta-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}
.meta-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.meta-value {
  font-size: 13px;
  color: var(--ink-800, #1e293b);
  font-weight: 600;
  line-height: 22px;
}
.questionnaire {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.q-name {
  font-weight: 650;
}
.preview-btn {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: var(--el-color-primary);
  cursor: pointer;
  font-weight: 500;
}
.preview-btn:hover {
  text-decoration: underline;
}
.link-block {
  margin-top: 14px;
}
.link-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 8px;
}
.link-label {
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
}
.link-expire {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.link-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #334155;
  user-select: text;
  cursor: text;
}
.gen-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-color-danger);
}
.footer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.footer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.preview-card {
  padding: 4px 2px 8px;
}
.preview-badge {
  display: inline-block;
  margin-bottom: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f0f5ff;
  color: #2f54eb;
  font-size: 11px;
  font-weight: 650;
}
.preview-q-text {
  font-size: 15px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
  line-height: 1.5;
  margin-bottom: 14px;
}
.preview-ctrl {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-unit {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.preview-opts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.preview-note {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
