<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { MED_SLOT_TITLES, type MedicationReminder } from '../shared/medication-labels'
import { currentSubscription, enablePush, pushSupport, type PushSupport } from '../shared/web-push'

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const form = ref<MedicationReminder | null>(null)

const support = ref<PushSupport>(pushSupport())
const pushOn = ref(false)
const pushBusy = ref(false)

const SLOT_FIELDS = [
  { key: 'morningAt', slot: 'MORNING' },
  { key: 'noonAt', slot: 'NOON' },
  { key: 'eveningAt', slot: 'EVENING' },
  { key: 'bedtimeAt', slot: 'BEDTIME' },
] as const
type SlotField = (typeof SLOT_FIELDS)[number]['key']

const FOLLOW_UPS = [
  { v: 15, label: '15 分钟' },
  { v: 30, label: '30 分钟' },
  { v: 60, label: '1 小时' },
  { v: 0, label: '不跟进' },
]

const pickerOpen = ref(false)
const pickerField = ref<SlotField>('morningAt')
const pickerValue = ref<string[]>(['08', '00'])

const supportHint: Record<Exclude<PushSupport, 'supported'>, string> = {
  insecure: '需通过 HTTPS 访问才能收到手机通知，目前只会在「消息」里提醒',
  'ios-needs-install': 'iPhone 需先点浏览器「分享 → 添加到主屏幕」，再从主屏幕图标打开，才能收到手机通知',
  unsupported: '当前浏览器不支持手机通知，提醒会出现在「消息」里',
}

onMounted(async () => {
  try {
    const res = await api<{ data: MedicationReminder }>('/api/c/v1/me/medications/reminder')
    form.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
  if (support.value === 'supported') {
    pushOn.value = Boolean(await currentSubscription().catch(() => null))
  }
})

async function save(next: Partial<MedicationReminder>) {
  if (!form.value) return
  const prev = { ...form.value }
  form.value = { ...form.value, ...next }
  saving.value = true
  try {
    const res = await api<{ data: MedicationReminder }>('/api/c/v1/me/medications/reminder', {
      method: 'PUT',
      body: JSON.stringify(form.value),
    })
    form.value = res.data
  } catch (e) {
    form.value = prev
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function openPicker(key: SlotField) {
  if (!form.value) return
  pickerField.value = key
  pickerValue.value = form.value[key].split(':')
  pickerOpen.value = true
}

function onPick({ selectedValues }: { selectedValues: string[] }) {
  pickerOpen.value = false
  void save({ [pickerField.value]: `${selectedValues[0]}:${selectedValues[1]}` })
}

async function turnOnPush() {
  pushBusy.value = true
  try {
    await enablePush()
    pushOn.value = true
    showToast({ type: 'success', message: '已开启手机通知' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '开启失败')
  } finally {
    pushBusy.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="服药提醒" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else-if="form">
      <section class="card">
        <div class="row">
          <div>
            <strong>按时提醒吃药</strong>
            <p class="sub">到点提醒一次，还没记录再跟进提醒一次</p>
          </div>
          <van-switch :model-value="form.enabled" :loading="saving" @update:model-value="(v: boolean) => save({ enabled: v })" />
        </div>
      </section>

      <template v-if="form.enabled">
        <section class="card">
          <p class="sec-title">提醒时间</p>
          <div v-for="f in SLOT_FIELDS" :key="f.key" class="row clickable" @click="openPicker(f.key)">
            <span>{{ MED_SLOT_TITLES[f.slot] }}</span>
            <span class="val">{{ form[f.key] }} <van-icon name="arrow" /></span>
          </div>
          <p class="sub">只提醒有药要吃的时段。</p>
        </section>

        <section class="card">
          <p class="sec-title">没记录时再提醒</p>
          <div class="chips">
            <button
              v-for="o in FOLLOW_UPS"
              :key="o.v"
              type="button"
              class="chip"
              :class="{ active: form.followUpMin === o.v }"
              @click="save({ followUpMin: o.v })"
            >
              {{ o.label }}
            </button>
          </div>
        </section>

        <section class="card">
          <p class="sec-title">手机通知</p>
          <template v-if="support === 'supported'">
            <div class="row">
              <span>{{ pushOn ? '本设备已开启通知' : '开启后锁屏也能收到提醒' }}</span>
              <van-button v-if="!pushOn" round size="small" type="primary" :loading="pushBusy" @click="turnOnPush">
                开启通知
              </van-button>
              <van-icon v-else name="success" class="ok" />
            </div>
          </template>
          <p v-else class="sub">{{ supportHint[support] }}</p>
        </section>
      </template>
    </template>

    <van-popup v-model:show="pickerOpen" position="bottom" round teleport="body">
      <van-time-picker v-model="pickerValue" title="提醒时间" @confirm="onPick" @cancel="pickerOpen = false" />
    </van-popup>
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
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
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
.sec-title {
  margin: 0 0 6px;
  font-weight: 800;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  font-size: 15px;
}
.row + .row {
  border-top: 1px solid var(--hx-n-100);
}
.row.clickable {
  cursor: pointer;
}
.val {
  font-weight: 800;
  color: var(--hx-text);
}
.val .van-icon {
  color: var(--hx-muted);
  font-weight: 400;
}
.sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.6;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
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
  font-weight: 700;
}
.ok {
  color: var(--hx-teal);
  font-size: 20px;
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
