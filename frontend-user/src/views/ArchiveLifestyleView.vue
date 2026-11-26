<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  applyDietTastes,
  buildLifestylePatch,
  emptyDiet,
  emptyExercise,
  emptyLifestyle,
  emptyPsych,
  emptySleep,
  normalizeDiet,
  normalizeExercise,
  normalizeLifestyle,
  normalizePsych,
  normalizeSleep,
  type DietStructured,
  type ExerciseStructured,
  type LifestyleStructured,
  type PsychStructured,
  type SleepStructured,
} from '../shared/lifestyle-options'

type TabKey = 'diet' | 'rest' | 'mood'

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const version = ref(0)
const activeTab = ref<TabKey>('diet')

const diet = reactive<DietStructured>(emptyDiet())
const exercise = reactive<ExerciseStructured>(emptyExercise())
const sleep = reactive<SleepStructured>(emptySleep())
const lifestyle = reactive<LifestyleStructured>(emptyLifestyle())
const psych = reactive<PsychStructured>(emptyPsych())

const smokingOptions = [
  { text: '从不吸烟', value: 'NEVER' },
  { text: '已戒烟', value: 'FORMER' },
  { text: '偶尔吸烟', value: 'OCCASIONAL' },
  { text: '经常吸烟', value: 'CURRENT' },
]

const drinkingOptions = [
  { text: '从不饮酒', value: 'NEVER' },
  { text: '已戒酒', value: 'FORMER' },
  { text: '偶尔饮酒', value: 'OCCASIONAL' },
  { text: '经常饮酒', value: 'CURRENT' },
]

const tasteOptions = [
  { text: '清淡少盐', value: 'LIGHT' },
  { text: '偏咸', value: 'SALTY' },
  { text: '偏油腻', value: 'OILY' },
  { text: '喜甜食', value: 'SWEET' },
  { text: '喜辛辣', value: 'SPICY' },
  { text: '三餐不规律', value: 'IRREGULAR' },
]

const sleepHoursOptions = [
  { text: '小于6小时', value: 'LT6' },
  { text: '6~7小时', value: 'H6_7' },
  { text: '7~8小时', value: 'H7_8' },
  { text: '8小时以上', value: 'GT8' },
]

const lateNightOptions = [
  { text: '几乎不熬夜', value: 'RARELY' },
  { text: '每周1~2次', value: 'WEEKLY_1_2' },
  { text: '每周3次以上', value: 'WEEKLY_3_PLUS' },
]

const exerciseFreqOptions = [
  { text: '几乎不运动', value: 'NONE' },
  { text: '每周1~2次', value: 'WEEKLY_1' },
  { text: '每周3~4次', value: 'WEEKLY_3' },
  { text: '几乎每天运动', value: 'DAILY' },
]

const moodOptions = [
  { text: '😊 轻松愉悦', value: 'RELAXED' },
  { text: '😐 平稳一般', value: 'NEUTRAL' },
  { text: '😔 容易焦虑', value: 'LOW' },
]

const stressOptions = [
  { text: '几乎无压力', value: 'LOW' },
  { text: '适中可控', value: 'MEDIUM' },
  { text: '压力较大', value: 'HIGH' },
]

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'diet', label: '饮食与烟酒' },
  { key: 'rest', label: '作息与运动' },
  { key: 'mood', label: '情绪与压力' },
]

/** 板块必填：吸烟、饮酒、至少一项口味 */
const dietComplete = computed(
  () =>
    !!lifestyle.smoking.status &&
    !!lifestyle.drinking.status &&
    (diet.tastes?.length ?? 0) > 0,
)
/** 板块必填：睡眠时长、熬夜、运动频次 */
const restComplete = computed(
  () => !!sleep.hoursBand && !!sleep.lateNight && !!exercise.frequency,
)
/** 板块必填：情绪、压力（补充说明选填） */
const moodComplete = computed(() => !!psych.mood && !!psych.stressLevel)

const tabDone = computed<Record<TabKey, boolean>>(() => ({
  diet: dietComplete.value,
  rest: restComplete.value,
  mood: moodComplete.value,
}))

const isLastTab = computed(() => activeTab.value === 'mood')

function applyContent(content: Record<string, unknown>) {
  Object.assign(diet, normalizeDiet(content.diet))
  Object.assign(exercise, normalizeExercise(content.exercise))
  Object.assign(sleep, normalizeSleep(content.sleep))
  const ls = normalizeLifestyle(content.lifestyle)
  Object.assign(lifestyle.smoking, ls.smoking)
  Object.assign(lifestyle.drinking, ls.drinking)
  lifestyle.note = ls.note
  Object.assign(psych, normalizePsych(content.psych))
}

function toggleTaste(value: string) {
  const set = new Set(diet.tastes || [])
  if (set.has(value)) set.delete(value)
  else set.add(value)
  const next = applyDietTastes([...set])
  diet.tastes = next.tastes
  diet.type = next.type
  diet.habit = next.habit
}

onMounted(async () => {
  try {
    const res = await api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      '/api/c/v1/me/archive/lifestyle',
    )
    version.value = res.data.version || 0
    applyContent((res.data.contentJson as Record<string, unknown>) || {})
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function persist(opts?: { toast?: string; goBack?: boolean }) {
  saving.value = true
  try {
    const patch = buildLifestylePatch({ diet, exercise, sleep, lifestyle, psych })
    const res = await api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      '/api/c/v1/me/archive/lifestyle',
      {
        method: 'PATCH',
        body: JSON.stringify({ version: version.value, contentJson: patch }),
      },
    )
    version.value = res.data.version || version.value
    applyContent((res.data.contentJson as Record<string, unknown>) || {})
    showToast({ type: 'success', message: opts?.toast || '已保存更新' })
    if (opts?.goBack) router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function nextTab() {
  if (activeTab.value === 'diet') activeTab.value = 'rest'
  else if (activeTab.value === 'rest') activeTab.value = 'mood'
}

async function onPrimary() {
  if (isLastTab.value) {
    await persist({ toast: '已保存更新', goBack: true })
    return
  }
  nextTab()
}

async function saveCurrentOnly() {
  await persist({ toast: '已保存当前项' })
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
      <h1>生活方式</h1>
      <span class="topbar-spacer" aria-hidden="true" />
    </header>

    <nav v-if="!loading" class="seg" role="tablist" aria-label="生活方式分段">
      <button
        v-for="t in tabs"
        :key="t.key"
        type="button"
        class="seg-item"
        role="tab"
        :aria-selected="activeTab === t.key"
        :class="{ on: activeTab === t.key }"
        @click="activeTab = t.key"
      >
        <span class="seg-dot" :class="{ ok: tabDone[t.key] }" aria-hidden="true" />
        <span class="seg-label">{{ t.label }}</span>
      </button>
    </nav>

    <div class="scroll">
      <van-loading v-if="loading" vertical class="boot">加载中</van-loading>

      <template v-else>
        <!-- Tab 1：饮食与烟酒 -->
        <section v-show="activeTab === 'diet'" class="card" role="tabpanel">
          <div class="card-hd">
            <span class="hd-ico tone-green" aria-hidden="true">
              <van-icon name="hot-o" />
            </span>
            <h2>饮食与烟酒</h2>
          </div>

          <div class="field">
            <p class="field-label">吸烟情况</p>
            <div class="capsules cols-2" role="radiogroup" aria-label="吸烟情况">
              <button
                v-for="o in smokingOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: lifestyle.smoking.status === o.value }"
                role="radio"
                :aria-checked="lifestyle.smoking.status === o.value"
                @click="lifestyle.smoking.status = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">饮酒情况</p>
            <div class="capsules cols-2" role="radiogroup" aria-label="饮酒情况">
              <button
                v-for="o in drinkingOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: lifestyle.drinking.status === o.value }"
                role="radio"
                :aria-checked="lifestyle.drinking.status === o.value"
                @click="lifestyle.drinking.status = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">日常饮食口味（可多选）</p>
            <div class="capsules cols-3" role="group" aria-label="日常饮食口味">
              <button
                v-for="o in tasteOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: diet.tastes.includes(o.value) }"
                :aria-pressed="diet.tastes.includes(o.value)"
                @click="toggleTaste(o.value)"
              >
                {{ o.text }}
              </button>
            </div>
          </div>
        </section>

        <!-- Tab 2：作息与运动 -->
        <section v-show="activeTab === 'rest'" class="card" role="tabpanel">
          <div class="card-hd">
            <span class="hd-ico tone-blue" aria-hidden="true"><van-icon name="clock-o" /></span>
            <h2>作息与运动</h2>
          </div>

          <div class="field">
            <p class="field-label">日均睡眠时长</p>
            <div class="capsules cols-2" role="radiogroup" aria-label="日均睡眠时长">
              <button
                v-for="o in sleepHoursOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: sleep.hoursBand === o.value }"
                role="radio"
                :aria-checked="sleep.hoursBand === o.value"
                @click="sleep.hoursBand = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">熬夜频率（23点后睡）</p>
            <div class="capsules cols-3" role="radiogroup" aria-label="熬夜频率">
              <button
                v-for="o in lateNightOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: sleep.lateNight === o.value }"
                role="radio"
                :aria-checked="sleep.lateNight === o.value"
                @click="sleep.lateNight = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">每周运动频次</p>
            <div class="capsules cols-2" role="radiogroup" aria-label="每周运动频次">
              <button
                v-for="o in exerciseFreqOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: exercise.frequency === o.value }"
                role="radio"
                :aria-checked="exercise.frequency === o.value"
                @click="exercise.frequency = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>
        </section>

        <!-- Tab 3：情绪与压力 -->
        <section v-show="activeTab === 'mood'" class="card" role="tabpanel">
          <div class="card-hd">
            <span class="hd-ico tone-orange" aria-hidden="true"><van-icon name="smile-o" /></span>
            <h2>情绪与压力</h2>
          </div>

          <div class="field">
            <p class="field-label">近期整体情绪状态</p>
            <div class="capsules cols-3 mood-caps" role="radiogroup" aria-label="近期整体情绪状态">
              <button
                v-for="o in moodOptions"
                :key="o.value"
                type="button"
                class="cap mood-cap"
                :class="{ on: psych.mood === o.value }"
                role="radio"
                :aria-checked="psych.mood === o.value"
                @click="psych.mood = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">当前生活/工作压力感受</p>
            <div class="capsules cols-3" role="radiogroup" aria-label="压力感受">
              <button
                v-for="o in stressOptions"
                :key="o.value"
                type="button"
                class="cap"
                :class="{ on: psych.stressLevel === o.value }"
                role="radio"
                :aria-checked="psych.stressLevel === o.value"
                @click="psych.stressLevel = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>

          <div class="field">
            <p class="field-label">补充说明（选填）</p>
            <textarea
              v-model="lifestyle.note"
              class="note"
              rows="3"
              maxlength="200"
              placeholder="如有睡眠困扰或特殊生活习惯，可在此备注给健管师..."
            />
          </div>
        </section>
      </template>
    </div>

    <div v-if="!loading" class="dock">
      <div class="dock-inner">
        <button
          v-if="!isLastTab"
          type="button"
          class="text-btn"
          :disabled="saving"
          @click="saveCurrentOnly"
        >
          仅保存当前项
        </button>
        <button type="button" class="save-btn" :disabled="saving" @click="onPrimary">
          <template v-if="saving">保存中…</template>
          <template v-else-if="isLastTab">保存更新</template>
          <template v-else>下一步（继续填写）</template>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  height: var(--hx-app-height, 100dvh);
  min-height: var(--hx-app-height, 100dvh);
  max-height: var(--hx-app-height, 100dvh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: linear-gradient(180deg, #e6f2ff 0%, #e9f9f3 16%, #f6f8fa 34%, #f6f8fa 100%);
  box-sizing: border-box;
}
.topbar {
  flex: none;
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  align-items: center;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 12px 0;
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
.topbar-spacer { width: 36px; height: 36px; }

.seg {
  flex: none;
  margin: 8px 16px 12px;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  padding: 4px;
  border-radius: 12px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  box-sizing: border-box;
}
.seg-item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 34px;
  margin: 0;
  padding: 6px 4px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #4e5969;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  box-sizing: border-box;
}
.seg-item.on {
  background: #ffffff;
  color: #1677ff;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
}
.seg-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c9cdd4;
  flex: none;
}
.seg-dot.ok {
  background: #10b981;
}
.seg-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  box-sizing: border-box;
}
.boot { padding: 48px 0; }

.card {
  margin: 0 16px 12px;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(22, 119, 255, 0.04);
  box-sizing: border-box;
}
.card-hd {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.hd-ico {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 16px;
  flex: none;
}
.tone-green { background: #e8ffea; color: #00b42a; }
.tone-blue { background: #e8f3ff; color: #1677ff; }
.tone-orange { background: #fff3e8; color: #ff7d00; }
.card-hd h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 800;
  color: #1d2129;
}

.field + .field { margin-top: 14px; }
.field-label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 650;
  color: #4e5969;
}
.capsules {
  display: grid;
  gap: 8px;
}
.capsules.cols-2 {
  grid-template-columns: repeat(2, 1fr);
}
.capsules.cols-3 {
  grid-template-columns: repeat(3, 1fr);
}
.cap {
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  padding: 0 8px;
  line-height: 1.2;
  text-align: center;
  cursor: pointer;
  box-sizing: border-box;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cap.on {
  border-color: #1677ff;
  background: #e8f3ff;
  color: #1677ff;
}
.mood-caps .mood-cap {
  white-space: nowrap;
  font-size: 12px;
  height: 36px;
  min-height: 36px;
  padding: 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.note {
  width: 100%;
  box-sizing: border-box;
  border: 0;
  border-radius: 12px;
  background: #f2f3f5;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: #1d2129;
  font-family: inherit;
  resize: vertical;
  min-height: 72px;
  outline: none;
}
.note::placeholder { color: #c9cdd4; }

.dock {
  flex: none;
  position: relative;
  z-index: 20;
  width: 100%;
  max-width: 430px;
  margin: 0 auto;
  padding: 12px 16px calc(20px + env(safe-area-inset-bottom, 0px));
  background: #fff;
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.06);
  box-sizing: border-box;
}
.dock-inner {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
}
.text-btn {
  align-self: center;
  margin: 0;
  padding: 4px 8px;
  border: 0;
  background: transparent;
  color: #86909c;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.text-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #14b8a6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.save-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
.save-btn:not(:disabled):active { transform: scale(0.98); }
</style>
