<script setup lang="ts">
import { computed } from 'vue'
import PatientArchiveView from '../portals/workspace/PatientArchiveView.vue'
import PatientCarePlanView from '../portals/workspace/PatientCarePlanView.vue'
import PatientFollowupView from '../portals/workspace/PatientFollowupView.vue'
import PatientHealthReportView from '../portals/workspace/PatientHealthReportView.vue'
import PatientObservationLayout from '../portals/workspace/PatientObservationLayout.vue'
import PatientChatView from '../portals/workspace/PatientChatView.vue'

export type CockpitSheetMode =
  | 'archive'
  | 'followups'
  | 'care-plan'
  | 'reports'
  | 'observations'
  | 'care-chat'

const props = defineProps<{
  modelValue: boolean
  peopleId: string
  mode: CockpitSheetMode
  patientName?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

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
    size="78%"
    append-to-body
    destroy-on-close
    class="cockpit-patient-sheet"
  >
    <div v-if="peopleId && visible" class="sheet-body" :class="{ chat: mode === 'care-chat' }">
      <PatientArchiveView v-if="mode === 'archive'" :people-id="peopleId" />
      <PatientFollowupView v-else-if="mode === 'followups'" :people-id="peopleId" />
      <PatientCarePlanView v-else-if="mode === 'care-plan'" :people-id="peopleId" />
      <PatientHealthReportView v-else-if="mode === 'reports'" :people-id="peopleId" />
      <PatientObservationLayout v-else-if="mode === 'observations'" :people-id="peopleId" />
      <PatientChatView v-else-if="mode === 'care-chat'" :people-id="peopleId" />
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
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
}

.sheet-body.chat :deep(.chat-page) {
  border: 0;
  border-radius: 0;
  box-shadow: none;
  min-height: 0;
}
</style>
