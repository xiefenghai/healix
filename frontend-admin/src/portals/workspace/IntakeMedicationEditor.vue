<script setup lang="ts">
/**
 * 首诊问卷「在用药物明细」：与用药管理「添加用药」同字段形态的可增删列表。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
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

function emptyItem(): IntakeMedItem {
  return {
    drugName: '',
    usageMethod: 'ORAL',
    frequency: 'QD',
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
  const known: Record<string, string> = {
    QD: '每日1次',
    BID: '每日2次',
    TID: '每日3次',
    QID: '每日4次',
    QN: '每晚1次',
    QOD: '隔日1次',
    PRN: '必要时',
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
  const tight = ['g', 'mg', 'ml', 'G', 'MG', 'ML'].includes(unit)
  return tight ? `${amount}${unit}` : `${amount}${amount && unit ? ' ' : ''}${unit}`.trim()
}

function rowSummary(row: IntakeMedItem) {
  const parts: string[] = []
  const name = row.drugName.trim()
  if (name) parts.push(name)
  else parts.push('未命名药品')
  const dose = doseLabel(row)
  if (dose) parts.push(dose)
  const usage = dictLabel(usageOptions.value, row.usageMethod)
  if (usage) parts.push(usage)
  const freq = frequencyLabel(row.frequency)
  if (freq) parts.push(freq)
  if (row.courseMode === 'days' && row.courseDays) parts.push(`×${row.courseDays}天`)
  else if (row.courseMode !== 'days') parts.push('长期')
  let line = parts.join(' · ')
  if (row.timingNote?.trim()) line += `（${row.timingNote.trim()}）`
  return line
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
    frequency: o.frequency ? String(o.frequency) : 'QD',
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

function syncFromProp() {
  const next = parseList(props.modelValue)
  items.splice(0, items.length, ...next.map((x) => ({ ...emptyItem(), ...x })))
  // 外部同步时：空草稿行展开，其余折叠
  const draftIdx = items.findIndex((x) => !x.drugName.trim())
  expandedIdx.value = draftIdx >= 0 ? draftIdx : null
}

watch(
  () => props.modelValue,
  () => {
    const cur = JSON.stringify(items)
    const next = JSON.stringify(parseList(props.modelValue))
    if (cur !== next) syncFromProp()
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
  emit('update:modelValue', out)
}

function addItem() {
  if (props.disabled) return
  items.push(emptyItem())
  expandedIdx.value = items.length - 1
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

function toggleExpand(idx: number) {
  expandedIdx.value = expandedIdx.value === idx ? null : idx
}

function collapseRow() {
  expandedIdx.value = null
}

/** 确定：校验药名后折叠成摘要行 */
function confirmRow(idx: number) {
  const row = items[idx]
  if (!row?.drugName.trim()) {
    ElMessage.warning('请先填写药品名称')
    return
  }
  emitList()
  expandedIdx.value = null
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

function applyDrugCatalog(row: IntakeMedItem, item: DrugCatalogItem) {
  row.drugCode = item.code
  row.drugName = item.displayName
  if (item.defaultDoseAmount) row.doseAmount = item.defaultDoseAmount
  if (item.defaultDoseUnit) row.doseUnit = item.defaultDoseUnit
  if (item.defaultUsageMethod) row.usageMethod = item.defaultUsageMethod
  if (item.defaultFrequency) row.frequency = item.defaultFrequency
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
      <!-- 折叠：一行摘要 -->
      <button
        v-if="expandedIdx !== idx"
        type="button"
        class="med-summary"
        @click="toggleExpand(idx)"
      >
        <span class="med-summary-idx">{{ idx + 1 }}</span>
        <span class="med-summary-text" :class="{ placeholder: !row.drugName.trim() }">
          {{ rowSummary(row) }}
        </span>
        <span class="med-summary-hint">展开</span>
        <el-button
          link
          type="danger"
          size="small"
          :disabled="disabled"
          @click.stop="removeItem(idx)"
        >
          删除
        </el-button>
      </button>

      <!-- 展开：完整表单 -->
      <template v-else>
        <div class="med-head">
          <strong>药品 {{ idx + 1 }}</strong>
          <div class="med-head-actions">
            <el-button
              link
              type="danger"
              size="small"
              :disabled="disabled"
              @click="removeItem(idx)"
            >
              删除
            </el-button>
          </div>
        </div>

        <el-form label-width="88px" class="med-form" @submit.prevent>
          <el-form-item label="药品名称" required>
            <el-autocomplete
              v-model="row.drugName"
              :fetch-suggestions="searchDrugCatalog"
              :trigger-on-focus="false"
              :disabled="disabled"
              clearable
              maxlength="128"
              placeholder="搜索药品库，或手动输入"
              style="width: 100%"
              value-key="displayName"
              @select="(item: DrugCatalogItem) => applyDrugCatalog(row, item)"
              @change="onFieldChange"
            >
              <template #default="{ item }">
                <div class="drug-option">
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
          </el-form-item>

          <el-form-item label="单次剂量">
            <div class="dose-row">
              <el-input
                v-model="row.doseAmount"
                :disabled="disabled"
                placeholder="如：0.5 / 5"
                @change="onFieldChange"
              />
              <el-select
                v-model="row.doseUnit"
                clearable
                :disabled="disabled"
                placeholder="单位"
                style="width: 120px"
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
          </el-form-item>

          <el-form-item label="用法" required>
            <el-select
              v-model="row.usageMethod"
              :disabled="disabled"
              placeholder="请选择"
              style="width: 100%"
              @change="onFieldChange"
            >
              <el-option
                v-for="o in usageOptions"
                :key="o.dictCode"
                :label="o.dictCodeDesc"
                :value="o.dictCode"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="频率">
            <el-select
              v-model="row.frequency"
              clearable
              :disabled="disabled"
              placeholder="如：qd / bid / tid"
              style="width: 100%"
              @change="onFieldChange"
            >
              <el-option
                v-for="o in frequencyOptions"
                :key="o.dictCode"
                :label="o.dictCodeDesc"
                :value="o.dictCode"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="row.frequency !== 'PRN'" label="服药时段">
            <div class="timing-block">
              <el-checkbox-group v-model="row.slots" :disabled="disabled" @change="onFieldChange">
                <el-checkbox v-for="o in SLOT_OPTIONS" :key="o.value" :value="o.value">
                  {{ o.label }}
                </el-checkbox>
              </el-checkbox-group>
              <div class="slot-hint">可不选，提交后按频次自动安排</div>
            </div>
          </el-form-item>

          <el-form-item label="用药时机">
            <div class="timing-block">
              <el-input
                v-model="row.timingNote"
                :disabled="disabled"
                maxlength="64"
                placeholder="如：晨起、饭后、痛时服"
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
                controls-position="right"
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
              placeholder="如：24h≤4粒"
              @change="onFieldChange"
            />
          </el-form-item>

          <div class="confirm-row">
            <el-button type="primary" size="small" :disabled="disabled" @click="confirmRow(idx)">
              确定
            </el-button>
            <el-button size="small" :disabled="disabled" @click="collapseRow">收起</el-button>
          </div>
        </el-form>
      </template>
    </div>

    <div v-if="items.length" class="foot">
      <el-button type="primary" plain size="small" :disabled="disabled" @click="addItem">
        再加一种药
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

.med-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 0;
  padding: 10px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}

.med-summary:hover {
  background: #f8fafc;
}

.med-summary-idx {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--ink-100, #f1f5f9);
  color: var(--ink-600);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.med-summary-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--ink-800);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.med-summary-text.placeholder {
  color: var(--ink-400);
  font-weight: 400;
}

.med-summary-hint {
  flex: none;
  font-size: 12px;
  color: var(--ink-400);
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
