<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from './http'
import {
  EXAM_FINDING_FIELDS,
  examTypeLabel,
  formatExamFindingValue,
} from './exam-panels'
import {
  formatOcrDt,
  MED_DOSE_UNIT_FALLBACK,
  MED_FREQUENCY_FALLBACK,
  MED_TIMING_PRESETS,
  MED_USAGE_OPTIONS,
  type MedDictOption,
  type OcrReview,
} from './agent-ocr'

const props = defineProps<{
  review: OcrReview
}>()

const emit = defineEmits<{
  preview: [url: string]
  confirm: []
  discard: []
}>()

const medFrequencyOptions = ref<MedDictOption[]>([...MED_FREQUENCY_FALLBACK])
const medDoseUnitOptions = ref<MedDictOption[]>([...MED_DOSE_UNIT_FALLBACK])

const editable = computed(() => props.review.status === 'pending')
const saving = computed(() => props.review.status === 'saving')

onMounted(() => {
  void loadMedDictOptions()
})

async function loadMedDictOptions() {
  try {
    const [frequency, doseUnit] = await Promise.all([
      api<{ data: Array<{ dictCode: string; dictCodeDesc?: string }> }>(
        '/api/b/v1/dict?dictType=OPTION&parentCode=medicationFrequency',
      ),
      api<{ data: Array<{ dictCode: string; dictCodeDesc?: string }> }>(
        '/api/b/v1/dict?dictType=OPTION&parentCode=doseUnit',
      ),
    ])
    if (frequency.data?.length) {
      medFrequencyOptions.value = frequency.data
        .filter((o) => o.dictCode && o.dictCode !== 'OTHER')
        .map((o) => ({
          value: o.dictCode,
          label: (o.dictCodeDesc || o.dictCode).replace(/\s*\([^)]*\)\s*$/, '').trim(),
        }))
    }
    if (doseUnit.data?.length) {
      medDoseUnitOptions.value = doseUnit.data
        .filter((o) => o.dictCode && o.dictCode !== 'OTHER')
        .map((o) => ({
          value: o.dictCode,
          label: o.dictCodeDesc || o.dictCode,
        }))
    }
  } catch {
    // 字典失败时用内置兜底
  }
}

function flagLabel(flag?: string | null) {
  if (flag === 'H') return '偏高'
  if (flag === 'L') return '偏低'
  if (flag === 'N') return '正常'
  return flag || '—'
}

function specimenLabel(code?: string | null) {
  if (code === 'URINE') return '尿'
  if (code === 'BLOOD') return '血'
  return code || '—'
}

function medFrequencyLabel(code?: string | null) {
  if (!code) return '—'
  return medFrequencyOptions.value.find((o) => o.value === code)?.label || code
}

function medDoseUnitLabel(code?: string | null) {
  if (!code) return ''
  return medDoseUnitOptions.value.find((o) => o.value === code)?.label || code
}

function examFindingRows() {
  const exam = props.review.exam
  if (!exam?.examType) return []
  const fields = EXAM_FINDING_FIELDS[exam.examType] || []
  const findings = exam.findings || {}
  return fields
    .filter((f) => findings[f.key] != null && findings[f.key] !== '')
    .map((f) => ({
      key: f.key,
      label: f.label,
      value: formatExamFindingValue(f.key, findings[f.key]),
      unit: f.unit || '',
    }))
}

/** 服用提示选项：含 OCR 常见「餐前/餐后」写法 */
const timingSelectOptions = computed(() => {
  const base = [...MED_TIMING_PRESETS]
  const extra = ['餐前', '餐后']
  const seen = new Set<string>(base)
  for (const t of extra) {
    if (!seen.has(t)) {
      base.push(t)
      seen.add(t)
    }
  }
  return base
})

</script>

<template>
  <div class="ocr-card">
    <div class="ocr-card-head">
      <div class="ocr-card-title">
        <strong>{{ review.title }}</strong>
        <span class="ocr-kind">{{
          review.kind === 'LAB' ? '检验' : review.kind === 'MED' ? '用药' : '检查'
        }}</span>
      </div>
      <button
        v-if="review.imageUrl"
        type="button"
        class="ocr-view-link"
        @click="emit('preview', review.imageUrl!)"
      >
        查看大图
      </button>
    </div>

    <button
      v-if="review.imageUrl"
      type="button"
      class="ocr-origin"
      title="点击放大查看"
      @click="emit('preview', review.imageUrl!)"
    >
      <img :src="review.imageUrl" alt="上传原图" />
    </button>

    <template v-if="review.kind === 'LAB' && review.lab">
      <div class="ocr-meta">
        <div class="ocr-meta-field">
          <span class="ocr-meta-label">标本</span>
          <span class="ocr-meta-value">{{ specimenLabel(review.lab.specimenType) }}</span>
        </div>
        <label class="ocr-meta-field">
          <span class="ocr-meta-label">采样时间</span>
          <el-date-picker
            v-if="editable"
            v-model="review.lab.sampledAt"
            type="datetime"
            size="small"
            clearable
            placeholder="采样时间"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm"
            class="ocr-dt"
          />
          <span v-else class="ocr-meta-value">{{ formatOcrDt(review.lab.sampledAt) }}</span>
        </label>
        <label class="ocr-meta-field">
          <span class="ocr-meta-label">报告时间</span>
          <el-date-picker
            v-if="editable"
            v-model="review.lab.reportedAt"
            type="datetime"
            size="small"
            clearable
            placeholder="报告时间"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm"
            class="ocr-dt"
          />
          <span v-else class="ocr-meta-value">{{ formatOcrDt(review.lab.reportedAt) }}</span>
        </label>
      </div>
      <div class="ocr-table-wrap">
        <table class="ocr-table">
          <thead>
            <tr>
              <th>项目</th>
              <th>结果</th>
              <th>单位</th>
              <th>参考范围</th>
              <th>标志</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in review.lab.items" :key="item.itemCode">
              <td>{{ item.itemName }}</td>
              <td>
                <template v-if="editable">
                  <input
                    v-if="item.valueText && item.valueNum == null"
                    v-model="item.valueText"
                    class="ocr-input"
                    type="text"
                  />
                  <input
                    v-else
                    v-model.number="item.valueNum"
                    class="ocr-input"
                    type="number"
                    step="any"
                  />
                </template>
                <template v-else>
                  {{ item.valueNum != null ? item.valueNum : item.valueText || '—' }}
                </template>
              </td>
              <td>{{ item.unit || '—' }}</td>
              <td>
                {{
                  item.refLow != null || item.refHigh != null
                    ? `${item.refLow ?? ''} ~ ${item.refHigh ?? ''}`
                    : '—'
                }}
              </td>
              <td :class="{ hi: item.abnormalFlag === 'H', lo: item.abnormalFlag === 'L' }">
                {{ flagLabel(item.abnormalFlag) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else-if="review.kind === 'EXAM' && review.exam">
      <div class="ocr-meta">
        <div class="ocr-meta-field">
          <span class="ocr-meta-label">类型</span>
          <span class="ocr-meta-value">{{
            review.exam.examTypeName || examTypeLabel(review.exam.examType || '')
          }}</span>
        </div>
        <label class="ocr-meta-field">
          <span class="ocr-meta-label">检查时间</span>
          <el-date-picker
            v-if="editable"
            v-model="review.exam.examinedAt"
            type="datetime"
            size="small"
            clearable
            placeholder="检查时间"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm"
            class="ocr-dt"
          />
          <span v-else class="ocr-meta-value">{{ formatOcrDt(review.exam.examinedAt) }}</span>
        </label>
      </div>
      <div class="ocr-table-wrap">
        <table class="ocr-table">
          <thead>
            <tr>
              <th>字段</th>
              <th>识别结果</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>结论</td>
              <td>
                <textarea
                  v-if="editable"
                  v-model="review.exam.conclusion"
                  class="ocr-textarea"
                  rows="3"
                />
                <template v-else>{{ review.exam.conclusion || '—' }}</template>
              </td>
            </tr>
            <tr v-for="row in examFindingRows()" :key="row.key">
              <td>{{ row.label }}</td>
              <td>{{ row.value }}{{ row.unit ? ` ${row.unit}` : '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else-if="review.kind === 'MED' && review.med">
      <div class="ocr-table-wrap">
        <table class="ocr-table ocr-table-med">
          <thead>
            <tr>
              <th>药品</th>
              <th>单次剂量</th>
              <th>频次</th>
              <th>用法</th>
              <th>服用提示</th>
              <th>疗程</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, mi) in review.med.items" :key="mi">
              <td>
                <input v-if="editable" v-model="item.drugName" class="ocr-input" type="text" />
                <template v-else>{{ item.drugName || '—' }}</template>
              </td>
              <td>
                <div v-if="editable" class="ocr-dose-edit">
                  <input
                    v-model="item.doseAmount"
                    class="ocr-input ocr-input-sm"
                    type="text"
                    placeholder="剂量"
                  />
                  <el-select
                    v-model="item.doseUnit"
                    class="ocr-unit-select"
                    size="small"
                    clearable
                    placeholder="单位"
                  >
                    <el-option
                      v-for="opt in medDoseUnitOptions"
                      :key="opt.value"
                      :label="opt.label"
                      :value="opt.value"
                    />
                  </el-select>
                </div>
                <template v-else>
                  {{ item.doseAmount || '—'
                  }}{{ item.doseUnit ? ` ${medDoseUnitLabel(item.doseUnit)}` : '' }}
                </template>
              </td>
              <td>
                <el-select
                  v-if="editable"
                  v-model="item.frequency"
                  class="ocr-freq-select"
                  size="small"
                  clearable
                  placeholder="频次"
                >
                  <el-option
                    v-for="opt in medFrequencyOptions"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
                <template v-else>{{ medFrequencyLabel(item.frequency) }}</template>
              </td>
              <td>
                <el-select
                  v-if="editable"
                  v-model="item.usageMethod"
                  class="ocr-usage-select"
                  size="small"
                  clearable
                  placeholder="用法"
                >
                  <el-option
                    v-for="opt in MED_USAGE_OPTIONS"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
                <template v-else>
                  {{ MED_USAGE_OPTIONS.find((o) => o.value === item.usageMethod)?.label || '—' }}
                </template>
              </td>
              <td>
                <el-select
                  v-if="editable"
                  v-model="item.timingNote"
                  class="ocr-timing-select"
                  size="small"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  placeholder="服用提示"
                >
                  <el-option
                    v-for="tag in timingSelectOptions"
                    :key="tag"
                    :label="tag"
                    :value="tag"
                  />
                </el-select>
                <template v-else>{{ item.timingNote || '—' }}</template>
              </td>
              <td>
                <div v-if="editable" class="ocr-course-edit">
                  <input
                    v-model.number="item.courseDays"
                    class="ocr-input ocr-input-sm"
                    type="number"
                    min="1"
                    placeholder="天"
                  />
                  <span class="ocr-course-unit">天</span>
                </div>
                <template v-else>
                  {{ item.courseDays != null && item.courseDays > 0 ? `${item.courseDays}天` : '—' }}
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <p v-if="review.warnings?.length" class="ocr-warn">{{ review.warnings.join('；') }}</p>

    <div v-if="editable || saving" class="ocr-actions">
      <button type="button" class="action-chip" :disabled="saving" @click="emit('discard')">
        取消
      </button>
      <button type="button" class="action-chip primary" :disabled="saving" @click="emit('confirm')">
        {{
          saving
            ? '写入中…'
            : review.kind === 'MED'
              ? '确认写入'
              : '确认入库'
        }}
      </button>
    </div>
    <p v-else-if="review.status === 'confirmed'" class="ocr-status ok">已确认入库</p>
  </div>
</template>

<style scoped>
.ocr-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ocr-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ocr-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ocr-card-head strong {
  font-size: 13px;
  color: var(--ink-900);
}

.ocr-kind {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
  flex-shrink: 0;
}

.ocr-view-link {
  border: 0;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  font-size: 12px;
  color: var(--brand-500);
  cursor: pointer;
  flex-shrink: 0;
}

.ocr-view-link:hover {
  text-decoration: underline;
}

.ocr-origin {
  display: block;
  width: fit-content;
  max-width: 100%;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--ink-200);
  background: #f8fafc;
  padding: 0;
  margin: 0;
  line-height: 0;
  cursor: zoom-in;
}

.ocr-origin:hover {
  border-color: var(--brand-300, #93c5fd);
}

.ocr-origin img {
  display: block;
  width: auto;
  max-width: min(220px, 100%);
  max-height: 120px;
  object-fit: contain;
  background: #f8fafc;
}

.ocr-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 24px;
  padding: 2px 0 4px;
  font-size: 12px;
  color: var(--ink-600);
}

.ocr-meta-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  min-width: 0;
}

.ocr-meta-label {
  flex-shrink: 0;
  color: var(--ink-500);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.2;
}

.ocr-meta-value {
  color: var(--ink-800);
  font-size: 12.5px;
  font-weight: 500;
  line-height: 1.35;
  white-space: nowrap;
}

.ocr-dt {
  width: 168px;
}

.ocr-dt :deep(.el-input__wrapper) {
  min-height: 28px;
}

.ocr-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--ink-100);
  border-radius: 8px;
}

.ocr-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.ocr-table th,
.ocr-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink-100);
  text-align: left;
  vertical-align: top;
}

.ocr-table th {
  background: #f8fafc;
  color: var(--ink-500);
  font-weight: 600;
}

.ocr-table tbody tr:last-child td {
  border-bottom: 0;
}

.ocr-table td.hi {
  color: #dc2626;
  font-weight: 600;
}

.ocr-table td.lo {
  color: #2563eb;
  font-weight: 600;
}

.ocr-input,
.ocr-textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--ink-200);
  border-radius: 6px;
  padding: 4px 8px;
  font: inherit;
  background: #fff;
}

.ocr-input-sm {
  width: 64px;
  flex-shrink: 0;
}

.ocr-table-med {
  min-width: 640px;
  table-layout: fixed;
}

.ocr-table-med th:nth-child(1),
.ocr-table-med td:nth-child(1) {
  width: 22%;
}

.ocr-table-med th:nth-child(2),
.ocr-table-med td:nth-child(2) {
  width: 18%;
}

.ocr-table-med th:nth-child(3),
.ocr-table-med td:nth-child(3),
.ocr-table-med th:nth-child(4),
.ocr-table-med td:nth-child(4) {
  width: 15%;
}

.ocr-table-med th:nth-child(5),
.ocr-table-med td:nth-child(5) {
  width: 16%;
}

.ocr-table-med th:nth-child(6),
.ocr-table-med td:nth-child(6) {
  width: 14%;
}

.ocr-table-med td {
  vertical-align: middle;
}

.ocr-dose-edit {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.ocr-unit-select {
  width: 72px;
  flex-shrink: 0;
}

.ocr-freq-select,
.ocr-usage-select,
.ocr-timing-select {
  width: 100%;
}

.ocr-unit-select :deep(.el-select__wrapper),
.ocr-freq-select :deep(.el-select__wrapper),
.ocr-usage-select :deep(.el-select__wrapper),
.ocr-timing-select :deep(.el-select__wrapper) {
  min-height: 28px;
}

.ocr-course-edit {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.ocr-course-unit {
  font-size: 11px;
  color: var(--ink-500);
}

.ocr-textarea {
  resize: vertical;
  min-height: 64px;
}

.ocr-warn {
  margin: 0;
  font-size: 12px;
  color: #b45309;
}

.ocr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.ocr-status.ok {
  margin: 0;
  font-size: 12px;
  color: #059669;
}

.action-chip {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}

.action-chip:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.action-chip.primary {
  background: var(--brand-500);
  color: #fff;
  border-color: var(--brand-500);
}
</style>
