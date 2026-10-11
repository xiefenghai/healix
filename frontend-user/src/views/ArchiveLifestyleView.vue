<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { loadDictOptions, type DictItem } from '../shared/dict'
import {
  buildLifestylePatch,
  drinkingShowsDetail,
  emptyDiet,
  emptyExercise,
  emptyLifestyle,
  emptySleep,
  normalizeDiet,
  normalizeExercise,
  normalizeLifestyle,
  normalizeSleep,
  smokingShowsDetail,
  smokingShowsQuitYear,
  type DietStructured,
  type ExerciseStructured,
  type LifestyleStructured,
  type SleepStructured,
} from '../shared/lifestyle-options'

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const version = ref(0)
const tab = ref('smoking')

const diet = reactive<DietStructured>(emptyDiet())
const exercise = reactive<ExerciseStructured>(emptyExercise())
const sleep = reactive<SleepStructured>(emptySleep())
const lifestyle = reactive<LifestyleStructured>(emptyLifestyle())

const smokingOpts = ref<DictItem[]>([])
const drinkingOpts = ref<DictItem[]>([])
const drinkingFreqOpts = ref<DictItem[]>([])
const appetiteLevelOpts = ref<DictItem[]>([])
const dietHabitOpts = ref<DictItem[]>([])
const dietTypeOpts = ref<DictItem[]>([])
const exerciseFreqOpts = ref<DictItem[]>([])
const exerciseIntensityOpts = ref<DictItem[]>([])
const sleepQualityOpts = ref<DictItem[]>([])
const sleepDisorderOpts = ref<DictItem[]>([])

const showSmokingDetail = computed(() => smokingShowsDetail(lifestyle.smoking.status))
const showQuitYear = computed(() => smokingShowsQuitYear(lifestyle.smoking.status))
const showDrinkingDetail = computed(() => drinkingShowsDetail(lifestyle.drinking.status))

const tabs = [
  { name: 'smoking', title: '吸烟' },
  { name: 'drinking', title: '饮酒' },
  { name: 'diet', title: '饮食' },
  { name: 'exercise', title: '运动' },
  { name: 'sleep', title: '睡眠' },
] as const

function applyContent(content: Record<string, unknown>) {
  Object.assign(diet, normalizeDiet(content.diet))
  Object.assign(exercise, normalizeExercise(content.exercise))
  Object.assign(sleep, normalizeSleep(content.sleep))
  const ls = normalizeLifestyle(content.lifestyle)
  Object.assign(lifestyle.smoking, ls.smoking)
  Object.assign(lifestyle.drinking, ls.drinking)
  lifestyle.note = ls.note
}

onMounted(async () => {
  try {
    const [
      res,
      smoking,
      drinking,
      drinkingFrequency,
      appetiteLevel,
      dietHabit,
      dietType,
      exerciseFrequency,
      exerciseIntensity,
      sleepQuality,
      sleepDisorder,
    ] = await Promise.all([
      api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
        '/api/c/v1/me/archive/lifestyle',
      ),
      loadDictOptions('smoking').catch(() => []),
      loadDictOptions('drinking').catch(() => []),
      loadDictOptions('drinkingFrequency').catch(() => []),
      loadDictOptions('appetiteLevel').catch(() => []),
      loadDictOptions('dietHabit').catch(() => []),
      loadDictOptions('dietType').catch(() => []),
      loadDictOptions('exerciseFrequency').catch(() => []),
      loadDictOptions('exerciseIntensity').catch(() => []),
      loadDictOptions('sleepQuality').catch(() => []),
      loadDictOptions('sleepDisorder').catch(() => []),
    ])
    version.value = res.data.version || 0
    applyContent((res.data.contentJson as Record<string, unknown>) || {})
    smokingOpts.value = smoking
    drinkingOpts.value = drinking
    drinkingFreqOpts.value = drinkingFrequency
    appetiteLevelOpts.value = appetiteLevel
    dietHabitOpts.value = dietHabit
    dietTypeOpts.value = dietType
    exerciseFreqOpts.value = exerciseFrequency
    exerciseIntensityOpts.value = exerciseIntensity
    sleepQualityOpts.value = sleepQuality
    sleepDisorderOpts.value = sleepDisorder
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function save() {
  saving.value = true
  try {
    const patch = buildLifestylePatch({ diet, exercise, sleep, lifestyle })
    const res = await api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      '/api/c/v1/me/archive/lifestyle',
      {
        method: 'PATCH',
        body: JSON.stringify({ version: version.value, contentJson: patch }),
      },
    )
    version.value = res.data.version || version.value
    applyContent((res.data.contentJson as Record<string, unknown>) || {})
    showToast({ type: 'success', message: '已保存' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar
      title="生活方式"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="router.back()"
    />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <div class="seg">
        <button
          v-for="t in tabs"
          :key="t.name"
          type="button"
          class="seg-item"
          :class="{ active: tab === t.name }"
          @click="tab = t.name"
        >
          {{ t.title }}
        </button>
      </div>

      <div class="panel">
        <!-- 吸烟 -->
        <template v-if="tab === 'smoking'">
          <div class="block">
            <div class="label">吸烟状态</div>
            <div class="chips">
              <button
                v-for="o in smokingOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: lifestyle.smoking.status === o.dictCode }"
                @click="lifestyle.smoking.status = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div v-if="showSmokingDetail" class="block fields">
            <van-field
              :model-value="lifestyle.smoking.cigarettesPerDay ?? ''"
              type="digit"
              label="每日支数"
              placeholder="支/天"
              input-align="right"
              @update:model-value="lifestyle.smoking.cigarettesPerDay = $event"
            />
            <van-field
              :model-value="lifestyle.smoking.years ?? ''"
              type="digit"
              label="烟龄"
              placeholder="年"
              input-align="right"
              @update:model-value="lifestyle.smoking.years = $event"
            />
          </div>
          <div v-if="showQuitYear" class="block fields">
            <van-field
              v-model="lifestyle.smoking.quitYear"
              label="戒烟年份"
              placeholder="如 2020"
              input-align="right"
            />
            <van-field
              :model-value="lifestyle.smoking.years ?? ''"
              type="digit"
              label="既往烟龄"
              placeholder="年"
              input-align="right"
              @update:model-value="lifestyle.smoking.years = $event"
            />
          </div>
          <div class="block fields">
            <van-field
              v-model="lifestyle.smoking.note"
              label="备注"
              placeholder="补充说明（选填）"
              input-align="right"
            />
          </div>
        </template>

        <!-- 饮酒 -->
        <template v-else-if="tab === 'drinking'">
          <div class="block">
            <div class="label">饮酒状态</div>
            <div class="chips">
              <button
                v-for="o in drinkingOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: lifestyle.drinking.status === o.dictCode }"
                @click="lifestyle.drinking.status = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <template v-if="showDrinkingDetail">
            <div class="block">
              <div class="label">频率</div>
              <div class="chips">
                <button
                  v-for="o in drinkingFreqOpts"
                  :key="o.dictCode"
                  type="button"
                  class="chip"
                  :class="{ active: lifestyle.drinking.frequency === o.dictCode }"
                  @click="lifestyle.drinking.frequency = o.dictCode"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="block fields">
              <van-field
                v-model="lifestyle.drinking.type"
                label="酒类"
                placeholder="如啤酒/白酒"
                input-align="right"
              />
              <van-field
                v-model="lifestyle.drinking.amountPerDay"
                label="日均量"
                placeholder="如 2 两"
                input-align="right"
              />
            </div>
          </template>
          <div class="block fields">
            <van-field
              v-model="lifestyle.drinking.note"
              label="备注"
              placeholder="补充说明（选填）"
              input-align="right"
            />
          </div>
        </template>

        <!-- 饮食 -->
        <template v-else-if="tab === 'diet'">
          <div class="block">
            <div class="label">食欲</div>
            <div class="chips">
              <button
                v-for="o in appetiteLevelOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: diet.appetite === o.dictCode }"
                @click="diet.appetite = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block">
            <div class="label">习惯</div>
            <div class="chips">
              <button
                v-for="o in dietHabitOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: diet.habit === o.dictCode }"
                @click="diet.habit = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block">
            <div class="label">类型</div>
            <div class="chips">
              <button
                v-for="o in dietTypeOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: diet.type === o.dictCode }"
                @click="diet.type = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block fields">
            <van-field
              v-model="diet.preference"
              label="偏好"
              placeholder="如偏咸/清淡"
              input-align="right"
            />
            <van-field v-model="diet.note" label="备注" placeholder="选填" input-align="right" />
          </div>
        </template>

        <!-- 运动 -->
        <template v-else-if="tab === 'exercise'">
          <div class="block">
            <div class="label">频率</div>
            <div class="chips">
              <button
                v-for="o in exerciseFreqOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: exercise.frequency === o.dictCode }"
                @click="exercise.frequency = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block">
            <div class="label">强度</div>
            <div class="chips">
              <button
                v-for="o in exerciseIntensityOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: exercise.intensity === o.dictCode }"
                @click="exercise.intensity = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block fields">
            <van-field
              :model-value="exercise.durationMin ?? ''"
              type="digit"
              label="时长"
              placeholder="分钟"
              input-align="right"
              @update:model-value="exercise.durationMin = $event"
            />
            <van-field
              v-model="exercise.type"
              label="类型"
              placeholder="如快走、游泳"
              input-align="right"
            />
            <van-field v-model="exercise.note" label="备注" placeholder="选填" input-align="right" />
          </div>
          <div class="block">
            <div class="section-title">补充说明</div>
            <van-field
              v-model="lifestyle.note"
              rows="3"
              autosize
              type="textarea"
              placeholder="其他生活方式补充说明（选填）"
              class="note-field"
            />
          </div>
        </template>

        <!-- 睡眠 -->
        <template v-else-if="tab === 'sleep'">
          <div class="block">
            <div class="label">质量</div>
            <div class="chips">
              <button
                v-for="o in sleepQualityOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: sleep.quality === o.dictCode }"
                @click="sleep.quality = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block fields">
            <van-field
              :model-value="sleep.hours ?? ''"
              type="number"
              label="时长"
              placeholder="小时/天"
              input-align="right"
              @update:model-value="sleep.hours = $event"
            />
          </div>
          <div class="block">
            <div class="label">障碍</div>
            <div class="chips">
              <button
                v-for="o in sleepDisorderOpts"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: sleep.disorder === o.dictCode }"
                @click="sleep.disorder = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>
          <div class="block fields">
            <van-field v-model="sleep.note" label="备注" placeholder="选填" input-align="right" />
          </div>
        </template>
      </div>

      <div class="footer">
        <van-button round block type="primary" :loading="saving" @click="save">保存</van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.22), transparent 40%),
    radial-gradient(circle at 92% 4%, rgba(245, 166, 35, 0.12), transparent 34%),
    var(--hx-bg);
  padding-bottom: 96px;
}

.loading {
  padding: 48px 0;
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

.seg {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 4px;
  margin: 8px 16px 0;
  padding: 4px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--hx-shadow);
}
.seg-item {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 550;
  line-height: 1;
  padding: 10px 0;
  border-radius: 10px;
  transition: background 0.18s ease, color 0.18s ease, transform 0.18s ease;
}
.seg-item.active {
  background: linear-gradient(145deg, #5cb8b8, #2b9e9e);
  color: #fff;
  box-shadow: 0 4px 12px rgba(43, 158, 158, 0.28);
}

.panel {
  margin: 14px 16px 0;
  padding: 4px 0 8px;
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
  overflow: hidden;
}

.block {
  padding: 14px 16px 6px;
}
.block + .block {
  border-top: 1px solid #f0f4f6;
}
.section-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 650;
  color: var(--hx-text);
}
.label {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--hx-muted);
  font-weight: 500;
}
.block .label + .chips + .label {
  margin-top: 14px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.chip {
  border: 1px solid #e4ecec;
  background: #f7fbfb;
  color: var(--hx-text);
  font-size: 13px;
  padding: 8px 14px;
  border-radius: 999px;
  line-height: 1.2;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}

.fields {
  padding-top: 4px;
  padding-bottom: 4px;
}
.fields :deep(.van-field) {
  padding: 12px 0;
  background: transparent;
}
.fields :deep(.van-cell::after) {
  left: 0;
  right: 0;
  border-color: #f0f4f6;
}
.fields :deep(.van-field__label) {
  color: var(--hx-muted);
  width: 5.2em;
}
.note-field {
  margin: 0 -4px;
  background: #f7fbfb;
  border-radius: 12px;
  overflow: hidden;
}
.note-field :deep(.van-field__control) {
  min-height: 72px;
}

.footer {
  position: fixed;
  left: 50%;
  bottom: 0;
  z-index: 20;
  width: min(375px, 100%);
  transform: translateX(-50%);
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, rgba(247, 249, 252, 0), rgba(247, 249, 252, 0.92) 28%, #f7f9fc);
}
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
  height: 44px;
  font-weight: 600;
  box-shadow: 0 8px 18px rgba(43, 158, 158, 0.28);
}
</style>
