<script setup lang="ts">
/**
 * 首诊问卷工作台：模块导航 + 表单 + BMI/高亮追问。
 * 可独立路由页，也可 embedded 嵌入驾驶舱抽屉。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { debounce } from '../../shared/debounce'
import {
  calcBmi,
  CARE_TRACK_OPTIONS,
  FIELD_STAFF_AI_SUMMARY,
  formatAnswerBrief,
  mergeLiveHighlights,
  formatIntakeAiSummaryText,
  formatPrefillSource,
  generateIntakeAiSummary,
  hasParsedIntakeAiSummary,
  isAnswerEmpty,
  isIntakeAnswerFilled,
  loadIntakeOptionMaps,
  matchShowIf,
  mergePrefillIntoAnswers,
  careTracksAnswerValue,
  normalizeCareTracks,
  openIntake,
  openIntakeSupplement,
  parseIntakeAiSummaryText,
  primaryProgramFromTracks,
  resolveIntakeFieldLabel,
  saveIntakeDraft,
  severityLabel,
  submitIntake,
  suggestedCareTracksFromAppeal,
  suggestedCareTracksFromIllness,
  valuesEqual,
  type IntakeCompleteness,
  type IntakeHighlightHit,
  type IntakeItemView,
  type IntakeModuleView,
  type IntakeOptionMaps,
  type IntakePrefillCell,
  type IntakeProgramCode,
  type QuestionnaireResponseView,
} from '../../shared/intake'
import { api } from '../../shared/http'
import { publishPatientCrossTab } from '../../shared/patient-cross-tab'
import FieldInput from './FieldInput.vue'
import IntakeAiSummaryBlocks from './IntakeAiSummaryBlocks.vue'

const route = useRoute()
const router = useRouter()

const props = withDefaults(
  defineProps<{
    /** 驾驶舱抽屉等场景传入；不传则走路由 */
    peopleId?: string
    followupRecordId?: string | null
    purpose?: string | null
    /** 嵌入抽屉：提交/关闭走 emit，不关窗、不跳路由 */
    embedded?: boolean
  }>(),
  { embedded: false },
)

const emit = defineEmits<{
  submitted: []
  close: []
}>()

const isEmbedded = computed(() => !!props.embedded)
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const followupRecordId = computed(() => {
  if (props.embedded) return props.followupRecordId || null
  const q = route.query.followupRecordId
  return typeof q === 'string' && q ? q : null
})
const isSupplement = computed(() => {
  if (props.embedded) return props.purpose === 'SUPPLEMENT'
  return String(route.query.purpose || '') === 'SUPPLEMENT'
})

const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const saveError = ref('')
const lastSavedAt = ref<Date | null>(null)
const patientName = ref('')
const responseId = ref('')
const status = ref('DRAFT')
const programCode = ref<IntakeProgramCode>('NONE')
const quickMode = ref(true)
const activeModule = ref('')
const modules = ref<IntakeModuleView[]>([])
const optionMaps = ref<IntakeOptionMaps>({})
const prefill = ref<Record<string, IntakePrefillCell>>({})
const answers = reactive<Record<string, unknown>>({})
const completeness = ref<IntakeCompleteness>({})
const serverHighlights = ref<IntakeHighlightHit[]>([])
const summaryOpen = ref(false)
const riskAck = ref(false)
const hydrated = ref(false)
const aiSummaryGenerating = ref(false)
const aiSummaryMeta = ref<{ fromLlm: boolean; note?: string | null } | null>(null)
const aiSummaryEditRaw = ref(false)
/** 从侧栏/确认框跳转后短暂高亮的题目 */
const focusedField = ref('')
let focusClearTimer: ReturnType<typeof setTimeout> | null = null
let aiSummaryAutoTried = false

const fieldLabelMap = computed(() => {
  const m = new Map<string, string>()
  for (const mod of modules.value) {
    for (const it of mod.items) m.set(it.field, it.label)
  }
  return m
})

const fieldItemMap = computed(() => {
  const m = new Map<string, IntakeItemView>()
  for (const mod of modules.value) {
    for (const it of mod.items) m.set(it.field, it)
  }
  return m
})

function labelOf(field: string, hitLabel?: string | null) {
  return resolveIntakeFieldLabel(field, {
    hitLabel,
    moduleLabel: fieldLabelMap.value.get(field),
  })
}

function displayAnswer(field: string, value: unknown) {
  return formatAnswerBrief(value, fieldItemMap.value.get(field), optionMaps.value)
}

function levelAllowed(level: string) {
  if (!quickMode.value) return true
  // 健管师补充 / AI 总结在精简模式下仍展示
  return level === 'REQUIRED' || level === 'FIRST_VISIT' || level === 'STAFF'
}

function itemVisible(item: IntakeItemView) {
  if (!levelAllowed(item.level)) return false
  // 待补模块/题目已由服务端按 showIf 裁剪，避免前端再用当前答案藏题导致与「N 项待补」不一致
  if (isSupplement.value) return true
  return matchShowIf(item.showIf, answers)
}

function isFilled(field: string) {
  return isIntakeAnswerFilled(field, answers)
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
    .filter((m) => isSupplement.value || matchShowIf(m.showIf, answers))
    .map((m) => ({
      ...m,
      items: m.items.filter(itemVisible),
    }))
    .filter((m) => m.items.length > 0),
)

const careTracks = computed(() => normalizeCareTracks(answers['goal.careTracks']))

const illnessTrackHints = computed(() => suggestedCareTracksFromIllness(answers['basic.presentIllness']))

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

function requiredStillMissing() {
  return (localCompleteness.value.requiredMissing?.length || 0) > 0
}

async function runAiSummary(force = false) {
  if (!responseId.value || status.value !== 'DRAFT' || aiSummaryGenerating.value) return
  if (!force && !isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) return
  // 必收未齐：不调 AI（自动/手动都拦），避免空卷生成无意义总结
  if (requiredStillMissing()) {
    ElMessage.warning('请先完成必收项后再生成 AI 总结')
    return
  }
  aiSummaryGenerating.value = true
  try {
    const draft = await generateIntakeAiSummary(responseId.value, { ...answers })
    answers[FIELD_STAFF_AI_SUMMARY] = formatIntakeAiSummaryText(draft)
    aiSummaryMeta.value = { fromLlm: draft.fromLlm, note: draft.note }
    aiSummaryEditRaw.value = false
    scheduleSave()
    if (draft.note) ElMessage.info(draft.note)
    else if (draft.fromLlm) ElMessage.success('已生成 AI 总结，可继续修改')
    else ElMessage.warning('已生成模板总结，建议润色后提交')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成总结失败')
  } finally {
    aiSummaryGenerating.value = false
  }
}

watch(activeModule, (code) => {
  if (code !== 'INTAKE_AI_SUMMARY' || status.value !== 'DRAFT') return
  if (aiSummaryAutoTried) return
  if (!isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) return
  // 必收未齐：不自动生成，等填完再进本模块或手动点
  if (requiredStillMissing()) return
  aiSummaryAutoTried = true
  void runAiSummary(false)
})

function isInternalScript(script?: string | null) {
  const s = (script || '').trim()
  return s.startsWith('【内部】') || s.startsWith('（不对客户说）')
}

function displayScript(script?: string | null) {
  const s = (script || '').trim()
  if (s.startsWith('【内部】')) return s.replace(/^【内部】\s*/, '')
  if (s.startsWith('（不对客户说）')) return s.replace(/^（不对客户说）\s*/, '') || '仅供健管师查看'
  return s
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
    if (!isSupplement.value && !matchShowIf(m.showIf, answers)) continue
    for (const it of m.items) {
      // 进度按当前模式可见题（快速 = 必收+首诊建议；待补信任服务端裁剪）
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

const highlightHits = computed(() =>
  // 本地可判定的题跟 answers 即时联动；computed 类规则仍保留服务端结果
  mergeLiveHighlights(modules.value, answers, serverHighlights.value),
)

const riskUnasked = computed(() => {
  const list: IntakeItemView[] = []
  for (const m of modules.value) {
    if (!matchShowIf(m.showIf, answers)) continue
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
    const fromText = displayAnswer(field, cell?.value)
    const toText = displayAnswer(field, answers[field])
    // 结构不同但案头文案相同（过敏/用药常见）→ 不算变更
    if (fromText === toText) continue
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

const hasAiSummary = computed(() => !isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY]))
const aiSummaryParsed = computed(() => parseIntakeAiSummaryText(answers[FIELD_STAFF_AI_SUMMARY]))
const showAiSummaryBlocks = computed(() => hasParsedIntakeAiSummary(aiSummaryParsed.value))

/** 正式首诊须有总结正文；待补问卷仍可选 */
const canSubmit = computed(
  () =>
    status.value === 'DRAFT' &&
    (localCompleteness.value.requiredMissing?.length || 0) === 0 &&
    (isSupplement.value || hasAiSummary.value),
)

const openingSummary = ref(false)

function applyView(view: QuestionnaireResponseView, seedAnswers: boolean) {
  responseId.value = view.id
  status.value = view.status || 'DRAFT'
  programCode.value = (view.programCode as IntakeProgramCode) || 'NONE'
  modules.value = view.modules || []
  prefill.value = (view.prefill || {}) as Record<string, IntakePrefillCell>
  completeness.value = view.completeness || {}
  serverHighlights.value = view.highlights || []
  void loadIntakeOptionMaps(view.modules).then((maps) => {
    optionMaps.value = maps
  })

  if (seedAnswers) {
    const merged = mergePrefillIntoAnswers(view.answers || {}, prefill.value)
    Object.keys(answers).forEach((k) => delete answers[k])
    Object.assign(answers, merged)
    // 核心诉求改为多选：历史单码字符串归一成数组
    if (typeof answers['goal.primaryAppeal'] === 'string' && answers['goal.primaryAppeal']) {
      answers['goal.primaryAppeal'] = [answers['goal.primaryAppeal']]
    }
    // 待补：管理方向/性别以预填为准，避免草稿改窄后藏模块，和「N 项待补」对不上
    if (isSupplement.value) {
      const tracks = prefill.value['goal.careTracks']?.value
      if (!isAnswerEmpty(tracks)) answers['goal.careTracks'] = tracks
      const gender = prefill.value['profile.gender']?.value
      if (!isAnswerEmpty(gender)) answers['profile.gender'] = gender
    }
    // 正式首诊：不默认勾选减重；管理方向由健管师点选，或按诉求/病史提示后采纳
  }

  syncProgramFromTracks()
  if (!activeModule.value || !visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || modules.value[0]?.code || ''
  }
}

function syncProgramFromTracks() {
  programCode.value = primaryProgramFromTracks(careTracks.value, answers['goal.primaryAppeal'])
}

function setCareTracks(next: IntakeProgramCode[], persist = true) {
  answers['goal.careTracks'] = careTracksAnswerValue(next)
  syncProgramFromTracks()
  if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || ''
  }
  if (persist && hydrated.value && status.value === 'DRAFT') {
    scheduleSave.cancel()
    void persistDraft()
  }
}

function toggleCareTrack(code: Exclude<IntakeProgramCode, 'NONE'>) {
  const cur = [...careTracks.value]
  const idx = cur.indexOf(code)
  if (idx >= 0) cur.splice(idx, 1)
  else cur.push(code)
  setCareTracks(cur)
}

function setNoProgram() {
  setCareTracks([])
}

function adoptIllnessTrackHints() {
  const merged = [...careTracks.value]
  for (const t of illnessTrackHints.value) {
    if (!merged.includes(t as Exclude<IntakeProgramCode, 'NONE'>)) {
      merged.push(t as Exclude<IntakeProgramCode, 'NONE'>)
    }
  }
  setCareTracks(merged)
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
  aiSummaryAutoTried = false
  aiSummaryMeta.value = null
  aiSummaryEditRaw.value = false
  clearFocusTimer()
  focusedField.value = ''
  try {
    await loadPatientName()
    const view = isSupplement.value
      ? await openIntakeSupplement(peopleId.value, { channel: 'PHONE' })
      : await openIntake(peopleId.value, {
          // 不传 programCode：续开草稿保留原选择；新建不默认减重
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

watch(
  () => answers['goal.careTracks'],
  () => {
    if (!hydrated.value || status.value !== 'DRAFT') return
    syncProgramFromTracks()
    if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
      activeModule.value = visibleModules.value[0]?.code || ''
    }
  },
  { deep: true },
)

watch(
  () => answers['goal.primaryAppeal'],
  (appeal, prev) => {
    if (!hydrated.value || status.value !== 'DRAFT' || appeal === prev) return
    const suggested = suggestedCareTracksFromAppeal(appeal)
    if (!suggested.length) return
    // 仅在尚未勾选任何方向，或当前方向完全不含建议项时，自动补上建议方向
    const cur = careTracks.value
    if (!cur.length || suggested.some((s) => !cur.includes(s as Exclude<IntakeProgramCode, 'NONE'>))) {
      const merged = [...cur]
      for (const s of suggested) {
        if (!merged.includes(s as Exclude<IntakeProgramCode, 'NONE'>)) {
          merged.push(s as Exclude<IntakeProgramCode, 'NONE'>)
        }
      }
      answers['goal.careTracks'] = careTracksAnswerValue(merged)
      syncProgramFromTracks()
    }
  },
)

watch(quickMode, () => {
  if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || ''
  }
})

watch(visibleModules, (mods) => {
  if (!mods.some((m) => m.code === activeModule.value)) {
    activeModule.value = mods[0]?.code || ''
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

function aiSummaryLooksIncomplete() {
  const text = String(answers[FIELD_STAFF_AI_SUMMARY] ?? '')
  return /尚未完成首诊|入组当日尚未完成首诊|首诊未完成/.test(text)
}

async function openSummary() {
  if (status.value !== 'DRAFT' || openingSummary.value) return
  riskAck.value = riskUnasked.value.length === 0
  // 正式首诊：无总结，或总结仍写着「尚未完成首诊」→ 提交前按当前答卷重生成
  if (
    !isSupplement.value &&
    (!hasAiSummary.value || (aiSummaryLooksIncomplete() && !requiredStillMissing()))
  ) {
    openingSummary.value = true
    try {
      await runAiSummary(true)
    } finally {
      openingSummary.value = false
    }
  }
  summaryOpen.value = true
}

function jumpToAiSummary() {
  summaryOpen.value = false
  activeModule.value = 'INTAKE_AI_SUMMARY'
  void focusQuestion(FIELD_STAFF_AI_SUMMARY)
}

function clearFocusTimer() {
  if (focusClearTimer) {
    clearTimeout(focusClearTimer)
    focusClearTimer = null
  }
}

async function focusQuestion(field: string) {
  if (!field) return
  clearFocusTimer()
  focusedField.value = field
  await nextTick()
  // 模块切换后 DOM 可能再刷一帧
  await new Promise((r) => requestAnimationFrame(() => r(undefined)))
  const el = document.querySelector(`[data-intake-field="${CSS.escape(field)}"]`) as HTMLElement | null
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    const focusable = el.querySelector<HTMLElement>(
      'input, textarea, select, button, [tabindex]:not([tabindex="-1"])',
    )
    focusable?.focus({ preventScroll: true })
  }
  focusClearTimer = setTimeout(() => {
    if (focusedField.value === field) focusedField.value = ''
    focusClearTimer = null
  }, 2600)
}

function jumpToField(field: string) {
  summaryOpen.value = false
  for (const m of modules.value) {
    if (m.items.some((it) => it.field === field)) {
      // 若快速模式藏住了该题，切完整模式
      const item = m.items.find((it) => it.field === field)
      if (item && !levelAllowed(item.level)) quickMode.value = false
      activeModule.value = m.code
      void focusQuestion(field)
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
    // 通知仍打开的患者详情页刷新首诊状态 / 档案
    publishPatientCrossTab({
      peopleId: peopleId.value,
      reason: isSupplement.value ? 'INTAKE_SUPPLEMENT' : 'INTAKE_SUBMITTED',
    })
    if (isEmbedded.value) {
      emit('submitted')
      return
    }
    // 同标签打开：提交后回患者档案
    await new Promise((r) => setTimeout(r, 350))
    await router.push(`/workspace/patients/${peopleId.value}/archive`)
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
  if (isEmbedded.value) {
    emit('close')
    return
  }
  router.push(`/workspace/patients/${peopleId.value}/archive`)
}

onMounted(() => {
  void bootstrap()
})

/** 抽屉内切换患者 / 随访 / 待补时重新拉卷 */
watch(
  () =>
    [props.peopleId, props.followupRecordId ?? '', props.purpose ?? '', props.embedded] as const,
  (next, prev) => {
    if (!props.embedded || !prev) return
    if (next[0] === prev[0] && next[1] === prev[1] && next[2] === prev[2]) return
    void bootstrap()
  },
)

onBeforeUnmount(() => {
  scheduleSave.cancel()
  clearFocusTimer()
  if (hydrated.value && status.value === 'DRAFT') {
    void persistDraft()
  }
})
</script>

<template>
  <div class="intake" :class="{ 'intake--embedded': isEmbedded }" v-loading="loading">
    <header class="top">
      <div class="top-left">
        <button
          v-if="!isEmbedded"
          type="button"
          class="back"
          @click="backToPatient"
          title="关闭本页"
        >
          关闭
        </button>
        <div class="who">
          <strong>{{ patientName || peopleId }}</strong>
          <span class="sub">{{ isSupplement ? '待补问卷' : '首诊问卷' }}</span>
        </div>
      </div>

      <div class="top-mid">
        <div v-if="!isSupplement" class="track-switch" role="group" aria-label="管理方向">
          <span class="track-switch-label">管理方向</span>
          <div class="track-btns">
            <button
              v-for="opt in CARE_TRACK_OPTIONS"
              :key="opt.code"
              type="button"
              class="track-btn"
              :class="{ on: careTracks.includes(opt.code) }"
              :disabled="status !== 'DRAFT'"
              @click="toggleCareTrack(opt.code)"
            >
              {{ opt.label }}
            </button>
            <button
              type="button"
              class="track-btn track-btn-none"
              :class="{ on: careTracks.length === 0 }"
              :disabled="status !== 'DRAFT'"
              @click="setNoProgram"
            >
              暂不加入
            </button>
          </div>
          <button
            v-if="status === 'DRAFT' && illnessTrackHints.some((t) => !careTracks.includes(t))"
            type="button"
            class="track-hint"
            @click="adoptIllnessTrackHints"
          >
            病史含糖/压，一并纳入
          </button>
        </div>
        <div v-if="!isSupplement" class="mode-switch" role="group" aria-label="填写范围">
          <span class="mode-switch-label">填写范围</span>
          <el-radio-group v-model="quickMode" class="mode-switch-group">
            <el-radio-button :value="true">
              <span class="mode-btn-title">快速</span>
              <span class="mode-btn-desc">必收 + 首诊建议</span>
            </el-radio-button>
            <el-radio-button :value="false">
              <span class="mode-btn-title">完整</span>
              <span class="mode-btn-desc">全部题目</span>
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="pills" aria-label="填写进度">
          <template v-if="isSupplement">
            <span
              class="pill soft"
              :class="{ warn: (localCompleteness.allDone || 0) < (localCompleteness.allTotal || 0) }"
            >
              待补 {{ localCompleteness.allDone || 0 }}/{{ localCompleteness.allTotal || 0 }}
            </span>
          </template>
          <template v-else>
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
          </template>
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
          :loading="submitting || openingSummary"
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
          <div
            v-if="currentModule.script"
            class="script"
            :class="{ internal: isInternalScript(currentModule.script) }"
          >
            <span class="script-label">
              {{ isInternalScript(currentModule.script) ? '内部提示' : '沟通话术' }}
            </span>
            <p>{{ displayScript(currentModule.script) }}</p>
          </div>

          <div v-if="currentModule.code === 'INTAKE_AI_SUMMARY'" class="ai-summary-bar">
            <div class="ai-summary-actions">
              <el-button
                type="primary"
                :loading="aiSummaryGenerating"
                :disabled="status !== 'DRAFT' || requiredStillMissing()"
                @click="runAiSummary(true)"
              >
                {{ isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY]) ? '生成总结' : '重新生成' }}
              </el-button>
              <el-button
                v-if="showAiSummaryBlocks"
                text
                type="primary"
                @click="aiSummaryEditRaw = !aiSummaryEditRaw"
              >
                {{
                  aiSummaryEditRaw
                    ? '收起原文'
                    : status === 'DRAFT'
                      ? '编辑原文'
                      : '查看原文'
                }}
              </el-button>
              <span v-if="aiSummaryMeta" class="ai-meta">
                {{ aiSummaryMeta.fromLlm ? '来源：AI' : '来源：模板' }}
                <template v-if="aiSummaryMeta.note"> · {{ aiSummaryMeta.note }}</template>
              </span>
            </div>
            <IntakeAiSummaryBlocks
              v-if="showAiSummaryBlocks"
              class="ai-summary-blocks"
              :parsed="aiSummaryParsed"
            />
            <p v-else-if="!aiSummaryGenerating" class="ai-empty-hint">
              <template v-if="requiredStillMissing()">
                请先完成必收项（{{ localCompleteness.requiredDone || 0 }}/{{
                  localCompleteness.requiredTotal || 0
                }}），再生成 AI 总结。
              </template>
              <template v-else>
                点击「生成总结」后将按概况、要点、风险与下一步分节展示。
              </template>
            </p>
          </div>

          <div
            v-for="(it, qi) in currentModule.items"
            :key="it.field"
            v-show="it.field !== FIELD_STAFF_AI_SUMMARY || aiSummaryEditRaw || !showAiSummaryBlocks"
            class="q"
            :data-intake-field="it.field"
            :class="{
              hit: highlightHits.some((h) => h.field === it.field),
              req: it.level === 'REQUIRED',
              filled: isFilled(it.field),
              prefill: isPrefillTone(it.field),
              dirty: isDirty(it.field),
              focus: focusedField === it.field,
            }"
          >
            <div class="q-head">
              <span class="q-no">{{ qi + 1 }}</span>
              <div class="q-main">
                <div class="q-label-row">
                  <span class="q-label">
                    <i v-if="it.level === 'REQUIRED'" class="star" aria-hidden="true">*</i>
                    {{ it.field === FIELD_STAFF_AI_SUMMARY ? '总结原文（可编辑）' : it.label }}
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
                <p v-if="it.field === FIELD_STAFF_AI_SUMMARY" class="ai-edit-hint">
                  按【要点】【风险与禁忌】【建议下一步】分节书写时，上方会自动拆成要点展示。
                </p>
                <FieldInput
                  :field="it.field"
                  :type="it.type"
                  :unit="it.unit"
                  :options="it.options"
                  :min="it.min"
                  :max="it.max"
                  :exclusive="it.exclusive"
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
              :loading="submitting || openingSummary"
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

        <!-- 高风险未问优先：勿被长「高亮追问」挤出视口 -->
        <section v-if="riskUnasked.length" class="panel risk-panel">
          <h3>高风险未问 · {{ riskUnasked.length }}</h3>
          <div class="risk-list">
            <button
              v-for="(it, idx) in riskUnasked.slice(0, 5)"
              :key="it.field"
              type="button"
              class="risk-item"
              @click="jumpToField(it.field)"
            >
              <span class="risk-idx">{{ idx + 1 }}</span>
              <span class="risk-label">{{ it.label }}</span>
            </button>
          </div>
          <p v-if="riskUnasked.length > 5" class="muted">还有 {{ riskUnasked.length - 5 }} 项…</p>
        </section>

        <section class="panel hits-panel">
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
              <div class="hit-title">{{ labelOf(h.field, h.label) }}</div>
              <div class="hit-meta">{{ severityLabel(h.severity) }}</div>
              <p v-if="h.followUp">{{ h.followUp }}</p>
              <p v-if="h.planEffect" class="plan">方案影响：{{ h.planEffect }}</p>
              <button type="button" class="hit-jump" @click="jumpToField(h.field)">去查看</button>
            </div>
          </div>
          <p v-else class="muted">填写时若命中风险题，追问会出现在这里</p>
        </section>
      </aside>
    </div>

    <el-dialog v-model="summaryOpen" title="提交前确认" width="640px" align-center destroy-on-close>
      <div class="sum">
        <section>
          <h4>必收缺项</h4>
          <div v-if="localCompleteness.requiredMissing?.length" class="sum-risk-list">
            <button
              v-for="(f, idx) in localCompleteness.requiredMissing"
              :key="f"
              type="button"
              class="sum-risk-item"
              @click="jumpToField(f)"
            >
              <span class="risk-idx">{{ idx + 1 }}</span>
              <span class="risk-label">{{ labelOf(f) }}</span>
            </button>
          </div>
          <p v-else class="ok">已齐全</p>
        </section>

        <section>
          <h4>高风险未问</h4>
          <div v-if="riskUnasked.length" class="sum-risk-list">
            <button
              v-for="(it, idx) in riskUnasked"
              :key="it.field"
              type="button"
              class="sum-risk-item"
              @click="jumpToField(it.field)"
            >
              <span class="risk-idx">{{ idx + 1 }}</span>
              <span class="risk-label">{{ it.label }}</span>
            </button>
          </div>
          <p v-else class="ok">无</p>
          <el-checkbox v-if="riskUnasked.length" v-model="riskAck">已知晓未问高风险题，仍要提交</el-checkbox>
        </section>

        <section>
          <h4>变更对照</h4>
          <ul v-if="changeRows.length" class="changes">
            <li v-for="row in changeRows" :key="row.field">
              <b>{{ row.label }}</b>
              <span class="muted">（{{ row.source }}）</span>
              ：{{ displayAnswer(row.field, row.from) }} → {{ displayAnswer(row.field, row.to) }}
            </li>
          </ul>
          <p v-else class="ok">无相对预填的修改</p>
        </section>

        <section>
          <h4>高亮汇总</h4>
          <ul v-if="highlightHits.length">
            <li v-for="(h, i) in highlightHits" :key="`s-${h.field}-${i}`">
              {{ labelOf(h.field, h.label) }} · {{ severityLabel(h.severity) }}
              <span v-if="h.planEffect"> — {{ h.planEffect }}</span>
            </li>
          </ul>
          <p v-else class="ok">无</p>
        </section>

        <section>
          <h4>AI 总结{{ isSupplement ? '' : '（必填）' }}</h4>
          <IntakeAiSummaryBlocks
            v-if="hasAiSummary"
            class="ai-preview-blocks"
            compact
            :parsed="aiSummaryParsed"
          />
          <template v-else-if="isSupplement">
            <p class="muted">尚未生成；待补问卷可跳过。</p>
          </template>
          <template v-else>
            <p class="sum-warn">尚未生成或已清空，正式首诊须有总结正文后再提交。</p>
            <button type="button" class="link" @click="jumpToAiSummary">去 AI 总结模块</button>
          </template>
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
  height: 100%;
  min-height: 0;
  background: var(--ink-50);
}

.intake--embedded {
  background: transparent;
}

.top {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid var(--ink-200);
  flex-shrink: 0;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 160px;
}

.back {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-600);
  cursor: pointer;
  font-size: 13px;
  border-radius: 8px;
  padding: 6px 10px;
}

.back:hover {
  border-color: var(--ink-300);
  color: var(--ink-800);
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
  min-width: 0;
}

.track-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 4px 8px 4px 10px;
  border-radius: 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.track-switch-label {
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
  white-space: nowrap;
}

.track-btns {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.track-btn {
  appearance: none;
  border: 1px solid #93c5fd;
  background: #fff;
  color: #1e40af;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.track-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.track-btn.on {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.track-btn-none.on {
  background: #64748b;
  border-color: #64748b;
}

.track-hint {
  appearance: none;
  border: none;
  background: transparent;
  color: #b45309;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 0 4px;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 4px 10px;
  border-radius: 12px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}

.mode-switch-label {
  font-size: 12px;
  font-weight: 600;
  color: #047857;
  white-space: nowrap;
}

.mode-switch-group :deep(.el-radio-button__inner) {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  height: auto;
  min-height: 40px;
  padding: 6px 14px;
  line-height: 1.15;
  border-radius: 0 !important;
  border-color: #6ee7b7 !important;
  background: #fff;
  box-shadow: none !important;
}

.mode-switch-group :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 8px 0 0 8px !important;
}

.mode-switch-group :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 8px 8px 0 !important;
}

.mode-switch-group :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: #059669 !important;
  border-color: #059669 !important;
  color: #fff !important;
}

.mode-btn-title {
  font-size: 14px;
  font-weight: 700;
}

.mode-btn-desc {
  font-size: 11px;
  font-weight: 400;
  opacity: 0.85;
}

.pills {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-600);
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
  white-space: nowrap;
}

.pill.warn {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}

.pill.soft {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

.pill.pct {
  position: relative;
  overflow: hidden;
  min-width: 64px;
  justify-content: center;
  padding: 0 12px;
  color: var(--ink-700);
  font-variant-numeric: tabular-nums;
}

.pct-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: color-mix(in srgb, var(--brand-500) 22%, transparent);
  pointer-events: none;
}

.pill.pct em {
  position: relative;
  font-style: normal;
  font-weight: 600;
}

.top-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.save {
  font-size: 12px;
  color: var(--ink-400);
  white-space: nowrap;
}

.save.ok {
  color: #059669;
}

.save.err {
  color: #b45309;
}

.body {
  flex: 1;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 340px;
  min-height: 0;
}

.nav {
  overflow: auto;
  padding: 12px 10px;
  border-right: 1px solid var(--ink-200);
  background: #fff;
}

.nav-head {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-400);
  padding: 4px 10px 10px;
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
  border-radius: 10px;
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

.nav-idx {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-500);
  background: var(--ink-100);
  flex-shrink: 0;
}

.nav-item.on .nav-idx {
  background: var(--brand-100);
  color: var(--brand-700);
}

.nav-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-fill {
  font-size: 11px;
  color: var(--ink-400);
  font-variant-numeric: tabular-nums;
}

.nav-item.on .nav-fill {
  color: var(--brand-600);
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
  padding: 18px 24px 40px;
  min-width: 0;
}

.form-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.form-head-text {
  min-width: 0;
}

.mod-kicker {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.mod-title {
  margin: 0;
  font-size: 18px;
  color: var(--ink-800);
  line-height: 1.3;
}

.form-nav {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.script {
  font-size: 13px;
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--ink-200);
  border-left: 3px solid var(--brand-400);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  line-height: 1.55;
}

.script.internal {
  border-left-color: #94a3b8;
  background: #f8fafc;
  color: var(--ink-500);
}

.script.internal .script-label {
  color: #64748b;
}

.script-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--brand-600);
  margin-bottom: 4px;
}

.ai-summary-bar {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #99f6e4;
  border-radius: 12px;
  background: #f0fdfa;
}

.ai-summary-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.ai-meta {
  font-size: 12px;
  color: var(--ink-500);
}

.ai-summary-blocks {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(15, 118, 110, 0.12);
}

.ai-empty-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.45;
}

.ai-edit-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.4;
}

.ai-preview-blocks {
  max-height: 280px;
  overflow: auto;
  padding: 4px 2px 0;
}

.script p {
  margin: 0;
}

.q {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 14px 14px 12px;
  margin-bottom: 10px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.q.prefill {
  background: color-mix(in srgb, var(--ink-50) 70%, #fff);
}

.q.dirty {
  background: #fffbeb;
  border-color: #fde68a;
}

.q.hit {
  border-color: #f59e0b;
  box-shadow: 0 0 0 1px color-mix(in srgb, #f59e0b 35%, transparent);
}

.q.req.hit {
  border-color: #ef4444;
  box-shadow: 0 0 0 1px color-mix(in srgb, #ef4444 30%, transparent);
}

.q.focus {
  border-color: #0d9488;
  box-shadow:
    0 0 0 2px color-mix(in srgb, #0d9488 45%, transparent),
    0 0 0 6px color-mix(in srgb, #0d9488 16%, transparent);
  animation: q-focus-pulse 1.2s ease-out 1;
}

@keyframes q-focus-pulse {
  0% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #0d9488 55%, transparent),
      0 0 0 10px color-mix(in srgb, #0d9488 22%, transparent);
  }
  100% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #0d9488 45%, transparent),
      0 0 0 6px color-mix(in srgb, #0d9488 16%, transparent);
  }
}

.q-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.q-no {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  background: var(--ink-100);
  flex-shrink: 0;
  margin-top: 1px;
}

.q.filled .q-no {
  color: #047857;
  background: #d1fae5;
}

.q-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.q-label-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.q-label {
  font-size: 14px;
  font-weight: 560;
  color: var(--ink-800);
  line-height: 1.4;
}

.star {
  color: #ef4444;
  font-style: normal;
  margin-right: 2px;
}

.q-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.tag {
  font-size: 11px;
  line-height: 1;
  padding: 4px 7px;
  border-radius: 999px;
  color: var(--ink-600);
  background: var(--ink-100);
  white-space: nowrap;
}

.tag-soft {
  color: #b45309;
  background: #fffbeb;
}

.prefill-note {
  font-size: 12px;
  color: var(--ink-400);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dirty-note {
  color: #b45309;
}

.restore {
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  white-space: nowrap;
}

.restore:hover {
  text-decoration: underline;
}

.form-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--ink-200);
}

.side {
  overflow: auto;
  padding: 12px;
  border-left: 1px solid var(--ink-200);
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.panel {
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 12px;
  background: #fff;
}

.panel h3 {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-700);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.panel-head h3 {
  margin: 0;
}

.badge {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #92400e;
  background: #fef3c7;
}

.bmi-panel {
  flex-shrink: 0;
  background: linear-gradient(180deg, #f8fafc 0%, #fff 70%);
}

.bmi-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.bmi-value {
  font-size: 36px;
  font-weight: 700;
  line-height: 1;
  color: var(--ink-800);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.bmi-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-500);
}

.bmi-band {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
}

.bmi-band.ok {
  background: #d1fae5;
  color: #047857;
}

.bmi-band.warn {
  background: #fef3c7;
  color: #b45309;
}

.bmi-band.danger {
  background: #fee2e2;
  color: #b91c1c;
}

.bmi-rows {
  margin: 0;
  display: grid;
  gap: 8px;
}

.bmi-rows > div {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 13px;
}

.bmi-rows dt {
  color: var(--ink-500);
}

.bmi-rows dd {
  margin: 0;
  color: var(--ink-800);
  font-variant-numeric: tabular-nums;
}

.bmi-rows small {
  color: var(--ink-400);
  margin-left: 2px;
}

.hits-panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.hits {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 吃满侧栏剩余高度，多了面板内滚，底下不再空一截 */
  overflow: auto;
  padding-right: 2px;
  overscroll-behavior: contain;
}

.hit-card {
  border-radius: 10px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.45;
  border: 1px solid var(--ink-200);
}

.hit-card.sev-danger {
  background: #fef2f2;
  border-color: #fecaca;
}

.hit-card.sev-warn {
  background: #fffbeb;
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
  margin: 2px 0 6px;
}

.hit-card p {
  margin: 0 0 4px;
  color: var(--ink-700);
}

.hit-card .plan {
  margin: 4px 0 0;
  color: var(--ink-600);
}

.hit-jump {
  margin-top: 8px;
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.hit-jump:hover {
  text-decoration: underline;
}

.risk-panel {
  flex-shrink: 0;
  border-color: #fde68a;
  background: #fffbeb;
}

.risk-list,
.sum-risk-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.risk-item,
.sum-risk-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  margin: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.72);
  text-align: left;
  cursor: pointer;
  color: var(--ink-800);
  font-size: 13px;
  line-height: 1.45;
}

.risk-item:hover,
.sum-risk-item:hover {
  border-color: #fcd34d;
  background: #fff;
}

.risk-idx {
  flex: 0 0 auto;
  min-width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 999px;
  background: #fde68a;
  color: #92400e;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}

.risk-label {
  flex: 1;
  min-width: 0;
  color: var(--brand-700, #0f766e);
}

.sum-risk-item {
  background: var(--ink-50, #f8fafc);
}

.sum-risk-item:hover {
  border-color: var(--ink-200);
}

.muted {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.5;
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

.sum .sum-warn {
  margin: 0 0 6px;
  font-size: 13px;
  color: #b45309;
}

.link {
  border: none;
  background: none;
  color: var(--brand-600);
  cursor: pointer;
  padding: 0;
  font-size: inherit;
}

.link:hover {
  text-decoration: underline;
}

.changes li {
  margin-bottom: 4px;
}

@media (max-width: 1100px) {
  .body {
    grid-template-columns: 180px minmax(0, 1fr) 300px;
  }
}

@media (max-width: 960px) {
  .body {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .nav,
  .side {
    border: none;
    max-height: 220px;
  }

  .intake {
    height: auto;
    min-height: 100%;
  }
}
</style>
