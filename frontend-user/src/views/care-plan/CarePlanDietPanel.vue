<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatCarePlanFoodItem } from '../../shared/care-plan-labels'
import {
  groupDietNoteSections,
  shortCalorieHint,
  toDietPrincipleItems,
} from '../../shared/care-plan-diet-notes'
import {
  resolveFoodChipDetail,
  type FoodChipDetail,
  type FoodChipKind,
} from '../../shared/care-plan-food-tips'
import { buildSampleWeek, parseSampleDayMeals, type SampleMealRow } from './carePlanDisplay'

const props = defineProps<{
  diet: Record<string, unknown>
  principles: string[]
  recommended: Array<{ code?: string; label?: string } | string>
  limited: Array<{ code?: string; label?: string } | string>
  noteLines: string[]
  calorieHint: string
  energy: { energyKcal?: number; carbG?: number; proteinG?: number; fatG?: number } | null
  allergy: {
    avoid: Array<{ name: string; highRisk: boolean }>
    intolerances: string[]
    highRisk: boolean
  }
}>()

const emit = defineEmits<{
  goDiet: []
  prefill: []
}>()

type DietTab = 'principles' | 'meals' | 'foods'
const dietTab = ref<DietTab>('principles')
const weekDay = ref('')
const openMeal = ref('')
const notesOpen = ref(false)
const foodSheet = ref<FoodChipDetail | null>(null)

function openFoodChip(item: { code?: string; label?: string } | string, kind: FoodChipKind) {
  foodSheet.value = resolveFoodChipDetail(
    typeof item === 'string' ? item : { code: item.code, label: formatCarePlanFoodItem(item) },
    kind,
  )
}
function closeFoodSheet() {
  foodSheet.value = null
}

const sampleDay = computed(() => parseSampleDayMeals(props.diet.sampleDay))
const sampleWeek = computed(() => buildSampleWeek(props.diet.sampleWeek))

watch(
  sampleWeek,
  (rows) => {
    if (!weekDay.value && rows.length) weekDay.value = rows[0].day
  },
  { immediate: true },
)

const activeMeals = computed((): SampleMealRow[] => {
  if (sampleWeek.value.length && weekDay.value) {
    const hit = sampleWeek.value.find((d) => d.day === weekDay.value)
    if (hit?.meals.length) return hit.meals
  }
  return sampleDay.value
})

const principleItems = computed(() => toDietPrincipleItems(props.principles))
const noteSections = computed(() => groupDietNoteSections(props.noteLines))
const hasDietNotes = computed(() => noteSections.value.some((s) => s.lines.length))
const calorieLine = computed(() =>
  shortCalorieHint(props.calorieHint, props.energy?.energyKcal ?? null),
)
const energyKcal = computed(() => {
  if (props.energy?.energyKcal && props.energy.energyKcal > 0) return props.energy.energyKcal
  const kcal = Number((String(props.calorieHint || '').match(/(\d{3,4})\s*(?:千卡|大卡|kcal)/i) || [])[1] || 0)
  return kcal > 0 ? kcal : null
})
/** 设计稿目标环：建议目标展示用（约 3/4 弧） */
const goalRingStyle = computed(() => ({
  background: 'conic-gradient(from -90deg, #34d399 0deg, #10b981 270deg, #d1fae5 270deg)',
}))

const hasMeals = computed(() => sampleDay.value.length > 0 || sampleWeek.value.length > 0)
const hasFoods = computed(() => props.recommended.length > 0 || props.limited.length > 0)
const hasPrinciples = computed(
  () =>
    props.principles.length > 0 ||
    !!props.energy ||
    !!props.calorieHint ||
    hasDietNotes.value ||
    props.allergy.avoid.length > 0 ||
    props.allergy.intolerances.length > 0,
)

const hasAny = computed(() => hasPrinciples.value || hasMeals.value || hasFoods.value)

const tabs = computed(() => {
  const list: Array<{ key: DietTab; label: string }> = []
  if (hasPrinciples.value) list.push({ key: 'principles', label: '饮食原则' })
  if (hasMeals.value) list.push({ key: 'meals', label: '每日食谱' })
  if (hasFoods.value) list.push({ key: 'foods', label: '食物推荐' })
  return list
})

watch(
  tabs,
  (list) => {
    if (!list.some((t) => t.key === dietTab.value) && list[0]) dietTab.value = list[0].key
  },
  { immediate: true },
)

watch(weekDay, () => {
  openMeal.value = ''
})

/** 由克数估算供能占比（仅展示，不改原始克数） */
const macroPercents = computed(() => {
  const e = props.energy
  if (!e) return null
  const carbK = (e.carbG ?? 0) * 4
  const proK = (e.proteinG ?? 0) * 4
  const fatK = (e.fatG ?? 0) * 9
  const sum = carbK + proK + fatK
  if (sum <= 0) return null
  return {
    carb: Math.round((carbK / sum) * 100),
    protein: Math.round((proK / sum) * 100),
    fat: Math.round((fatK / sum) * 100),
  }
})

function mealIcon(key: string) {
  if (key === 'breakfast') return 'sun'
  if (key === 'lunch') return 'fork'
  if (key === 'dinner') return 'moon'
  return 'apple'
}

function mealSummary(row: SampleMealRow) {
  return (row.items || []).join('、') || row.value || ''
}

function toggleMeal(key: string) {
  openMeal.value = openMeal.value === key ? '' : key
}
</script>

<template>
  <div class="diet-panel">
    <van-empty v-if="!hasAny" description="暂无饮食方案" image-size="56" />
    <template v-else>
      <div v-if="tabs.length > 1" class="sub-seg">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="sub-seg-item"
          :class="{ active: dietTab === t.key }"
          @click="dietTab = t.key"
        >
          {{ t.label }}
        </button>
      </div>

      <!-- 热量卡：贴设计稿（薄荷绿顶区 + 大号千卡 + 目标环） -->
      <section v-if="energy || energyKcal" class="energy-card">
        <div class="energy-hero">
          <div class="energy-left">
            <div class="energy-label">
              <span class="flame" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M12 3c2 3 1 5-1 7 3 0 6 2 6 6a5 5 0 01-10 0c0-3 2-5 3-7-2 1-3 3-3 5a7 7 0 0014 0c0-5-4-8-9-11z" />
                </svg>
              </span>
              每日热量建议
            </div>
            <div class="kcal">
              <b>{{ energyKcal ?? energy?.energyKcal }}</b>
              <em>千卡/日</em>
            </div>
          </div>
          <button type="button" class="goal-ring" :style="goalRingStyle" aria-label="去记饮食" @click="emit('goDiet')">
            <span class="goal-inner">
              <strong>目标</strong>
              <span>达成</span>
            </span>
          </button>
        </div>

        <div v-if="macroPercents && energy" class="macro-cols">
          <div class="macro-col">
            <span class="mk">碳水</span>
            <strong>{{ macroPercents.carb }}%</strong>
            <em>(约{{ energy.carbG ?? '-' }}g)</em>
          </div>
          <div class="macro-col">
            <span class="mk">蛋白质</span>
            <strong>{{ macroPercents.protein }}%</strong>
            <em>(约{{ energy.proteinG ?? '-' }}g)</em>
          </div>
          <div class="macro-col">
            <span class="mk">脂肪</span>
            <strong>{{ macroPercents.fat }}%</strong>
            <em>(约{{ energy.fatG ?? '-' }}g)</em>
          </div>
        </div>
      </section>
      <section v-else-if="calorieLine && dietTab === 'principles'" class="card hint-only">
        <p class="hint-fallback">{{ calorieLine }}</p>
        <button type="button" class="go-diet-link" @click="emit('goDiet')">去记饮食</button>
      </section>

      <!-- 原则 -->
      <template v-if="dietTab === 'principles'">
        <div
          v-if="allergy.avoid.length || allergy.intolerances.length"
          class="allergy-alert"
          :class="{ danger: allergy.highRisk }"
        >
          <div class="allergy-alert-title"><van-icon name="warning-o" /> 过敏提醒</div>
          <p v-if="allergy.avoid.length">
            请避免食用：
            <span
              v-for="(a, i) in allergy.avoid"
              :key="'av-' + i"
              class="allergy-name"
              :class="{ risk: a.highRisk }"
            >
              {{ a.name }}<template v-if="a.highRisk">（高危）</template>
            </span>
            ，外出就餐、购买加工食品时注意查看配料或询问成分。
          </p>
          <p v-if="allergy.intolerances.length">
            不耐受：{{ allergy.intolerances.join('、') }}，可少量尝试，注意食用量。
          </p>
          <p v-if="allergy.highRisk" class="allergy-emergency">
            如误食后出现呼吸困难、喉头发紧、全身风团或头晕，请立即就医或拨打 120。
          </p>
        </div>

        <section v-if="principleItems.length" class="card principles-card">
          <div class="label">饮食执行原则</div>
          <ul class="principle-list">
            <li v-for="item in principleItems" :key="item.key">
              <span class="check" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M5 12.5l4.2 4.2L19 7" />
                </svg>
              </span>
              <p>{{ item.label }}</p>
            </li>
          </ul>
        </section>

        <section v-if="hasDietNotes" class="card notes-card">
          <button type="button" class="notes-toggle" :aria-expanded="notesOpen" @click="notesOpen = !notesOpen">
            <span class="notes-title">
              <span class="notes-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
                  <path d="M12 8v5M12 16.2h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                </svg>
              </span>
              饮食注意事项{{ notesOpen ? '' : '（点击展开）' }}
            </span>
            <i :class="{ open: notesOpen }" aria-hidden="true">⌄</i>
          </button>
          <div v-if="notesOpen" class="notes-body">
            <div v-for="sec in noteSections" :key="sec.title" class="note-sec">
              <h4>{{ sec.title }}</h4>
              <ul>
                <li v-for="(line, i) in sec.lines" :key="i">{{ line }}</li>
              </ul>
            </div>
          </div>
        </section>
      </template>

      <!-- 食谱 -->
      <template v-else-if="dietTab === 'meals'">
        <section class="card meals-card">
          <div class="meals-head">
            <strong>{{ sampleWeek.length ? '一周食谱示例' : '今日参考食谱' }}</strong>
            <button type="button" class="prefill" @click="emit('prefill')">按示例预填</button>
          </div>

          <div v-if="sampleWeek.length" class="week-days-wrap">
            <div class="week-days">
              <button
                v-for="d in sampleWeek"
                :key="d.day"
                type="button"
                :class="{ on: weekDay === d.day }"
                @click="weekDay = d.day"
              >
                {{ d.label }}
              </button>
            </div>
          </div>

          <van-empty v-if="!activeMeals.length" description="当天暂无食谱" image-size="48" />
          <div v-else class="meal-list">
            <div v-for="row in activeMeals" :key="row.key" class="meal-row-wrap">
              <button type="button" class="meal-row" @click="toggleMeal(row.key)">
                <span class="meal-ico" :class="mealIcon(row.key)" aria-hidden="true">
                  <svg v-if="mealIcon(row.key) === 'sun'" viewBox="0 0 24 24">
                    <circle cx="12" cy="12" r="4" />
                    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
                  </svg>
                  <svg v-else-if="mealIcon(row.key) === 'fork'" viewBox="0 0 24 24">
                    <path d="M6 3v8M9 3v8M6 7h3M7.5 11v10M16 3c-1.6 0-2.5 1.5-2.5 3.5S15 10 16 10v11M16 3c1.4 0 2.5 1.5 2.5 3.5S17.5 10 16 10" />
                  </svg>
                  <svg v-else-if="mealIcon(row.key) === 'moon'" viewBox="0 0 24 24">
                    <path d="M20 14.5A7.5 7.5 0 019.5 4 8 8 0 1020 14.5z" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24">
                    <path d="M12 21s-7-4.5-7-10a4 4 0 017-2.5A4 4 0 0119 11c0 5.5-7 10-7 10z" />
                  </svg>
                </span>
                <span class="meal-copy">
                  <strong>{{ row.label }}</strong>
                  <span class="meal-sub">{{ mealSummary(row) }}</span>
                </span>
                <span class="meal-chev" :class="{ open: openMeal === row.key }" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none">
                    <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
              </button>
              <div v-if="openMeal === row.key" class="meal-detail">
                <ul>
                  <li v-for="(item, i) in row.items" :key="i">{{ item }}</li>
                </ul>
                <p v-if="row.swapHint" class="swap-hint">替换建议：{{ row.swapHint }}</p>
              </div>
            </div>
          </div>
        </section>

        <section v-if="hasDietNotes" class="card notes-card">
          <button type="button" class="notes-toggle" :aria-expanded="notesOpen" @click="notesOpen = !notesOpen">
            <span class="notes-title">
              <span class="notes-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
                  <path d="M12 8v5M12 16.2h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                </svg>
              </span>
              饮食注意事项{{ notesOpen ? '' : '（点击展开）' }}
            </span>
            <i :class="{ open: notesOpen }" aria-hidden="true">⌄</i>
          </button>
          <div v-if="notesOpen" class="notes-body">
            <div v-for="sec in noteSections" :key="'m-' + sec.title" class="note-sec">
              <h4>{{ sec.title }}</h4>
              <ul>
                <li v-for="(line, i) in sec.lines" :key="i">{{ line }}</li>
              </ul>
            </div>
          </div>
        </section>
      </template>

      <!-- 推荐 -->
      <section v-else class="card">
        <div v-if="recommended.length" class="block">
          <div class="label ok">推荐食物</div>
          <div class="chips">
            <button
              v-for="(item, i) in recommended"
              :key="'r-' + i"
              type="button"
              class="chip ok"
              @click="openFoodChip(item, 'recommend')"
            >
              {{ formatCarePlanFoodItem(item) }}
            </button>
          </div>
        </div>
        <div v-if="limited.length" class="block">
          <div class="label limit">限制食物</div>
          <div class="chips">
            <button
              v-for="(item, i) in limited"
              :key="'l-' + i"
              type="button"
              class="chip limit"
              @click="openFoodChip(item, 'limit')"
            >
              {{ formatCarePlanFoodItem(item) }}
            </button>
          </div>
        </div>
      </section>
    </template>

    <!-- 食物详情半屏抽屉 -->
    <van-popup
      :show="!!foodSheet"
      position="bottom"
      round
      teleport="body"
      :style="{ height: '58%' }"
      class="food-sheet-popup"
      @update:show="(v: boolean) => !v && closeFoodSheet()"
    >
      <div v-if="foodSheet" class="food-sheet">
        <div class="fs-handle" aria-hidden="true" />
        <header class="fs-head">
          <div>
            <span class="fs-badge" :class="foodSheet.kind">
              {{ foodSheet.kind === 'recommend' ? '推荐' : '限制' }}
            </span>
            <h3>{{ foodSheet.name }}</h3>
          </div>
          <button type="button" class="fs-close" aria-label="关闭" @click="closeFoodSheet">✕</button>
        </header>

        <div class="fs-body">
          <section class="fs-block">
            <h4>为什么{{ foodSheet.kind === 'recommend' ? '推荐' : '限制' }}</h4>
            <p>{{ foodSheet.reason }}</p>
          </section>
          <section class="fs-block">
            <h4>每日建议摄入</h4>
            <p>{{ foodSheet.dailyHint }}</p>
          </section>
          <section class="fs-block">
            <h4>{{ foodSheet.kind === 'recommend' ? '推荐做法' : '替代建议' }}</h4>
            <div class="fs-recipes">
              <span v-for="(r, i) in foodSheet.recipes" :key="i" class="fs-recipe">{{ r }}</span>
            </div>
          </section>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.sub-seg {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  margin: 12px 16px 0;
  padding: 4px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
}
.sub-seg-item {
  border: 0;
  background: transparent;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 600;
  padding: 10px 0;
  border-radius: 10px;
  font-family: inherit;
}
.sub-seg-item.active {
  background: linear-gradient(145deg, #10b981, #059669);
  color: #fff;
  font-weight: 700;
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.28);
}
.card,
.energy-card {
  margin: 16px 16px 0;
  padding: 16px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.02);
}
.energy-card {
  padding: 12px;
}
.energy-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%);
}
.energy-left {
  min-width: 0;
  flex: 1;
}
.energy-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}
.flame {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #10b981;
  color: #fff;
  display: grid;
  place-items: center;
}
.flame svg {
  width: 12px;
  height: 12px;
  fill: currentColor;
  stroke: none;
}
.kcal {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: 8px;
}
.kcal b {
  font-size: 36px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.04em;
  line-height: 1;
}
.kcal em {
  font-style: normal;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}
.goal-ring {
  flex: none;
  width: 68px;
  height: 68px;
  border-radius: 50%;
  border: 0;
  padding: 0;
  display: grid;
  place-items: center;
  cursor: pointer;
  font-family: inherit;
}
.goal-inner {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  background: #fff;
  display: grid;
  place-content: center;
  text-align: center;
  line-height: 1.15;
  box-shadow: 0 1px 4px rgba(16, 185, 129, 0.12);
}
.goal-inner strong {
  font-size: 11px;
  font-weight: 800;
  color: #059669;
}
.goal-inner span {
  font-size: 11px;
  font-weight: 700;
  color: #059669;
}
.macro-cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-top: 4px;
  padding: 14px 4px 6px;
}
.macro-col {
  text-align: center;
  padding: 0 6px;
  position: relative;
}
.macro-col + .macro-col::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 1px;
  background: #e2e8f0;
}
.macro-col .mk {
  display: block;
  font-size: 12px;
  color: #94a3b8;
  font-weight: 600;
}
.macro-col strong {
  display: block;
  margin-top: 4px;
  font-size: 18px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.02em;
}
.macro-col em {
  display: block;
  margin-top: 2px;
  font-style: normal;
  font-size: 11px;
  color: #94a3b8;
}
.hint-fallback {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  line-height: 1.45;
}
.go-diet-link {
  margin-top: 10px;
  border: 0;
  background: transparent;
  color: #059669;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  padding: 0;
  cursor: pointer;
}
.block + .block {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f1f5f9;
}
.label {
  font-size: 12px;
  font-weight: 700;
  color: #94a3b8;
  margin-bottom: 10px;
}
.label.ok {
  color: #047857;
}
.label.limit {
  color: #c2410c;
}
.principle-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.principle-list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 0;
  border-bottom: 1px solid #f8fafc;
}
.principle-list li:last-child {
  border-bottom: 0;
  padding-bottom: 2px;
}
.principle-list li:first-child {
  padding-top: 4px;
}
.principle-list .check {
  flex: none;
  width: 20px;
  height: 20px;
  margin-top: 0.15em;
  border-radius: 50%;
  background: #ecfdf5;
  color: #059669;
  display: grid;
  place-items: center;
}
.principle-list .check svg {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.principle-list p {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.6;
  color: #334155;
  text-align: left;
  word-break: break-word;
}
.notes-toggle {
  width: 100%;
  border: 0;
  background: transparent;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 700;
  color: #0f766e;
  cursor: pointer;
}
.notes-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  text-align: left;
}
.notes-ico {
  flex: none;
  width: 20px;
  height: 20px;
  color: #059669;
  display: grid;
  place-items: center;
}
.notes-ico svg {
  width: 18px;
  height: 18px;
  display: block;
}
.notes-toggle i {
  font-style: normal;
  color: #94a3b8;
  font-size: 16px;
  transition: transform 0.2s ease;
}
.notes-toggle i.open {
  transform: rotate(180deg);
}
.notes-body {
  margin-top: 12px;
  padding-top: 4px;
}
.note-sec + .note-sec {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}
.note-sec h4 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 800;
  color: #0f172a;
}
.note-sec ul {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}
.note-sec li + li {
  margin-top: 4px;
}
.allergy-alert {
  margin: 16px 16px 0;
  padding: 12px 14px;
  border-radius: 20px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  font-size: 13px;
  line-height: 1.6;
  color: #9a3412;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.02);
}
.allergy-alert.danger {
  background: #fff1f2;
  border-color: #fecdd3;
  color: #9f1239;
}
.allergy-alert p {
  margin: 4px 0 0;
}
.allergy-alert-title {
  font-weight: 800;
}
.allergy-name + .allergy-name::before {
  content: '、';
}
.allergy-name {
  font-weight: 700;
}
.allergy-name.risk {
  color: #e11d48;
}
.allergy-emergency {
  font-weight: 600;
}
.meals-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.meals-head strong {
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
}
.prefill {
  border: 0;
  background: transparent;
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.week-days-wrap {
  position: relative;
  margin: 14px 0 4px;
}
.week-days {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}
.week-days button {
  min-width: 0;
  border: 0;
  border-radius: 12px;
  padding: 8px 2px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  white-space: nowrap;
}
.week-days button.on {
  background: #10b981;
  color: #fff;
}
.meal-list {
  margin-top: 8px;
}
.meal-row {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border: 0;
  border-top: 1px solid #f1f5f9;
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
}
.meal-row .meal-ico,
.meal-row .meal-chev {
  margin-top: 2px;
}
.meal-row-wrap:first-child .meal-row {
  border-top: 0;
}
.meal-ico {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
}
.meal-ico svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.75;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.meal-ico.sun,
.meal-ico.fork,
.meal-ico.moon,
.meal-ico.apple {
  background: #ecfdf5;
  color: #059669;
}
.meal-copy {
  flex: 1;
  min-width: 0;
  text-align: left;
}
.meal-copy strong {
  display: block;
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
}
.meal-sub {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: unset;
  white-space: normal;
  word-break: break-word;
}
.meal-chev {
  flex: none;
  width: 20px;
  height: 20px;
  color: #cbd5e1;
  display: grid;
  place-items: center;
  transition: color 0.2s ease;
}
.meal-chev svg {
  width: 16px;
  height: 16px;
  display: block;
}
.meal-chev.open {
  color: #94a3b8;
}
.meal-detail {
  padding: 0 0 12px 52px;
}
.meal-detail ul {
  margin: 0;
  padding-left: 16px;
  font-size: 13px;
  line-height: 1.5;
  color: #334155;
}
.meal-detail li + li {
  margin-top: 4px;
}
.swap-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.45;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  border: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 10px;
  border-radius: 999px;
  line-height: 1.2;
  font-family: inherit;
  cursor: pointer;
  transition: transform 0.12s ease, opacity 0.12s ease;
  -webkit-tap-highlight-color: transparent;
}
.chip:active {
  transform: scale(0.95);
  opacity: 0.88;
}
.chip.ok {
  color: #047857;
  background: #d1fae5;
}
.chip.limit {
  color: #c2410c;
  background: #ffedd5;
}
.diet-panel {
  padding-bottom: calc(48px + env(safe-area-inset-bottom, 0px));
}
:deep(.van-empty) {
  padding: 28px 0;
}
:deep(.food-sheet-popup) {
  overflow: hidden;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.food-sheet {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 8px 16px calc(16px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}
.fs-handle {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: #e2e8f0;
  margin: 4px auto 12px;
}
.fs-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.fs-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  margin-bottom: 6px;
}
.fs-badge.recommend {
  color: #047857;
  background: #d1fae5;
}
.fs-badge.limit {
  color: #c2410c;
  background: #ffedd5;
}
.fs-head h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 800;
  color: #0f172a;
  line-height: 1.35;
}
.fs-close {
  flex: none;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: #f1f5f9;
  color: #64748b;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  font-family: inherit;
}
.fs-body {
  margin-top: 16px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
  -webkit-overflow-scrolling: touch;
}
.fs-block + .fs-block {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f1f5f9;
}
.fs-block h4 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 800;
  color: #94a3b8;
}
.fs-block p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: #334155;
}
.fs-recipes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.fs-recipe {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  background: #f8fafc;
  border: 1px solid #eef2f6;
  border-radius: 12px;
  padding: 8px 12px;
}
</style>
