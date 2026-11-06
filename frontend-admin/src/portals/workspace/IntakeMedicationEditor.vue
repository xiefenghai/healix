<script setup lang="ts">
/**
 * 首诊问卷「在用药物明细」：与用药管理「添加用药」同字段形态的可增删列表。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { MED_TIMING_PRESETS } from '../../shared/agent-ocr'

export interface IntakeMedItem {
  id?: string
  drugName: string
  drugCode?: string
  usageMethod: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  timingNote?: string
  startDate?: string
  stopDate?: string
  courseDays?: number | null
  courseMode?: 'long' | 'days'
  slots?: string[]
  hasAdverseReaction?: boolean
  remark?: string
}

interface DictItem {
  dictCode: string
  dictCodeDesc: string
}

interface DrugCatalogItem {
  code: string
  displayName: string
  genericName?: string
  spec?: string
  defaultDoseAmount?: string
  defaultDoseUnit?: string
  defaultUsageMethod?: string
  defaultFrequency?: string
}

const SLOT_OPTIONS = [
  { value: 'MORNING', label: '早' },
  { value: 'NOON', label: '午' },
  { value: 'EVENING', label: '晚' },
  { value: 'BEDTIME', label: '睡前' },
] as const

const QUICK_FREQ = [
  { code: 'QD', label: '每天1次' },
  { code: 'BID', label: '每天2次' },
  { code: 'TID', label: '每天3次' },
  { code: 'PRN', label: '按需' },
] as const

const SLOTS_BY_FREQ: Record<string, string[]> = {
  QD: ['MORNING'],
  BID: ['MORNING', 'EVENING'],
  TID: ['MORNING', 'NOON', 'EVENING'],
  QID: ['MORNING', 'NOON', 'EVENING', 'BEDTIME'],
  QN: ['BEDTIME'],
  QOD: ['MORNING'],
  PRN: [],
}

const props = defineProps<{
  modelValue: unknown
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: IntakeMedItem[]): void
}>()

const doseUnitOptions = ref<DictItem[]>([])
const usageOptions = ref<DictItem[]>([])
const frequencyOptions = ref<DictItem[]>([])
const legacyHint = ref('')
/** 当前展开编辑的行下标；null = 全部折叠成一行摘要 */
const expandedIdx = ref<number | null>(null)
/** 展开行是否打开「详细用法」 */
const showAdvanced = ref(false)
/** 展开行是否走自定义频率下拉 */
const customFreq = ref(false)

function emptyItem(): IntakeMedItem {
  return {
    drugName: '',
    usageMethod: 'ORAL',
    frequency: '',
    doseAmount: '',
    doseUnit: 'MG',
    timingNote: '',
    startDate: '',
    stopDate: '',
    courseDays: null,
    courseMode: 'long',
    slots: [],
    hasAdverseReaction: false,
    remark: '',
  }
}

function dictLabel(options: DictItem[], code?: string | null) {
  if (!code) return ''
  return options.find((o) => o.dictCode === code)?.dictCodeDesc ?? code
}

function frequencyLabel(code?: string | null) {
  if (!code) return ''
  const quick = QUICK_FREQ.find((q) => q.code === code)
  if (quick) return quick.label
  const known: Record<string, string> = {
    QID: '每天4次',
    QN: '每晚1次',
    QOD: '隔日1次',
    OTHER: '其他',
  }
  if (known[code]) return known[code]
  const full = dictLabel(frequencyOptions.value, code)
  return full.replace(/\s*\([^)]*\)\s*$/, '').trim() || full || code
}

function doseLabel(row: IntakeMedItem) {
  if (!row.doseAmount && !row.doseUnit) return ''
  const unit = dictLabel(doseUnitOptions.value, row.doseUnit) || row.doseUnit || ''
  const amount = row.doseAmount?.trim() ?? ''
  if (!amount) return unit
  return `${amount} ${unit}`.trim()
}

function slotPhrase(slots?: string[]) {
  const order = ['MORNING', 'NOON', 'EVENING', 'BEDTIME']
  const label: Record<string, string> = { MORNING: '早', NOON: '午', EVENING: '晚', BEDTIME: '睡前' }
  return order.filter((s) => slots?.includes(s)).map((s) => label[s]).join('')
}

function rowBrief(row: IntakeMedItem) {
  const bits: string[] = []
  const freq = frequencyLabel(row.frequency)
  if (freq) bits.push(freq)
  const dose = doseLabel(row)
  if (dose) bits.push(dose)
  let line = bits.join('，')
  const when = `${slotPhrase(row.slots)}${row.timingNote?.trim() || ''}`
  if (when) line += `（${when}）`
  if (row.courseMode === 'days' && row.courseDays) line += ` · ${row.courseDays}天`
  return line || '待补充用法'
}

function rowHasAdvanced(row: IntakeMedItem) {
  return !!(
    row.timingNote?.trim() ||
    row.startDate ||
    row.courseMode === 'days' ||
    row.hasAdverseReaction ||
    row.remark?.trim()
  )
}

function isQuickFreq(code?: string) {
  return QUICK_FREQ.some((q) => q.code === code)
}

function normalizeItem(raw: unknown): IntakeMedItem | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const drugName = String(o.drugName ?? o.name ?? '').trim()
  if (!drugName && !o.id) {
    // 允许空草稿行
  }
  const courseDays =
    o.courseDays == null || o.courseDays === ''
      ? null
      : Number(o.courseDays)
  const stopDate = o.stopDate ? String(o.stopDate) : ''
  const courseMode: 'long' | 'days' =
    o.courseMode === 'days' || (courseDays != null && courseDays > 0) || !!stopDate
      ? 'days'
      : 'long'
  return {
    id: o.id ? String(o.id) : undefined,
    drugName: String(o.drugName ?? o.name ?? ''),
    drugCode: o.drugCode ? String(o.drugCode) : '',
    usageMethod: String(o.usageMethod || 'ORAL'),
    frequency: o.frequency ? String(o.frequency) : '',
    doseAmount: o.doseAmount != null ? String(o.doseAmount) : '',
    doseUnit: o.doseUnit ? String(o.doseUnit) : 'MG',
    timingNote: o.timingNote ? String(o.timingNote) : '',
    startDate: o.startDate ? String(o.startDate) : '',
    stopDate,
    courseDays: Number.isFinite(courseDays as number) ? (courseDays as number) : null,
    courseMode,
    slots: Array.isArray(o.slots) ? o.slots.map(String) : [],
    hasAdverseReaction: o.hasAdverseReaction === true || o.hasAdverseReaction === 1,
    remark: o.remark ? String(o.remark) : '',
  }
}

function parseList(v: unknown): IntakeMedItem[] {
  legacyHint.value = ''
  if (v == null || v === '') return []
  if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return []
    try {
      const parsed = JSON.parse(t)
      return parseList(parsed)
    } catch {
      legacyHint.value = '原文本记录已保留在备注提示中，请按结构化重新录入'
      return []
    }
  }
  if (Array.isArray(v)) {
    return v.map(normalizeItem).filter((x): x is IntakeMedItem => !!x)
  }
  const one = normalizeItem(v)
  return one ? [one] : []
}

const items = reactive<IntakeMedItem[]>([])
/** 首次从 props 灌入时才自动展开空草稿；之后选药/改字段不得改折叠态 */
const listHydrated = ref(false)
/** 本组件 emit 后的回写，避免误当成外部刷新去改 expandedIdx */
let syncFromSelf = false

function syncFromProp() {
  const keepExpand = expandedIdx.value
  const next = parseList(props.modelValue)
  items.splice(0, items.length, ...next.map((x) => ({ ...emptyItem(), ...x })))
  if (!listHydrated.value) {
    listHydrated.value = true
    const draftIdx = items.findIndex((x) => !x.drugName.trim())
    expandedIdx.value = draftIdx >= 0 ? draftIdx : null
    return
  }
  // 选药 / 自动保存回写：保持当前展开行，绝不自动收起
  if (keepExpand != null && items.length > 0) {
    expandedIdx.value = Math.min(keepExpand, items.length - 1)
  } else if (keepExpand != null && items.length === 0) {
    expandedIdx.value = null
  }
}

watch(
  () => props.modelValue,
  () => {
    const cur = JSON.stringify(items)
    const next = JSON.stringify(parseList(props.modelValue))
    if (cur === next) return
    if (syncFromSelf) {
      syncFromSelf = false
      // 仍同步字段（父级可能规范化），但上面会保留 expandedIdx
    }
    syncFromProp()
  },
  { immediate: true, deep: true },
)

function emitList() {
  const out = items.map((row) => {
    const courseMode = row.courseMode || 'long'
    return {
      ...(row.id ? { id: row.id } : {}),
      drugName: row.drugName.trim(),
      drugCode: row.drugCode || undefined,
      usageMethod: row.usageMethod || 'ORAL',
      frequency: row.frequency || undefined,
      doseAmount: row.doseAmount?.trim() || undefined,
      doseUnit: row.doseUnit || undefined,
      timingNote: row.timingNote?.trim() || undefined,
      startDate: row.startDate || undefined,
      stopDate: courseMode === 'long' ? undefined : row.stopDate || undefined,
      courseDays: courseMode === 'days' ? row.courseDays ?? undefined : undefined,
      slots: row.frequency === 'PRN' ? undefined : row.slots?.length ? [...row.slots] : undefined,
      hasAdverseReaction: !!row.hasAdverseReaction,
      remark: row.remark?.trim() || undefined,
    } satisfies IntakeMedItem
  })
  syncFromSelf = true
  emit('update:modelValue', out)
  void nextTick(() => {
    syncFromSelf = false
  })
}

function addItem() {
  if (props.disabled) return
  items.push(emptyItem())
  openRow(items.length - 1)
  emitList()
}

function removeItem(idx: number) {
  if (props.disabled) return
  items.splice(idx, 1)
  if (expandedIdx.value == null) {
    // keep collapsed
  } else if (expandedIdx.value === idx) {
    expandedIdx.value = null
  } else if (expandedIdx.value > idx) {
    expandedIdx.value -= 1
  }
  emitList()
}

function openRow(idx: number) {
  expandedIdx.value = idx
  const row = items[idx]
  showAdvanced.value = row ? rowHasAdvanced(row) : false
  customFreq.value = !!row?.frequency && !isQuickFreq(row.frequency)
}

function applyFrequency(row: IntakeMedItem, code: string) {
  if (props.disabled) return
  row.frequency = code
  if (code in SLOTS_BY_FREQ) row.slots = [...SLOTS_BY_FREQ[code]]
  customFreq.value = !isQuickFreq(code)
  emitList()
}

function pickQuick(row: IntakeMedItem, code: string) {
  customFreq.value = false
  applyFrequency(row, code)
}

function pickCustom(row: IntakeMedItem) {
  customFreq.value = true
  if (!row.frequency || isQuickFreq(row.frequency)) {
    row.frequency = ''
    row.slots = []
    emitList()
  }
}

function pickUsage(row: IntakeMedItem, code: string) {
  if (props.disabled) return
  row.usageMethod = code
  emitList()
}

function rowReady(row?: IntakeMedItem) {
  if (!row) return false
  return !!row.drugName.trim() && !!row.frequency && !!row.doseAmount?.trim()
}

const usageChips = computed(() =>
  usageOptions.value.length
    ? usageOptions.value
    : [
        { dictCode: 'ORAL', dictCodeDesc: '口服' },
        { dictCode: 'INJECTION', dictCodeDesc: '注射' },
        { dictCode: 'INHALATION', dictCodeDesc: '吸入' },
      ],
)

function collapseRow() {
  expandedIdx.value = null
}

/** 药名、频率、剂量都手填后才允许保存，并收成一行摘要 */
function confirmRow(idx: number) {
  const row = items[idx]
  if (!row?.drugName.trim()) {
    ElMessage.warning('请先填写药品名称')
    return
  }
  if (!row.frequency) {
    ElMessage.warning('请选择用药频率')
    return
  }
  if (!row.doseAmount?.trim()) {
    ElMessage.warning('请填写单次剂量')
    return
  }
  expandedIdx.value = null
  emitList()
}

function onFieldChange() {
  emitList()
}

function applyTimingPreset(row: IntakeMedItem, tag: string) {
  if (props.disabled) return
  row.timingNote = tag
  emitList()
}

async function searchDrugCatalog(query: string, cb: (rows: DrugCatalogItem[]) => void) {
  const keyword = query.trim()
  if (!keyword) {
    cb([])
    return
  }
  try {
    const res = await api<{ data: DrugCatalogItem[] }>(
      `/api/b/v1/drug-catalog?keyword=${encodeURIComponent(keyword)}&limit=20`,
    )
    cb(res.data || [])
  } catch {
    cb([])
  }
}

function applyDrugCatalog(row: IntakeMedItem, raw: DrugCatalogItem | Record<string, unknown>) {
  const item = raw as DrugCatalogItem
  const name = String(item.displayName || '').trim()
  if (!name) return
  row.drugCode = item.code || ''
  row.drugName = name
  if (item.defaultDoseUnit) row.doseUnit = item.defaultDoseUnit
  if (item.defaultUsageMethod) row.usageMethod = item.defaultUsageMethod
  emitList()
}

function onCourseMode(row: IntakeMedItem, mode: 'long' | 'days') {
  row.courseMode = mode
  if (mode === 'long') {
    row.courseDays = null
    row.stopDate = ''
  }
  emitList()
}

const filledCount = computed(() => items.filter((x) => x.drugName.trim()).length)

async function loadDict() {
  const [doseUnit, usage, frequency] = await Promise.all([
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=doseUnit'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationUsage'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationFrequency'),
  ])
  doseUnitOptions.value = doseUnit.data ?? []
  usageOptions.value = usage.data ?? []
  frequencyOptions.value = frequency.data ?? []
}

onMounted(() => {
  void loadDict()
})
</script>

<template>
  <div class="intake-meds" :class="{ disabled }">
    <p v-if="legacyHint" class="legacy">{{ legacyHint }}</p>
    <div v-if="!items.length" class="empty">
      <p>尚未录入在用药物</p>
      <el-button type="primary" plain size="small" :disabled="disabled" @click="addItem">添加用药</el-button>
    </div>

    <div
      v-for="(row, idx) in items"
      :key="row.id || `new-${idx}`"
      class="med-card"
      :class="{ collapsed: expandedIdx !== idx }"
    >
      <div v-if="expandedIdx !== idx" class="med-line">
        <span class="med-pill" aria-hidden="true">💊</span>
        <span class="med-line-name" :class="{ placeholder: !row.drugName.trim() }">
          {{ row.drugName.trim() || '未命名药品' }}
        </span>
        <span class="med-line-meta">{{ rowBrief(row) }}</span>
        <button type="button" class="med-edit" :disabled="disabled" @click="openRow(idx)">编辑</button>
        <button
          type="button"
          class="med-trash"
          :disabled="disabled"
          aria-label="删除"
          title="删除"
          @click="removeItem(idx)"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
              d="M4 7h16M9 7V5h6v2M8 7l1 13h6l1-13"
            />
          </svg>
        </button>
      </div>

      <template v-else>
        <div class="med-head">
          <strong>药品 {{ idx + 1 }}</strong>
          <button
            type="button"
            class="med-trash"
            :disabled="disabled"
            aria-label="删除"
            title="删除"
            @click="removeItem(idx)"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
              <path
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M4 7h16M9 7V5h6v2M8 7l1 13h6l1-13"
              />
            </svg>
          </button>
        </div>

        <div class="quick">
          <label class="quick-label">药品名称</label>
          <el-autocomplete
            v-model="row.drugName"
            :fetch-suggestions="searchDrugCatalog"
            :trigger-on-focus="false"
            :disabled="disabled"
            clearable
            maxlength="128"
            placeholder="输入药品名称 / 搜药库"
            style="width: 100%"
            value-key="displayName"
            @select="(item: DrugCatalogItem) => applyDrugCatalog(row, item)"
            @change="onFieldChange"
          >
            <template #default="{ item }">
              <div class="drug-option" @mousedown.prevent>
                <div class="drug-option-main">
                  <span class="drug-option-name">{{ item.displayName }}</span>
                  <span
                    v-if="item.genericName && item.genericName !== item.displayName"
                    class="drug-option-generic"
                  >
                    {{ item.genericName }}
                  </span>
                </div>
                <span v-if="item.spec" class="drug-option-spec">{{ item.spec }}</span>
              </div>
            </template>
          </el-autocomplete>

          <label class="quick-label">快速频率</label>
          <div class="chip-row">
            <button
              v-for="q in QUICK_FREQ"
              :key="q.code"
              type="button"
              class="chip"
              :class="{ on: !customFreq && row.frequency === q.code }"
              :disabled="disabled"
              @click="pickQuick(row, q.code)"
            >
              {{ q.label }}
            </button>
            <button
              type="button"
              class="chip"
              :class="{ on: customFreq }"
              :disabled="disabled"
              @click="pickCustom(row)"
            >
              自定义
            </button>
          </div>
          <el-select
            v-if="customFreq"
            :model-value="row.frequency"
            :disabled="disabled"
            placeholder="选择频率"
            style="width: 100%"
            @update:model-value="(v: string) => applyFrequency(row, v)"
          >
            <el-option
              v-for="o in frequencyOptions"
              :key="o.dictCode"
              :label="o.dictCodeDesc"
              :value="o.dictCode"
            />
          </el-select>

          <label class="quick-label">单次剂量</label>
          <div class="dose-row">
            <el-input
              v-model="row.doseAmount"
              class="dose-input"
              :disabled="disabled"
              placeholder="1"
              @change="onFieldChange"
            />
            <el-select
              v-model="row.doseUnit"
              :disabled="disabled"
              placeholder="片/粒"
              style="width: 108px"
              @change="onFieldChange"
            >
              <el-option
                v-for="o in doseUnitOptions"
                :key="o.dictCode"
                :label="o.dictCodeDesc"
                :value="o.dictCode"
              />
            </el-select>
          </div>

          <label class="quick-label">用法</label>
          <div class="chip-row">
            <button
              v-for="o in usageChips"
              :key="o.dictCode"
              type="button"
              class="chip"
              :class="{ on: row.usageMethod === o.dictCode }"
              :disabled="disabled"
              @click="pickUsage(row, o.dictCode)"
            >
              {{ o.dictCodeDesc }}
            </button>
          </div>
        </div>

        <button
          type="button"
          class="adv-toggle"
          :disabled="disabled"
          @click="showAdvanced = !showAdvanced"
        >
          {{ showAdvanced ? '收起详细用法' : '+ 添加详细用法用量' }}
        </button>

        <el-form v-if="showAdvanced" label-width="88px" class="med-form" @submit.prevent>
          <el-form-item v-if="row.frequency && row.frequency !== 'PRN'" label="服药时段">
            <div class="timing-block">
              <el-checkbox-group v-model="row.slots" :disabled="disabled" @change="onFieldChange">
                <el-checkbox v-for="o in SLOT_OPTIONS" :key="o.value" :value="o.value">
                  {{ o.label }}
                </el-checkbox>
              </el-checkbox-group>
              <div class="slot-hint">改频率会重排时段，之后仍可手改</div>
            </div>
          </el-form-item>

          <el-form-item label="用药时机">
            <div class="timing-block">
              <el-input
                v-model="row.timingNote"
                :disabled="disabled"
                maxlength="64"
                placeholder="饭前 / 饭后"
                @change="onFieldChange"
              />
              <div class="timing-presets">
                <el-button
                  v-for="tag in MED_TIMING_PRESETS"
                  :key="tag"
                  size="small"
                  text
                  type="primary"
                  :disabled="disabled"
                  @click="applyTimingPreset(row, tag)"
                >
                  {{ tag }}
                </el-button>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="疗程">
            <div class="course-block">
              <el-radio-group
                :model-value="row.courseMode || 'long'"
                :disabled="disabled"
                @update:model-value="(v: 'long' | 'days') => onCourseMode(row, v)"
              >
                <el-radio value="long">长期服用</el-radio>
                <el-radio value="days">固定天数</el-radio>
              </el-radio-group>
              <el-input-number
                v-if="row.courseMode === 'days'"
                v-model="row.courseDays"
                :disabled="disabled"
                :min="1"
                :max="3650"
                :controls="false"
                @change="onFieldChange"
              />
              <span v-if="row.courseMode === 'days'" class="course-unit">天</span>
            </div>
          </el-form-item>

          <el-form-item label="开始服药">
            <el-date-picker
              v-model="row.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              :disabled="disabled"
              style="width: 100%"
              @change="onFieldChange"
            />
          </el-form-item>

          <el-form-item v-if="row.courseMode === 'days'" label="停药时间">
            <el-date-picker
              v-model="row.stopDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="可选"
              :disabled="disabled"
              style="width: 100%"
              @change="onFieldChange"
            />
          </el-form-item>

          <el-form-item label="不良反应">
            <el-radio-group
              v-model="row.hasAdverseReaction"
              :disabled="disabled"
              @change="onFieldChange"
            >
              <el-radio :value="false">否</el-radio>
              <el-radio :value="true">是</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="备注">
            <el-input
              v-model="row.remark"
              type="textarea"
              :rows="2"
              maxlength="150"
              show-word-limit
              :disabled="disabled"
              placeholder="特殊说明（选填）"
              @change="onFieldChange"
            />
          </el-form-item>
        </el-form>

        <div class="confirm-row">
          <el-button type="primary" size="small" :disabled="disabled || !rowReady(row)" @click="confirmRow(idx)">
            保存
          </el-button>
          <el-button size="small" :disabled="disabled" @click="collapseRow">收起</el-button>
        </div>
      </template>
    </div>

    <div v-if="items.length" class="foot">
      <el-button type="primary" plain size="small" :disabled="disabled" @click="addItem">
        + 再加一种药
      </el-button>
      <span class="meta">已填 {{ filledCount }} 种</span>
    </div>
  </div>
</template>

<style scoped>
.intake-meds {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.intake-meds.disabled {
  opacity: 0.85;
}

.legacy {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff7ed;
  color: #9a3412;
  font-size: 12.5px;
}

.empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px dashed var(--ink-200);
  border-radius: 10px;
  background: #fff;
}

.empty p {
  margin: 0;
  color: var(--ink-500);
  font-size: 13px;
}

.med-card {
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: #fff;
  padding: 10px 12px 4px;
}

.med-card.collapsed {
  padding: 0;
  overflow: hidden;
}

.med-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  min-height: 40px;
}

.med-pill {
  flex: none;
  font-size: 14px;
  line-height: 1;
}

.med-line-name {
  flex: none;
  max-width: 42%;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.med-line-name.placeholder {
  font-weight: 400;
  color: var(--ink-400);
}

.med-line-meta {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--ink-500);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.med-edit {
  flex: none;
  border: none;
  background: transparent;
  color: var(--brand-600, #2563eb);
  font-size: 12px;
  cursor: pointer;
  padding: 0 2px;
}

.med-edit:disabled,
.med-trash:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.med-trash {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 8px;
  background: #fef2f2;
  color: #dc2626;
  cursor: pointer;
}

.med-trash:hover:not(:disabled) {
  background: #fee2e2;
}

.quick {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 2px 0 8px;
}

.quick-label {
  font-size: 12px;
  color: var(--ink-500);
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}

.chip.on {
  border-color: var(--brand-500, #2563eb);
  background: var(--brand-50, #eff6ff);
  color: var(--brand-700, #1d4ed8);
  font-weight: 600;
}

.adv-toggle {
  border: none;
  background: transparent;
  color: var(--brand-600, #2563eb);
  font-size: 12px;
  cursor: pointer;
  padding: 0 0 8px;
  text-align: left;
}

.med-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.med-head strong {
  font-size: 13px;
  color: var(--ink-800);
}

.med-head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.confirm-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0 2px;
  margin-top: 4px;
}

.med-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.dose-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.dose-input :deep(.el-input__inner::placeholder) {
  color: #cbd5e1;
}

.timing-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.slot-hint {
  font-size: 12px;
  color: var(--ink-400);
}

.timing-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}

.course-block {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.course-unit {
  font-size: 13px;
  color: var(--ink-500);
}

.drug-option {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.drug-option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.drug-option-name {
  font-weight: 600;
}

.drug-option-generic,
.drug-option-spec {
  font-size: 12px;
  color: var(--ink-400);
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
}

.meta {
  font-size: 12px;
  color: var(--ink-400);
}
</style>
