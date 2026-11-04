<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { debounce } from '../shared/debounce'
import {
  ACTIVITY_OPTIONS,
  GOAL_TYPE_LABELS,
  type DietGoal,
  type DietGoalStatus,
} from '../shared/diet'

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const status = ref<DietGoalStatus | null>(null)
const preview = ref<DietGoal | null>(null)
const previewError = ref('')

const form = reactive({
  goalType: 'MAINTAIN',
  rateKgPerWeek: 0.5,
  activityLevel: 'SEDENTARY',
  heightCm: '' as string | number,
  weightKg: '' as string | number,
})

const editable = computed(() => status.value?.editableByPatient !== false)
const staffGoal = computed(() => (status.value?.goal?.source === 'STAFF' ? status.value.goal : null))
const profileMissing = computed(() =>
  (status.value?.missing ?? []).filter((m) => m === 'GENDER' || m === 'BIRTHDAY'),
)
const rateOptions = computed(() => (form.goalType === 'GAIN' ? [0.25, 0.5] : [0.25, 0.5, 0.75, 1]))
const shown = computed(() => staffGoal.value ?? preview.value)

function payload() {
  return {
    goalType: form.goalType,
    rateKgPerWeek: form.goalType === 'MAINTAIN' ? null : form.rateKgPerWeek,
    activityLevel: form.activityLevel,
    heightCm: form.heightCm === '' ? null : Number(form.heightCm),
    weightKg: form.weightKg === '' ? null : Number(form.weightKg),
  }
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: DietGoalStatus }>('/api/c/v1/me/diet/goal')
    status.value = res.data
    const g = res.data.goal
    form.heightCm = res.data.heightCm ?? g?.basisHeightCm ?? ''
    form.weightKg = res.data.weightKg ?? g?.basisWeightKg ?? ''
    if (g) {
      form.goalType = g.goalType || 'MAINTAIN'
      form.rateKgPerWeek = g.rateKgPerWeek || 0.5
      form.activityLevel = g.activityLevel || 'SEDENTARY'
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
  if (editable.value) void refreshPreview()
}

async function refreshPreview() {
  if (!editable.value || profileMissing.value.length || !form.heightCm || !form.weightKg) {
    preview.value = null
    return
  }
  try {
    const res = await api<{ data: DietGoal }>('/api/c/v1/me/diet/goal/preview', {
      method: 'POST',
      body: JSON.stringify(payload()),
    })
    preview.value = res.data
    previewError.value = ''
  } catch (e) {
    preview.value = null
    previewError.value = e instanceof Error ? e.message : '计算失败'
  }
}

const debouncedPreview = debounce(() => void refreshPreview(), 400)

watch(
  () => [form.goalType, form.rateKgPerWeek, form.activityLevel, form.heightCm, form.weightKg],
  () => {
    if (!loading.value) debouncedPreview()
  },
)

watch(
  () => form.goalType,
  (t) => {
    if (t === 'GAIN' && form.rateKgPerWeek > 0.5) form.rateKgPerWeek = 0.5
  },
)

async function save() {
  if (!form.heightCm || !form.weightKg) {
    showToast('请填写身高和体重')
    return
  }
  saving.value = true
  try {
    await api('/api/c/v1/me/diet/goal', { method: 'PUT', body: JSON.stringify(payload()) })
    showToast({ type: 'success', message: '目标已保存' })
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="热量目标" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else-if="status">
      <section v-if="shown" class="card result">
        <p class="muted">每日建议摄入</p>
        <p class="kcal"><strong>{{ shown.energyKcal }}</strong> 千卡</p>
        <div class="macros">
          <div><span>碳水</span><strong>{{ shown.carbG }}g</strong></div>
          <div><span>蛋白质</span><strong>{{ shown.proteinG }}g</strong></div>
          <div><span>脂肪</span><strong>{{ shown.fatG }}g</strong></div>
          <div><span>钠</span><strong>≤{{ shown.sodiumMg }}mg</strong></div>
        </div>
        <p v-if="shown.bmrKcal" class="basis">
          基础代谢 {{ shown.bmrKcal }} · 日常消耗 {{ shown.tdeeKcal }} 千卡
        </p>
        <p v-if="shown.floorApplied" class="hint warn">已按安全下限调整，避免摄入过低</p>
        <p class="hint">营养素比例按「{{ shown.diseaseLabel || status.diseaseLabel }}」推荐分配；运动消耗不计入预算。</p>
      </section>

      <template v-if="staffGoal">
        <section class="card">
          <p class="staff">当前目标由医护团队设定，如需调整请联系你的医护团队。</p>
          <p v-if="staffGoal.note" class="muted">备注：{{ staffGoal.note }}</p>
        </section>
      </template>

      <template v-else>
        <section v-if="profileMissing.length" class="card">
          <p class="staff">计算热量需要性别和出生日期，请先完善个人资料。</p>
          <van-button round size="small" type="primary" @click="router.push('/archive/basic')">去完善</van-button>
        </section>

        <section class="card form">
          <div class="block">
            <div class="label">身高 / 体重</div>
            <div class="row2">
              <van-field v-model="form.heightCm" type="number" placeholder="身高" input-align="center">
                <template #extra>cm</template>
              </van-field>
              <van-field v-model="form.weightKg" type="number" placeholder="体重" input-align="center">
                <template #extra>kg</template>
              </van-field>
            </div>
            <p class="muted small">修改后会同步记一条身高 / 体重数据</p>
          </div>

          <div class="block">
            <div class="label">目标</div>
            <div class="chips">
              <button
                v-for="(label, key) in GOAL_TYPE_LABELS"
                :key="key"
                type="button"
                class="chip"
                :class="{ active: form.goalType === key }"
                @click="form.goalType = key"
              >
                {{ label }}
              </button>
            </div>
          </div>

          <div v-if="form.goalType !== 'MAINTAIN'" class="block">
            <div class="label">每周{{ form.goalType === 'LOSE' ? '减' : '增' }}重</div>
            <div class="chips">
              <button
                v-for="r in rateOptions"
                :key="r"
                type="button"
                class="chip"
                :class="{ active: form.rateKgPerWeek === r }"
                @click="form.rateKgPerWeek = r"
              >
                {{ r }} kg
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">日常活动量</div>
            <div class="acts">
              <button
                v-for="o in ACTIVITY_OPTIONS"
                :key="o.value"
                type="button"
                class="act"
                :class="{ active: form.activityLevel === o.value }"
                @click="form.activityLevel = o.value"
              >
                <strong>{{ o.label }}</strong>
                <span>{{ o.hint }}</span>
              </button>
            </div>
          </div>

          <p v-if="previewError" class="hint warn">{{ previewError }}</p>
        </section>

        <div class="footer">
          <van-button round block type="primary" :loading="saving" :disabled="!!profileMissing.length" @click="save">
            保存目标
          </van-button>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
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
.loading {
  padding: 48px 0;
}
.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.muted {
  margin: 0;
  color: var(--hx-muted);
  font-size: 13px;
}
.small {
  margin-top: 6px;
  font-size: 11px;
}
.result {
  text-align: center;
}
.kcal {
  margin: 4px 0 0;
  color: var(--hx-muted);
}
.kcal strong {
  font-size: 36px;
  font-weight: 800;
  color: var(--hx-teal);
}
.macros {
  display: flex;
  justify-content: space-around;
  margin-top: 12px;
}
.macros div {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: var(--hx-muted);
}
.macros strong {
  margin-top: 2px;
  font-size: 15px;
  color: var(--hx-text);
}
.basis {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.5;
}
.hint.warn {
  color: var(--hx-danger-text);
}
.staff {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.6;
}
.form {
  padding: 8px 12px;
}
.block {
  padding: 10px 4px;
}
.label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.row2 {
  display: flex;
  gap: 12px;
}
.row2 :deep(.van-field) {
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--hx-n-50);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid var(--hx-n-200);
  background: var(--hx-n-50);
  color: var(--hx-text);
  font-size: 13px;
  padding: 8px 14px;
  border-radius: 999px;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.acts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.act {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--hx-n-200);
  border-radius: 12px;
  background: var(--hx-n-50);
  text-align: left;
}
.act strong {
  font-size: 14px;
  color: var(--hx-text);
}
.act span {
  font-size: 11px;
  color: var(--hx-muted);
}
.act.active {
  border-color: var(--hx-teal);
  background: var(--hx-teal-light);
}
.footer {
  margin: 20px 16px 0;
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
