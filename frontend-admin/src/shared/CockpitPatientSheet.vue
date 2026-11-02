<script setup lang="ts">
import { computed } from 'vue'
import PatientArchiveView from '../portals/workspace/PatientArchiveView.vue'
import PatientCarePlanView from '../portals/workspace/PatientCarePlanView.vue'
import PatientFollowupView from '../portals/workspace/PatientFollowupView.vue'
import PatientHealthReportView from '../portals/workspace/PatientHealthReportView.vue'
import PatientObservationLayout from '../portals/workspace/PatientObservationLayout.vue'
import PatientChatView from '../portals/workspace/PatientChatView.vue'
import PatientMedicationView from '../portals/workspace/PatientMedicationView.vue'
import PatientAssessmentsView from '../portals/workspace/PatientAssessmentsView.vue'
import PatientIntakeView from '../portals/workspace/PatientIntakeView.vue'

export type CockpitSheetMode =
  | 'archive'
  | 'followups'
  | 'care-plan'
  | 'reports'
  | 'observations'
  | 'care-chat'
  | 'medications'
  | 'assessments'
  | 'intake'

export type IntakeSheetOpts = {
  followupRecordId?: string | null
  purpose?: string | null
}

const props = defineProps<{
  modelValue: boolean
  peopleId: string
  mode: CockpitSheetMode
  patientName?: string
  /** 健康数据抽屉初始子页：metrics / trends / labs / exams */
  observationsSub?: string
  /** 首诊问卷：关联随访 / 待补 */
  intakeFollowupRecordId?: string | null
  intakePurpose?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 管理报告或管理方案在抽屉内发布成功 */
  published: []
  'care-plan-published': []
  /** 随访抽屉内要求打开首诊问卷 */
  'open-intake': [opts: IntakeSheetOpts]
  /** 首诊问卷提交成功 */
  'intake-submitted': []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const drawerSize = computed(() => (props.mode === 'intake' ? '92%' : '78%'))

const title = computed(() => {
  const name = props.patientName ? ` · ${props.patientName}` : ''
  switch (props.mode) {
    case 'archive':
      return `患者档案${name}`
    case 'followups':
      return `随访${name}`
    case 'care-plan':
      return `管理方案${name}`
    case 'reports':
      return `健康报告${name}`
    case 'observations':
      return `健康数据${name}`
    case 'care-chat':
      return `联系患者${name}`
    case 'medications':
      return `用药管理${name}`
    case 'assessments':
      return `疾病评估${name}`
    case 'intake':
      return props.intakePurpose === 'SUPPLEMENT' ? `待补问卷${name}` : `首诊问卷${name}`
    default:
      return `患者工作台${name}`
  }
})
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="title"
    direction="rtl"
    :size="drawerSize"
    append-to-body
    destroy-on-close
    class="cockpit-patient-sheet"
  >
    <div
      v-if="peopleId && visible"
      class="sheet-body"
      :class="{ chat: mode === 'care-chat', intake: mode === 'intake' }"
    >
      <PatientArchiveView v-if="mode === 'archive'" :people-id="peopleId" />
      <PatientFollowupView
        v-else-if="mode === 'followups'"
        :people-id="peopleId"
        @open-intake="(opts) => emit('open-intake', opts)"
      />
      <PatientCarePlanView
        v-else-if="mode === 'care-plan'"
        :people-id="peopleId"
        @published="emit('care-plan-published')"
      />
      <PatientHealthReportView
        v-else-if="mode === 'reports'"
        :people-id="peopleId"
        @published="emit('published')"
      />
      <PatientObservationLayout
        v-else-if="mode === 'observations'"
        :people-id="peopleId"
        :initial-sub="observationsSub"
      />
      <PatientChatView v-else-if="mode === 'care-chat'" :people-id="peopleId" />
      <PatientMedicationView v-else-if="mode === 'medications'" :people-id="peopleId" />
      <PatientAssessmentsView v-else-if="mode === 'assessments'" :people-id="peopleId" />
      <PatientIntakeView
        v-else-if="mode === 'intake'"
        embedded
        :people-id="peopleId"
        :followup-record-id="intakeFollowupRecordId"
        :purpose="intakePurpose"
        @submitted="emit('intake-submitted')"
        @close="visible = false"
      />
    </div>
  </el-drawer>
</template>

<style scoped>
.sheet-body {
  height: calc(100vh - 120px);
  overflow: auto;
  padding: 0 4px 16px;
}

.sheet-body.chat {
  padding: 0;
  overflow: hidden;
}

.sheet-body.intake {
  padding: 0;
  overflow: hidden;
}
</style>
