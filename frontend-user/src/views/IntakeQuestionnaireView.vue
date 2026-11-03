<script setup lang="ts">
/**
 * C 端首诊（Typeform 风格）：
 * 开场预期 → 一题一屏 → 章节过渡 → 提交校验 → 完成回馈
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, getAuthMode } from '../api/http'
import { loadDictOptions, type DictItem } from '../shared/dict'
import {
  BUNDLE_GROUPS,
  C_INTAKE_TITLE,
  DEPTH_TABS,
  applyCareTracksFromAppeal,
  applyPatientConsentDefaults,
  cLabel,
  initAnswers,
  isAnswerEmpty,
  isShortTextField,
  matchShowIf,
  shouldAskOnC,
  type IntakeDepth,
  type IntakeItem,
  type IntakeModule,
  type IntakeView,
} from '../shared/intake-c'

type Phase = 'intro' | 'fill' | 'confirm' | 'done'

type Step =
  | {
      kind: 'single'
      field: string
      item: IntakeItem
      moduleTitle: string
      moduleCode: string
    }
  | {
      kind: 'bundle'
      id: string
      title: string
      items: IntakeItem[]
      moduleTitle: string
      moduleCode: string
    }

const route = useRoute()
const router = useRouter()
const isLinkSession = computed(() => getAuthMode() === 'intake_link')

const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const phase = ref<Phase>('intro')
const depth = ref<IntakeDepth>('MINIMAL')
const view = ref<IntakeView | null>(null)
const answers = reactive<Record<string, unknown>>({})
const optionMap = reactive<Record<string, DictItem[]>>({})
const stepIndex = ref(0)
const fieldError = ref('')
const saveHint = ref('')
const halfCheered = ref(false)
const skippedHintShown = ref(false)
const chapterFlash = ref('')
const datePickerOpen = ref(false)
const dateTargetField = ref('')
const optionSheetOpen = ref(false)
const sheetQuery = ref('')
const animKey = ref(0)

let saveTimer: ReturnType<typeof setTimeout> | null = null
let saveHintTimer: ReturnType<typeof setTimeout> | null = null
let skipAutosave = true
let autoAdvanceTimer: ReturnType<typeof setTimeout> | null = null

const depthMeta = computed(() => DEPTH_TABS.find((t) => t.code === depth.value))

function getAns(field: string) {
  return answers[field]
}

const visibleModules = computed(() => {
  const mods = view.value?.modules || []
  return mods
    .filter((m) => matchShowIf(m.showIf, getAns))
    .map((m) => ({
      ...m,
      items: (m.items || []).filter((it) => matchShowIf(it.showIf, getAns) && shouldAskOnC(it, answers)),
    }))
    .filter((m) => m.items.length)
})

const steps = computed<Step[]>(() => {
  const out: Step[] = []
  const consumed = new Set<string>()
  for (const m of visibleModules.value) {
    const items = m.items
    for (let i = 0; i < items.length; i++) {
      const it = items[i]
      if (consumed.has(it.field)) continue

      const bundle = BUNDLE_GROUPS.find((g) => g.fields[0] === it.field)
      if (bundle) {
        const bundled = bundle.fields
          .map((f) => items.find((x) => x.field === f))
          .filter((x): x is IntakeItem => !!x)
        if (bundled.length >= 2) {
          bundled.forEach((x) => consumed.add(x.field))
          out.push({
            kind: 'bundle',
            id: bundle.id,
            title: bundle.title,
            items: bundled,
            moduleTitle: m.title,
            moduleCode: m.code,
          })
          continue
        }
      }

      consumed.add(it.field)
      out.push({
        kind: 'single',
        field: it.field,
        item: it,
        moduleTitle: m.title,
        moduleCode: m.code,
      })
    }
  }
  return out
})

const totalSteps = computed(() => steps.value.length)
const currentStep = computed(() => steps.value[stepIndex.value] || null)
/** 单题步骤；模板回调里会丢掉 Step 联合收窄，用此计算属性访问 field/item */
const currentSingle = computed(() => {
  const s = currentStep.value
  return s?.kind === 'single' ? s : null
})
const isLastStep = computed(() => stepIndex.value >= totalSteps.value - 1 && totalSteps.value > 0)
const progressPct = computed(() => {
  if (totalSteps.value <= 0) return 0
  if (phase.value === 'done') return 100
  if (phase.value === 'confirm') return 100
  // 未答完不显示 100%，避免「进度满了还要再点一题」
  return Math.min(99, Math.round((stepIndex.value / totalSteps.value) * 100))
})

const currentPrimaryItem = computed(() => {
  const s = currentStep.value
  if (!s) return null
  return s.kind === 'single' ? s.item : s.items[0]
})

const currentOptions = computed(() => {
  const it = currentPrimaryItem.value
  if (!it?.options) return [] as DictItem[]
  return optionMap[it.options] || []
})

const sheetOptions = computed(() => {
  const q = sheetQuery.value.trim().toLowerCase()
  if (!q) return currentOptions.value
  return currentOptions.value.filter(
    (o) =>
      o.dictCodeDesc?.toLowerCase().includes(q) ||
      o.dictCode?.toLowerCase().includes(q),
  )
})

/**
 * 仅「单选 + 选项很多」用底部弹层（如职业）。
 * 多选一律铺在当前页：选完若只显示「已选 N 项」，客户看不见自己勾了什么。
 */
const useSheetPicker = computed(() => {
  const it = currentPrimaryItem.value
  if (!it || it.type !== 'ENUM') return false
  if (it.field === 'consent.sensitiveHealth') return false
  return currentOptions.value.length > 8
})

async function ensureOptions(item: IntakeItem) {
  const parent = item.options
  if (!parent || optionMap[parent]) return
  try {
    optionMap[parent] = await loadDictOptions(parent)
  } catch {
    optionMap[parent] = []
  }
}

async function prefetchOptions(modules: IntakeModule[]) {
  const parents = new Set<string>()
  for (const m of modules) {
    for (const it of m.items || []) {
      if (it.options) parents.add(it.options)
    }
  }
  await Promise.all(
    [...parents].map(async (p) => {
      if (optionMap[p]) return
      try {
        optionMap[p] = await loadDictOptions(p)
      } catch {
        optionMap[p] = []
      }
    }),
  )
}

function applyView(data: IntakeView, keepAnswers: Record<string, unknown> | null) {
  view.value = data
  const next = initAnswers(data)
  if (keepAnswers) {
    for (const [k, v] of Object.entries(keepAnswers)) {
      if (v !== undefined && v !== null && v !== '') next[k] = v
    }
  }
  applyCareTracksFromAppeal(next)
  applyPatientConsentDefaults(next)
  Object.keys(answers).forEach((k) => delete answers[k])
  Object.assign(answers, next)
  void prefetchOptions(data.modules || [])
}

watch(
  () => answers['goal.primaryAppeal'],
  () => {
    if (skipAutosave) return
    applyCareTracksFromAppeal(answers)
  },
  { deep: true },
)

watch(
  () => answers['consent.sensitiveHealth'],
  () => applyPatientConsentDefaults(answers),
)

async function load(nextDepth: IntakeDepth, keep = true) {
  loading.value = true
  skipAutosave = true
  try {
    const keepSnap = keep ? { ...answers } : null
    const res = await api<{ data: IntakeView }>(
      `/api/c/v1/me/intake?depth=${encodeURIComponent(nextDepth)}`,
    )
    depth.value = nextDepth
    applyView(res.data, keepSnap)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
    requestAnimationFrame(() => {
      skipAutosave = false
    })
  }
}

watch(
  steps,
  (list, prev) => {
    if (phase.value !== 'fill') return
    if (list.length === 0) {
      stepIndex.value = 0
      return
    }
    const prevStep = prev?.[stepIndex.value]
    const key =
      prevStep?.kind === 'single'
        ? prevStep.field
        : prevStep?.kind === 'bundle'
          ? prevStep.id
          : ''
    if (key) {
      const ni = list.findIndex((s) =>
        s.kind === 'single' ? s.field === key : s.kind === 'bundle' && s.id === key,
      )
      if (ni >= 0) {
        stepIndex.value = ni
        return
      }
      if (!skippedHintShown.value && prev && list.length < prev.length) {
        skippedHintShown.value = true
        showToast({ message: '已按你的回答跳过无关题目', duration: 1500 })
      }
    }
    if (stepIndex.value >= list.length) stepIndex.value = list.length - 1
  },
  { flush: 'post' },
)

watch(currentStep, (s, prev) => {
  fieldError.value = ''
  animKey.value += 1
  if (s && prev && s.moduleCode !== prev.moduleCode) {
    chapterFlash.value = s.moduleTitle
    window.setTimeout(() => {
      if (chapterFlash.value === s.moduleTitle) chapterFlash.value = ''
    }, 1200)
  }
  if (!s) return
  if (s.kind === 'single') void ensureOptions(s.item)
  else s.items.forEach((it) => void ensureOptions(it))
})

watch(
  () => [stepIndex.value, totalSteps.value, phase.value] as const,
  ([idx, total, p]) => {
    if (p !== 'fill' || total <= 0 || halfCheered.value) return
    if ((idx + 1) / total >= 0.5 && idx > 0) {
      halfCheered.value = true
      showToast({ message: '已完成一半，继续加油', duration: 1400 })
    }
  },
)

/**
 * 草稿保存：只在换题 / 输入停顿 / 离开页时调用。
 * 禁止 deep watch answers——派生字段（careTracks）一写就会连环请求。
 */
async function saveDraftSilent() {
  if (!view.value?.id || phase.value === 'done') return
  skipAutosave = true
  try {
    applyPatientConsentDefaults(answers)
    applyCareTracksFromAppeal(answers)
    await api('/api/c/v1/me/intake/draft', {
      method: 'PUT',
      body: JSON.stringify({ id: view.value.id, depth: depth.value, answers }),
    })
  } finally {
    requestAnimationFrame(() => {
      skipAutosave = false
    })
  }
}

async function flushDraft(showHint = true) {
  if (skipAutosave || !view.value?.id) return
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  if (phase.value !== 'fill' && phase.value !== 'confirm') return
  try {
    saving.value = true
    if (showHint) saveHint.value = '保存中'
    await saveDraftSilent()
    if (showHint) {
      saveHint.value = '已保存'
      if (saveHintTimer) clearTimeout(saveHintTimer)
      saveHintTimer = setTimeout(() => {
        if (saveHint.value === '已保存') saveHint.value = ''
      }, 1200)
    }
  } catch {
    if (showHint) saveHint.value = '保存失败'
  } finally {
    saving.value = false
  }
}

/** 打字停顿后再存，避免每个按键打接口 */
function scheduleTypingSave() {
  if (skipAutosave || phase.value !== 'fill' || !view.value?.id) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    void flushDraft(true)
  }, 1800)
}

function onPageHide() {
  if (phase.value === 'fill' || phase.value === 'confirm') {
    void flushDraft(false)
  }
}

function isOn(item: IntakeItem, code: string) {
  const v = answers[item.field]
  return Array.isArray(v) ? v.includes(code) : v === code
}

function pick(item: IntakeItem, code: string) {
  void ensureOptions(item)
  if (item.type !== 'MULTI') {
    answers[item.field] = code
    fieldError.value = ''
    if (autoAdvanceTimer) clearTimeout(autoAdvanceTimer)
    autoAdvanceTimer = setTimeout(() => {
      if (isLastStep.value) {
        void flushDraft(false)
        return
      }
      void goNext()
    }, 240)
    return
  }
  const exclusive = item.exclusive || []
  const prev = Array.isArray(answers[item.field]) ? [...(answers[item.field] as string[])] : []
  if (prev.includes(code)) {
    answers[item.field] = prev.filter((c) => c !== code)
  } else if (exclusive.includes(code)) {
    answers[item.field] = [code]
  } else {
    answers[item.field] = [...prev.filter((c) => !exclusive.includes(c)), code]
  }
  fieldError.value = ''
  void flushDraft(false)
}

function onText(item: IntakeItem, v: string) {
  answers[item.field] = v
  fieldError.value = ''
  scheduleTypingSave()
}

/** 输入中不钳制 min/max（否则身高打 1 会立刻变成 100） */
function onNumber(item: IntakeItem, v: string) {
  answers[item.field] = v
  fieldError.value = ''
  scheduleTypingSave()
}

function numberError(item: IntakeItem, raw: unknown): string | null {
  if (raw === undefined || raw === null || raw === '') {
    return item.level === 'REQUIRED' ? `请填写${cLabel(item)}` : null
  }
  const n = typeof raw === 'number' ? raw : Number(String(raw).trim())
  if (!Number.isFinite(n)) return `${cLabel(item)}请输入数字`
  if (item.min != null && n < item.min) {
    return `${cLabel(item)}需 ≥ ${item.min}${item.unit || ''}`
  }
  if (item.max != null && n > item.max) {
    return `${cLabel(item)}需 ≤ ${item.max}${item.unit || ''}`
  }
  return null
}

/** 下一题/提交前：把合法数字落成 number，非法保留字符串并报错 */
function finalizeNumbers(items: IntakeItem[]): string | null {
  for (const it of items) {
    if (it.type !== 'NUMBER') continue
    const raw = answers[it.field]
    const err = numberError(it, raw)
    if (err) return err
    if (raw !== undefined && raw !== null && raw !== '') {
      answers[it.field] = typeof raw === 'number' ? raw : Number(String(raw).trim())
    }
  }
  return null
}

function validateCurrent(): boolean {
  const step = currentStep.value
  if (!step) return true
  const items = step.kind === 'single' ? [step.item] : step.items
  const numErr = finalizeNumbers(items.filter((it) => it.type === 'NUMBER'))
  if (numErr) {
    fieldError.value = numErr
    return false
  }
  for (const it of items) {
    if (it.type === 'NUMBER') continue
    if (it.level !== 'REQUIRED') continue
    if (isAnswerEmpty(it, answers[it.field])) {
      fieldError.value = it.type === 'MULTI' ? '请至少选择一项' : `请填写${cLabel(it)}`
      return false
    }
  }
  fieldError.value = ''
  return true
}

async function goNext() {
  if (autoAdvanceTimer) {
    clearTimeout(autoAdvanceTimer)
    autoAdvanceTimer = null
  }
  if (!validateCurrent()) return
  await flushDraft(false)
  if (isLastStep.value) {
    phase.value = 'confirm'
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  stepIndex.value += 1
  await nextTick()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function goPrev() {
  if (autoAdvanceTimer) {
    clearTimeout(autoAdvanceTimer)
    autoAdvanceTimer = null
  }
  fieldError.value = ''
  await flushDraft(false)
  if (stepIndex.value <= 0) {
    phase.value = 'intro'
    return
  }
  stepIndex.value -= 1
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function skipOptional() {
  const step = currentStep.value
  if (!step) return
  if (step.kind === 'single') {
    if (step.item.level === 'REQUIRED') return
    answers[step.field] = step.item.type === 'MULTI' ? [] : ''
  } else {
    return
  }
  fieldError.value = ''
  if (isLastStep.value) {
    phase.value = 'confirm'
    return
  }
  stepIndex.value += 1
}

async function startFill() {
  if (!view.value) await load(depth.value, false)
  if (!steps.value.length) {
    showToast('本档暂无题目')
    return
  }
  halfCheered.value = false
  skippedHintShown.value = false
  stepIndex.value = 0
  phase.value = 'fill'
  window.scrollTo({ top: 0 })
}

async function changeDepth(next: IntakeDepth) {
  if (next === depth.value) return
  if (phase.value === 'fill') {
    try {
      await showConfirmDialog({
        title: `切换到「${DEPTH_TABS.find((t) => t.code === next)?.label}」`,
        message: '已填内容会保留；题目可能增减。',
        confirmButtonText: '切换',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  if (view.value?.id) {
    try {
      saving.value = true
      await api('/api/c/v1/me/intake/draft', {
        method: 'PUT',
        body: JSON.stringify({ id: view.value.id, depth: next, answers }),
      })
    } catch {
      /* 仍切档 */
    } finally {
      saving.value = false
    }
  }
  await load(next, true)
  if (phase.value === 'fill') stepIndex.value = 0
}

async function submit() {
  if (!view.value?.id) return
  for (let i = 0; i < steps.value.length; i++) {
    const s = steps.value[i]
    const items = s.kind === 'single' ? [s.item] : s.items
    const numErr = finalizeNumbers(items.filter((it) => it.type === 'NUMBER'))
    if (numErr) {
      stepIndex.value = i
      fieldError.value = numErr
      showToast(numErr)
      return
    }
    for (const it of items) {
      if (it.type === 'NUMBER') continue
      if (it.level === 'REQUIRED' && isAnswerEmpty(it, answers[it.field])) {
        stepIndex.value = i
        fieldError.value = `请填写${cLabel(it)}`
        showToast('还有必填题未填')
        return
      }
    }
  }
  submitting.value = true
  try {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    await saveDraftSilent()
    const res = await api<{ data: IntakeView }>('/api/c/v1/me/intake/submit', {
      method: 'POST',
      body: JSON.stringify({ id: view.value.id, depth: depth.value, answers }),
    })
    view.value = res.data
    phase.value = 'done'
    showToast('已收到，谢谢')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '提交失败'
    showToast(msg.includes('冲突') || msg.includes('409') ? '与已有档案不一致，请联系健管师' : msg)
  } finally {
    submitting.value = false
  }
}

async function continueQuick() {
  phase.value = 'intro'
  await changeDepth('QUICK')
}

function openDatePicker(field: string) {
  dateTargetField.value = field
  datePickerOpen.value = true
}

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  if (dateTargetField.value) answers[dateTargetField.value] = selectedValues.join('-')
  datePickerOpen.value = false
  fieldError.value = ''
}

function openOptionSheet() {
  sheetQuery.value = ''
  optionSheetOpen.value = true
}

function pickFromSheet(code: string) {
  const it = currentPrimaryItem.value
  if (!it) return
  pick(it, code)
  if (it.type !== 'MULTI') optionSheetOpen.value = false
}

const datePickerValue = computed(() => {
  const raw = String(answers[dateTargetField.value] || '')
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw.split('-')
  return ['1990', '01', '01']
})

function backFromConfirm() {
  phase.value = 'fill'
  fieldError.value = ''
}

async function agreeAndSubmit() {
  answers['consent.sensitiveHealth'] = 'YES'
  applyPatientConsentDefaults(answers)
  await submit()
}

onMounted(async () => {
  const q = String(route.query.depth || '').toUpperCase()
  const initial: IntakeDepth =
    q === 'QUICK' || q === 'FULL' || q === 'MINIMAL' ? q : 'MINIMAL'
  await load(initial, false)
  // 链接会话：少一次选择摩擦，直接进填写（深度已默认极简）
  if (isLinkSession.value && steps.value.length) {
    phase.value = 'fill'
  } else {
    phase.value = 'intro'
  }
  window.addEventListener('pagehide', onPageHide)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') onPageHide()
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', onPageHide)
  if (saveTimer) clearTimeout(saveTimer)
  if (saveHintTimer) clearTimeout(saveHintTimer)
  if (autoAdvanceTimer) clearTimeout(autoAdvanceTimer)
  onPageHide()
})
</script>

<template>
  <div class="page">
    <header
      class="top"
      :class="{
        'top--solo':
          phase === 'done' || (isLinkSession && phase !== 'fill' && phase !== 'confirm'),
      }"
    >
      <button
        v-if="phase === 'fill'"
        type="button"
        class="back"
        aria-label="上一题"
        @click="goPrev"
      >
        ‹
      </button>
      <button
        v-else-if="phase === 'confirm'"
        type="button"
        class="back"
        aria-label="返回修改"
        @click="backFromConfirm"
      >
        ‹
      </button>
      <button
        v-else-if="!isLinkSession && phase !== 'done'"
        type="button"
        class="back"
        aria-label="返回"
        @click="router.back()"
      >
        ‹
      </button>
      <div class="top-main">
        <h1>{{ C_INTAKE_TITLE }}</h1>
        <p class="top-sub">
          <span v-if="phase === 'fill' && saveHint" class="save-hint">{{ saveHint }}</span>
          <span v-else-if="phase === 'fill' && currentStep">
            {{ stepIndex + 1 }}/{{ totalSteps }} · {{ currentStep.moduleTitle }}
          </span>
          <span v-else-if="phase === 'confirm'">确认并提交</span>
          <span v-else-if="phase === 'intro'">帮我们更好了解你</span>
          <span v-else>已提交</span>
        </p>
      </div>
      <div v-if="phase === 'fill' || phase === 'confirm'" class="top-pct">{{ progressPct }}%</div>
    </header>

    <div v-if="phase === 'fill' || phase === 'confirm'" class="prog-track" aria-hidden="true">
      <i :style="{ width: `${progressPct}%` }" />
    </div>

    <transition name="fade">
      <div v-if="chapterFlash" class="chapter">{{ chapterFlash }}</div>
    </transition>

    <div v-if="loading" class="state-load">
      <div class="spinner" />
      <p>正在准备…</p>
    </div>

    <!-- 开场 -->
    <section v-else-if="phase === 'intro'" class="intro">
      <p class="eyebrow">Healix · {{ C_INTAKE_TITLE }}</p>
      <h2>你现在方便花多少时间？</h2>
      <p class="lead">一题一屏，答错了也能改。中途退出下次接着填。</p>

      <div class="depth-list" role="radiogroup" aria-label="评估时长">
        <button
          v-for="t in DEPTH_TABS"
          :key="t.code"
          type="button"
          class="depth-card"
          :class="{ on: depth === t.code }"
          role="radio"
          :aria-checked="depth === t.code"
          @click="changeDepth(t.code)"
        >
          <div class="depth-top">
            <b>{{ t.label }}</b>
            <em>{{ t.eta }}</em>
          </div>
          <span>{{ t.about }} · {{ t.hint }}</span>
        </button>
      </div>

      <ul class="trust">
        <li>姓名等建档信息不会再问你</li>
        <li>只用于为你定制管理方案，严格保密</li>
        <li>填到一半也能停，进度会自动保存</li>
      </ul>

      <button type="button" class="btn-primary" :disabled="!view" @click="startFill">
          开始{{ depthMeta?.label ? `「${depthMeta.label}」` : '' }}评估
      </button>
    </section>

    <!-- 提交前确认（隐私授权，不当问卷题） -->
    <section v-else-if="phase === 'confirm'" class="confirm">
      <p class="eyebrow">马上就好</p>
      <h2>确认提交初步评估？</h2>
      <p class="lead">
        你填写的健康信息仅用于定制管理方案，我们会严格保密，不会对外泄露。
      </p>
      <ul class="trust">
        <li>可随时联系健康管理师更正信息</li>
        <li>提交后如需补充，可再填「标准」评估</li>
      </ul>
      <button type="button" class="btn-primary" :disabled="submitting" @click="agreeAndSubmit">
        {{ submitting ? '提交中…' : '同意并提交' }}
      </button>
      <button type="button" class="btn-ghost confirm-back" @click="backFromConfirm">
        返回修改
      </button>
    </section>

    <!-- 填写 -->
    <section v-else-if="phase === 'fill' && currentStep" :key="animKey" class="fill">
      <p class="mod-tag">{{ currentStep.moduleTitle }}</p>

      <!-- 身高体重同屏 -->
      <template v-if="currentStep.kind === 'bundle'">
        <h2 class="q-title">
          {{ currentStep.title }}
          <em class="req">必填</em>
        </h2>
        <div class="bundle">
          <label v-for="it in currentStep.items" :key="it.field" class="bundle-field">
            <span>{{ cLabel(it) }}</span>
            <van-field
              :model-value="answers[it.field] == null ? '' : String(answers[it.field])"
              type="number"
              inputmode="decimal"
              :placeholder="it.min != null && it.max != null ? `${it.min}–${it.max}` : '请输入'"
              class="num-input"
              @update:model-value="(v: string) => onNumber(it, v)"
            >
              <template v-if="it.unit" #right-icon>
                <span class="unit">{{ it.unit }}</span>
              </template>
            </van-field>
          </label>
        </div>
      </template>

      <!-- 单题 -->
      <template v-else-if="currentSingle">
        <h2 class="q-title">
          {{ cLabel(currentSingle.item) }}
          <em v-if="currentSingle.item.level === 'REQUIRED'" class="req">必填</em>
          <em v-else class="opt-tag">选填</em>
        </h2>
        <p v-if="currentSingle.item.type === 'MULTI'" class="q-hint">可多选</p>

        <div
          v-if="(currentSingle.item.type === 'ENUM' || currentSingle.item.type === 'MULTI') && !useSheetPicker"
          class="choice-list"
        >
          <button
            v-for="o in currentOptions"
            :key="o.dictCode"
            type="button"
            class="choice"
            :class="{ on: isOn(currentSingle.item, o.dictCode) }"
            @click="pick(currentSingle.item, o.dictCode)"
          >
            <span class="choice-mark">{{ isOn(currentSingle.item, o.dictCode) ? '✓' : '' }}</span>
            <span class="choice-label">{{ o.dictCodeDesc }}</span>
          </button>
          <p v-if="!currentOptions.length" class="hint">选项加载中…</p>
        </div>

        <button
          v-else-if="currentSingle.item.type === 'ENUM'"
          type="button"
          class="sheet-trigger"
          @click="openOptionSheet"
        >
          {{
            currentOptions.find((o) => o.dictCode === answers[currentSingle!.field])?.dictCodeDesc
              || '点击选择'
          }}
        </button>

        <van-field
          v-else-if="currentSingle.item.type === 'NUMBER'"
          :model-value="answers[currentSingle.field] == null ? '' : String(answers[currentSingle.field])"
          type="number"
          :placeholder="
            currentSingle.item.min != null && currentSingle.item.max != null
              ? `${currentSingle.item.min}–${currentSingle.item.max}`
              : '请输入'
          "
          class="big-input"
          @update:model-value="(v: string) => currentSingle && onNumber(currentSingle.item, v)"
        >
          <template v-if="currentSingle.item.unit" #right-icon>
            <span class="unit">{{ currentSingle.item.unit }}</span>
          </template>
        </van-field>

        <button
          v-else-if="currentSingle.item.type === 'DATE'"
          type="button"
          class="sheet-trigger"
          @click="openDatePicker(currentSingle.field)"
        >
          {{ answers[currentSingle.field] || '选择日期' }}
        </button>

        <van-field
          v-else-if="isShortTextField(currentSingle.field)"
          :model-value="String(answers[currentSingle.field] ?? '')"
          type="text"
          :maxlength="currentSingle.field.includes('mobile') ? 11 : 32"
          placeholder="请填写"
          class="big-input"
          @update:model-value="(v: string) => currentSingle && onText(currentSingle.item, v)"
        />

        <van-field
          v-else-if="currentSingle.item.type === 'TEXT' || currentSingle.item.type === 'STRING' || !currentSingle.item.type"
          :model-value="String(answers[currentSingle.field] ?? '')"
          type="textarea"
          rows="3"
          autosize
          maxlength="200"
          show-word-limit
          :placeholder="currentSingle.item.level === 'REQUIRED' ? '请填写' : '选填，最多 200 字'"
          class="big-input"
          @update:model-value="(v: string) => currentSingle && onText(currentSingle.item, v)"
        />

        <p v-else class="hint">本题可跳过</p>
      </template>

      <p v-if="fieldError" class="err" role="alert">{{ fieldError }}</p>

      <div class="nav-bar">
        <button type="button" class="btn-ghost nav-side" @click="goPrev">
          {{ stepIndex === 0 ? '返回' : '上一题' }}
        </button>
        <button
          v-if="
            currentSingle &&
            currentSingle.item.level !== 'REQUIRED' &&
            currentSingle.item.type !== 'ENUM'
          "
          type="button"
          class="btn-text"
          @click="skipOptional"
        >
          跳过
        </button>
        <button
          type="button"
          class="btn-primary nav-main"
          :disabled="submitting"
          @click="goNext"
        >
          {{ isLastStep ? '完成' : '下一题' }}
        </button>
      </div>
    </section>

    <van-empty
      v-else-if="phase === 'fill' && !currentStep"
      description="本档暂无题目"
      image-size="72"
    />

    <!-- 完成 -->
    <section v-else-if="phase === 'done'" class="done">
      <div class="done-ico" aria-hidden="true">✓</div>
      <h2>{{ C_INTAKE_TITLE }}已提交</h2>
      <p>
        健康管理师会据此跟进。
        <template v-if="depth === 'MINIMAL'">若还方便，补一份「标准」评估，方案会更贴合你。</template>
        <template v-else>感谢你的耐心填写。</template>
      </p>
      <button v-if="depth === 'MINIMAL'" type="button" class="btn-primary" @click="continueQuick">
        再花约 5 分钟补全
      </button>
      <button
        v-if="!isLinkSession"
        type="button"
        class="btn-ghost"
        style="margin-top: 10px; width: 100%"
        @click="router.replace('/')"
      >
        回首页
      </button>
      <p v-else class="done-hint">可以关闭本页了</p>
    </section>

    <van-popup v-model:show="datePickerOpen" position="bottom" round>
      <van-date-picker
        title="选择日期"
        :min-date="new Date(1920, 0, 1)"
        :max-date="new Date()"
        :model-value="datePickerValue"
        @confirm="onDateConfirm"
        @cancel="datePickerOpen = false"
      />
    </van-popup>

    <van-popup v-model:show="optionSheetOpen" position="bottom" round :style="{ height: '72%' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ currentPrimaryItem ? cLabel(currentPrimaryItem) : '请选择' }}</strong>
          <button type="button" class="sheet-done" @click="optionSheetOpen = false">完成</button>
        </div>
        <van-search v-model="sheetQuery" placeholder="搜索" shape="round" />
        <div class="sheet-list">
          <button
            v-for="o in sheetOptions"
            :key="o.dictCode"
            type="button"
            class="choice"
            :class="{ on: currentPrimaryItem && isOn(currentPrimaryItem, o.dictCode) }"
            @click="pickFromSheet(o.dictCode)"
          >
            <span class="choice-mark">
              {{ currentPrimaryItem && isOn(currentPrimaryItem, o.dictCode) ? '✓' : '' }}
            </span>
            <span class="choice-label">{{ o.dictCodeDesc }}</span>
          </button>
          <p v-if="!sheetOptions.length" class="hint" style="padding: 16px">无匹配选项</p>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100dvh;
  background: var(--hx-bg);
  padding-bottom: calc(28px + env(safe-area-inset-bottom));
}
.top {
  position: sticky;
  top: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: calc(10px + env(safe-area-inset-top)) 16px 8px;
  background: rgba(255, 248, 236, 0.92);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(214, 226, 220, 0.5);
}
.top--solo {
  grid-template-columns: 1fr auto;
}
.back {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.8);
  font-size: 28px;
  line-height: 1;
  color: var(--hx-ink);
}
.top-main h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 800;
}
.top-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  min-height: 1.2em;
}
.save-hint { color: var(--hx-teal-700); }
.top-pct {
  font-size: 15px;
  font-weight: 800;
  color: var(--hx-teal-700);
  font-variant-numeric: tabular-nums;
}
.prog-track {
  height: 3px;
  background: rgba(191, 233, 219, 0.5);
}
.prog-track i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--hx-teal-400), var(--hx-teal-700));
  transition: width 0.28s ease;
}

.chapter {
  position: fixed;
  left: 50%;
  top: 42%;
  z-index: 40;
  transform: translate(-50%, -50%);
  padding: 14px 22px;
  border-radius: 999px;
  background: rgba(19, 34, 46, 0.88);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  pointer-events: none;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.state-load {
  padding: 72px 16px;
  text-align: center;
  color: var(--hx-muted);
}
.spinner {
  width: 36px;
  height: 36px;
  margin: 0 auto 12px;
  border-radius: 50%;
  border: 3px solid var(--hx-teal-200);
  border-top-color: var(--hx-teal-600);
  animation: spin 0.75s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.intro {
  padding: 28px 20px 20px;
  max-width: 480px;
  margin: 0 auto;
  animation: rise 0.35s ease both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
}
.eyebrow {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-teal-700);
}
.intro h2 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.lead {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--hx-n-700);
}
.depth-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 18px;
}
.depth-card {
  text-align: left;
  padding: 14px 16px;
  border-radius: 16px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
}
.depth-card.on {
  border-color: var(--hx-teal-500);
  box-shadow: 0 0 0 3px var(--hx-teal-100);
  background: linear-gradient(180deg, #fff, var(--hx-teal-50));
}
.depth-top {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.depth-top b { font-size: 16px; font-weight: 800; }
.depth-top em {
  font-style: normal;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal-700);
}
.depth-card > span { font-size: 12px; color: var(--hx-muted); }
.trust {
  margin: 0 0 22px;
  padding: 12px 14px 12px 32px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.75);
  border: 1px solid rgba(191, 233, 219, 0.65);
}
.trust li {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-n-700);
}

.fill {
  padding: 22px 20px 110px;
  max-width: 480px;
  margin: 0 auto;
  animation: rise 0.32s ease both;
}
.mod-tag {
  margin: 0 0 10px;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal-700);
}
.q-title {
  margin: 0 0 10px;
  font-size: clamp(22px, 6vw, 28px);
  font-weight: 800;
  line-height: 1.32;
  letter-spacing: -0.02em;
  color: var(--hx-ink);
}
.req, .opt-tag {
  margin-left: 6px;
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  vertical-align: 3px;
}
.req { color: var(--hx-warn-text); background: var(--hx-warn-bg); }
.opt-tag { color: var(--hx-muted); background: var(--hx-n-100); }
.q-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--hx-muted);
}

.choice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.choice {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  width: 100%;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
  text-align: left;
  font-size: 16px;
  color: var(--hx-ink);
}
.choice.on {
  border-color: var(--hx-teal-500);
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  font-weight: 700;
}
.choice-mark {
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  border: 1.5px solid var(--hx-n-300);
  display: grid;
  place-items: center;
  font-size: 13px;
  background: #fff;
}
.choice.on .choice-mark {
  border-color: var(--hx-teal-600);
  background: var(--hx-teal-600);
  color: #fff;
}
.choice-label { flex: 1; line-height: 1.4; }

.confirm {
  padding: 28px 20px 40px;
  max-width: 480px;
  margin: 0 auto;
  animation: rise 0.35s ease both;
}
.confirm .eyebrow {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-teal-700);
}
.confirm h2 {
  margin: 0 0 12px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.confirm .lead {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.65;
  color: var(--hx-n-700);
}
.confirm-back {
  width: 100%;
  margin-top: 10px;
}

.bundle {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 8px;
}
.bundle-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-n-800);
}
.num-input, .big-input {
  padding: 10px 14px;
  border-radius: 14px;
  background: #fff;
  border: 1.5px solid var(--hx-n-200);
}
.sheet-trigger {
  width: 100%;
  min-height: 52px;
  padding: 12px 16px;
  border-radius: 14px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
  text-align: left;
  font-size: 16px;
}
.unit { font-size: 14px; color: var(--hx-muted); }
.err {
  margin: 14px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-danger-text);
}
.hint { margin: 8px 0 0; font-size: 12px; color: var(--hx-muted); }

.nav-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(10px);
  box-shadow: 0 -6px 18px rgba(19, 34, 46, 0.06);
}
.nav-side {
  flex: 0 0 auto;
  min-width: 76px;
  height: 48px;
  padding: 0 12px;
}
.nav-main { flex: 1; height: 48px; }
.btn-text {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 14px;
  font-weight: 600;
  height: 48px;
  padding: 0 6px;
}
.btn-primary, .btn-ghost {
  height: 48px;
  border-radius: 14px;
  font-size: 16px;
  font-weight: 700;
  border: 0;
}
.btn-primary {
  width: 100%;
  background: linear-gradient(135deg, var(--hx-teal-500), var(--hx-teal-700));
  color: #fff;
  box-shadow: 0 10px 20px rgba(14, 159, 131, 0.22);
}
.btn-primary:disabled { opacity: 0.55; box-shadow: none; }
.btn-ghost {
  background: var(--hx-n-100);
  color: var(--hx-n-800);
}

.done {
  margin: 48px 20px 0;
  padding: 36px 22px 24px;
  text-align: center;
  border-radius: var(--hx-r-xl);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--hx-elev-2);
  max-width: 420px;
  margin-left: auto;
  margin-right: auto;
  animation: rise 0.4s ease both;
}
.done-ico {
  width: 64px;
  height: 64px;
  margin: 0 auto;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 30px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(145deg, var(--hx-teal-400), var(--hx-teal-700));
}
.done h2 {
  margin: 16px 0 8px;
  font-size: 22px;
  font-weight: 800;
}
.done p {
  margin: 0 0 22px;
  font-size: 14px;
  line-height: 1.65;
  color: var(--hx-muted);
}
.done-hint { margin-top: 12px !important; margin-bottom: 0 !important; }

.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 12px 0;
}
.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 8px 8px;
}
.sheet-done {
  border: 0;
  background: transparent;
  color: var(--hx-teal-700);
  font-weight: 700;
}
.sheet-list {
  flex: 1;
  overflow: auto;
  padding: 8px 4px 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
