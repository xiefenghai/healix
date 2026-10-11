<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { submitWithRetry } from '../shared/retry'
import {
  FOLLOWUP_CONTACT_TARGET_OPTIONS,
  FOLLOWUP_METHOD_OPTIONS,
  LIFESTYLE_LEVEL_OPTIONS,
  PLAN_SATISFACTION_OPTIONS,
  buildRoutineFollowupPayload,
  validateRoutineForm,
} from '../shared/labels'

interface FollowupDetail {
  id: string
  peopleId: string
  status: string
  recordType: string
  followupType?: string
  content?: Record<string, unknown>
}

const route = useRoute()
const router = useRouter()
const followupId = computed(() => String(route.params.followupId || ''))
const loading = ref(false)
const submitting = ref(false)
const detail = ref<FollowupDetail | null>(null)

const contactTarget = ref('PATIENT')
const followupMethod = ref('PHONE')
const guidance = ref('')
const adherenceNote = ref('')
const lifestyleLevel = ref('FAIR')
const planSatisfaction = ref('SATISFIED')
const lifestyleNote = ref('')
const unsatisfiedReason = ref('')

type PickerField = 'contactTarget' | 'followupMethod' | 'lifestyleLevel' | 'planSatisfaction'

const showPicker = ref(false)
const picker = ref<{
  field: PickerField
  title: string
  options: ReadonlyArray<{ label: string; value: string }>
} | null>(null)

const fieldSetters: Record<PickerField, (v: string) => void> = {
  contactTarget: (v) => {
    contactTarget.value = v
  },
  followupMethod: (v) => {
    followupMethod.value = v
  },
  lifestyleLevel: (v) => {
    lifestyleLevel.value = v
  },
  planSatisfaction: (v) => {
    planSatisfaction.value = v
  },
}

function openPicker(
  field: PickerField,
  title: string,
  options: ReadonlyArray<{ label: string; value: string }>,
) {
  picker.value = { field, title, options }
  showPicker.value = true
}

function pickOption(value: string) {
  if (picker.value) fieldSetters[picker.value.field](value)
  showPicker.value = false
}

function optionLabel(options: ReadonlyArray<{ label: string; value: string }>, value: string) {
  return options.find((o) => o.value === value)?.label || value
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: FollowupDetail }>(`/api/b/v1/followups/${followupId.value}`)
    detail.value = res.data
    if (res.data.status !== 'OPEN' || res.data.recordType !== 'PERIODIC') {
      showToast('仅支持办结未完成的定期随访')
    }
    const content = res.data.content
    if (content) {
      if (typeof content.contactTarget === 'string') contactTarget.value = content.contactTarget
      if (typeof content.followupMethod === 'string') followupMethod.value = content.followupMethod
      if (typeof content.guidance === 'string') guidance.value = content.guidance
      const section = content.section as Record<string, unknown> | undefined
      if (section) {
        if (typeof section.adherenceNote === 'string') adherenceNote.value = section.adherenceNote
        if (typeof section.lifestyleLevel === 'string') lifestyleLevel.value = section.lifestyleLevel
        if (typeof section.planSatisfaction === 'string') planSatisfaction.value = section.planSatisfaction
        if (typeof section.lifestyleNote === 'string') lifestyleNote.value = section.lifestyleNote
        if (typeof section.unsatisfiedReason === 'string') unsatisfiedReason.value = section.unsatisfiedReason
      }
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  const err = validateRoutineForm({
    contactTarget: contactTarget.value,
    followupMethod: followupMethod.value,
    adherenceNote: adherenceNote.value,
    lifestyleLevel: lifestyleLevel.value,
    planSatisfaction: planSatisfaction.value,
    unsatisfiedReason: unsatisfiedReason.value,
  })
  if (err) {
    showToast(err)
    return
  }
  submitting.value = true
  try {
    const body = buildRoutineFollowupPayload({
      contactTarget: contactTarget.value,
      followupMethod: followupMethod.value,
      guidance: guidance.value,
      adherenceNote: adherenceNote.value,
      lifestyleLevel: lifestyleLevel.value,
      planSatisfaction: planSatisfaction.value,
      lifestyleNote: lifestyleNote.value,
      unsatisfiedReason: unsatisfiedReason.value,
    })
    const ok = await submitWithRetry(
      () =>
        api(`/api/b/v1/followups/${followupId.value}/complete`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      { title: '办结失败' },
    )
    if (!ok) return
    showToast('已办结')
    if (detail.value?.peopleId) {
      await router.replace(`/patients/${detail.value.peopleId}/followups`)
    } else {
      router.back()
    }
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="办结随访" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <section v-else class="form hx-surface">
      <p class="notice hx-muted">手机端支持常规健康回访办结；入组随访等请用电脑端</p>
      <van-cell
        title="联系对象"
        is-link
        :value="optionLabel(FOLLOWUP_CONTACT_TARGET_OPTIONS, contactTarget)"
        @click="openPicker('contactTarget', '联系对象', FOLLOWUP_CONTACT_TARGET_OPTIONS)"
      />
      <van-cell
        title="随访方式"
        is-link
        :value="optionLabel(FOLLOWUP_METHOD_OPTIONS, followupMethod)"
        @click="openPicker('followupMethod', '随访方式', FOLLOWUP_METHOD_OPTIONS)"
      />
      <van-field v-model="adherenceNote" rows="2" autosize type="textarea" label="打卡概况" required />
      <van-cell
        title="生活习惯"
        is-link
        :value="optionLabel(LIFESTYLE_LEVEL_OPTIONS, lifestyleLevel)"
        @click="openPicker('lifestyleLevel', '生活习惯', LIFESTYLE_LEVEL_OPTIONS)"
      />
      <van-cell
        title="方案满意度"
        is-link
        :value="optionLabel(PLAN_SATISFACTION_OPTIONS, planSatisfaction)"
        @click="openPicker('planSatisfaction', '方案满意度', PLAN_SATISFACTION_OPTIONS)"
      />
      <van-field
        v-if="planSatisfaction === 'UNSATISFIED'"
        v-model="unsatisfiedReason"
        rows="2"
        autosize
        type="textarea"
        label="不满意原因"
        required
      />
      <van-field v-model="lifestyleNote" rows="2" autosize type="textarea" label="生活备注" />
      <van-field v-model="guidance" rows="2" autosize type="textarea" label="指导建议" />
      <van-button block type="primary" round :loading="submitting" class="submit" @click="submit">
        确认办结
      </van-button>
    </section>

    <van-popup v-model:show="showPicker" position="bottom" round>
      <div v-if="picker" class="picker">
        <div class="picker-title">{{ picker.title }}</div>
        <van-cell
          v-for="o in picker.options"
          :key="o.value"
          :title="o.label"
          clickable
          @click="pickOption(o.value)"
        />
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.form {
  margin: 8px 16px 24px;
  padding-bottom: 16px;
  overflow: hidden;
}
.notice {
  margin: 0;
  padding: 14px 16px 8px;
  font-size: 12px;
  line-height: 1.5;
}
.submit {
  margin: 16px;
  width: calc(100% - 32px);
  height: 44px;
  font-weight: 650;
}
.picker-title {
  padding: 16px;
  font-weight: 650;
  text-align: center;
  color: var(--hx-ink);
}
</style>
