<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, apiUpload } from '../api/http'
import { loadDictOptions, type DictItem } from '../shared/dict'
import { isPatientSource } from '../shared/health-data-source'
import { LAB_PANELS, type LabPanel } from '../shared/lab-panels'

interface LabItemRow {
  itemCode: string
  itemName: string
  unit: string
  refLow: number | null
  refHigh: number | null
  qualitative: boolean
  valueNum: string
  valueText: string
}

interface OcrItem {
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  refLow?: number | null
  refHigh?: number | null
}

interface OcrResult {
  specimenType?: string
  sampledAt?: string
  reportedAt?: string
  note?: string
  items?: OcrItem[]
  ignoredItems?: Array<{ rawName: string; reason?: string }>
  warnings?: string[]
}

const route = useRoute()
const router = useRouter()
const editId = computed(() => {
  const id = String(route.params.id || '')
  return route.path.includes('/edit') && id ? id : ''
})
const isEdit = computed(() => !!editId.value)
const pageLoading = ref(false)
const saving = ref(false)
const ocrLoading = ref(false)
const ocrHint = ref('')
const saveSource = ref<'PATIENT' | 'PATIENT_OCR'>('PATIENT')
const specimenOverride = ref<'BLOOD' | 'URINE' | ''>('')
const catalog = ref<DictItem[]>([])
const activePanelKey = ref(LAB_PANELS[0].key)
const fileInputRef = ref<HTMLInputElement | null>(null)
const form = reactive({
  sampledAt: '',
  reportedAt: '',
  note: '',
})
const valuesByCode = reactive<Record<string, { valueNum: string; valueText: string }>>({})
const metaOverrideByCode = reactive<
  Record<string, { unit?: string; refLow?: number | null; refHigh?: number | null; itemName?: string }>
>({})

const activePanel = computed<LabPanel>(
  () => LAB_PANELS.find((p) => p.key === activePanelKey.value) || LAB_PANELS[0],
)
const navTitle = computed(() => (isEdit.value ? '修改检验' : '添加检验'))
const submitLabel = computed(() => (isEdit.value ? '保存修改' : '保存'))

const specimenType = computed(() => {
  if (specimenOverride.value === 'BLOOD' || specimenOverride.value === 'URINE') {
    return specimenOverride.value
  }
  return activePanel.value.specimenType
})

function nowLocalInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

function parseContent(raw?: string): {
  unit?: string
  refLow?: number
  refHigh?: number
  qualitative?: boolean
} {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as {
      unit?: string
      refLow?: number
      refHigh?: number
      qualitative?: boolean
    }
  } catch {
    return {}
  }
}

function ensurePanelSlots(codes: string[]) {
  for (const code of codes) {
    if (!valuesByCode[code]) {
      valuesByCode[code] = { valueNum: '', valueText: '' }
    }
  }
}

function slotOf(code: string) {
  ensurePanelSlots([code])
  return valuesByCode[code]
}

/** 首屏即初始化，避免模板访问 undefined 导致白屏 */
ensurePanelSlots(LAB_PANELS[0].codes)
form.sampledAt = nowLocalInput()
form.reportedAt = nowLocalInput()

function isFilledCode(code: string) {
  const slot = valuesByCode[code]
  if (!slot) return false
  const dict = catalog.value.find((c) => c.dictCode === code)
  const qualitative = !!parseContent(dict?.content).qualitative
  if (qualitative) return !!slot.valueText.trim()
  return slot.valueNum !== '' && Number.isFinite(Number(slot.valueNum))
}

const panelRows = computed<LabItemRow[]>(() => {
  ensurePanelSlots(activePanel.value.codes)
  return activePanel.value.codes.map((code) => {
    const dict = catalog.value.find((c) => c.dictCode === code)
    const meta = parseContent(dict?.content)
    const override = metaOverrideByCode[code] || {}
    const slot = valuesByCode[code]
    return {
      itemCode: code,
      itemName: override.itemName || dict?.dictCodeDesc || code,
      unit: override.unit || meta.unit || '',
      refLow: override.refLow !== undefined ? override.refLow : (meta.refLow ?? null),
      refHigh: override.refHigh !== undefined ? override.refHigh : (meta.refHigh ?? null),
      qualitative: !!meta.qualitative,
      valueNum: slot.valueNum,
      valueText: slot.valueText,
    }
  })
})

function refText(row: LabItemRow) {
  if (row.refLow != null && row.refHigh != null) return `${row.refLow}~${row.refHigh}`
  if (row.refLow != null) return `≥${row.refLow}`
  if (row.refHigh != null) return `≤${row.refHigh}`
  return row.qualitative ? '定性' : ''
}

function onPanelChange(key: string) {
  activePanelKey.value = key
  const panel = LAB_PANELS.find((p) => p.key === key)
  if (panel) ensurePanelSlots(panel.codes)
}

function openOcrPicker() {
  fileInputRef.value?.click()
}

function applyOcr(data: OcrResult) {
  saveSource.value = 'PATIENT_OCR'
  form.sampledAt = data.sampledAt ? String(data.sampledAt).slice(0, 16) : nowLocalInput()
  form.reportedAt = data.reportedAt ? String(data.reportedAt).slice(0, 16) : nowLocalInput()
  form.note = data.note || ''
  if (data.specimenType === 'BLOOD' || data.specimenType === 'URINE') {
    specimenOverride.value = data.specimenType
  }

  for (const item of data.items || []) {
    if (!item.itemCode) continue
    ensurePanelSlots([item.itemCode])
    const dict = catalog.value.find((c) => c.dictCode === item.itemCode)
    const qualitative = !!parseContent(dict?.content).qualitative
    valuesByCode[item.itemCode] = {
      valueNum: qualitative || item.valueNum == null ? '' : String(item.valueNum),
      valueText: qualitative ? item.valueText || '' : '',
    }
    metaOverrideByCode[item.itemCode] = {
      itemName: item.itemName,
      unit: item.unit,
      refLow: item.refLow ?? null,
      refHigh: item.refHigh ?? null,
    }
  }

  const firstFilled =
    LAB_PANELS.find((p) => p.codes.some((c) => isFilledCode(c))) || LAB_PANELS[0]
  activePanelKey.value = firstFilled.key
  ensurePanelSlots(firstFilled.codes)

  const ignored = data.ignoredItems?.length ? `已忽略 ${data.ignoredItems.length} 项未映射项目` : ''
  const warn = data.warnings?.length ? data.warnings.join('；') : ''
  ocrHint.value = [ignored, warn, '识别结果仅供参考，请核对后提交'].filter(Boolean).join('；')
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
    const res = await apiUpload<{ data: OcrResult }>('/api/c/v1/me/labs/ocr', file)
    const data = res.data
    if (!data?.items?.length) {
      const ignored = data?.ignoredItems?.map((i) => i.rawName).join('、') || ''
      showToast(ignored ? `未识别到可录入项目，已忽略：${ignored}` : '未识别到可录入的检验项目')
      return
    }
    applyOcr(data)
    showToast({ type: 'success', message: '识别完成，请核对后提交' })
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}

function collectFilledItems() {
  const allCodes = [...new Set(LAB_PANELS.flatMap((p) => p.codes))]
  ensurePanelSlots(allCodes)
  return allCodes
    .map((code) => {
      const dict = catalog.value.find((c) => c.dictCode === code)
      const meta = parseContent(dict?.content)
      const override = metaOverrideByCode[code] || {}
      const slot = valuesByCode[code]
      const qualitative = !!meta.qualitative
      const filled = qualitative
        ? !!slot?.valueText.trim()
        : !!slot && slot.valueNum !== '' && Number.isFinite(Number(slot.valueNum))
      if (!filled) return null
      return {
        itemCode: code,
        itemName: override.itemName || dict?.dictCodeDesc || code,
        valueNum: qualitative ? undefined : Number(slot.valueNum),
        valueText: qualitative ? slot.valueText.trim() : undefined,
        unit: override.unit || meta.unit || undefined,
        refLow: override.refLow !== undefined ? override.refLow : (meta.refLow ?? null),
        refHigh: override.refHigh !== undefined ? override.refHigh : (meta.refHigh ?? null),
      }
    })
    .filter(Boolean) as Array<Record<string, unknown>>
}

function applyExisting(data: any) {
  if (data.specimenType === 'BLOOD' || data.specimenType === 'URINE') {
    specimenOverride.value = data.specimenType
  }
  form.sampledAt = data.sampledAt ? String(data.sampledAt).slice(0, 16) : nowLocalInput()
  form.reportedAt = data.reportedAt ? String(data.reportedAt).slice(0, 16) : nowLocalInput()
  form.note = data.note || ''
  const src = String(data.source || '').toUpperCase()
  saveSource.value = src === 'PATIENT_OCR' ? 'PATIENT_OCR' : 'PATIENT'

  for (const item of data.items || []) {
    if (!item.itemCode) continue
    ensurePanelSlots([item.itemCode])
    const dict = catalog.value.find((c) => c.dictCode === item.itemCode)
    const qualitative = !!parseContent(dict?.content).qualitative
    valuesByCode[item.itemCode] = {
      valueNum: qualitative || item.valueNum == null ? '' : String(item.valueNum),
      valueText: qualitative ? item.valueText || '' : '',
    }
    metaOverrideByCode[item.itemCode] = {
      itemName: item.itemName,
      unit: item.unit,
      refLow: item.refLow ?? null,
      refHigh: item.refHigh ?? null,
    }
  }

  const firstFilled =
    LAB_PANELS.find((p) => p.codes.some((c) => isFilledCode(c))) || LAB_PANELS[0]
  activePanelKey.value = firstFilled.key
  ensurePanelSlots(firstFilled.codes)
}

async function submit() {
  if (!form.reportedAt) {
    showToast('请填写报告时间')
    return
  }
  const items = collectFilledItems()
  if (!items.length) {
    showToast('请至少填写一项检验结果')
    return
  }

  saving.value = true
  try {
    const body = {
      specimenType: specimenType.value,
      sampledAt: toApiDateTime(form.sampledAt),
      reportedAt: toApiDateTime(form.reportedAt),
      note: form.note || undefined,
      source: saveSource.value,
      items,
    }
    if (isEdit.value) {
      await api(`/api/c/v1/me/labs/${editId.value}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      })
      showToast({ type: 'success', message: '已保存修改' })
      router.replace(`/health-data/labs/${editId.value}`)
    } else {
      const res = await api<{ data: { id: string } }>('/api/c/v1/me/labs', {
        method: 'POST',
        body: JSON.stringify(body),
      })
      showToast({ type: 'success', message: '已添加检验' })
      router.replace(`/health-data/labs/${res.data.id}`)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!isEdit.value) {
    form.sampledAt = nowLocalInput()
    form.reportedAt = nowLocalInput()
  }
  ensurePanelSlots(activePanel.value.codes)
  try {
    catalog.value = await loadDictOptions('labItemCode')
  } catch {
    catalog.value = []
    showToast('检验项目目录加载失败')
  }

  if (!isEdit.value) return
  pageLoading.value = true
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/labs/${editId.value}`)
    if (!isPatientSource(res.data?.source)) {
      showToast('仅可修改自己添加的检验')
      router.replace(`/health-data/labs/${editId.value}`)
      return
    }
    applyExisting(res.data)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
    router.back()
  } finally {
    pageLoading.value = false
  }
})
</script>

<template>
  <div class="page">
    <van-nav-bar :title="navTitle" left-arrow @click-left="router.back()" />
    <van-loading v-if="pageLoading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else>
    <section class="card">
      <div class="ocr-head">
        <div>
          <h2>拍照识别</h2>
          <p class="hint">上传化验单照片，自动预填后请核对</p>
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
      <p v-if="saveSource === 'PATIENT_OCR'" class="ocr-tag">已使用 OCR 预填</p>
      <p v-if="ocrHint" class="ocr-hint">{{ ocrHint }}</p>
    </section>

    <section class="card">
      <h2>检验分类</h2>
      <div class="panel-row">
        <button
          v-for="p in LAB_PANELS"
          :key="p.key"
          type="button"
          class="panel-chip"
          :class="{ active: activePanelKey === p.key }"
          @click="onPanelChange(p.key)"
        >
          {{ p.label }}
        </button>
      </div>
      <p class="hint">选择报告类别后填写对应项目，未测项目可留空</p>
    </section>

    <section class="card">
      <h2>报告信息</h2>
      <van-field label="采样时间">
        <template #input>
          <input v-model="form.sampledAt" type="datetime-local" class="datetime" />
        </template>
      </van-field>
      <van-field label="报告时间" required>
        <template #input>
          <input v-model="form.reportedAt" type="datetime-local" class="datetime" />
        </template>
      </van-field>
      <van-field v-model="form.note" label="备注" maxlength="100" placeholder="选填" />
    </section>

    <section class="card">
      <h2>{{ activePanel.label }}</h2>
      <div v-for="row in panelRows" :key="row.itemCode" class="item-block">
        <div class="item-head">
          <strong>{{ row.itemName }}</strong>
          <span v-if="refText(row)" class="ref">参考 {{ refText(row) }}</span>
        </div>
        <van-field
          v-if="row.qualitative"
          v-model="slotOf(row.itemCode).valueText"
          :label="row.unit || '结果'"
          placeholder="如 阴性 / 阳性"
        />
        <van-field
          v-else
          v-model="slotOf(row.itemCode).valueNum"
          type="number"
          :label="row.unit || '数值'"
          placeholder="填写数值"
        />
      </div>
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
.file-input {
  display: none;
}
.panel-row {
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
.datetime {
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
