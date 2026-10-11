<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, apiUpload } from '../api/http'
import { isPatientSource } from '../shared/health-data-source'
import {
  EXAM_FINDING_FIELDS,
  EXAM_PANELS,
  blankFindings,
  buildFindingsPayload,
  examTypeLabel,
  hasExamDraft,
  optionLabel,
  type ExamFindingField,
  type ExamPanel,
} from '../shared/exam-panels'

const route = useRoute()
const router = useRouter()
const editId = computed(() => {
  const id = String(route.params.id || '')
  return route.path.includes('/edit') && id ? id : ''
})
const isEdit = computed(() => !!editId.value)
const pageLoading = ref(false)
const saving = ref(false)
const loadingExisting = ref(false)
const activePanelKey = ref(EXAM_PANELS[0].key)
const examType = ref(EXAM_PANELS[0].examTypes[0])
const ocrLoading = ref(false)
const ocrHint = ref('')
const ocrApplied = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const form = reactive({
  examinedAt: '',
  conclusion: '',
})

interface ExamOcrResult {
  examType?: string | null
  examTypeName?: string | null
  examinedAt?: string | null
  conclusion?: string | null
  findings?: Record<string, unknown> | null
  ignoredFindings?: string[]
  warnings?: string[]
}
const findings = reactive<Record<string, unknown>>(blankFindings(examType.value))

const activePanel = computed<ExamPanel>(
  () => EXAM_PANELS.find((p) => p.key === activePanelKey.value) || EXAM_PANELS[0],
)

const fieldRows = computed<ExamFindingField[]>(() => EXAM_FINDING_FIELDS[examType.value] || [])

const showTypeSwitch = computed(() => activePanel.value.examTypes.length > 1)
const navTitle = computed(() => (isEdit.value ? '修改检查' : '添加检查'))
const submitLabel = computed(() => (isEdit.value ? '保存修改' : '保存'))

function nowLocalInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

function resetFindings(type: string) {
  const next = blankFindings(type)
  for (const key of Object.keys(findings)) delete findings[key]
  Object.assign(findings, next)
}

function applyFindings(type: string, raw?: Record<string, unknown> | null) {
  resetFindings(type)
  if (!raw) return
  for (const field of EXAM_FINDING_FIELDS[type] || []) {
    if (raw[field.key] === undefined) continue
    if (field.type === 'bool') findings[field.key] = !!raw[field.key]
    else if (field.type === 'number') {
      findings[field.key] = raw[field.key] == null ? null : String(raw[field.key])
    } else {
      findings[field.key] = raw[field.key] == null ? '' : String(raw[field.key])
    }
  }
}

/** findings 为弱类型存储，van-field 的 model 只接受 string | number */
function findingText(key: string) {
  const v = findings[key]
  return v == null ? '' : String(v)
}

function onPanelChange(key: string) {
  activePanelKey.value = key
  const panel = EXAM_PANELS.find((p) => p.key === key) || EXAM_PANELS[0]
  if (!panel.examTypes.includes(examType.value)) {
    examType.value = panel.examTypes[0]
  }
}

watch(examType, (type) => {
  if (loadingExisting.value) return
  resetFindings(type)
})

form.examinedAt = nowLocalInput()

function openOcrPicker() {
  fileInputRef.value?.click()
}

/** OCR 识别不出类型时保留当前选择，只回填结论与时间 */
function applyOcr(data: ExamOcrResult) {
  const type = data.examType && EXAM_FINDING_FIELDS[data.examType] ? data.examType : ''
  if (type) {
    const panel = EXAM_PANELS.find((p) => p.examTypes.includes(type)) || EXAM_PANELS[0]
    loadingExisting.value = true
    activePanelKey.value = panel.key
    examType.value = type
    applyFindings(type, data.findings || {})
    loadingExisting.value = false
  }
  form.examinedAt = data.examinedAt ? String(data.examinedAt).slice(0, 16) : nowLocalInput()
  form.conclusion = data.conclusion || ''
  ocrApplied.value = true
  ocrHint.value = [
    type ? '' : '未识别出检查类型，请手动选择',
    data.ignoredFindings?.length ? `已忽略 ${data.ignoredFindings.length} 项无法对齐的测量` : '',
    data.warnings?.length ? data.warnings.join('；') : '',
    '识别结果仅供参考，请核对后提交',
  ]
    .filter(Boolean)
    .join('；')
}

async function onOcrFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    showToast('图片大小不能超过 5MB')
    return
  }
  ocrLoading.value = true
  try {
    const res = await apiUpload<{ data: ExamOcrResult }>('/api/c/v1/me/exams/ocr', file)
    applyOcr(res.data)
    showToast({ type: 'success', message: '识别完成，请核对后提交' })
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}

async function submit() {
  if (!form.examinedAt) {
    showToast('请填写检查时间')
    return
  }
  const payloadFindings = buildFindingsPayload(examType.value, findings)
  if (!hasExamDraft(examType.value, payloadFindings, form.conclusion)) {
    showToast('请至少填写一项测量或结论')
    return
  }

  saving.value = true
  try {
    const body = {
      examType: examType.value,
      examinedAt: toApiDateTime(form.examinedAt),
      conclusion: form.conclusion.trim() || undefined,
      findings: Object.keys(payloadFindings).length ? payloadFindings : undefined,
    }
    if (isEdit.value) {
      await api(`/api/c/v1/me/exams/${editId.value}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      })
      showToast({ type: 'success', message: '已保存修改' })
      router.replace(`/health-data/exams/${editId.value}`)
    } else {
      const res = await api<{ data: { id: string } }>('/api/c/v1/me/exams', {
        method: 'POST',
        body: JSON.stringify(body),
      })
      showToast({ type: 'success', message: '已添加检查' })
      router.replace(`/health-data/exams/${res.data.id}`)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!isEdit.value) return
  pageLoading.value = true
  loadingExisting.value = true
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/exams/${editId.value}`)
    const data = res.data
    if (!isPatientSource(data?.source)) {
      showToast('仅可修改自己添加的检查')
      router.replace(`/health-data/exams/${editId.value}`)
      return
    }
    const type = String(data.examType || EXAM_PANELS[0].examTypes[0])
    const panel = EXAM_PANELS.find((p) => p.examTypes.includes(type)) || EXAM_PANELS[0]
    activePanelKey.value = panel.key
    examType.value = type
    form.examinedAt = data.examinedAt ? String(data.examinedAt).slice(0, 16) : nowLocalInput()
    form.conclusion = data.conclusion || ''
    applyFindings(type, data.findings || {})
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
    router.back()
  } finally {
    loadingExisting.value = false
    pageLoading.value = false
  }
})
</script>

<template>
  <div class="page">
    <van-nav-bar :title="navTitle" left-arrow @click-left="router.back()" />
    <van-loading v-if="pageLoading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else>
    <section v-if="!isEdit" class="card">
      <div class="ocr-head">
        <div>
          <h2>拍照识别</h2>
          <p class="hint">上传检查报告照片，自动预填后请核对</p>
        </div>
        <van-button size="small" round type="primary" plain :loading="ocrLoading" @click="openOcrPicker">
          拍照识别
        </van-button>
      </div>
      <input
        ref="fileInputRef"
        class="file-input"
        type="file"
        accept="image/*"
        capture="environment"
        @change="onOcrFileChange"
      />
      <p v-if="ocrApplied" class="ocr-tag">已使用 OCR 预填</p>
      <p v-if="ocrHint" class="ocr-hint">{{ ocrHint }}</p>
    </section>

    <section class="card">
      <h2>检查分类</h2>
      <div class="panel-row">
        <button
          v-for="p in EXAM_PANELS"
          :key="p.key"
          type="button"
          class="panel-chip"
          :class="{ active: activePanelKey === p.key }"
          @click="onPanelChange(p.key)"
        >
          {{ p.label }}
        </button>
      </div>
      <p class="hint">选择检查类别后填写关键测量与结论</p>
    </section>

    <section class="card">
      <h2>报告信息</h2>
      <van-field label="检查时间" required>
        <template #input>
          <input v-model="form.examinedAt" type="datetime-local" class="datetime" />
        </template>
      </van-field>
      <div v-if="showTypeSwitch" class="type-row">
        <span class="type-label">检查项目</span>
        <div class="type-chips">
          <button
            v-for="t in activePanel.examTypes"
            :key="t"
            type="button"
            class="panel-chip"
            :class="{ active: examType === t }"
            @click="examType = t"
          >
            {{ examTypeLabel(t) }}
          </button>
        </div>
      </div>
      <van-field v-else :model-value="examTypeLabel(examType)" label="检查项目" readonly />
    </section>

    <section class="card">
      <h2>{{ examTypeLabel(examType) }}</h2>
      <div v-for="field in fieldRows" :key="field.key" class="item-block">
        <div class="item-head">
          <strong>{{ field.label }}</strong>
          <span v-if="field.unit" class="ref">{{ field.unit }}</span>
        </div>
        <van-field v-if="field.type === 'select'" label="结果">
          <template #input>
            <select v-model="findings[field.key]" class="select">
              <option value="">请选择</option>
              <option v-for="opt in field.options || []" :key="opt" :value="opt">
                {{ optionLabel(field.key, opt) }}
              </option>
            </select>
          </template>
        </van-field>
        <van-field v-else-if="field.type === 'bool'" label="结果">
          <template #input>
            <van-switch v-model="findings[field.key]" size="20px" />
          </template>
        </van-field>
        <van-field
          v-else-if="field.type === 'number'"
          :model-value="findingText(field.key)"
          type="number"
          :label="field.unit || '数值'"
          placeholder="填写数值"
          @update:model-value="findings[field.key] = $event"
        />
        <van-field
          v-else
          :model-value="findingText(field.key)"
          label="结果"
          placeholder="填写"
          @update:model-value="findings[field.key] = $event"
        />
      </div>
      <van-field
        v-model="form.conclusion"
        rows="3"
        autosize
        type="textarea"
        maxlength="1000"
        show-word-limit
        label="结论"
        placeholder="可填写影像/功能学结论摘要"
      />
    </section>

    <div class="footer">
      <van-button round block type="primary" :loading="saving" @click="submit">{{ submitLabel }}</van-button>
    </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 88px;
}
.card {
  background: #fff;
  border-radius: 16px;
  margin: 12px 16px;
  padding: 14px;
  box-shadow: var(--hx-shadow);
}
h2 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 700;
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.ocr-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.ocr-head .hint {
  margin: 4px 0 0;
}
.file-input {
  display: none;
}
.ocr-tag {
  margin: 10px 0 0;
  display: inline-block;
  font-size: 11px;
  font-weight: 650;
  color: #b45309;
  background: #fff7ed;
  border-radius: 999px;
  padding: 3px 8px;
}
.ocr-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #9a3412;
  background: #fff7ed;
  border-radius: 10px;
  padding: 8px 10px;
}
.panel-row,
.type-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.panel-chip {
  border: 0;
  background: #f3f6f8;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  padding: 7px 10px;
  border-radius: 999px;
}
.panel-chip.active {
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}
.type-row {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f3f3;
}
.type-label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  color: var(--hx-muted);
}
.datetime,
.select {
  width: 100%;
  border: 0;
  background: transparent;
  font-size: 14px;
  outline: none;
  color: var(--hx-text);
}
.item-block + .item-block {
  margin-top: 8px;
  border-top: 1px solid #f0f3f3;
  padding-top: 8px;
}
.item-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 2px;
}
.item-head strong {
  font-size: 14px;
}
.ref {
  font-size: 11px;
  color: var(--hx-muted);
  flex-shrink: 0;
}
.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, transparent, #f7f9fc 30%);
}
</style>
