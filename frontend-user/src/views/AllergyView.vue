<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'

type Category = 'DRUG' | 'FOOD' | 'ENVIRONMENT' | 'OTHER'

interface AllergyItem {
  id: string
  category: Category
  categoryLabel: string
  allergenCode: string | null
  allergenName: string
  allergyType: 'ALLERGY' | 'INTOLERANCE'
  reactions: string[]
  reactionLabels: string[]
  reactionNote: string | null
  severity: string
  severityLabel: string
  highRisk: boolean
  clinicalStatus: 'ACTIVE' | 'RESOLVED'
  verification: 'UNCONFIRMED' | 'CONFIRMED' | 'REFUTED'
  verificationLabel: string
  onsetText: string | null
  note: string | null
  effective: boolean
  editableByPatient: boolean
}

interface Summary {
  status: 'UNKNOWN' | 'NKA' | 'HAS'
  statusLabel: string
  items: AllergyItem[]
}

interface Catalog {
  categories: Array<{ code: Category; label: string }>
  allergens: Array<{ code: string; category: Category; label: string; defaultType: string }>
  reactions: Array<{ code: string; label: string; highRisk: boolean }>
  severities: Array<{ code: string; label: string }>
}

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const summary = ref<Summary | null>(null)
const catalog = ref<Catalog | null>(null)
const showForm = ref(false)
const editingId = ref<string | null>(null)
/**
 * 关闭后的穿透防护：
 * 弹层 teleport 到 body，关闭瞬间同一触点会点到下层「添加/列表」。
 * 用时间戳拒绝误开 + body 级透明盾牌吞掉残余 pointer/click。
 */
const formOpenLocked = ref(false)
const clickShield = ref(false)
let formOpenLockTimer: ReturnType<typeof setTimeout> | null = null
let formClosedAt = 0
const FORM_CLOSE_LOCK_MS = 1000

const form = reactive({
  category: 'DRUG' as Category,
  allergenCode: '',
  customName: '',
  allergyType: 'ALLERGY' as 'ALLERGY' | 'INTOLERANCE',
  reactions: [] as string[],
  reactionNote: '',
  severity: 'UNKNOWN',
  clinicalStatus: 'ACTIVE',
  onsetText: '',
  note: '',
})

/** 不耐受预设物质（有字典码的走 allergenCode，其余走自定义名称） */
const INTOLERANCE_SUBSTANCES: Array<{ catalogCode: string | null; label: string }> = [
  { catalogCode: 'MILK', label: '牛奶/乳制品' },
  { catalogCode: 'WHEAT', label: '面食/麸质' },
  { catalogCode: 'SHELLFISH', label: '海鲜/贝类' },
  { catalogCode: 'EGG', label: '鸡蛋' },
  { catalogCode: 'SOY', label: '大豆' },
  { catalogCode: null, label: '酒精' },
  { catalogCode: null, label: '咖啡因' },
  { catalogCode: null, label: '辛辣食物' },
]

/**
 * 不耐受症状 UI 码 → 后端 AllergyReactionEnum。
 * 无精确枚举的项落到 OTHER，并用 noteTag 写入 reactionNote，避免丢语义。
 */
const INTOLERANCE_REACTIONS: Array<{
  uiCode: string
  label: string
  backend: string
  noteTag?: string
}> = [
  { uiCode: 'DIARRHEA', label: '腹泻', backend: 'ABDOMINAL', noteTag: '腹泻' },
  { uiCode: 'BLOAT', label: '腹胀/产气', backend: 'OTHER', noteTag: '腹胀/产气' },
  { uiCode: 'PAIN', label: '腹痛', backend: 'ABDOMINAL', noteTag: '腹痛' },
  { uiCode: 'NAUSEA_VOMIT', label: '恶心呕吐', backend: 'NAUSEA_VOMIT' },
  { uiCode: 'HEARTBURN', label: '反酸烧心', backend: 'OTHER', noteTag: '反酸烧心' },
  { uiCode: 'FLUSH', label: '皮肤泛红', backend: 'RASH' },
  { uiCode: 'DIZZY', label: '头晕乏力', backend: 'OTHER', noteTag: '头晕乏力' },
  { uiCode: 'OTHER', label: '其他', backend: 'OTHER' },
]

/** 页面草稿态：HAS 才展示添加/列表；NKA/UNKNOWN 展示确认保存 */
const draftStatus = ref<'HAS' | 'NKA' | 'UNKNOWN'>('UNKNOWN')

const items = computed(() => summary.value?.items ?? [])
const hasEffective = computed(() => items.value.some((i) => i.effective))
const showHasUi = computed(() => draftStatus.value === 'HAS')
const confirmDisabled = computed(() => {
  if (draftStatus.value === 'HAS') return true
  return draftStatus.value === summary.value?.status
})
const isIntolerance = computed(() => form.allergyType === 'INTOLERANCE')
const commonAllergens = computed(() => {
  if (isIntolerance.value) return []
  return (catalog.value?.allergens ?? []).filter((a) => a.category === form.category)
})
const substanceOptions = computed(() =>
  isIntolerance.value
    ? INTOLERANCE_SUBSTANCES.map((s) => ({
        code: s.catalogCode || `CUSTOM:${s.label}`,
        catalogCode: s.catalogCode,
        label: s.label,
      }))
    : commonAllergens.value.map((a) => ({
        code: a.code,
        catalogCode: a.code,
        label: a.label,
      })),
)
const reactionOptions = computed(() =>
  isIntolerance.value
    ? INTOLERANCE_REACTIONS.map((r) => ({
        code: r.uiCode,
        label: r.label,
        highRisk: false,
      }))
    : (catalog.value?.reactions ?? []).map((r) => ({
        code: r.code,
        label: r.label,
        highRisk: r.highRisk,
      })),
)
const allergenFieldLabel = computed(() => (isIntolerance.value ? '不耐受物质' : '过敏原'))
const reactionFieldLabel = computed(() =>
  isIntolerance.value ? '不适症状（可多选）' : '过敏反应症状（可多选）',
)
const allergenPlaceholder = computed(() =>
  isIntolerance.value
    ? '不在上面？直接输入，如：冰饮、芒果'
    : '不在上面？直接输入，如：阿莫西林、菠萝',
)
const willBeHighRisk = computed(() => {
  if (isIntolerance.value) return form.severity === 'SEVERE'
  return (
    form.severity === 'SEVERE' ||
    form.reactions.some((r) => catalog.value?.reactions.find((x) => x.code === r)?.highRisk)
  )
})

function isDangerReaction(r: { highRisk?: boolean; label?: string }) {
  return !!r.highRisk || /过敏性休克|喉头水肿/.test(r.label || '')
}

function isDangerSeverity(s: { code?: string; label?: string }) {
  return s.code === 'SEVERE' || /重度|严重/.test(s.label || '')
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [s, c] = await Promise.all([
      api<{ data: Summary }>('/api/c/v1/me/allergies'),
      catalog.value ? Promise.resolve({ data: catalog.value }) : api<{ data: Catalog }>('/api/c/v1/me/allergies/catalog'),
    ])
    summary.value = s.data
    catalog.value = c.data
    syncDraftFromSummary()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function syncDraftFromSummary() {
  if (!summary.value) return
  if (hasEffective.value || summary.value.status === 'HAS') draftStatus.value = 'HAS'
  else if (summary.value.status === 'NKA') draftStatus.value = 'NKA'
  else draftStatus.value = 'UNKNOWN'
}

function selectStatus(status: 'HAS' | 'NKA' | 'UNKNOWN') {
  draftStatus.value = status
}

function resetForm() {
  form.category = 'DRUG'
  form.allergenCode = ''
  form.customName = ''
  form.allergyType = 'ALLERGY'
  form.reactions = []
  form.reactionNote = ''
  form.severity = 'UNKNOWN'
  form.clinicalStatus = 'ACTIVE'
  form.onsetText = ''
  form.note = ''
}

function lockFormReopen(ms = FORM_CLOSE_LOCK_MS) {
  formClosedAt = Date.now()
  formOpenLocked.value = true
  clickShield.value = true
  if (formOpenLockTimer) clearTimeout(formOpenLockTimer)
  formOpenLockTimer = setTimeout(() => {
    formOpenLocked.value = false
    clickShield.value = false
    formOpenLockTimer = null
  }, ms)
}

function canOpenForm() {
  if (showForm.value) return false
  if (formOpenLocked.value) return false
  if (Date.now() - formClosedAt < FORM_CLOSE_LOCK_MS) return false
  return true
}

/** 弹窗一旦关闭（含点遮罩），立刻上锁 */
watch(showForm, (open, prev) => {
  if (prev && !open) lockFormReopen()
})

function openAdd() {
  if (!canOpenForm()) return
  editingId.value = null
  resetForm()
  showForm.value = true
}

function closeForm() {
  // 先亮盾牌再关弹层，吞掉同一次触点的后续事件
  lockFormReopen()
  showForm.value = false
}

function onShowFormUpdate(v: boolean) {
  if (!v) lockFormReopen()
  showForm.value = v
}

function swallowShieldEvent(e: Event) {
  e.preventDefault()
  e.stopPropagation()
}

function mapReactionsForEdit(item: AllergyItem): string[] {
  if (item.allergyType !== 'INTOLERANCE') return [...item.reactions]
  const note = item.reactionNote || ''
  const blob = `${note} ${(item.reactionLabels || []).join(' ')}`
  const picked = new Set<string>()
  for (const r of INTOLERANCE_REACTIONS) {
    if (r.noteTag && blob.includes(r.noteTag)) {
      picked.add(r.uiCode)
      continue
    }
    if (r.uiCode === 'NAUSEA_VOMIT' && item.reactions.includes('NAUSEA_VOMIT')) picked.add(r.uiCode)
    if (r.uiCode === 'FLUSH' && item.reactions.includes('RASH')) picked.add(r.uiCode)
    if (r.uiCode === 'OTHER' && item.reactions.includes('OTHER') && !INTOLERANCE_REACTIONS.some((x) => x.noteTag && blob.includes(x.noteTag))) {
      picked.add('OTHER')
    }
  }
  return [...picked]
}

function fillFormFromItem(item: AllergyItem) {
  editingId.value = item.id
  form.allergyType = item.allergyType
  form.category = item.allergyType === 'INTOLERANCE' ? 'FOOD' : item.category
  form.allergenCode = item.allergenCode ?? ''
  form.customName = item.allergenCode ? '' : item.allergenName
  // 不耐受无字典码但名称命中预设（如酒精）
  if (
    item.allergyType === 'INTOLERANCE' &&
    !item.allergenCode &&
    INTOLERANCE_SUBSTANCES.some((s) => !s.catalogCode && s.label === item.allergenName)
  ) {
    form.customName = item.allergenName
  }
  form.reactions = mapReactionsForEdit(item)
  form.reactionNote = item.reactionNote ?? ''
  form.severity = item.severity
  form.clinicalStatus = item.clinicalStatus
  form.onsetText = item.onsetText ?? ''
  form.note = item.note ?? ''
}

function openEdit(item: AllergyItem) {
  if (!canOpenForm()) return
  if (!item.editableByPatient) {
    showToast('该条目已由医护确认，如需修改请联系健管师')
    return
  }
  fillFormFromItem(item)
  showForm.value = true
}

function onItemClick(item: AllergyItem) {
  openEdit(item)
}

/** 按字典码或名称匹配已有过敏条目（与后端 assertNoDuplicate 一致） */
function findExistingBySubstance(opt: {
  catalogCode: string | null
  label: string
}): AllergyItem | undefined {
  const label = opt.label.trim()
  return items.value.find((item) => {
    if (opt.catalogCode && item.allergenCode === opt.catalogCode) return true
    return item.allergenName === label
  })
}

function findExistingByPayload(payload: {
  allergenCode: string | null
  allergenName: string
}): AllergyItem | undefined {
  const name = payload.allergenName.trim()
  return items.value.find((item) => {
    if (payload.allergenCode && item.allergenCode === payload.allergenCode) return true
    return item.allergenName === name
  })
}

function pickCategory(c: Category) {
  form.category = c
  form.allergenCode = ''
  form.customName = ''
}

function selectAllergyType(type: 'ALLERGY' | 'INTOLERANCE') {
  if (form.allergyType === type) return
  form.allergyType = type
  // 切换类型时清空物质与症状，避免混杂提交
  form.allergenCode = ''
  form.customName = ''
  form.reactions = []
  if (type === 'INTOLERANCE') {
    form.category = 'FOOD'
  } else if (!editingId.value) {
    form.category = 'DRUG'
  }
}

function isSubstanceSelected(opt: { catalogCode: string | null; label: string; code: string }) {
  if (opt.catalogCode) return form.allergenCode === opt.catalogCode
  return !form.allergenCode && form.customName.trim() === opt.label
}

function pickSubstance(opt: { catalogCode: string | null; label: string; code: string }) {
  if (isSubstanceSelected(opt)) {
    form.allergenCode = ''
    form.customName = ''
    return
  }
  // 新建时选中已有物质：可编辑则切编辑；医护已确认则拦截
  if (!editingId.value) {
    const existing = findExistingBySubstance(opt)
    if (existing) {
      if (!existing.editableByPatient) {
        showToast('该条目已由医护确认，如需修改请联系健管师')
        return
      }
      fillFormFromItem(existing)
      showToast('已切换为编辑该过敏记录')
      return
    }
  }
  if (opt.catalogCode) {
    form.allergenCode = opt.catalogCode
    form.customName = ''
    // 不耐受模式下不跟字典 defaultType 回写，避免把类型打回「过敏」
    if (!isIntolerance.value) {
      const hit = catalog.value?.allergens.find((a) => a.code === opt.catalogCode)
      if (hit?.defaultType === 'ALLERGY' || hit?.defaultType === 'INTOLERANCE') {
        form.allergyType = hit.defaultType
      }
    }
  } else {
    form.allergenCode = ''
    form.customName = opt.label
  }
}

function onCustomInput() {
  if (form.customName.trim()) form.allergenCode = ''
}

function toggleReaction(code: string) {
  const i = form.reactions.indexOf(code)
  if (i >= 0) form.reactions.splice(i, 1)
  else form.reactions.push(code)
}

function resolveSavePayload() {
  const intolerance = isIntolerance.value
  const category: Category = intolerance ? 'FOOD' : form.category

  let allergenCode: string | null = null
  let allergenName = form.customName.trim()
  if (form.allergenCode) {
    const hit = catalog.value?.allergens.find((a) => a.code === form.allergenCode)
    if (hit) {
      allergenCode = hit.code
      const preset = INTOLERANCE_SUBSTANCES.find((s) => s.catalogCode === hit.code)?.label
      allergenName = allergenName || preset || hit.label
    }
  }

  let reactions: string[] = []
  let reactionNote = form.reactionNote.trim()
  if (intolerance) {
    const selected = INTOLERANCE_REACTIONS.filter((r) => form.reactions.includes(r.uiCode))
    const codes = new Set<string>()
    const tags: string[] = []
    for (const r of selected) {
      codes.add(r.backend)
      if (r.noteTag) tags.push(r.noteTag)
    }
    reactions = [...codes]
    if (tags.length) {
      const prefix = tags.join('、')
      if (!reactionNote) reactionNote = prefix
      else if (!tags.every((t) => reactionNote.includes(t))) {
        reactionNote = `${prefix}；${reactionNote}`
      }
    }
  } else {
    reactions = [...form.reactions]
  }

  return {
    category,
    allergenCode,
    allergenName,
    allergyType: form.allergyType,
    reactions,
    reactionNote: reactionNote || null,
    severity: form.severity,
    clinicalStatus: form.clinicalStatus,
    onsetText: form.onsetText.trim() || null,
    note: form.note.trim() || null,
  }
}

async function save() {
  const payload = resolveSavePayload()
  if (!payload.allergenName) {
    showToast(isIntolerance.value ? '请选择或填写不耐受物质' : '请选择或填写过敏原')
    return
  }
  saving.value = true
  try {
    const body = JSON.stringify(payload)
    if (editingId.value) {
      await api(`/api/c/v1/me/allergies/${editingId.value}`, { method: 'PUT', body })
    } else {
      // 自定义输入等同已有名时：可编辑则改 PUT，医护确认则拦截
      const dup = findExistingByPayload(payload)
      if (dup) {
        if (!dup.editableByPatient) {
          showToast('该条目已由医护确认，如需修改请联系健管师')
          return
        }
        await api(`/api/c/v1/me/allergies/${dup.id}`, { method: 'PUT', body })
      } else {
        await api('/api/c/v1/me/allergies', { method: 'POST', body })
      }
    }
    showToast({ type: 'success', message: '已保存，待健管师确认' })
    lockFormReopen()
    showForm.value = false
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteAllergy(id: string) {
  try {
    await showConfirmDialog({ title: '删除过敏记录', message: '确定删除这条过敏记录吗？' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/allergies/${id}`, { method: 'DELETE' })
    showToast({ type: 'success', message: '已删除' })
    if (editingId.value === id) {
      lockFormReopen()
      showForm.value = false
    }
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

async function remove() {
  if (!editingId.value) return
  await deleteAllergy(editingId.value)
}

async function removeFromList(item: AllergyItem, e: Event) {
  e.stopPropagation()
  e.preventDefault()
  if (!item.editableByPatient) {
    showToast('该条目已由医护确认，如需修改请联系健管师')
    return
  }
  await deleteAllergy(item.id)
}

async function confirmStatus() {
  if (draftStatus.value === 'HAS' || confirmDisabled.value) return
  const status = draftStatus.value
  try {
    const res = await api<{ data: Summary }>('/api/c/v1/me/allergies/status', {
      method: 'PUT',
      body: JSON.stringify({ status }),
    })
    summary.value = res.data
    syncDraftFromSummary()
    showToast({ type: 'success', message: status === 'NKA' ? '已记录：没有过敏' : '已记录：不确定' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  }
}

function detail(item: AllergyItem) {
  const parts: string[] = []
  if (item.reactionLabels.length) parts.push(item.reactionLabels.join('、'))
  if (item.reactionNote) parts.push(item.reactionNote)
  if (item.severity !== 'UNKNOWN') parts.push(item.severityLabel)
  if (item.onsetText) parts.push(`首次 ${item.onsetText}`)
  return parts.join(' · ') || '未填写反应'
}

/** 自填未确认不展示角标；仅医护确认/排除时展示 */
function verifyBadge(item: AllergyItem): { text: string; cls: string } | null {
  if (item.verification === 'CONFIRMED') return { text: '医护已确认', cls: 'ok' }
  if (item.verification === 'REFUTED') return { text: '医护已排除', cls: 'muted' }
  return null
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <button type="button" class="back" aria-label="返回" @click="router.back()">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" />
        </svg>
      </button>
      <h1>过敏史</h1>
      <span class="topbar-spacer" aria-hidden="true" />
    </header>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else-if="summary">
      <p class="tip-bar">
        过敏信息会用于用药、饮食记录和健康助手的安全提醒。你填写的内容需要健管师确认，确认后如需修改请联系健管师。
      </p>

      <section class="card">
        <div class="status-title">你是否有药物、食物或其他过敏？</div>
        <div class="chips">
          <button
            type="button"
            class="chip"
            :class="{ active: draftStatus === 'NKA' }"
            @click="selectStatus('NKA')"
          >
            我没有过敏
          </button>
          <button
            type="button"
            class="chip"
            :class="{ active: draftStatus === 'UNKNOWN' }"
            @click="selectStatus('UNKNOWN')"
          >
            不确定
          </button>
          <button
            type="button"
            class="chip"
            :class="{ active: draftStatus === 'HAS' }"
            @click="selectStatus('HAS')"
          >
            我有过敏
          </button>
        </div>
      </section>

      <div v-if="showHasUi" class="toolbar">
        <button
          type="button"
          class="add-btn"
          :disabled="formOpenLocked"
          @click="openAdd"
        >
          <van-icon name="plus" />
          添加过敏
        </button>
      </div>

      <template v-if="showHasUi">
        <section
          v-for="item in items"
          :key="item.id"
          class="card item"
          :class="{ risk: item.highRisk && item.effective, inactive: !item.effective }"
          @click="onItemClick(item)"
        >
          <div class="item-top">
            <div class="item-main">
              <div class="item-head">
                <strong>{{ item.allergenName }}</strong>
                <span class="badge">{{ item.categoryLabel }}</span>
                <span v-if="item.allergyType === 'INTOLERANCE'" class="badge muted">不耐受</span>
                <span v-if="item.highRisk" class="badge danger">高危</span>
                <span v-if="item.clinicalStatus === 'RESOLVED'" class="badge muted">已消失</span>
                <span
                  v-if="verifyBadge(item)"
                  class="badge"
                  :class="verifyBadge(item)!.cls"
                >{{ verifyBadge(item)!.text }}</span>
              </div>
              <p class="line">{{ detail(item) }}</p>
              <p v-if="!item.editableByPatient" class="meta">已由医护确认，如需修改请联系健管师</p>
            </div>
            <button
              v-if="item.editableByPatient"
              type="button"
              class="item-del"
              aria-label="删除"
              @click="removeFromList(item, $event)"
            >
              删除
            </button>
          </div>
        </section>
      </template>

      <div v-else class="toolbar">
        <van-button
          round
          block
          type="primary"
          :disabled="confirmDisabled"
          @click="confirmStatus"
        >
          确认保存
        </van-button>
      </div>
    </template>

    <!-- 关闭后盖住全屏，吞掉穿透到「添加过敏/列表」的残余点击 -->
    <Teleport to="body">
      <div
        v-if="clickShield"
        class="allergy-click-shield"
        aria-hidden="true"
        @touchstart="swallowShieldEvent"
        @touchend="swallowShieldEvent"
        @touchcancel="swallowShieldEvent"
        @pointerdown="swallowShieldEvent"
        @pointerup="swallowShieldEvent"
        @click="swallowShieldEvent"
      />
    </Teleport>

    <van-popup
      :show="showForm"
      position="bottom"
      class="allergy-popup"
      teleport="body"
      :close-on-click-overlay="true"
      :overlay-style="{ background: 'rgba(15, 23, 42, 0.45)' }"
      :lock-scroll="true"
      @update:show="onShowFormUpdate"
    >
      <div class="sheet" @click.stop>
        <header class="sheet-hd">
          <span class="drag" aria-hidden="true" />
          <h3>{{ editingId ? '编辑过敏史' : '添加过敏史' }}</h3>
          <button
            type="button"
            class="sheet-close"
            aria-label="关闭"
            @touchend.prevent.stop="closeForm"
            @click.prevent.stop="closeForm"
          >
            <van-icon name="cross" />
          </button>
        </header>

        <div class="sheet-body">
          <div class="block">
            <div class="fld-label required">类型</div>
            <div class="caps">
              <button
                type="button"
                class="cap"
                :class="{ on: form.allergyType === 'ALLERGY' }"
                @click="selectAllergyType('ALLERGY')"
              >
                过敏
              </button>
              <button
                type="button"
                class="cap"
                :class="{ on: form.allergyType === 'INTOLERANCE' }"
                @click="selectAllergyType('INTOLERANCE')"
              >
                不耐受（如喝牛奶腹泻）
              </button>
            </div>
          </div>

          <div v-if="!isIntolerance" class="block">
            <div class="fld-label required">来源</div>
            <div class="caps">
              <button
                v-for="c in catalog?.categories ?? []"
                :key="c.code"
                type="button"
                class="cap"
                :class="{ on: form.category === c.code }"
                @click="pickCategory(c.code)"
              >
                {{ c.label }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="fld-label required">{{ allergenFieldLabel }}</div>
            <div v-if="substanceOptions.length" class="caps">
              <button
                v-for="a in substanceOptions"
                :key="a.code"
                type="button"
                class="cap"
                :class="{ on: isSubstanceSelected(a) }"
                @click="pickSubstance(a)"
              >
                {{ a.label }}
              </button>
            </div>
            <input
              v-model="form.customName"
              class="soft-input"
              type="text"
              maxlength="64"
              :placeholder="allergenPlaceholder"
              @input="onCustomInput"
            />
          </div>

          <div class="block">
            <div class="fld-label">{{ reactionFieldLabel }}</div>
            <div class="caps">
              <button
                v-for="r in reactionOptions"
                :key="r.code"
                type="button"
                class="cap"
                :class="{ on: form.reactions.includes(r.code), danger: isDangerReaction(r) }"
                @click="toggleReaction(r.code)"
              >
                {{ r.label }}
              </button>
            </div>
            <input
              v-model="form.reactionNote"
              class="soft-input"
              type="text"
              maxlength="128"
              placeholder="补充描述（选填）"
            />
          </div>

          <div class="block">
            <div class="fld-label">严重程度</div>
            <div class="caps">
              <button
                v-for="s in catalog?.severities ?? []"
                :key="s.code"
                type="button"
                class="cap"
                :class="{ on: form.severity === s.code, danger: isDangerSeverity(s) }"
                @click="form.severity = s.code"
              >
                {{ s.label }}
              </button>
            </div>
            <p v-if="willBeHighRisk" class="risk-hint">将标记为高危过敏，就诊时请主动告知医生</p>
          </div>

          <div class="block">
            <div class="fld-label">首次发生</div>
            <input
              v-model="form.onsetText"
              class="soft-input"
              type="text"
              maxlength="32"
              placeholder="如：2015 年、小时候（选填）"
            />
          </div>

          <div v-if="editingId" class="block">
            <div class="fld-label">现在还过敏吗</div>
            <div class="caps">
              <button
                type="button"
                class="cap"
                :class="{ on: form.clinicalStatus === 'ACTIVE' }"
                @click="form.clinicalStatus = 'ACTIVE'"
              >
                仍过敏
              </button>
              <button
                type="button"
                class="cap"
                :class="{ on: form.clinicalStatus === 'RESOLVED' }"
                @click="form.clinicalStatus = 'RESOLVED'"
              >
                已不过敏
              </button>
            </div>
          </div>

          <div class="block">
            <div class="fld-label">备注</div>
            <textarea
              v-model="form.note"
              class="soft-input soft-area"
              maxlength="200"
              rows="2"
              placeholder="选填"
            />
          </div>
        </div>

        <footer class="sheet-ft">
          <button
            v-if="editingId"
            type="button"
            class="del-btn"
            :disabled="saving"
            @click="remove"
          >
            删除
          </button>
          <button
            type="button"
            class="save-btn"
            :disabled="saving"
            @click.stop.prevent="save"
          >
            {{ saving ? '保存中…' : '保存过敏记录' }}
          </button>
        </footer>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}
.add-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #14b8a6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.add-btn:disabled {
  opacity: 0.55;
  cursor: default;
}
.topbar {
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  align-items: center;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 12px 10px;
}
.back {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  display: grid;
  place-items: center;
  padding: 0;
  color: #1d2129;
  cursor: pointer;
}
.back svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.topbar h1 {
  margin: 0;
  text-align: center;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
}
.topbar-spacer {
  width: 36px;
  height: 36px;
}
.loading {
  padding: 48px 0;
}
.tip-bar {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: #ebf4ff;
  color: #1677ff;
  font-size: 12px;
  line-height: 1.5;
  box-sizing: border-box;
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--hx-card-shadow);
}
.status-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid transparent;
  background: #f7f8fa;
  color: #4e5969;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  padding: 8px 12px;
  border-radius: 999px;
  line-height: 1.2;
  cursor: pointer;
}
.chip.active {
  background: #e8f3ff;
  color: #1677ff;
  border: 1px solid #1677ff;
  font-weight: 600;
}
.toolbar {
  margin: 14px 16px 0;
}
.toolbar :deep(.van-button) {
  height: 44px;
  font-weight: 700;
  border: 0;
  background: linear-gradient(135deg, #1677ff, #10b981);
}
.toolbar :deep(.van-button--disabled) {
  opacity: 0.45;
}
.item.risk {
  background: var(--hx-danger-bg, #fde7ea);
  border: 1px solid #fecaca;
}
.item.inactive {
  opacity: 0.6;
}
.item-top {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-del {
  flex: none;
  margin: 0;
  padding: 4px 10px;
  border: 1px solid #ffccc7;
  border-radius: 999px;
  background: #fff2f0;
  color: #f53f3f;
  font-size: 12px;
  font-weight: 650;
  font-family: inherit;
  line-height: 1.4;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.item-del:active {
  opacity: 0.85;
}
.item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.item-head strong {
  font-size: 16px;
  color: #0f172a;
}
.badge {
  font-size: 11px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  border-radius: 999px;
  padding: 2px 8px;
}
.badge.ok {
  color: #047857;
  background: rgba(16, 185, 129, 0.12);
}
.badge.warn {
  color: var(--hx-warn-text, #8a5c00);
  background: var(--hx-warn-bg, #fff3da);
}
.badge.danger {
  color: #fff;
  background: var(--hx-danger, #e0435a);
}
.badge.muted {
  color: #64748b;
  background: #f0f4f6;
}
.line {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #0f172a;
}
.meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: #86909c;
}
.sheet {
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  background: #fff;
  box-sizing: border-box;
}
.sheet-hd {
  position: relative;
  z-index: 3;
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 48px 8px;
  background: #fff;
}
.drag {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: #c9cdd4;
  margin-bottom: 12px;
}
.sheet-hd h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  text-align: center;
  line-height: 1.3;
  pointer-events: none;
}
.sheet-close {
  position: absolute;
  top: 14px;
  right: 8px;
  z-index: 5;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #86909c;
  display: grid;
  place-items: center;
  padding: 0;
  font-size: 18px;
  cursor: pointer;
  pointer-events: auto;
  -webkit-tap-highlight-color: transparent;
}
.sheet-close :deep(.van-icon) {
  pointer-events: none;
}
.sheet-body {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 12px 16px 24px;
  box-sizing: border-box;
}
.block + .block {
  margin-top: 16px;
}
.fld-label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: #1d2129;
  line-height: 1.3;
}
.fld-label.required::after {
  content: ' *';
  color: #f53f3f;
  font-weight: 700;
}
.caps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.cap {
  border: 1px solid transparent;
  background: #f7f8fa;
  color: #4e5969;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  padding: 8px 12px;
  border-radius: 999px;
  line-height: 1.2;
  cursor: pointer;
}
.cap.on {
  background: #e8f3ff;
  color: #1677ff;
  border: 1px solid #1677ff;
  font-weight: 600;
}
.cap.danger {
  background: #fff2f0;
  color: #f53f3f;
  border-color: transparent;
}
.cap.danger.on {
  background: #ffece8;
  color: #f53f3f;
  border: 1px solid #f53f3f;
  font-weight: 700;
}
.soft-input {
  display: block;
  width: 100%;
  margin-top: 8px;
  box-sizing: border-box;
  border: 0;
  outline: none;
  background: #f7f8fa;
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 500;
  color: #1d2129;
  font-family: inherit;
  line-height: 1.45;
}
.soft-input::placeholder {
  color: #c9cdd4;
  font-weight: 500;
}
.soft-area {
  resize: vertical;
  min-height: 64px;
}
.risk-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #f53f3f;
  line-height: 1.4;
}
.sheet-ft {
  flex: none;
  display: flex;
  gap: 10px;
  padding: 12px 16px calc(20px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid #f2f3f5;
  background: #fff;
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.04);
  box-sizing: border-box;
}
.del-btn {
  flex: 0 0 88px;
  height: 44px;
  border-radius: 999px;
  border: 1px solid #fecaca;
  background: #fff;
  color: #f53f3f;
  font-size: 14px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.save-btn {
  flex: 1;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #10b981);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.22);
}
.save-btn:disabled,
.del-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
.save-btn:not(:disabled):active {
  transform: scale(0.98);
}
</style>

<!--
  van-popup teleport 到 body；左右 auto 居中，避免覆盖 Vant transform 动画。
  注意：绝不能对 .van-popup 写 display:flex !important——会盖住关闭时的 display:none，
  导致遮罩已关、白底表单仍留在页面上，看起来像「关不掉 / 又弹出来」。
-->
<style>
.van-popup.allergy-popup {
  width: 100% !important;
  max-width: 430px !important;
  left: 0 !important;
  right: 0 !important;
  margin-left: auto !important;
  margin-right: auto !important;
  border-radius: 20px 20px 0 0 !important;
  max-height: 85vh !important;
  overflow: hidden !important;
  background: #fff !important;
}
.van-popup.allergy-popup .sheet {
  width: 100%;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
}
.allergy-click-shield {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: transparent;
  touch-action: none;
}
</style>
