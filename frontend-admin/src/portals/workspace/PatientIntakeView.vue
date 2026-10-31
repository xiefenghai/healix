<script setup lang="ts">
/**
 * 首诊问卷全屏工作台：模块导航 + 表单 + BMI/高亮追问。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { debounce } from '../../shared/debounce'
import {
  calcBmi,
  collectLocalHighlights,
  formatAnswerBrief,
  formatPrefillSource,
  isAnswerEmpty,
  matchShowIf,
  mergePrefillIntoAnswers,
  openIntake,
  openIntakeSupplement,
  saveIntakeDraft,
  submitIntake,
  valuesEqual,
  type IntakeCompleteness,
  type IntakeHighlightHit,
  type IntakeItemView,
  type IntakeModuleView,
  type IntakePrefillCell,
  type IntakeProgramCode,
  type QuestionnaireResponseView,
} from '../../shared/intake'
import { api } from '../../shared/http'
import FieldInput from './FieldInput.vue'

const route = useRoute()
const router = useRouter()

const peopleId = computed(() => String(route.params.peopleId || ''))
const followupRecordId = computed(() => {
  const q = route.query.followupRecordId
  return typeof q === 'string' && q ? q : null
})
const isSupplement = computed(() => String(route.query.purpose || '') === 'SUPPLEMENT')

const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const saveError = ref('')
const lastSavedAt = ref<Date | null>(null)
const patientName = ref('')
const responseId = ref('')
const status = ref('DRAFT')
const programCode = ref<IntakeProgramCode>('WEIGHT_LOSS')
const quickMode = ref(true)
const activeModule = ref('')
const modules = ref<IntakeModuleView[]>([])
const prefill = ref<Record<string, IntakePrefillCell>>({})
const answers = reactive<Record<string, unknown>>({})
const completeness = ref<IntakeCompleteness>({})
const serverHighlights = ref<IntakeHighlightHit[]>([])
const summaryOpen = ref(false)
const riskAck = ref(false)
const hydrated = ref(false)

const fieldLabelMap = computed(() => {
  const m = new Map<string, string>()
  for (const mod of modules.value) {
    for (const it of mod.items) m.set(it.field, it.label)
  }
  return m
})

function labelOf(field: string) {
  return fieldLabelMap.value.get(field) || field
}

function levelAllowed(level: string) {
  if (!quickMode.value) return true
  return level === 'REQUIRED' || level === 'FIRST_VISIT'
}

function itemVisible(item: IntakeItemView) {
  if (!levelAllowed(item.level)) return false
  return matchShowIf(item.showIf, answers)
}

function isFilled(field: string) {
  return !isAnswerEmpty(answers[field])
}

function isDirty(field: string) {
  const cell = prefill.value[field]
  if (!cell || isAnswerEmpty(cell.value)) {
    // 无预填：有值即算用户填写（不标黄）
    return false
  }
  return !valuesEqual(answers[field], cell.value)
}

function isPrefillTone(field: string) {
  const cell = prefill.value[field]
  if (!cell || isAnswerEmpty(cell.value)) return false
  return valuesEqual(answers[field], cell.value)
}

const visibleModules = computed(() =>
  modules.value
    .map((m) => ({
      ...m,
      items: m.items.filter(itemVisible),
    }))
    .filter((m) => m.items.length > 0),
)

const currentModule = computed(() => visibleModules.value.find((m) => m.code === activeModule.value) || null)

type ModuleTone = 'red' | 'orange' | 'green' | 'gray'

function moduleTone(mod: IntakeModuleView): ModuleTone {
  const items = mod.items.filter(itemVisible)
  if (!items.length) return 'gray'
  const requiredMissing = items.some((it) => it.level === 'REQUIRED' && !isFilled(it.field))
  if (requiredMissing) return 'red'
  const riskUnasked = items.some(
    (it) => (it.highlights?.length || 0) > 0 && !isFilled(it.field),
  )
  if (riskUnasked) return 'orange'
  const anyFilled = items.some((it) => isFilled(it.field))
  if (!anyFilled) return 'gray'
  const allDone = items.every((it) => isFilled(it.field))
  return allDone ? 'green' : 'gray'
}

function moduleFill(mod: IntakeModuleView) {
  const items = mod.items.filter(itemVisible)
  const done = items.filter((it) => isFilled(it.field)).length
  return { done, total: items.length }
}

const currentModuleIndex = computed(() =>
  visibleModules.value.findIndex((m) => m.code === activeModule.value),
)

function goPrevModule() {
  const i = currentModuleIndex.value
  if (i > 0) activeModule.value = visibleModules.value[i - 1].code
}

function goNextModule() {
  const i = currentModuleIndex.value
  if (i >= 0 && i < visibleModules.value.length - 1) {
    activeModule.value = visibleModules.value[i + 1].code
  }
}

const localCompleteness = computed(() => {
  let requiredDone = 0
  let requiredTotal = 0
  let firstVisitDone = 0
  let firstVisitTotal = 0
  let allDone = 0
  let allTotal = 0
  const requiredMissing: string[] = []
  const firstVisitMissing: string[] = []
  for (const m of modules.value) {
    for (const it of m.items) {
      // 进度按当前模式可见题（快速 = 必收+首诊建议）
      if (!itemVisible(it)) continue
      allTotal++
      const filled = isFilled(it.field)
      if (filled) allDone++
      if (it.level === 'REQUIRED') {
        requiredTotal++
        if (filled) requiredDone++
        else requiredMissing.push(it.field)
      } else if (it.level === 'FIRST_VISIT') {
        firstVisitTotal++
        if (filled) firstVisitDone++
        else firstVisitMissing.push(it.field)
      }
    }
  }
  return {
    requiredDone,
    requiredTotal,
    firstVisitDone,
    firstVisitTotal,
    allDone,
    allTotal,
    requiredMissing,
    firstVisitMissing,
  } satisfies IntakeCompleteness
})

const overallPct = computed(() => {
  const c = localCompleteness.value
  const allT = c.allTotal || 0
  return allT ? Math.round(((c.allDone || 0) / allT) * 100) : 0
})

const saveStatusText = computed(() => {
  if (saving.value) return '保存中…'
  if (saveError.value) return `保存失败 · 将重试`
  if (!lastSavedAt.value) return '未保存'
  const sec = Math.round((Date.now() - lastSavedAt.value.getTime()) / 1000)
  if (sec < 5) return '已保存 · 刚刚'
  if (sec < 60) return `已保存 · ${sec} 秒前`
  return `已保存 · ${Math.floor(sec / 60)} 分钟前`
})

const bmi = computed(() => calcBmi(answers['metric.HEIGHT'], answers['metric.WEIGHT']))

const bmiBand = computed(() => {
  const v = bmi.value
  if (v == null) return null
  if (v < 18.5) return { label: '偏瘦', tone: 'warn' as const }
  if (v < 24) return { label: '正常', tone: 'ok' as const }
  if (v < 28) return { label: '超重', tone: 'warn' as const }
  return { label: '肥胖', tone: 'danger' as const }
})

const highlightHits = computed(() => {
  const local = collectLocalHighlights(modules.value, answers)
  // 合并服务端（含 computed 等前端未覆盖的规则）
  const map = new Map<string, IntakeHighlightHit>()
  for (const h of serverHighlights.value || []) map.set(`${h.field}|${h.severity}|${h.planEffect}`, h)
  for (const h of local) map.set(`${h.field}|${h.severity}|${h.planEffect}`, h)
  return [...map.values()]
})

const riskUnasked = computed(() => {
  const list: IntakeItemView[] = []
  for (const m of modules.value) {
    for (const it of m.items) {
      if (!itemVisible(it)) continue
      if ((it.highlights?.length || 0) === 0) continue
      if (!isFilled(it.field)) list.push(it)
    }
  }
  return list
})

const changeRows = computed(() => {
  const rows: Array<{ field: string; label: string; from: unknown; to: unknown; source?: string }> = []
  for (const [field, cell] of Object.entries(prefill.value)) {
    if (isAnswerEmpty(cell?.value) && isAnswerEmpty(answers[field])) continue
    if (valuesEqual(cell?.value, answers[field])) continue
    if (isAnswerEmpty(answers[field])) continue
    rows.push({
      field,
      label: labelOf(field),
      from: cell?.value,
      to: answers[field],
      source: formatPrefillSource(cell?.sourceBizCode),
    })
  }
  return rows
})

const canSubmit = computed(
  () => status.value === 'DRAFT' && (localCompleteness.value.requiredMissing?.length || 0) === 0,
)

function applyView(view: QuestionnaireResponseView, seedAnswers: boolean) {
  responseId.value = view.id
  status.value = view.status || 'DRAFT'
  programCode.value = (view.programCode as IntakeProgramCode) || 'WEIGHT_LOSS'
  modules.value = view.modules || []
  prefill.value = (view.prefill || {}) as Record<string, IntakePrefillCell>
  completeness.value = view.completeness || {}
  serverHighlights.value = view.highlights || []

  if (seedAnswers) {
    const merged = mergePrefillIntoAnswers(view.answers || {}, prefill.value)
    Object.keys(answers).forEach((k) => delete answers[k])
    Object.assign(answers, merged)
  }

  if (!activeModule.value || !visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || modules.value[0]?.code || ''
  }
}

async function loadPatientName() {
  try {
    const res = await api<{ data: { displayName?: string } }>(`/api/b/v1/patients/${peopleId.value}`)
    patientName.value = res.data?.displayName || peopleId.value
  } catch {
    patientName.value = peopleId.value
  }
}

async function bootstrap() {
  if (!peopleId.value) return
  loading.value = true
  hydrated.value = false
  try {
    await loadPatientName()
    const view = isSupplement.value
      ? await openIntakeSupplement(peopleId.value, { channel: 'PHONE' })
      : await openIntake(peopleId.value, {
          // 不传 programCode：续开草稿保留原项目；新建由后端默认 WEIGHT_LOSS
          followupRecordId: followupRecordId.value,
          channel: 'PHONE',
        })
    if (isSupplement.value) {
      quickMode.value = false
    }
    applyView(view, true)
    hydrated.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || (isSupplement.value ? '打开待补问卷失败' : '打开首诊问卷失败'))
  } finally {
    loading.value = false
  }
}

async function persistDraft() {
  if (!responseId.value || status.value !== 'DRAFT' || !hydrated.value) return
  saving.value = true
  saveError.value = ''
  try {
    const view = await saveIntakeDraft(responseId.value, {
      answers: { ...answers },
      programCode: programCode.value,
      channel: 'PHONE',
    })
    applyView(view, false)
    completeness.value = view.completeness || completeness.value
    serverHighlights.value = view.highlights || []
    lastSavedAt.value = new Date()
  } catch (e) {
    saveError.value = (e as Error).message || '网络错误'
  } finally {
    saving.value = false
  }
}

const scheduleSave = debounce(() => {
  void persistDraft()
}, 2000)

watch(
  answers,
  () => {
    if (!hydrated.value || status.value !== 'DRAFT') return
    scheduleSave()
  },
  { deep: true },
)

watch(programCode, async (next, prev) => {
  if (!hydrated.value || next === prev || status.value !== 'DRAFT') return
  scheduleSave.cancel()
  await persistDraft()
})

watch(quickMode, () => {
  if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || ''
  }
})

function setAnswer(field: string, value: unknown) {
  answers[field] = value
}

function restorePrefill(field: string) {
  const cell = prefill.value[field]
  if (!cell) return
  answers[field] = cell.value
}

function goModule(code: string) {
  activeModule.value = code
}

function severityClass(sev?: string) {
  if (sev === 'BLOCK' || sev === 'REFER') return 'sev-danger'
  if (sev === 'CAUTION') return 'sev-warn'
  return 'sev-info'
}

function openSummary() {
  riskAck.value = riskUnasked.value.length === 0
  summaryOpen.value = true
}

function jumpToField(field: string) {
  summaryOpen.value = false
  for (const m of modules.value) {
    if (m.items.some((it) => it.field === field)) {
      // 若快速模式藏住了该题，切完整模式
      const item = m.items.find((it) => it.field === field)
      if (item && !levelAllowed(item.level)) quickMode.value = false
      activeModule.value = m.code
      break
    }
  }
}

async function doSubmit(conflictResolutions?: Record<string, unknown>) {
  if (!responseId.value) return
  submitting.value = true
  try {
    scheduleSave.cancel()
    await persistDraft()
    const view = await submitIntake(responseId.value, conflictResolutions)
    applyView(view, false)
    status.value = view.status || 'SUBMITTED'
    summaryOpen.value = false
    ElMessage.success(isSupplement.value ? '待补问卷已提交' : '首诊已提交')
    await router.push(`/workspace/patients/${peopleId.value}/assessments`)
  } catch (e) {
    const msg = (e as Error).message || '提交失败'
    if (msg.includes('冲突')) {
      try {
        await ElMessageBox.confirm(
          '打开问卷后部分字段已被他人修改。是否以本次作答为准覆盖？',
          '字段冲突',
          { confirmButtonText: '以本次为准', cancelButtonText: '返回修改', type: 'warning' },
        )
        const resolutions: Record<string, unknown> = {}
        for (const row of changeRows.value) resolutions[row.field] = row.to
        await doSubmit(resolutions)
      } catch {
        /* 用户取消 */
      }
    } else {
      ElMessage.error(msg)
    }
  } finally {
    submitting.value = false
  }
}

function backToPatient() {
  router.push(`/workspace/patients/${peopleId.value}/archive`)
}

onMounted(() => {
  void bootstrap()
})

onBeforeUnmount(() => {
  scheduleSave.cancel()
  if (hydrated.value && status.value === 'DRAFT') {
    void persistDraft()
  }
})
</script>

<template>
  <div class="intake" v-loading="loading">
    <header class="top">
      <div class="top-left">
        <button type="button" class="back" @click="backToPatient" title="关闭本页">关闭</button>
        <div class="who">
          <strong>{{ patientName || peopleId }}</strong>
          <span class="sub">{{ isSupplement ? '待补问卷' : '首诊问卷' }}</span>
        </div>
      </div>

      <div class="top-mid">
        <el-radio-group
          v-if="!isSupplement"
          v-model="programCode"
          size="small"
          :disabled="status !== 'DRAFT'"
        >
          <el-radio-button value="WEIGHT_LOSS">减重管理</el-radio-button>
          <el-radio-button value="NONE">暂不加入</el-radio-button>
        </el-radio-group>
        <el-radio-group v-if="!isSupplement" v-model="quickMode" size="small" class="mode">
          <el-radio-button :value="true">快速</el-radio-button>
          <el-radio-button :value="false">完整</el-radio-button>
        </el-radio-group>
        <div class="pills" aria-label="填写进度">
          <span
            class="pill"
            :class="{ warn: (localCompleteness.requiredDone || 0) < (localCompleteness.requiredTotal || 0) }"
          >
            必收 {{ localCompleteness.requiredDone || 0 }}/{{ localCompleteness.requiredTotal || 0 }}
          </span>
          <span
            class="pill"
            :class="{ soft: (localCompleteness.firstVisitDone || 0) < (localCompleteness.firstVisitTotal || 0) }"
          >
            建议 {{ localCompleteness.firstVisitDone || 0 }}/{{ localCompleteness.firstVisitTotal || 0 }}
          </span>
          <span class="pill pct">
            <i class="pct-bar" :style="{ width: `${overallPct}%` }" />
            <em>{{ overallPct }}%</em>
          </span>
        </div>
      </div>

      <div class="top-right">
        <span class="save" :class="{ err: !!saveError, ok: !saveError && !!lastSavedAt }">{{ saveStatusText }}</span>
        <el-button
          type="primary"
          :disabled="status !== 'DRAFT'"
          :loading="submitting"
          @click="openSummary"
        >
          提交
        </el-button>
      </div>
    </header>

    <div v-if="!loading" class="body">
      <aside class="nav">
        <div class="nav-head">模块</div>
        <button
          v-for="(m, idx) in visibleModules"
          :key="m.code"
          type="button"
          class="nav-item"
          :class="{ on: m.code === activeModule, [moduleTone(m)]: true }"
          @click="goModule(m.code)"
        >
          <span class="nav-idx">{{ idx + 1 }}</span>
          <span class="nav-copy">
            <span class="nav-title">{{ m.title }}</span>
            <span class="nav-fill">{{ moduleFill(m).done }}/{{ moduleFill(m).total }}</span>
          </span>
          <i class="dot" />
        </button>
        <p v-if="!visibleModules.length" class="empty-nav">当前模式无可见模块</p>
      </aside>

      <main class="form">
        <template v-if="currentModule">
          <div class="form-head">
            <div class="form-head-text">
              <p class="mod-kicker">
                模块 {{ currentModuleIndex + 1 }} / {{ visibleModules.length }}
              </p>
              <h2 class="mod-title">{{ currentModule.title }}</h2>
            </div>
            <div class="form-nav">
              <el-button size="small" :disabled="currentModuleIndex <= 0" @click="goPrevModule">
                上一模块
              </el-button>
              <el-button
                size="small"
                :disabled="currentModuleIndex < 0 || currentModuleIndex >= visibleModules.length - 1"
                @click="goNextModule"
              >
                下一模块
              </el-button>
            </div>
          </div>
          <div v-if="currentModule.script" class="script">
            <span class="script-label">沟通话术</span>
            <p>{{ currentModule.script }}</p>
          </div>

          <div
            v-for="(it, qi) in currentModule.items"
            :key="it.field"
            class="q"
            :class="{
              hit: highlightHits.some((h) => h.field === it.field),
              req: it.level === 'REQUIRED',
              filled: isFilled(it.field),
              prefill: isPrefillTone(it.field),
              dirty: isDirty(it.field),
            }"
          >
            <div class="q-head">
              <span class="q-no">{{ qi + 1 }}</span>
              <div class="q-main">
                <div class="q-label-row">
                  <span class="q-label">
                    <i v-if="it.level === 'REQUIRED'" class="star" aria-hidden="true">*</i>
                    {{ it.label }}
                  </span>
                  <span class="q-tags">
                    <span v-if="it.level === 'FIRST_VISIT'" class="tag tag-soft">首诊建议</span>
                    <span v-if="it.sensitive" class="tag">敏感</span>
                  </span>
                </div>
                <div v-if="isPrefillTone(it.field)" class="prefill-note">
                  预填 · {{ formatPrefillSource(prefill[it.field]?.sourceBizCode) }}
                  <template v-if="prefill[it.field]?.gmtModified">
                    · {{ String(prefill[it.field]?.gmtModified).replace('T', ' ').slice(0, 16) }}
                  </template>
                </div>
                <div v-else-if="isDirty(it.field)" class="prefill-note dirty-note">
                  已改预填值
                  <button
                    v-if="prefill[it.field]"
                    type="button"
                    class="restore"
                    @click="restorePrefill(it.field)"
                  >
                    恢复
                  </button>
                </div>
                <FieldInput
                  :field="it.field"
                  :type="it.type"
                  :unit="it.unit"
                  :options="it.options"
                  :model-value="answers[it.field]"
                  :prefilled="isPrefillTone(it.field)"
                  :dirty="isDirty(it.field)"
                  :disabled="status !== 'DRAFT'"
                  @update:model-value="(v) => setAnswer(it.field, v)"
                />
              </div>
            </div>
          </div>

          <div class="form-foot">
            <el-button :disabled="currentModuleIndex <= 0" @click="goPrevModule">上一模块</el-button>
            <el-button
              v-if="currentModuleIndex < visibleModules.length - 1"
              type="primary"
              plain
              @click="goNextModule"
            >
              下一模块
            </el-button>
            <el-button
              v-else
              type="primary"
              :disabled="status !== 'DRAFT'"
              @click="openSummary"
            >
              去提交
            </el-button>
          </div>
        </template>
        <el-empty v-else description="请选择左侧模块" />
      </main>

      <aside class="side">
        <section class="panel bmi-panel">
          <h3>体测速览</h3>
          <div class="bmi-hero">
            <div class="bmi-value">{{ bmi ?? '—' }}</div>
            <div class="bmi-meta">
              <span>BMI</span>
              <span v-if="bmiBand" class="bmi-band" :class="bmiBand.tone">{{ bmiBand.label }}</span>
            </div>
          </div>
          <dl class="bmi-rows">
            <div>
              <dt>身高</dt>
              <dd>{{ formatAnswerBrief(answers['metric.HEIGHT']) || '—' }} <small>cm</small></dd>
            </div>
            <div>
              <dt>体重</dt>
              <dd>{{ formatAnswerBrief(answers['metric.WEIGHT']) || '—' }} <small>kg</small></dd>
            </div>
          </dl>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h3>高亮追问</h3>
            <span v-if="highlightHits.length" class="badge">{{ highlightHits.length }}</span>
          </div>
          <div v-if="highlightHits.length" class="hits">
            <div
              v-for="(h, i) in highlightHits"
              :key="`${h.field}-${i}`"
              class="hit-card"
              :class="severityClass(h.severity)"
            >
              <div class="hit-title">{{ labelOf(h.field) }}</div>
              <div class="hit-meta">{{ h.severity || '提示' }}</div>
              <p v-if="h.followUp">{{ h.followUp }}</p>
              <p v-if="h.planEffect" class="plan">方案影响：{{ h.planEffect }}</p>
              <button type="button" class="hit-jump" @click="jumpToField(h.field)">去查看</button>
            </div>
          </div>
          <p v-else class="muted">填写时若命中风险题，追问会出现在这里</p>
        </section>

        <section v-if="riskUnasked.length" class="panel risk-panel">
          <h3>高风险未问 · {{ riskUnasked.length }}</h3>
          <ul class="risk-list">
            <li v-for="it in riskUnasked.slice(0, 5)" :key="it.field">
              <button type="button" class="link" @click="jumpToField(it.field)">{{ it.label }}</button>
            </li>
          </ul>
          <p v-if="riskUnasked.length > 5" class="muted">还有 {{ riskUnasked.length - 5 }} 项…</p>
        </section>
      </aside>
    </div>

    <el-dialog v-model="summaryOpen" title="提交前确认" width="640px" align-center destroy-on-close>
      <div class="sum">
        <section>
          <h4>必收缺项</h4>
          <ul v-if="localCompleteness.requiredMissing?.length">
            <li v-for="f in localCompleteness.requiredMissing" :key="f">
              <button type="button" class="link" @click="jumpToField(f)">{{ labelOf(f) }}</button>
            </li>
          </ul>
          <p v-else class="ok">已齐全</p>
        </section>

        <section>
          <h4>高风险未问</h4>
          <ul v-if="riskUnasked.length">
            <li v-for="it in riskUnasked" :key="it.field">
              <button type="button" class="link" @click="jumpToField(it.field)">{{ it.label }}</button>
            </li>
          </ul>
          <p v-else class="ok">无</p>
          <el-checkbox v-if="riskUnasked.length" v-model="riskAck">已知晓未问高风险题，仍要提交</el-checkbox>
        </section>

        <section>
          <h4>变更对照</h4>
          <ul v-if="changeRows.length" class="changes">
            <li v-for="row in changeRows" :key="row.field">
              <b>{{ row.label }}</b>
              <span class="muted">（{{ row.source }}）</span>
              ：{{ formatAnswerBrief(row.from) }} → {{ formatAnswerBrief(row.to) }}
            </li>
          </ul>
          <p v-else class="ok">无相对预填的修改</p>
        </section>

        <section>
          <h4>高亮汇总</h4>
          <ul v-if="highlightHits.length">
            <li v-for="(h, i) in highlightHits" :key="`s-${h.field}-${i}`">
              {{ labelOf(h.field) }} · {{ h.severity }}
              <span v-if="h.planEffect"> — {{ h.planEffect }}</span>
            </li>
          </ul>
          <p v-else class="ok">无</p>
        </section>
      </div>
      <template #footer>
        <el-button @click="summaryOpen = false">返回修改</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit || (riskUnasked.length > 0 && !riskAck)"
          @click="doSubmit()"
        >
          {{ isSupplement ? '提交待补' : '提交并完成首诊' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.intake {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  min-height: 480px;
  background: var(--ink-50);
}

.top {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid var(--ink-200);
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 180px;
}

.back {
  border: none;
  background: transparent;
  color: var(--ink-500);
  cursor: pointer;
  font-size: 13px;
}

.who {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.who strong {
  font-size: 15px;
  color: var(--ink-800);
}

.who .sub {
  font-size: 12px;
  color: var(--ink-400);
}

.top-mid {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  flex-wrap: wrap;
}

.mode {
  margin-left: 4px;
}

.progress {
  font-size: 12px;
  color: var(--ink-600);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.save {
  font-size: 12px;
  color: var(--ink-400);
}

.save.err {
  color: #b45309;
}

.body {
  flex: 1;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 260px;
  min-height: 0;
}

.nav {
  overflow: auto;
  padding: 12px 8px;
  border-right: 1px solid var(--ink-200);
  background: #fff;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
  border: none;
  background: transparent;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--ink-700);
  cursor: pointer;
  margin-bottom: 2px;
}

.nav-item:hover {
  background: var(--ink-50);
}

.nav-item.on {
  background: var(--brand-50);
  color: var(--brand-700);
  font-weight: 600;
}

.nav-item .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-300);
  flex-shrink: 0;
}

.nav-item.red .dot {
  background: #ef4444;
}

.nav-item.orange .dot {
  background: #f59e0b;
}

.nav-item.green .dot {
  background: #10b981;
}

.empty-nav {
  font-size: 12px;
  color: var(--ink-400);
  padding: 8px;
}

.form {
  overflow: auto;
  padding: 20px 24px 40px;
}

.script {
  font-size: 13px;
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  line-height: 1.55;
}

.mod-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--ink-800);
}

.q {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.q.hit {
  border-color: #f59e0b;
}

.q.req.hit {
  border-color: #ef4444;
}

.q-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 6px;
}

.q-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-800);
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.restore {
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.prefill-note {
  font-size: 12px;
  color: var(--ink-400);
  margin-bottom: 6px;
}

.side {
  overflow: auto;
  padding: 12px;
  border-left: 1px solid var(--ink-200);
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card {
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  padding: 12px;
}

.card h3 {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-700);
}

.bmi {
  margin: 0;
  display: grid;
  gap: 8px;
}

.bmi > div {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.bmi dt {
  color: var(--ink-500);
}

.bmi dd {
  margin: 0;
  color: var(--ink-800);
}

.hits {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hit-card {
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.45;
  border: 1px solid var(--ink-200);
}

.hit-card.sev-danger {
  background: #fef2f2;
  border-color: #fecaca;
}

.hit-card.sev-warn {
  background: var(--amber-50);
  border-color: #fde68a;
}

.hit-card.sev-info {
  background: var(--ink-50);
}

.hit-title {
  font-weight: 600;
  color: var(--ink-800);
}

.hit-meta {
  color: var(--ink-500);
  margin-bottom: 4px;
}

.hit-card .plan {
  margin: 4px 0 0;
  color: var(--ink-600);
}

.muted {
  font-size: 12px;
  color: var(--ink-400);
}

.sum section {
  margin-bottom: 16px;
}

.sum h4 {
  margin: 0 0 6px;
  font-size: 13px;
}

.sum ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
}

.sum .ok {
  margin: 0;
  font-size: 13px;
  color: #059669;
}

.link {
  border: none;
  background: none;
  color: var(--brand-600);
  cursor: pointer;
  padding: 0;
  font-size: inherit;
}

.changes li {
  margin-bottom: 4px;
}

@media (max-width: 960px) {
  .body {
    grid-template-columns: 1fr;
  }

  .nav,
  .side {
    border: none;
    max-height: 200px;
  }

  .intake {
    height: auto;
    min-height: calc(100vh - 56px);
  }
}
</style>
