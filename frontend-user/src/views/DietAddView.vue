<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, apiUpload } from '../api/http'
import { debounce } from '../shared/debounce'
import { compressImage } from '../shared/image'
import {
  defaultMealType,
  estimateNutrients,
  fmtAmount,
  MEAL_LABELS,
  MEAL_TYPES,
  OIL_LABELS,
  sumNutrients,
  unitGrams,
  ymd,
  type DietDay,
  type DietDraft,
  type FoodItem,
  type FrequentFood,
  type Meal,
  type MealItem,
  type MealType,
  type Nutrients,
  type OilLevel,
} from '../shared/diet'
import { parseSampleMeal } from '../shared/care-plan-labels'

interface Line {
  key: number
  food?: FoodItem
  foodName: string
  amount: number | string
  unit: string
  oilLevel: OilLevel
  /** 未命中食物库时的整份热量估算 */
  estKcal: number | string
  confidence?: number | null
  gramsConfirmed: boolean
  /** 估算条目的千卡/克，改份量时热量随之换算 */
  kcalPerG?: number
  /** AI 识别的克数，份量快选以此为基准 */
  baseGrams?: number
  portion?: number
  /** 同一次拍照识别的批次，重新识别时整批替换 */
  batch?: number
  /** 识别结果与列表里已有的这一条同名，待用户确认是否同一份 */
  dupOf?: string
  /** AI 的份量估算依据 */
  basis?: string
}

const MAX_PHOTOS = 4
const PORTIONS = [
  { label: '吃了一半', factor: 0.5 },
  { label: '差不多', factor: 1 },
  { label: '多一些', factor: 1.5 },
]
const HINT_CHIPS = ['全部吃完', '剩了一半', '汤没喝', '主食半碗', '只吃了一点']

const route = useRoute()
const router = useRouter()
const mealId = typeof route.query.mealId === 'string' ? route.query.mealId : ''
const date = ref(typeof route.query.date === 'string' ? route.query.date : ymd(new Date()))
const mealType = ref<MealType>(
  MEAL_TYPES.includes(route.query.mealType as MealType) ? (route.query.mealType as MealType) : defaultMealType(),
)
const source = ref<'MANUAL' | 'PHOTO_AI' | 'TEXT_AI'>('MANUAL')
const note = ref('')
const lines = ref<Line[]>([])
/** 页面上合并编辑的已有记录；同一天同一餐次保存后只留一条 */
const mealIds = ref<string[]>([])
const isEdit = computed(() => mealIds.value.length > 0)
const loading = ref(true)
const saving = ref(false)

const keyword = ref('')
const results = ref<FoodItem[]>([])
const searching = ref(false)
const frequent = ref<FrequentFood[]>([])

const recognizing = ref(false)
const warnings = ref<string[]>([])
const showText = ref(false)
const textInput = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const photoFiles = ref<File[]>([])
const photoPreviews = ref<string[]>([])
/** 在识别弹层里点「+」继续选图时追加，否则整组替换 */
let appendingPhotos = false
const showPhotoSheet = ref(false)
const photoHint = ref('')
const photoBatch = ref(0)

let seq = 0

const title = computed(() => (isEdit.value ? '编辑' : '添加') + MEAL_LABELS[mealType.value])

function gramsOf(l: Line): number {
  const amt = Number(l.amount)
  if (!Number.isFinite(amt) || amt <= 0) return 0
  const per = l.food ? unitGrams(l.food, l.unit) : 1
  return per ? amt * per : 0
}

function syncEstKcal(l: Line) {
  if (!l.food && l.kcalPerG) l.estKcal = Math.round(gramsOf(l) * l.kcalPerG)
}

function onAmountChange(l: Line) {
  l.gramsConfirmed = true
  l.portion = undefined
  syncEstKcal(l)
}

function onEstKcalInput(l: Line, v: string | number) {
  const g = gramsOf(l)
  const kcal = Number(v)
  l.kcalPerG = g > 0 && kcal > 0 ? kcal / g : undefined
}

function setPortion(l: Line, factor: number) {
  if (!l.baseGrams) return
  const grams = l.baseGrams * factor
  const per = l.unit === 'g' ? 1 : unitGrams(l.food, l.unit) || 1
  l.amount = l.unit === 'g' ? Math.max(1, Math.round(grams)) : Math.round((grams / per) * 2) / 2 || 0.5
  l.portion = factor
  l.gramsConfirmed = true
  syncEstKcal(l)
}

function nutrientsOf(l: Line): Partial<Nutrients> {
  const g = gramsOf(l)
  if (l.food) return estimateNutrients(l.food, g, l.food.kind === 'DISH' ? l.oilLevel : null)
  return { energyKcal: Number(l.estKcal) || 0 }
}

const total = computed(() => sumNutrients(lines.value.map(nutrientsOf)))
const unconfirmed = computed(() => lines.value.filter((l) => !l.gramsConfirmed).length)
const mealTargetKcal = ref<number | null>(null)

const MEAL_RING_R = 54
const MEAL_RING_C = 2 * Math.PI * MEAL_RING_R
const mealKcal = computed(() => Math.round(total.value.energyKcal || 0))
const mealRingPct = computed(() => {
  if (!mealTargetKcal.value) return 0
  return Math.min(1, mealKcal.value / mealTargetKcal.value)
})
const mealPctLabel = computed(() => {
  if (!mealTargetKcal.value) return null
  return Math.round((mealKcal.value / mealTargetKcal.value) * 100)
})
const mealOver = computed(() => mealTargetKcal.value != null && mealKcal.value > mealTargetKcal.value)

/** 三大营养素条长按供能占比（碳水/蛋白 4 kcal/g，脂肪 9 kcal/g） */
const mealMacros = computed(() => {
  const carb = total.value.carbG || 0
  const protein = total.value.proteinG || 0
  const fat = total.value.fatG || 0
  const shares = [
    { key: 'carb', label: '碳水', grams: carb, kcal: carb * 4, color: '#f0a020' },
    { key: 'protein', label: '蛋白质', grams: protein, kcal: protein * 4, color: '#5b8def' },
    { key: 'fat', label: '脂肪', grams: fat, kcal: fat * 9, color: '#e0435a' },
  ]
  const sum = shares.reduce((s, x) => s + x.kcal, 0) || 1
  return shares.map((x) => ({ ...x, pct: Math.round((x.kcal / sum) * 100) }))
})

async function loadDayTarget() {
  try {
    const res = await api<{ data: DietDay }>(`/api/c/v1/me/diet/days/${date.value}`)
    mealTargetKcal.value = res.data?.mealTargetsKcal?.[mealType.value] ?? null
  } catch {
    mealTargetKcal.value = null
  }
}

function unitOptions(l: Line) {
  return ['g', ...(l.food?.units ?? []).map((u) => u.unit)]
}

function changeUnit(l: Line, unit: string) {
  if (unit === l.unit) return
  const grams = gramsOf(l)
  l.unit = unit
  const per = unitGrams(l.food, unit) || 1
  const next = grams > 0 ? grams / per : unit === 'g' ? 100 : 1
  l.amount = unit === 'g' ? Math.round(next) : Math.round(next * 2) / 2 || 0.5
  l.gramsConfirmed = true
  syncEstKcal(l)
}

function addFood(food: FoodItem, amount?: number | null, unit?: string | null) {
  const u = unit && unitGrams(food, unit) ? unit : food.units?.[0]?.unit || 'g'
  const amt = amount ?? (u === 'g' ? (food.servingGrams ? Math.round(food.servingGrams) : 100) : 1)
  lines.value.push({
    key: ++seq,
    food,
    foodName: food.name,
    amount: amt,
    unit: u,
    oilLevel: 'NORMAL',
    estKcal: '',
    gramsConfirmed: true,
  })
  keyword.value = ''
  results.value = []
}

function addCustom() {
  const name = keyword.value.trim()
  if (!name) return
  lines.value.push({
    key: ++seq,
    foodName: name,
    amount: 100,
    unit: 'g',
    oilLevel: 'NORMAL',
    estKcal: '',
    gramsConfirmed: true,
  })
  keyword.value = ''
  results.value = []
}

function removeLine(i: number) {
  lines.value.splice(i, 1)
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) {
    results.value = []
    return
  }
  searching.value = true
  try {
    const res = await api<{ data: FoodItem[] }>(`/api/c/v1/me/foods/search?keyword=${encodeURIComponent(q)}&limit=20`)
    if (keyword.value.trim() === q) results.value = res.data || []
  } catch {
    results.value = []
  } finally {
    searching.value = false
  }
}
const debouncedSearch = debounce(() => void doSearch(), 250)
watch(keyword, () => debouncedSearch())

async function fetchFood(code: string): Promise<FoodItem | undefined> {
  try {
    const res = await api<{ data: FoodItem }>(`/api/c/v1/me/foods/${encodeURIComponent(code)}`)
    return res.data
  } catch {
    return undefined
  }
}

async function toLines(items: MealItem[], confirmed: boolean): Promise<Line[]> {
  const codes = [...new Set(items.map((i) => i.foodCode).filter((c): c is string => !!c))]
  const foods = new Map<string, FoodItem>()
  await Promise.all(
    codes.map(async (c) => {
      const f = await fetchFood(c)
      if (f) foods.set(c, f)
    }),
  )
  return items.map((it) => {
    const food = it.foodCode ? foods.get(it.foodCode) : undefined
    const unit = food && it.unit && unitGrams(food, it.unit) ? it.unit : 'g'
    const grams = it.grams ?? 0
    const kcal = it.energyKcal ?? 0
    return {
      key: ++seq,
      food,
      foodName: it.foodName,
      amount: unit === 'g' ? Math.round(it.grams ?? it.amount ?? 0) : (it.amount ?? 1),
      unit,
      oilLevel: (it.oilLevel as OilLevel) || 'NORMAL',
      estKcal: food ? '' : Math.round(it.energyKcal ?? 0),
      confidence: it.confidence,
      gramsConfirmed: confirmed || it.gramsConfirmed !== false,
      kcalPerG: !food && grams > 0 && kcal > 0 ? kcal / grams : undefined,
      baseGrams: !confirmed && grams > 0 ? grams : undefined,
      basis: it.basis || undefined,
    }
  })
}

async function applyDraft(draft: DietDraft, src: 'PHOTO_AI' | 'TEXT_AI', batch?: number) {
  warnings.value = draft.warnings || []
  if (!draft.items.length) {
    showToast(warnings.value[0] || '没有识别到食物')
    return
  }
  const next = await toLines(draft.items, false)
  if (batch) next.forEach((l) => (l.batch = batch))
  markDuplicates(next)
  lines.value.push(...next)
  if (!isEdit.value && !route.query.mealType && draft.mealType && draft.mealType !== mealType.value) {
    await switchMealType(draft.mealType)
  }
  source.value = src
  showToast({ type: 'success', message: `识别到 ${draft.items.length} 种食物，请确认份量` })
}

function foodKey(l: Line) {
  return l.food?.code || l.foodName.replace(/\s|（.*?）|\(.*?\)/g, '')
}

/** 分次识别时，与列表里已有条目同名的新条目先标出来，由用户判断是同一份还是另一份 */
function markDuplicates(next: Line[]) {
  const existing = new Map(lines.value.map((l) => [foodKey(l), l.foodName]))
  for (const l of next) {
    const hit = existing.get(foodKey(l))
    if (hit) l.dupOf = hit
  }
}

function dropDuplicate(l: Line) {
  lines.value = lines.value.filter((x) => x.key !== l.key)
}

const duplicates = computed(() => lines.value.filter((l) => l.dupOf).length)

function pickPhoto(append = false) {
  appendingPhotos = append
  fileInput.value?.click()
}

function setPhotos(files: File[]) {
  photoPreviews.value.forEach((u) => URL.revokeObjectURL(u))
  photoFiles.value = files
  photoPreviews.value = files.map((f) => URL.createObjectURL(f))
}

function removePhoto(i: number) {
  setPhotos(photoFiles.value.filter((_, idx) => idx !== i))
  if (!photoFiles.value.length) showPhotoSheet.value = false
}

function onPhoto(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = Array.from(input.files ?? [])
  input.value = ''
  if (!picked.length) return
  const base = appendingPhotos ? photoFiles.value : []
  const files = [...base, ...picked].slice(0, MAX_PHOTOS)
  if (base.length + picked.length > MAX_PHOTOS) showToast(`同一餐最多 ${MAX_PHOTOS} 张，已保留前 ${MAX_PHOTOS} 张`)
  setPhotos(files)
  if (!appendingPhotos) {
    photoHint.value = ''
    photoBatch.value = 0
  }
  showPhotoSheet.value = true
}

function appendHint(text: string) {
  const cur = photoHint.value.trim()
  if (cur.includes(text)) return
  photoHint.value = cur ? `${cur}，${text}` : text
}

/** 已有本组照片的识别结果时，重新识别整批替换 */
const canRefinePhoto = computed(
  () => photoFiles.value.length > 0 && photoBatch.value > 0 && lines.value.some((l) => l.batch === photoBatch.value),
)

async function recognizePhoto() {
  if (!photoFiles.value.length) return
  recognizing.value = true
  try {
    const hint = photoHint.value.trim()
    const files = await Promise.all(photoFiles.value.map((f) => compressImage(f)))
    const res = await apiUpload<{ data: DietDraft }>(
      `/api/c/v1/me/diet/recognize?mealType=${mealType.value}`,
      files,
      'file',
      hint ? { hint } : undefined,
    )
    showPhotoSheet.value = false
    const prev = photoBatch.value
    if (prev && res.data.items.length) lines.value = lines.value.filter((l) => l.batch !== prev)
    photoBatch.value = ++seq
    await applyDraft(res.data, 'PHOTO_AI', photoBatch.value)
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    recognizing.value = false
  }
}

onBeforeUnmount(() => setPhotos([]))

async function recognizeText() {
  const text = textInput.value.trim()
  if (!text) {
    showToast('请描述吃了什么')
    return
  }
  recognizing.value = true
  try {
    const res = await api<{ data: DietDraft }>('/api/c/v1/me/diet/recognize-text', {
      method: 'POST',
      body: JSON.stringify({ text, mealType: mealType.value }),
    })
    showText.value = false
    textInput.value = ''
    await applyDraft(res.data, 'TEXT_AI')
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    recognizing.value = false
  }
}

function toPayloadItem(l: Line) {
  const grams = Math.round(gramsOf(l) * 10) / 10
  if (l.food) {
    return {
      foodCode: l.food.code,
      amount: Number(l.amount),
      unit: l.unit,
      grams,
      oilLevel: l.food.kind === 'DISH' ? l.oilLevel : null,
      confidence: l.confidence ?? null,
      gramsConfirmed: l.gramsConfirmed,
    }
  }
  return {
    foodName: l.foodName,
    amount: grams,
    unit: 'g',
    grams,
    energyKcal: Number(l.estKcal) || 0,
    confidence: l.confidence ?? null,
    gramsConfirmed: l.gramsConfirmed,
  }
}

async function save() {
  if (!lines.value.length) {
    if (isEdit.value) await remove()
    else showToast('请至少添加一种食物')
    return
  }
  const bad = lines.value.find((l) => gramsOf(l) <= 0 || (!l.food && !(Number(l.estKcal) > 0)))
  if (bad) {
    showToast(bad.food ? `请填写「${bad.foodName}」的份量` : `请填写「${bad.foodName}」的克数和估算热量`)
    return
  }
  if (duplicates.value) {
    try {
      await showConfirmDialog({
        title: `还有 ${duplicates.value} 项可能重复`,
        message: '多张照片可能拍到了同一份食物，确定都保留吗？',
        confirmButtonText: '都保留',
        cancelButtonText: '回去看看',
      })
    } catch {
      return
    }
  }
  saving.value = true
  try {
    await saveSlot(lines.value.map(toPayloadItem))
    showToast({ type: 'success', message: '已保存' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function saveSlot(items: ReturnType<typeof toPayloadItem>[]) {
  return api('/api/c/v1/me/diet/meal-slot', {
    method: 'PUT',
    body: JSON.stringify({
      mealIds: mealIds.value,
      mealDate: date.value,
      mealType: mealType.value,
      source: source.value,
      note: note.value.trim() || null,
      items,
    }),
  })
}

async function remove() {
  try {
    await showConfirmDialog({
      title: `删除${MEAL_LABELS[mealType.value]}记录？`,
      message: lines.value.length ? '本餐已记录的食物将全部删除。' : '食物已全部移除，保存即删除这一餐的记录。',
      confirmButtonText: '删除',
    })
  } catch {
    return
  }
  try {
    await saveSlot([])
    showToast({ type: 'success', message: '已删除' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

async function applySamplePrefill() {
  if (isEdit.value || lines.value.length) {
    try {
      await showConfirmDialog({
        title: '用方案示例填充？',
        message: lines.value.length ? '将追加到当前列表，可再改份量后保存。' : '按管理方案示例日预填本餐。',
      })
    } catch {
      return
    }
  }
  try {
    const res = await api<{ data: { activeVersion?: { diet?: { sampleDay?: Record<string, unknown> } } } }>(
      '/api/c/v1/me/care-plan',
    )
    const raw = res.data?.activeVersion?.diet?.sampleDay
    if (!raw) {
      showToast('当前方案没有示例日')
      return
    }
    const keyMap: Record<MealType, string> = {
      BREAKFAST: 'breakfast',
      LUNCH: 'lunch',
      DINNER: 'dinner',
      SNACK: 'snacks',
    }
    const key = keyMap[mealType.value]
    const meal = parseSampleMeal(raw[key] ?? (key === 'snacks' ? raw.snack : undefined))
    if (!meal.text) {
      showToast('该餐次暂无示例')
      return
    }
    const parts = meal.text
      .split(/[+＋、，,；;]/)
      .map((s) => s.trim())
      .filter(Boolean)
    for (const name of parts) {
      lines.value.push({
        key: ++seq,
        foodName: name,
        amount: 100,
        unit: 'g',
        oilLevel: 'NORMAL',
        estKcal: '',
        gramsConfirmed: false,
      })
    }
    source.value = 'MANUAL'
    showToast({ type: 'success', message: `已预填 ${parts.length} 项，请确认后保存` })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '预填失败')
  }
}

async function load() {
  api<{ data: FrequentFood[] }>('/api/c/v1/me/foods/frequent?limit=12')
    .then((r) => (frequent.value = r.data || []))
    .catch(() => {})
  try {
    if (mealId) {
      const res = await api<{ data: Meal }>(`/api/c/v1/me/diet/meals/${mealId}`)
      date.value = res.data.mealDate
      mealType.value = res.data.mealType
    }
    await mergeSlotMeals(mealType.value, mealId)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
    void loadDayTarget()
  }
  if (!isEdit.value && route.query.prefill === '1') void applySamplePrefill()
}

/** 把这一天该餐次已有的记录并进来一起编辑，分多次拍照存下的重复项会标出来 */
async function mergeSlotMeals(type: MealType, firstId = '') {
  const res = await api<{ data: DietDay }>(`/api/c/v1/me/diet/days/${date.value}`)
  const meals = (res.data?.meals ?? [])
    .filter((m) => m.mealType === type && !m.recordedByStaff && !mealIds.value.includes(m.id))
    .sort((a, b) => Number(b.id === firstId) - Number(a.id === firstId))
  for (const m of meals) {
    const next = await toLines(m.items, true)
    markDuplicates(next)
    lines.value.push(...next)
    mealIds.value.push(m.id)
    const n = m.note?.trim()
    if (n && !note.value.includes(n)) note.value = note.value ? `${note.value}；${n}` : n
    if (source.value === 'MANUAL' && (m.source === 'PHOTO_AI' || m.source === 'TEXT_AI')) source.value = m.source
  }
}

async function switchMealType(t: MealType) {
  if (t === mealType.value) return
  mealType.value = t
  try {
    await mergeSlotMeals(t)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  }
}

watch([date, mealType], () => void loadDayTarget())
onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar :title="title" left-arrow fixed placeholder :border="false" @click-left="router.back()">
      <template v-if="isEdit" #right>
        <span class="nav-del" @click="remove">删除</span>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <button v-if="!isEdit" type="button" class="prefill-btn" @click="applySamplePrefill">
        使用方案示例预填本餐
      </button>
      <div class="meal-types">
        <button
          v-for="t in MEAL_TYPES"
          :key="t"
          type="button"
          class="chip"
          :class="{ active: mealType === t }"
          @click="switchMealType(t)"
        >
          {{ MEAL_LABELS[t] }}
        </button>
        <span class="date">{{ date }}</span>
      </div>

      <div class="search">
        <van-search v-model="keyword" placeholder="搜索食物，如 米饭、番茄炒蛋" shape="round" />
        <div class="ai-btns">
          <button type="button" class="ai-btn" :disabled="recognizing" @click="pickPhoto()">
            <van-icon name="photograph" /> 拍照识别
          </button>
          <button type="button" class="ai-btn" :disabled="recognizing" @click="showText = true">
            <van-icon name="edit" /> 文字识别
          </button>
        </div>
        <input ref="fileInput" type="file" accept="image/*" multiple hidden @change="onPhoto" />
      </div>

      <div v-if="recognizing" class="recognizing">
        <van-loading size="18px">正在识别…</van-loading>
      </div>

      <section v-if="keyword.trim()" class="card results">
        <van-loading v-if="searching && !results.length" size="18px" class="inline-loading" />
        <div v-for="f in results" :key="f.code" class="result" @click="addFood(f)">
          <div class="r-main">
            <span class="r-name">{{ f.name }}</span>
            <span v-if="f.kind === 'DISH'" class="tag">菜肴</span>
            <span v-if="f.giLevel === 'HIGH'" class="tag warn">高GI</span>
          </div>
          <span class="muted">{{ Math.round(f.energyKcal) }} 千卡/100g</span>
        </div>
        <div v-if="!searching" class="result custom" @click="addCustom">
          <span>找不到？添加「{{ keyword.trim() }}」并估算热量</span>
          <van-icon name="arrow" />
        </div>
      </section>

      <section v-else-if="frequent.length && !lines.length" class="card">
        <p class="sec-title">常吃</p>
        <div class="freq">
          <button v-for="f in frequent" :key="f.food.code" type="button" class="chip" @click="addFood(f.food, f.amount, f.unit)">
            {{ f.food.name }}
          </button>
        </div>
      </section>

      <section v-if="warnings.length || canRefinePhoto" class="card warns">
        <p v-for="w in warnings" :key="w">{{ w }}</p>
        <button v-if="canRefinePhoto" type="button" class="refine" @click="showPhotoSheet = true">
          <van-icon name="edit" /> 识别不准？补充说明重新识别
        </button>
      </section>

      <section v-if="lines.length" class="card meal-summary">
        <div class="meal-summary-head">
          <strong>本餐（{{ MEAL_LABELS[mealType] }}）</strong>
          <small v-if="mealTargetKcal">{{ MEAL_LABELS[mealType] }}建议约 {{ mealTargetKcal }} kcal</small>
        </div>
        <div class="ring-row">
          <div class="ring-wrap">
            <svg viewBox="0 0 132 132" class="ring">
              <circle cx="66" cy="66" :r="MEAL_RING_R" class="ring-bg" />
              <circle
                cx="66"
                cy="66"
                :r="MEAL_RING_R"
                class="ring-fg"
                :class="{ over: mealOver }"
                :stroke-dasharray="MEAL_RING_C"
                :stroke-dashoffset="MEAL_RING_C * (1 - mealRingPct)"
              />
            </svg>
            <div class="ring-text">
              <strong :class="{ over: mealOver }">{{ mealKcal }}</strong>
              <span class="ring-sub">
                <template v-if="mealPctLabel != null">kcal · {{ mealPctLabel }}%</template>
                <template v-else>kcal</template>
              </span>
            </div>
          </div>
          <div class="macro-col">
            <div v-for="m in mealMacros" :key="m.key" class="macro-row">
              <span>{{ m.label }}</span>
              <div class="macro-bar">
                <i :style="{ width: m.pct + '%', background: m.color }" />
              </div>
              <span class="macro-v">{{ Math.round(m.grams) }} g</span>
            </div>
            <p class="macro-tip">条长为供能占比</p>
          </div>
        </div>
      </section>

      <section v-for="(l, i) in lines" :key="l.key" class="card line" :class="{ pending: !l.gramsConfirmed }">
        <div class="line-head">
          <strong>{{ l.foodName }}</strong>
          <span v-if="!l.food" class="tag est">估算</span>
          <span class="line-kcal">{{ Math.round(nutrientsOf(l).energyKcal || 0) }} 千卡</span>
          <van-icon name="cross" class="del" @click="removeLine(i)" />
        </div>
        <p v-if="l.basis" class="muted basis">估算：{{ l.basis }}</p>
        <div v-if="l.dupOf" class="dup-hint">
          <span>和已有的「{{ l.dupOf }}」可能是同一份</span>
          <button type="button" class="chip sm" @click="dropDuplicate(l)">同一份，去掉</button>
          <button type="button" class="chip sm" @click="l.dupOf = undefined">是另一份</button>
        </div>
        <p v-if="!l.gramsConfirmed" class="confirm-hint">识别的份量可能不准，选一下实际吃了多少</p>
        <div v-if="l.baseGrams" class="portion-row">
          <button
            v-for="p in PORTIONS"
            :key="p.factor"
            type="button"
            class="chip sm"
            :class="{ active: l.portion === p.factor }"
            @click="setPortion(l, p.factor)"
          >
            {{ p.label }}
          </button>
        </div>

        <div class="amount-row">
          <van-stepper
            v-model="l.amount"
            :min="l.unit === 'g' ? 1 : 0.5"
            :step="l.unit === 'g' ? 10 : 0.5"
            :decimal-length="l.unit === 'g' ? 0 : 1"
            input-width="56px"
            @change="onAmountChange(l)"
          />
          <div class="units">
            <button
              v-for="u in unitOptions(l)"
              :key="u"
              type="button"
              class="chip sm"
              :class="{ active: l.unit === u }"
              @click="changeUnit(l, u)"
            >
              {{ u === 'g' ? '克' : u }}
            </button>
          </div>
        </div>
        <p v-if="l.unit !== 'g' && l.food" class="muted grams">约 {{ fmtAmount(Math.round(gramsOf(l))) }} 克</p>

        <div v-if="l.food?.kind === 'DISH'" class="oil-row">
          <span class="muted">油量</span>
          <button
            v-for="(label, k) in OIL_LABELS"
            :key="k"
            type="button"
            class="chip sm"
            :class="{ active: l.oilLevel === k }"
            @click="l.oilLevel = k"
          >
            {{ label }}
          </button>
        </div>

        <van-field
          v-if="!l.food"
          v-model="l.estKcal"
          type="digit"
          label="估算热量"
          placeholder="整份千卡"
          input-align="right"
          class="est-field"
          @update:model-value="(v: string) => onEstKcalInput(l, v)"
        >
          <template #extra>千卡</template>
        </van-field>

        <p v-if="l.food" class="muted macro">
          碳水 {{ Math.round(nutrientsOf(l).carbG || 0) }}g · 蛋白 {{ Math.round(nutrientsOf(l).proteinG || 0) }}g ·
          脂肪 {{ Math.round(nutrientsOf(l).fatG || 0) }}g
        </p>
      </section>

      <van-empty v-if="!lines.length && !keyword.trim()" image-size="64" description="搜索、拍照或文字描述添加食物" />

      <section v-if="lines.length" class="card">
        <van-field v-model="note" rows="1" autosize type="textarea" maxlength="200" placeholder="备注（选填）" />
      </section>

      <div v-if="lines.length || isEdit" class="footer">
        <div class="total">
          <span>合计</span>
          <strong>{{ Math.round(total.energyKcal) }}</strong>
          <span>千卡</span>
          <span v-if="unconfirmed" class="unconfirmed">{{ unconfirmed }} 项待确认</span>
        </div>
        <van-button round :type="lines.length ? 'primary' : 'danger'" :loading="saving" @click="save">
          {{ lines.length ? '保存' : '删除本餐' }}
        </van-button>
      </div>
    </template>

    <van-popup v-model:show="showText" position="bottom" round teleport="body">
      <div class="text-sheet">
        <strong>描述这一餐</strong>
        <van-field
          v-model="textInput"
          rows="3"
          autosize
          type="textarea"
          maxlength="300"
          show-word-limit
          placeholder="如：一碗米饭、番茄炒蛋半盘、一杯豆浆"
        />
        <van-button round block type="primary" :loading="recognizing" @click="recognizeText">识别</van-button>
      </div>
    </van-popup>

    <van-popup v-model:show="showPhotoSheet" position="bottom" round teleport="body">
      <div class="text-sheet">
        <div class="photo-strip">
          <div v-for="(u, i) in photoPreviews" :key="u" class="photo-cell">
            <img :src="u" alt="" class="photo-thumb" />
            <van-icon name="cross" class="photo-del" @click="removePhoto(i)" />
          </div>
          <button v-if="photoFiles.length < MAX_PHOTOS" type="button" class="photo-add" @click="pickPhoto(true)">
            <van-icon name="plus" />
          </button>
        </div>
        <p class="muted photo-tip">同一餐可拍多个角度（最多 {{ MAX_PHOTOS }} 张），会合并识别、不重复计算</p>
        <div>
          <strong>补充说明（选填）</strong>
          <p class="muted photo-tip">说说吃了多少、看不清的是什么，识别更准</p>
        </div>
        <van-field
          v-model="photoHint"
          rows="2"
          autosize
          type="textarea"
          maxlength="200"
          show-word-limit
          placeholder="如：米饭吃了半碗，鸡块吃了 3 块，左上角是绿豆汤没喝"
        />
        <div class="freq">
          <button v-for="c in HINT_CHIPS" :key="c" type="button" class="chip sm" @click="appendHint(c)">{{ c }}</button>
        </div>
        <van-button round block type="primary" :loading="recognizing" @click="recognizePhoto">
          {{ photoHint.trim() ? '按说明识别' : '直接识别' }}
        </van-button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.prefill-btn {
  display: block;
  width: 100%;
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px dashed #86efac;
  border-radius: 12px;
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
  font-size: 14px;
  font-weight: 600;
}
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 96px;
}
:deep(.van-nav-bar) {
  background: rgba(255, 250, 242, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.nav-del {
  color: var(--hx-danger);
  font-size: 14px;
}
.loading {
  padding: 48px 0;
}
.muted {
  color: var(--hx-muted);
  font-size: 12px;
}
.card {
  margin: 10px 16px 0;
  padding: 12px 14px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.meal-types {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 16px 0;
}
.meal-types .date {
  margin-left: auto;
  font-size: 12px;
  color: var(--hx-muted);
}
.chip {
  border: 1px solid var(--hx-n-200);
  background: var(--hx-n-50);
  color: var(--hx-text);
  font-size: 13px;
  padding: 6px 12px;
  border-radius: 999px;
}
.chip.sm {
  padding: 4px 10px;
  font-size: 12px;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.search {
  margin: 6px 4px 0;
}
.search :deep(.van-search) {
  background: transparent;
}
.ai-btns {
  display: flex;
  gap: 10px;
  margin: 0 12px;
}
.ai-btn {
  flex: 1;
  border: 0;
  border-radius: 12px;
  padding: 10px;
  background: #fff;
  color: var(--hx-teal);
  font-size: 14px;
  font-weight: 600;
  box-shadow: var(--hx-elev-2);
}
.ai-btn:disabled {
  opacity: 0.6;
}
.recognizing {
  display: flex;
  justify-content: center;
  padding: 14px 0 4px;
}
.inline-loading {
  display: flex;
  justify-content: center;
  padding: 8px 0;
}
.results {
  padding: 4px 14px;
}
.result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--hx-n-100);
}
.result:last-child {
  border-bottom: 0;
}
.r-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.r-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result.custom {
  color: var(--hx-teal);
  font-size: 13px;
}
.tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 10px;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
}
.tag.warn,
.tag.est {
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft);
}
.sec-title {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hx-muted);
}
.freq {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.warns p {
  margin: 4px 0;
  font-size: 12px;
  color: var(--hx-warn-text);
}
.refine {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--hx-teal);
}
.portion-row {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}
.photo-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.photo-cell {
  position: relative;
}
.photo-thumb {
  display: block;
  width: 56px;
  height: 56px;
  border-radius: 10px;
  object-fit: cover;
}
.photo-del {
  position: absolute;
  top: -6px;
  right: -6px;
  padding: 2px;
  border-radius: 50%;
  font-size: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}
.photo-add {
  width: 56px;
  height: 56px;
  border: 1px dashed var(--hx-n-300);
  border-radius: 10px;
  background: var(--hx-n-50);
  color: var(--hx-teal);
  font-size: 20px;
}
.photo-tip {
  margin: 4px 0 0;
}
.basis {
  margin: 4px 0 0;
}
.dup-hint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--hx-warn-text);
}
.line.pending {
  box-shadow: 0 0 0 1.5px var(--hx-warn) inset, var(--hx-elev-2);
}
.line-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.line-head strong {
  font-size: 15px;
}
.line-kcal {
  margin-left: auto;
  font-weight: 700;
  color: var(--hx-teal);
}
.del {
  margin-left: 6px;
  color: var(--hx-muted);
}
.confirm-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-warn-text);
}
.amount-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.units {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.grams {
  margin: 6px 0 0;
}
.oil-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.oil-row .muted {
  margin-right: 4px;
}
.est-field {
  margin-top: 6px;
  padding: 6px 0;
}
.macro {
  margin: 8px 0 0;
}
.meal-summary {
  padding: 16px 18px;
}
.meal-summary-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}
.meal-summary-head strong {
  font-size: 17px;
  font-weight: 900;
  color: var(--hx-text);
}
.meal-summary-head small {
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
  text-align: right;
}
.ring-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 16px;
  align-items: center;
}
.ring-wrap {
  position: relative;
  width: 120px;
  height: 120px;
}
.ring {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ring-bg {
  fill: none;
  stroke: var(--hx-n-100);
  stroke-width: 12;
}
.ring-fg {
  fill: none;
  stroke: var(--hx-teal);
  stroke-width: 12;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.35s ease;
}
.ring-fg.over {
  stroke: var(--hx-warn);
}
.ring-text {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.ring-text strong {
  font-size: 28px;
  font-weight: 900;
  letter-spacing: -0.02em;
  color: var(--hx-text);
  line-height: 1.1;
}
.ring-text strong.over {
  color: var(--hx-warn);
}
.ring-sub {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-muted);
}
.macro-col {
  display: grid;
  gap: 10px;
  min-width: 0;
}
.macro-row {
  display: grid;
  grid-template-columns: 48px 1fr 40px;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-text);
}
.macro-bar {
  height: 10px;
  border-radius: 5px;
  background: var(--hx-n-100);
  overflow: hidden;
}
.macro-bar i {
  display: block;
  height: 100%;
  border-radius: 5px;
  transition: width 0.3s ease;
}
.macro-v {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.macro-tip {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-n-600);
}
.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
  box-shadow: 0 -6px 16px rgba(14, 159, 131, 0.08);
}
.total {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 13px;
  color: var(--hx-muted);
}
.total strong {
  font-size: 22px;
  color: var(--hx-text);
}
.unconfirmed {
  margin-left: 8px;
  color: var(--hx-warn-text);
}
.footer .van-button {
  width: 120px;
}
.text-sheet {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 16px calc(16px + env(safe-area-inset-bottom));
}
.text-sheet :deep(.van-field) {
  border-radius: 12px;
  background: var(--hx-n-50);
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
