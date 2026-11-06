<script setup lang="ts">
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'

defineProps<{
  peopleId: string | null
  peopleName?: string | null
  /** 当前所属健管组；有值时为变更模式 */
  currentCareTeamId?: string | null
}>()

const emit = defineEmits<{
  closed: []
  joined: [payload: { peopleId: string; careTeamId: string; careTeamName?: string }]
  create: []
}>()
</script>

<template>
  <ChangeCareTeamDialog
    :people-id="peopleId"
    :people-name="peopleName"
    :current-care-team-id="currentCareTeamId"
    @closed="emit('closed')"
    @changed="(p) => emit('joined', p)"
    @create="emit('create')"
  />
</template>
