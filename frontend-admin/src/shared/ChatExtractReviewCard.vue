<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from './http'
import {
  applyWeightUnit,
  canConfirm,
  intakeExtractNotices,
  joinWhen,
  refreshAll,
  splitWhen,
  type ChatExtractReview,
  type ChatExtractRow,
} from './chat-extract'
import {
  DEFAULT_METRIC_REFS,
  evaluateAbnormal,
  evaluateBloodPressureFlags,
  refRangeText,
  resolveGlucoseRange,
  resolveWaistRange,
  type AbnormalFlag,
} from './metric-ranges'

const props = defineProps<{
  review: ChatExtractReview
  peopleId: string
  gender?: string
  /** 问卷仍打开时，INTAKE 行才能确认写入 */
  intakeOpen?: boolean
  /** 这批题分布在哪些模块，例如「涉及体测 3、睡眠与烟酒 6」 */
  spreadLine?: string
}>()

const emit = defineEmits<{
  written: [rows: ChatExtractRow[]]
  openReview: []
  discarded: []
}>()

const busy = ref(false)
const expanded = ref(false)
const sourcesOpen = ref(false)
const closed = computed(() =>
  ['CONFIRMED', 'SUPERSEDED', 'DISCARDED'].includes(props.review.status || ''),
)
const ready = computed(() => canConfirm(props.review))
const alive = computed(() => props.review.items.filter((row) => !row.removed && !row.outcome))
const settled = computed(() => props.review.items.filter((row) => row.outcome))
const hasVitals = computed(() => alive.value.some((row) => row.target === 'VITAL'))
const compactIntake = computed(
  () => alive.value.length > 3 && alive.value.every((row) => row.target === 'INTAKE'),
)
const extractNotices = computed(() => intakeExtractNotices(alive.value))

const sceneOptions = [
  { value: 'FASTING', label: '空腹' },
  { value: 'POSTPRANDIAL', label: '餐后' },
  { value: 'RANDOM', label: '随机' },
]
const bpOptions = [
  { value: 'HOME', label: '家庭自测' },
  { value: 'CLINIC', label: '诊室' },
  { value: 'THIRD_PARTY', label: '第三方机构' },
]

function touch() {
  refreshAll(props.review.items)
}

function setDate(row: ChatExtractRow, date: string | null) {
  const { time } = splitWhen(row.measuredAt)
  row.measuredAt = joinWhen(date || '', time)
  if (row.status === 'DUPLICATE') row.status = 'READY'
  touch()
}

function setTime(row: ChatExtractRow, time: string | null) {
  const { date } = splitWhen(row.measuredAt)
  row.measuredAt = joinWhen(date, time || '')
  if (row.status === 'DUPLICATE') row.status = 'READY'
  touch()
}

function chooseWeight(row: ChatExtractRow, unit: 'kg' | '斤') {
  applyWeightUnit(row, unit)
  touch()
}

function payload(row: ChatExtractRow) {
  return {
    id: row.id,
    target: row.target,
    code: row.code,
    quote: row.quote,
    value: row.value,
    systolic: row.systolic,
    diastolic: row.diastolic,
    unit: row.unit,
    sourceUnit: row.sourceUnit,
    measuredAt: row.measuredAt,
    mealContext: row.mealContext,
    bpContext: row.bpContext,
    archiveValue: row.archiveValue,
    derived: row.derived,
  }
}

async function submit(rows: ChatExtractRow[], discardIds: string[]) {
  touch()
  if (!props.review.messageId || busy.value || closed.value) return
  if (!rows.length && !discardIds.length) return
  if (rows.some((row) => row.status !== 'READY')) return
  busy.value = true
  try {
    const res = await api<{ data?: { status?: string } }>(
      `/api/b/v1/cockpit/patients/${props.peopleId}/chat-extract/confirm`,
      {
        method: 'POST',
        body: JSON.stringify({
          messageId: props.review.messageId,
          rows: rows.map(payload),
          discardIds,
        }),
      },
    )
    for (const row of rows) row.outcome = 'WRITTEN'
    for (const id of discardIds) {
      const found = props.review.items.find((item) => item.id === id)
      if (found) found.outcome = 'DISCARDED'
    }
    const pending = props.review.items.some((row) => !row.removed && !row.outcome)
    props.review.status = res.data?.status || (pending ? 'OPEN' : rows.length ? 'CONFIRMED' : 'DISCARDED')
    expanded.value = false
    const intake = rows.some((row) => row.target === 'INTAKE')
    if (intake) emit('written', rows.filter((row) => row.target === 'INTAKE'))
    const droppedIntake = discardIds.some((id) =>
      props.review.items.some((item) => item.id === id && item.target === 'INTAKE'),
    )
    if (!rows.length && droppedIntake) emit('discarded')
    ElMessage.success(rows.length ? (intake ? '已写入问卷' : '已写入') : '已丢弃')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '没有写入')
  } finally {
    busy.value = false
  }
}

function confirmRows(rows: ChatExtractRow[]) {
  if (rows.some((row) => row.target === 'INTAKE') && !props.intakeOpen) {
    ElMessage.info('问卷已关闭，请重新打开后再写入')
    return
  }
  submit(rows, [])
}

function multiCodes(row: ChatExtractRow) {
  try {
    const parsed = JSON.parse(row.archiveValue || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function toggleMulti(row: ChatExtractRow, code: string) {
  const next = multiCodes(row)
  const idx = next.indexOf(code)
  if (idx >= 0) next.splice(idx, 1)
  else next.push(code)
  row.archiveValue = JSON.stringify(next)
  touch()
}

function discardRows(rows: ChatExtractRow[]) {
  submit([], rows.map((row) => row.id))
}

async function undoDiscard() {
  if (!props.review.messageId || busy.value || props.review.status !== 'DISCARDED') return
  busy.value = true
  try {
    await api<{ data?: { status?: string } }>(
      `/api/b/v1/cockpit/patients/${props.peopleId}/chat-extract/reopen`,
      {
        method: 'POST',
        body: JSON.stringify({ messageId: props.review.messageId }),
      },
    )
    for (const row of props.review.items) {
      if (row.outcome === 'DISCARDED') row.outcome = null
    }
    props.review.status = 'OPEN'
    expanded.value = false
    ElMessage.success('已恢复，可以重新核对')
    emit('openReview')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '没有恢复')
  } finally {
    busy.value = false
  }
}

async function discardExtract() {
  try {
    await ElMessageBox.confirm(
      `确认要放弃这 ${alive.value.length} 项提取的数据吗？右侧核对会收起，问卷里原有的答案不会改。`,
      '放弃这次整理',
      { confirmButtonText: '放弃', cancelButtonText: '继续核对', type: 'warning' },
    )
  } catch {
    return
  }
  discardRows(alive.value)
}

function asNumber(value: unknown): number | null {
  if (value == null || value === '') return null
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

function glucoseRange(row: ChatExtractRow) {
  if (!row.mealContext) return null
  return resolveGlucoseRange(DEFAULT_METRIC_REFS.BLOOD_GLUCOSE, row.mealContext)
}

function rangeLabel(row: ChatExtractRow) {
  if (row.target !== 'VITAL') return ''
  if (row.code === 'BLOOD_PRESSURE') {
    return [refRangeText(DEFAULT_METRIC_REFS.BLOOD_PRESSURE_SYS), refRangeText(DEFAULT_METRIC_REFS.BLOOD_PRESSURE_DIA)]
      .filter(Boolean)
      .join(' / ')
  }
  if (row.code === 'BLOOD_GLUCOSE') {
    const range = glucoseRange(row)
    return range ? refRangeText(range) : ''
  }
  if (row.code === 'HEART_RATE') return refRangeText(DEFAULT_METRIC_REFS.HEART_RATE)
  if (row.code === 'WAIST') return refRangeText(resolveWaistRange(DEFAULT_METRIC_REFS.WAIST, props.gender))
  return ''
}

function singleFlag(row: ChatExtractRow): AbnormalFlag | null {
  if (row.code === 'BLOOD_GLUCOSE') {
    const range = glucoseRange(row)
    return range ? evaluateAbnormal(asNumber(row.value), range) : null
  }
  if (row.code === 'HEART_RATE') return evaluateAbnormal(asNumber(row.value), DEFAULT_METRIC_REFS.HEART_RATE)
  if (row.code === 'WAIST') {
    return evaluateAbnormal(asNumber(row.value), resolveWaistRange(DEFAULT_METRIC_REFS.WAIST, props.gender))
  }
  return null
}

function shownFlag(flag: AbnormalFlag | null) {
  return flag === 'H' || flag === 'L' ? flag : null
}

function arrowOf(flag: AbnormalFlag | null) {
  if (flag === 'H') return '↑'
  if (flag === 'L') return '↓'
  return ''
}

function bpMark(row: ChatExtractRow) {
  return evaluateBloodPressureFlags(
    asNumber(row.systolic),
    asNumber(row.diastolic),
    DEFAULT_METRIC_REFS.BLOOD_PRESSURE_SYS,
    DEFAULT_METRIC_REFS.BLOOD_PRESSURE_DIA,
  )
}

function rowAbnormal(row: ChatExtractRow) {
  if (row.code === 'BLOOD_PRESSURE') {
    const flags = bpMark(row)
    return !!(flags.sys || flags.dia)
  }
  return !!shownFlag(singleFlag(row))
}

function unitText(row: ChatExtractRow) {
  if (row.code === 'WEIGHT') return ''
  if (row.code === 'BLOOD_PRESSURE') return 'mmHg'
  return row.unit || ''
}

function optionLabel(options: { value: string; label: string }[], value?: string | null) {
  return options.find((item) => item.value === value)?.label || ''
}

function whenText(measuredAt?: string | null) {
  const { date, time } = splitWhen(measuredAt)
  if (date && time) return `${date.slice(5)} ${time}`
  if (date) return date.slice(5)
  return '未填时间'
}

function rowSummary(row: ChatExtractRow) {
  if (row.code === 'BLOOD_PRESSURE') {
    const scene = optionLabel(bpOptions, row.bpContext)
    return `${row.systolic ?? '—'}/${row.diastolic ?? '—'} mmHg${scene ? ' · ' + scene : ''} · ${whenText(row.measuredAt)}`
  }
  if (row.multi) {
    const labels = multiCodes(row).map((code) => optionLabel(row.options || [], code) || code)
    return labels.join('、') || '未填写'
  }
  if (row.target === 'ARCHIVE' || row.target === 'INTAKE') {
    const picked = row.options?.length ? optionLabel(row.options, row.archiveValue) : row.archiveValue
    return picked || '未填写'
  }
  const scene = row.code === 'BLOOD_GLUCOSE' ? optionLabel(sceneOptions, row.mealContext) : ''
  const unit = row.unit ? ` ${row.unit}` : ''
  return `${row.value ?? '—'}${unit}${scene ? ' · ' + scene : ''} · ${whenText(row.measuredAt)}`
}

function recallValue(row: ChatExtractRow) {
  const summary = rowSummary(row)
  if ((row.target === 'INTAKE' || row.target === 'ARCHIVE') && row.unit && summary && !summary.includes(row.unit)) {
    return `${summary} ${row.unit}`
  }
  return summary
}

function lineText(row: ChatExtractRow) {
  const summary = rowSummary(row)
  if (row.sourceUnit === '斤' && row.sourceValue != null) return `${summary}（${row.sourceValue}斤）`
  if (row.target === 'INTAKE') return summary
  if (row.target === 'ARCHIVE') return `${summary} · 现在${row.currentDisplay || '空'}`
  return summary
}

const ignoredNotes = computed(() =>
  (props.review.ignored || [])
    .map((item) => [item.reason, item.quote].filter(Boolean).join('：'))
    .filter(Boolean),
)

const ignoredText = computed(() => ignoredNotes.value.join('；'))

function openFix(row: ChatExtractRow) {
  return row.status !== 'READY' || !!row.editing
}

const closedLabel = computed(() => {
  if (props.review.status === 'DISCARDED') return '已丢弃'
  if (props.review.status === 'SUPERSEDED') return '已作废'
  return '已写入'
})
</script>

<template>
  <section
    class="extract-card"
    :class="{ done: closed, discarded: review.status === 'DISCARDED', compact: compactIntake && !closed }"
  >
    <header v-if="closed" class="head fold" @click="expanded = !expanded">
      <strong>从对话整理的记录</strong>
      <span class="pill" :class="{ ok: review.status === 'CONFIRMED' }">{{ closedLabel }}</span>
      <button
        v-if="review.status === 'DISCARDED'"
        type="button"
        class="ghost undo"
        :disabled="busy"
        @click.stop="undoDiscard"
      >
        恢复并重新核对
      </button>
      <span class="fold-entry">{{ expanded ? '收起' : `展开 ${review.items.length} 项` }}</span>
    </header>
    <header v-else class="head">
      <strong>从对话整理的记录</strong>
      <span v-if="alive.length" class="pill">{{ alive.length }} 条</span>
    </header>

    <div v-if="closed && expanded" class="snapshot recall">
      <p v-if="!review.items.length" class="muted">当时的识别明细没有留下</p>
      <article
        v-for="row in review.items"
        :key="row.id"
        class="recall-row"
        :title="row.quote || undefined"
      >
        <strong>{{ recallValue(row) }}</strong>
        <span>{{ row.label }}</span>
      </article>
      <p v-if="ignoredText" class="muted ellipsis" :title="ignoredText">未写入：{{ ignoredText }}</p>
    </div>

    <template v-if="!closed && compactIntake">
      <p class="summary">从这段话整理出 {{ alive.length }} 项，还没写入问卷。</p>
      <p v-if="spreadLine" class="summary spread">{{ spreadLine }}</p>
      <p v-if="extractNotices.length" class="warn">请留意：{{ extractNotices.join('；') }}。</p>
      <p v-if="review.overflow" class="warn">这次最多核对 12 项，其余没有列入。</p>
      <p v-if="!ready" class="warn">有几项还对不上，请在右侧改过再写入。</p>
      <p v-if="ignoredNotes.length" class="warn">同一题有另一种说法：{{ ignoredNotes.join('；') }}</p>
      <footer class="compact-actions">
        <button type="button" class="yes open-review" :disabled="busy" @click="emit('openReview')">
          打开右侧核对 {{ alive.length }} 项
        </button>
        <button type="button" class="ghost" :disabled="busy" @click="discardExtract">全部丢弃</button>
      </footer>
      <button type="button" class="text source-toggle" @click="sourcesOpen = !sourcesOpen">
        {{ sourcesOpen ? '收起识别来源' : '查看识别来源' }}
      </button>
      <div v-if="sourcesOpen" class="snapshot">
        <article v-for="row in alive" :key="row.id" class="snap">
          <div class="snap-line">
            <strong>{{ row.label }}</strong>
            <span>{{ rowSummary(row) }}</span>
          </div>
          <p v-if="row.quote" class="quote">
            <em class="quote-label">识别来源：</em>
            <span :title="row.quote">{{ row.quote }}</span>
          </p>
        </article>
      </div>
    </template>

    <template v-else-if="!closed">
      <p v-if="review.intake" class="muted">将写入正在填写的问卷</p>
      <p v-if="review.labHint" class="warn">化验项目请改用拍照识别</p>
      <p v-if="review.overflow" class="warn">有几行超出当次整理范围，已忽略</p>
      <p v-if="!alive.length" class="muted">没有可写入的内容</p>

      <div v-if="hasVitals" class="metric cols-head">
        <span>指标名称</span>
        <span>测量时间</span>
        <span>数值</span>
        <span>单位</span>
        <span>场景</span>
        <span>参考值</span>
        <span></span>
      </div>

      <article v-for="row in alive" :key="row.id" class="item">
        <div v-if="row.target === 'VITAL'" class="metric">
          <strong class="col-name">{{ row.label }}</strong>
          <div class="col-when">
            <el-date-picker
              :model-value="splitWhen(row.measuredAt).date || undefined"
              type="date"
              size="small"
              clearable
              placeholder="日期"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              class="metric-date"
              @update:model-value="setDate(row, $event)"
            />
            <el-time-picker
              :model-value="splitWhen(row.measuredAt).time || undefined"
              size="small"
              clearable
              placeholder="时刻"
              format="HH:mm"
              value-format="HH:mm"
              class="metric-time"
              @update:model-value="setTime(row, $event)"
            />
          </div>
          <div class="col-value" :class="{ pair: row.code === 'BLOOD_PRESSURE' }">
            <template v-if="row.code === 'BLOOD_PRESSURE'">
              <span class="num-wrap" :class="{ bad: bpMark(row).sys }">
                <input v-model.number="row.systolic" class="metric-num" type="number" placeholder="收缩" @change="touch" />
                <i v-if="bpMark(row).sys">{{ arrowOf(bpMark(row).sys) }}</i>
              </span>
              <span class="metric-slash">/</span>
              <span class="num-wrap" :class="{ bad: bpMark(row).dia }">
                <input v-model.number="row.diastolic" class="metric-num" type="number" placeholder="舒张" @change="touch" />
                <i v-if="bpMark(row).dia">{{ arrowOf(bpMark(row).dia) }}</i>
              </span>
            </template>
            <span v-else class="num-wrap" :class="{ bad: shownFlag(singleFlag(row)) }">
              <input v-model.number="row.value" class="metric-num" type="number" step="0.1" placeholder="数值" @change="touch" />
              <i v-if="shownFlag(singleFlag(row))">{{ arrowOf(shownFlag(singleFlag(row))) }}</i>
            </span>
          </div>
          <div class="col-unit">
            <select
              v-if="row.code === 'WEIGHT'"
              class="metric-extra"
              :value="row.unit === 'kg' ? 'kg' : ''"
              @change="chooseWeight(row, ($event.target as HTMLSelectElement).value as 'kg' | '斤')"
            >
              <option value="">单位</option>
              <option value="kg">公斤</option>
              <option value="斤">斤</option>
            </select>
            <span v-else class="unit-text">{{ unitText(row) }}</span>
          </div>
          <div class="col-scene">
            <select v-if="row.code === 'BLOOD_PRESSURE'" v-model="row.bpContext" class="metric-extra" @change="touch">
              <option v-for="opt in bpOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
            <select v-else-if="row.code === 'BLOOD_GLUCOSE'" v-model="row.mealContext" class="metric-extra" @change="touch">
              <option value="">场景</option>
              <option v-for="opt in sceneOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <span v-if="rangeLabel(row)" class="col-ref" :class="{ bad: rowAbnormal(row) }">{{ rangeLabel(row) }}</span>
          <span v-else class="col-ref"></span>
          <span class="ops">
            <button type="button" class="yes" :disabled="busy || row.status !== 'READY'" @click="confirmRows([row])">确认</button>
            <button type="button" class="no" :disabled="busy" @click="discardRows([row])">丢弃</button>
          </span>
        </div>
        <div v-else class="metric">
          <strong class="col-name">{{ row.label }}</strong>
          <span class="archive-summary">
            {{ lineText(row) }}
            <em v-if="row.status !== 'READY'" class="pill bad">{{ row.statusText || '待核对' }}</em>
          </span>
          <span class="ops">
            <button
              v-if="row.status === 'READY'"
              type="button"
              class="text"
              :disabled="busy"
              @click="row.editing = !row.editing"
            >
              {{ row.editing ? '收起' : '修改' }}
            </button>
            <button type="button" class="yes" :disabled="busy || row.status !== 'READY'" @click="confirmRows([row])">确认</button>
            <button type="button" class="no" :disabled="busy" @click="discardRows([row])">丢弃</button>
          </span>
        </div>
        <p v-if="row.quote || (row.target === 'VITAL' && row.status !== 'READY')" class="quote">
          <template v-if="row.quote">
            <em class="quote-label">识别来源：</em>
            <span :title="row.quote">{{ row.quote }}<template v-if="row.quoteTruncated">（已截断）</template></span>
          </template>
          <em v-if="row.target === 'VITAL' && row.status !== 'READY'" class="pill bad">{{ row.statusText || '待核对' }}</em>
        </p>
        <div v-if="row.target !== 'VITAL' && openFix(row)" class="grid">
          <div v-if="row.multi" class="field">
            <span>改为</span>
            <label v-for="opt in row.options" :key="opt.value" class="multi-opt">
              <input
                type="checkbox"
                :checked="multiCodes(row).includes(opt.value)"
                @change="toggleMulti(row, opt.value)"
              />
              {{ opt.label }}
            </label>
          </div>
          <label v-else-if="row.options?.length" class="field">
            <span>改为</span>
            <select v-model="row.archiveValue" @change="touch">
              <option value="">请选择</option>
              <option v-for="opt in row.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </label>
          <label v-else class="field">
            <span>改为</span>
            <input v-model="row.archiveValue" type="text" :maxlength="row.target === 'INTAKE' ? 200 : 40" @change="touch" />
          </label>
          <p v-if="row.derived" class="warn span-2">戒烟年份按这句话推算，可以改</p>
        </div>
      </article>

      <p v-if="ignoredText" class="muted ellipsis" :title="ignoredText">未写入：{{ ignoredText }}</p>

      <div v-if="settled.length" class="snapshot">
        <article v-for="row in settled" :key="row.id" class="snap">
          <div class="snap-line">
            <strong>{{ row.label }}</strong>
            <span>{{ rowSummary(row) }}</span>
            <em class="pill" :class="{ ok: row.outcome === 'WRITTEN' }">{{ row.outcome === 'DISCARDED' ? '已丢弃' : '已写入' }}</em>
          </div>
          <p v-if="row.quote" class="quote">
            <em class="quote-label">识别来源：</em>
            <span :title="row.quote">{{ row.quote }}</span>
          </p>
        </article>
      </div>

      <footer>
        <button type="button" class="ghost" :disabled="busy || !alive.length" @click="discardRows(alive)">全部丢弃</button>
        <button type="button" :disabled="busy || !ready" @click="confirmRows(alive)">全部写入</button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.extract-card.compact {
  width: min(420px, 100%);
}
.summary {
  margin: 0 0 8px;
  color: var(--ink-800, #1d2939);
  font-size: 14px;
  line-height: 1.5;
}
.summary.spread {
  margin-top: -4px;
  color: var(--ink-500, #667085);
  font-size: 13px;
}
.open-review {
  display: inline-flex;
  align-items: center;
  height: 28px;
  margin-top: 0;
  padding: 0 10px;
  border: 0;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}
.compact-actions {
  justify-content: flex-start;
  border-top: 0;
  padding-top: 0;
}
.source-toggle {
  display: block;
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink-500, #667085);
  font-size: 12px;
  cursor: pointer;
}
.extract-card {
  width: min(980px, 100%);
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid var(--ink-200, #e4e7ec);
  border-radius: 10px;
  background: #fff;
}
.extract-card.discarded {
  background: #f8fafc;
  border-color: #d0d5dd;
}
.undo {
  height: 26px;
  margin-left: auto;
  padding: 0 8px;
  border: 1px solid var(--ink-200, #e4e7ec);
  border-radius: 6px;
  background: #fff;
  color: var(--ink-700, #344054);
  font-size: 12px;
  cursor: pointer;
}
.undo:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.head.fold {
  cursor: pointer;
}
.fold-entry {
  margin-left: auto;
  color: var(--brand-500, #2c7ef8);
  font-size: 12px;
}
.snapshot {
  margin-top: 8px;
}
.snap {
  padding: 6px 0;
  border-top: 1px solid var(--ink-100, #f2f4f7);
}
.snap-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
  color: var(--ink-700, #344054);
}
.snap-line strong {
  flex: 0 0 72px;
  font-size: 13px;
  color: var(--ink-800, #1d2939);
}
.snap-line span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recall-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  padding: 7px 0;
  border-top: 1px solid var(--ink-100, #f2f4f7);
}
.recall-row strong {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 650;
  color: var(--ink-900, #101828);
}
.recall-row span {
  flex: 0 1 46%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
  font-size: 12px;
  color: var(--ink-400, #98a2b3);
}
.head strong {
  font-size: 13px;
  color: var(--ink-900, #101828);
}
.pill {
  font-size: 11px;
  line-height: 1;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--ink-100, #f2f4f7);
  color: var(--ink-600, #475467);
  white-space: nowrap;
}
.pill.ok {
  background: #ecfdf3;
  color: #027a48;
}
.pill.bad {
  background: #fffaeb;
  color: #b54708;
}
.item {
  padding: 5px 0;
  border-top: 1px solid var(--ink-100, #f2f4f7);
}
.metric {
  display: grid;
  grid-template-columns: 72px minmax(168px, 210px) minmax(108px, 128px) 84px minmax(96px, 112px) minmax(120px, 168px) max-content;
  align-items: center;
  column-gap: 8px;
}
.cols-head {
  margin-top: 8px;
  padding-bottom: 8px;
  color: var(--ink-400, #98a2b3);
  font-size: 11px;
  line-height: 1;
}
.col-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800, #1d2939);
  white-space: nowrap;
}
.col-when {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(68px, 0.85fr);
  gap: 6px;
  min-width: 0;
}
.col-when :deep(.el-date-editor) {
  width: 100% !important;
  min-width: 0 !important;
}
.metric :deep(.el-input__prefix) {
  display: none;
}
.metric :deep(.el-input__wrapper) {
  min-height: 26px;
  padding: 0 6px;
  border-radius: 6px;
}
.metric :deep(.el-input__inner) {
  font-size: 12px;
}
.col-value {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: center;
  min-width: 0;
}
.col-value.pair {
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  column-gap: 2px;
}
.num-wrap {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.num-wrap i {
  flex-shrink: 0;
  font-style: normal;
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
}
.num-wrap.bad input {
  color: #b42318;
  border-color: #fda29b;
  background: #fff6f6;
}
.num-wrap.bad i {
  color: #b42318;
}
.col-ref {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  line-height: 1.2;
  color: var(--ink-400, #98a2b3);
}
.col-ref.bad {
  color: #b42318;
}
.col-unit,
.col-scene {
  min-width: 0;
}
.unit-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ink-500, #667085);
}
.metric-num,
.metric-extra {
  width: 100%;
  min-width: 0;
  height: 26px;
  padding: 0 6px;
  border: 1px solid var(--ink-200, #e4e7ec);
  border-radius: 6px;
  background: #fff;
  color: var(--ink-800, #1d2939);
  font-size: 12px;
  box-sizing: border-box;
}
.metric-num::-webkit-outer-spin-button,
.metric-num::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.metric-slash {
  color: var(--ink-400, #98a2b3);
  text-align: center;
}
.archive-summary {
  grid-column: 2 / 7;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ink-600, #475467);
}
.archive-summary .pill {
  margin-left: 6px;
}
.ops {
  grid-column: 7;
  display: flex;
  flex-shrink: 0;
  gap: 4px;
}
.ops button,
footer button {
  height: 26px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}
.ops .yes,
.open-review,
footer button:not(.ghost) {
  color: #fff;
  background: var(--brand-500, #2c7ef8);
}
.ops .no,
.ops .text,
footer .ghost {
  color: var(--ink-500, #667085);
  border-color: var(--ink-200, #e4e7ec);
  background: #fff;
}
.ops button:disabled,
footer button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.quote,
.muted,
.warn {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ink-500, #667085);
}
.quote {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  min-width: 0;
}
.quote-label {
  flex-shrink: 0;
  font-style: normal;
  color: var(--ink-400, #98a2b3);
}
.quote > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.warn {
  color: #b54708;
}
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}
.span-2 {
  grid-column: 1 / -1;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  font-size: 12px;
  color: var(--ink-500, #667085);
}
.multi-opt {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-800, #1d2939);
}
.field .multi-opt input {
  width: auto;
  height: auto;
  padding: 0;
}
.field input,
.field select {
  width: 100%;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--ink-200, #e4e7ec);
  border-radius: 8px;
  background: #fff;
  color: var(--ink-800, #1d2939);
  font-size: 13px;
}
footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--ink-100, #f2f4f7);
}
footer button {
  height: 30px;
  padding: 0 12px;
  font-weight: 600;
}
.done {
  padding-bottom: 10px;
}
</style>
