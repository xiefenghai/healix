<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import {
  FOLLOWUP_CONTACT_TARGET_OPTIONS,
  FOLLOWUP_METHOD_OPTIONS,
  FOLLOWUP_TYPE_OPTIONS,
  LIFESTYLE_LEVEL_OPTIONS,
  PLAN_SATISFACTION_OPTIONS,
  buildRoutineFollowupPayload,
  validateRoutineForm,
} from '../shared/labels'

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

const submitting = ref(false)
const followupType = ref('ROUTINE')
const createTask = ref(true)
const completeNow = ref(false)
const showTypePicker = ref(false)

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

const typeLabel = computed(
  () => FOLLOWUP_TYPE_OPTIONS.find((o) => o.value === followupType.value)?.label || '常规健康回访',
)

watch(completeNow, (v) => {
  if (v) createTask.value = false
})

function openPicker(
  field: PickerField,
  title: string,
  options: ReadonlyArray<{ label: string; value: string }>,
) {
  picker.value = { field, title, options }
  showPicker.value = true
}

function pickOption(value: string) {
  if (!picker.value) return
  const map: Record<PickerField, (v: string) => void> = {
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
  map[picker.value.field](value)
  showPicker.value = false
}

function optionLabel(options: ReadonlyArray<{ label: string; value: string }>, value: string) {
  return options.find((o) => o.value === value)?.label || value
}

async function submit() {
  if (!peopleId.value) return
  let content: Record<string, unknown> = { followupType: followupType.value }
  if (completeNow.value) {
    if (followupType.value === 'ROUTINE') {
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
      content = buildRoutineFollowupPayload({
        contactTarget: contactTarget.value,
        followupMethod: followupMethod.value,
        guidance: guidance.value,
        adherenceNote: adherenceNote.value,
        lifestyleLevel: lifestyleLevel.value,
        planSatisfaction: planSatisfaction.value,
        lifestyleNote: lifestyleNote.value,
        unsatisfiedReason: unsatisfiedReason.value,
      })
    } else {
      if (!contactTarget.value || !followupMethod.value) {
        showToast('请选择联系对象与随访方式')
        return
      }
      content = {
        followupType: followupType.value,
        contactTarget: contactTarget.value,
        followupMethod: followupMethod.value,
        guidance: guidance.value.trim() || undefined,
        section: { note: adherenceNote.value.trim() || undefined },
      }
    }
  }
  submitting.value = true
  try {
    await api('/api/b/v1/followups', {
      method: 'POST',
      body: JSON.stringify({
        peopleId: peopleId.value,
        followupType: followupType.value,
        completeNow: completeNow.value,
        createTask: completeNow.value ? false : createTask.value,
        content,
      }),
    })
    showToast(
      completeNow.value ? '已记录随访' : createTask.value ? '已创建随访待办' : '已创建未完成随访',
    )
    await router.replace(`/patients/${peopleId.value}/followups`)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="新建随访" left-arrow @click-left="router.back()" />
    <section class="form hx-surface">
      <p class="tip hx-muted">可先建待办，或当场办结（常规随访建议填完整表单）</p>
      <van-cell title="随访类型" is-link required :value="typeLabel" @click="showTypePicker = true" />
      <van-cell title="当场办结">
        <template #value>
          <van-switch v-model="completeNow" size="20px" />
        </template>
      </van-cell>
      <van-cell v-if="!completeNow" title="同时开待办">
        <template #value>
          <van-switch v-model="createTask" size="20px" />
        </template>
      </van-cell>

      <template v-if="completeNow">
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
        <template v-if="followupType === 'ROUTINE'">
          <van-field
            v-model="adherenceNote"
            rows="2"
            autosize
            type="textarea"
            label="打卡概况"
            required
          />
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
        </template>
        <van-field
          v-else
          v-model="adherenceNote"
          rows="2"
          autosize
          type="textarea"
          label="随访摘要"
          placeholder="简要记录本次沟通"
        />
        <van-field v-model="guidance" rows="2" autosize type="textarea" label="指导建议" />
      </template>

      <van-button block type="primary" round class="submit" :loading="submitting" @click="submit">
        {{ completeNow ? '提交并办结' : '确认创建' }}
      </van-button>
    </section>

    <van-popup v-model:show="showTypePicker" position="bottom" round>
      <div class="picker-title">选择随访类型</div>
      <van-cell
        v-for="o in FOLLOWUP_TYPE_OPTIONS"
        :key="o.value"
        :title="o.label"
        clickable
        @click="
          followupType = o.value;
          showTypePicker = false
        "
      />
    </van-popup>

    <van-popup v-model:show="showPicker" position="bottom" round>
      <div v-if="picker">
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
  margin: 12px 16px;
  padding: 8px 0 16px;
  overflow: hidden;
}
.tip {
  margin: 8px 16px 12px;
  font-size: 13px;
  line-height: 1.45;
}
.submit {
  margin: 16px;
  width: calc(100% - 32px);
  height: 44px;
  font-weight: 650;
}
.picker-title {
  padding: 16px;
  text-align: center;
  font-weight: 650;
}
</style>
