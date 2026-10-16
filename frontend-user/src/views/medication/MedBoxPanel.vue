<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../../api/http'
import { loadDictOptions, toLabelMap } from '../../shared/dict'
import MedicationFormSheet from '../../shared/MedicationFormSheet.vue'
import {
  formatMedicationLine,
  MED_TIME_SLOT_LABELS,
  medLabel,
  medSourceLabel,
  type MedicationView,
} from '../../shared/medication-labels'

const emit = defineEmits<{ changed: [] }>()
const router = useRouter()

const loading = ref(true)
const tab = ref<'active' | 'history'>('active')
const meds = ref<MedicationView[]>([])
const showAdd = ref(false)
const maps = ref<{ usage: Record<string, string>; frequency: Record<string, string>; doseUnit: Record<string, string> }>({
  usage: {},
  frequency: {},
  doseUnit: {},
})

const activeMeds = computed(() => meds.value.filter((m) => m.status !== 'STOPPED'))
const historyMeds = computed(() =>
  meds.value
    .filter((m) => m.status === 'STOPPED')
    .sort((a, b) => (b.stopDate || '').localeCompare(a.stopDate || '')),
)
const shown = computed(() => (tab.value === 'active' ? activeMeds.value : historyMeds.value))

onActivated(load)

async function load() {
  loading.value = !meds.value.length
  try {
    const [res, usage, frequency, doseUnit] = await Promise.all([
      api<{ data: MedicationView[] }>('/api/c/v1/me/medications?status=ALL'),
      loadDictOptions('medicationUsage').catch(() => []),
      loadDictOptions('medicationFrequency').catch(() => []),
      loadDictOptions('doseUnit').catch(() => []),
    ])
    meds.value = res.data || []
    maps.value = { usage: toLabelMap(usage), frequency: toLabelMap(frequency), doseUnit: toLabelMap(doseUnit) }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function line(m: MedicationView) {
  return formatMedicationLine(m, maps.value)
}

function slotsText(m: MedicationView) {
  if (m.prn) return '需要时服用'
  return (m.suggestedSlots || []).map((s) => medLabel(MED_TIME_SLOT_LABELS, s)).join('、')
}

function onSaved() {
  tab.value = 'active'
  void load()
  emit('changed')
}

defineExpose({ reload: load })
</script>

<template>
  <div class="box">
    <div class="toolbar">
      <van-button round type="primary" icon="plus" @click="showAdd = true">手动添加</van-button>
      <van-button round plain type="primary" icon="photograph" @click="router.push('/medications/scan')">
        拍药盒添加
      </van-button>
    </div>

    <div class="seg">
      <button type="button" class="seg-item" :class="{ active: tab === 'active' }" @click="tab = 'active'">
        在用 <span v-if="activeMeds.length" class="seg-count">{{ activeMeds.length }}</span>
      </button>
      <button type="button" class="seg-item" :class="{ active: tab === 'history' }" @click="tab = 'history'">
        已停用 <span v-if="historyMeds.length" class="seg-count">{{ historyMeds.length }}</span>
      </button>
    </div>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>
    <van-empty v-else-if="!shown.length" :description="tab === 'active' ? '药箱还是空的，添加正在吃的药' : '没有停用的药'" />

    <section
      v-for="m in shown"
      :key="m.id"
      class="card"
      :class="{ history: m.status === 'STOPPED' }"
      @click="router.push(`/medications/${m.id}`)"
    >
      <div class="card-head">
        <strong>{{ m.drugName }}</strong>
        <span class="badge" :class="{ warn: m.source === 'PATIENT' && !m.verified }">{{ medSourceLabel(m) }}</span>
        <span v-if="m.openFeedbackCount" class="badge muted">反馈处理中</span>
        <van-icon name="arrow" class="arrow" />
      </div>
      <p class="line">{{ line(m) }}</p>
      <p v-if="m.status !== 'STOPPED' && slotsText(m)" class="meta">服药时段：{{ slotsText(m) }}</p>
      <p v-if="m.status === 'STOPPED'" class="meta">停用于 {{ m.stopDate || '—' }}</p>
      <p v-if="m.allergyConflict" class="conflict"><van-icon name="warning-o" /> 可能与过敏史冲突：{{ m.allergyConflict }}</p>
    </section>

    <MedicationFormSheet v-model:show="showAdd" @saved="onSaved" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin: 12px 16px 0;
}
.toolbar :deep(.van-button) {
  flex: 1;
  height: 42px;
  font-weight: 700;
}
.toolbar :deep(.van-button--primary:not(.van-button--plain)) {
  background: var(--hx-grad);
  border: 0;
}
.seg {
  display: flex;
  gap: 8px;
  margin: 14px 16px 0;
  padding: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(14, 159, 131, 0.12);
}
.seg-item {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 600;
  padding: 8px;
  border-radius: 999px;
}
.seg-item.active {
  color: #fff;
  background: var(--hx-grad);
}
.seg-count {
  margin-left: 4px;
  font-size: 11px;
}
.loading {
  padding: 48px 0;
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.card.history {
  opacity: 0.9;
  background: var(--hx-n-50);
}
.card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.card-head strong {
  font-size: 16px;
  font-weight: 700;
  color: var(--hx-text);
}
.arrow {
  margin-left: auto;
  color: var(--hx-muted);
}
.badge {
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.badge.warn {
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft, var(--hx-warn-bg));
}
.badge.muted {
  color: var(--hx-muted);
  background: var(--hx-n-100);
}
.line {
  margin: 8px 0 0;
  color: var(--hx-text);
  font-size: 13px;
  line-height: 1.5;
}
.meta {
  margin: 4px 0 0;
  color: var(--hx-muted);
  font-size: 12px;
}
.conflict {
  margin: 6px 0 0;
  color: var(--hx-danger-text);
  font-size: 12px;
}
</style>
