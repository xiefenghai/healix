<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, apiUpload } from '../api/http'
import { debounce } from '../shared/debounce'
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
  type DietDraft,
  type FoodItem,
  type FrequentFood,
  type Meal,
  type MealItem,
  type MealType,
  type Nutrients,
  type OilLevel,
} from '../shared/diet'

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
}

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
const loading = ref(!!mealId)
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

let seq = 0

const title = computed(() => (mealId ? '编辑' : '添加') + MEAL_LABELS[mealType.value])

function gramsOf(l: Line): number {
  const amt = Number(l.amount)
  if (!Number.isFinite(amt) || amt <= 0) return 0
  const per = l.food ? unitGrams(l.food, l.unit) : 1
  return per ? amt * per : 0
}

function nutrientsOf(l: Line): Partial<Nutrients> {
  const g = gramsOf(l)
  if (l.food) return estimateNutrients(l.food, g, l.food.kind === 'DISH' ? l.oilLevel : null)
  return { energyKcal: Number(l.estKcal) || 0 }
}

const total = computed(() => sumNutrients(lines.value.map(nutrientsOf)))
const unconfirmed = computed(() => lines.value.filter((l) => !l.gramsConfirmed).length)

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
    }
  })
}

async function applyDraft(draft: DietDraft, src: 'PHOTO_AI' | 'TEXT_AI') {
  warnings.value = draft.warnings || []
  if (!draft.items.length) {
    showToast(warnings.value[0] || '没有识别到食物')
    return
  }
  lines.value.push(...(await toLines(draft.items, false)))
  if (!mealId && !route.query.mealType && draft.mealType) mealType.value = draft.mealType
  source.value = src
  showToast({ type: 'success', message: `识别到 ${draft.items.length} 种食物，请确认份量` })
}

function pickPhoto() {
  fileInput.value?.click()
}

async function onPhoto(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  recognizing.value = true
  try {
    const res = await apiUpload<{ data: DietDraft }>(
      `/api/c/v1/me/diet/recognize?mealType=${mealType.value}`,
      file,
    )
    await applyDraft(res.data, 'PHOTO_AI')
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    recognizing.value = false
  }
}

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
    showToast('请至少添加一种食物')
    return
  }
  const bad = lines.value.find((l) => gramsOf(l) <= 0 || (!l.food && !(Number(l.estKcal) > 0)))
  if (bad) {
    showToast(bad.food ? `请填写「${bad.foodName}」的份量` : `请填写「${bad.foodName}」的克数和估算热量`)
    return
  }
  saving.value = true
  try {
    const body = JSON.stringify({
      mealDate: date.value,
      mealType: mealType.value,
      source: source.value,
      note: note.value.trim() || null,
      items: lines.value.map(toPayloadItem),
    })
    if (mealId) {
      await api(`/api/c/v1/me/diet/meals/${mealId}`, { method: 'PUT', body })
    } else {
      await api('/api/c/v1/me/diet/meals', { method: 'POST', body })
    }
    showToast({ type: 'success', message: '已保存' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  try {
    await showConfirmDialog({ title: '删除这条记录？', confirmButtonText: '删除' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/diet/meals/${mealId}`, { method: 'DELETE' })
    showToast({ type: 'success', message: '已删除' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

async function load() {
  api<{ data: FrequentFood[] }>('/api/c/v1/me/foods/frequent?limit=12')
    .then((r) => (frequent.value = r.data || []))
    .catch(() => {})
  if (!mealId) return
  try {
    const res = await api<{ data: Meal }>(`/api/c/v1/me/diet/meals/${mealId}`)
    const m = res.data
    date.value = m.mealDate
    mealType.value = m.mealType
    note.value = m.note || ''
    if (m.source === 'PHOTO_AI' || m.source === 'TEXT_AI') source.value = m.source
    lines.value = await toLines(m.items, true)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar :title="title" left-arrow fixed placeholder :border="false" @click-left="router.back()">
      <template v-if="mealId" #right>
        <span class="nav-del" @click="remove">删除</span>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <div class="meal-types">
        <button
          v-for="t in MEAL_TYPES"
          :key="t"
          type="button"
          class="chip"
          :class="{ active: mealType === t }"
          @click="mealType = t"
        >
          {{ MEAL_LABELS[t] }}
        </button>
        <span class="date">{{ date }}</span>
      </div>

      <div class="search">
        <van-search v-model="keyword" placeholder="搜索食物，如 米饭、番茄炒蛋" shape="round" />
        <div class="ai-btns">
          <button type="button" class="ai-btn" :disabled="recognizing" @click="pickPhoto">
            <van-icon name="photograph" /> 拍照识别
          </button>
          <button type="button" class="ai-btn" :disabled="recognizing" @click="showText = true">
            <van-icon name="edit" /> 文字识别
          </button>
        </div>
        <input ref="fileInput" type="file" accept="image/*" capture="environment" hidden @change="onPhoto" />
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

      <section v-if="warnings.length" class="card warns">
        <p v-for="w in warnings" :key="w">{{ w }}</p>
      </section>

      <section v-for="(l, i) in lines" :key="l.key" class="card line" :class="{ pending: !l.gramsConfirmed }">
        <div class="line-head">
          <strong>{{ l.foodName }}</strong>
          <span v-if="!l.food" class="tag est">估算</span>
          <span class="line-kcal">{{ Math.round(nutrientsOf(l).energyKcal || 0) }} 千卡</span>
          <van-icon name="cross" class="del" @click="removeLine(i)" />
        </div>
        <p v-if="!l.gramsConfirmed" class="confirm-hint">识别的份量可能不准，请确认</p>

        <div class="amount-row">
          <van-stepper
            v-model="l.amount"
            :min="l.unit === 'g' ? 1 : 0.5"
            :step="l.unit === 'g' ? 10 : 0.5"
            :decimal-length="l.unit === 'g' ? 0 : 1"
            input-width="56px"
            @change="l.gramsConfirmed = true"
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

      <div v-if="lines.length" class="footer">
        <div class="total">
          <span>合计</span>
          <strong>{{ Math.round(total.energyKcal) }}</strong>
          <span>千卡</span>
          <span v-if="unconfirmed" class="unconfirmed">{{ unconfirmed }} 项待确认</span>
        </div>
        <van-button round type="primary" :loading="saving" @click="save">保存</van-button>
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
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 96px;
}
:deep(.van-nav-bar) {
  background: rgba(247, 249, 252, 0.88);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 650;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.nav-del {
  color: #e11d48;
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
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
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
  border: 1px solid #e4ecec;
  background: #f7fbfb;
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
  box-shadow: var(--hx-shadow);
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
  border-bottom: 1px solid #f0f4f6;
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
  color: #a16207;
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
  color: #a16207;
}
.line.pending {
  box-shadow: 0 0 0 1.5px #f5a623 inset, var(--hx-shadow);
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
  font-weight: 650;
  color: var(--hx-teal);
}
.del {
  margin-left: 6px;
  color: var(--hx-muted);
}
.confirm-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #a16207;
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
  box-shadow: 0 -6px 16px rgba(43, 158, 158, 0.08);
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
  color: #a16207;
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
  background: #f7fbfb;
}
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
</style>
