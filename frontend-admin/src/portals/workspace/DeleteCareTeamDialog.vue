<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'

export interface DeleteCareTeamTarget {
  id: string
  name: string
}

interface DeleteImpact {
  teamId: string
  teamName: string
  patientCount: number
  openTaskCount: number
}

const props = defineProps<{
  modelValue: boolean
  team: DeleteCareTeamTarget | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  deleted: [teamId: string]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loadingImpact = ref(false)
const deleting = ref(false)
const impact = ref<DeleteImpact | null>(null)
const confirmName = ref('')

const expectedName = computed(() => (props.team?.name || impact.value?.teamName || '').trim())
const nameMatched = computed(
  () => !!expectedName.value && confirmName.value.trim() === expectedName.value,
)
/** 空组：无患者且无未完成待办 → 宽松灰底提示 */
const isEmptyTeam = computed(() => {
  if (!impact.value) return false
  return (impact.value.patientCount || 0) <= 0 && (impact.value.openTaskCount || 0) <= 0
})
const hasImpactRisk = computed(() => {
  if (!impact.value) return false
  return (impact.value.patientCount || 0) > 0 || (impact.value.openTaskCount || 0) > 0
})

watch(
  () => [props.modelValue, props.team?.id] as const,
  async ([open, teamId]) => {
    if (!open || !teamId) return
    confirmName.value = ''
    impact.value = null
    loadingImpact.value = true
    try {
      const res = await api<{ data: DeleteImpact }>(`/api/b/v1/care-teams/${teamId}/delete-impact`)
      impact.value = res.data
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载删除影响失败')
      visible.value = false
    } finally {
      loadingImpact.value = false
    }
  },
)

async function confirmDelete() {
  if (!props.team?.id || !nameMatched.value) return
  deleting.value = true
  try {
    const name = encodeURIComponent(confirmName.value.trim())
    await api(`/api/b/v1/care-teams/${props.team.id}?confirmName=${name}`, { method: 'DELETE' })
    ElMessage.success('健管组已删除，患者已进入公共池')
    visible.value = false
    emit('deleted', props.team.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="删除健管组"
    width="480px"
    destroy-on-close
    :close-on-click-modal="false"
    class="delete-care-team-dialog"
  >
    <div v-loading="loadingImpact" class="body">
      <p v-if="hasImpactRisk" class="warn">
        此操作会软删除健管组「<strong>{{ expectedName || '—' }}</strong>」。患者档案与方案将予以保留，仅解除组关联并退回公共患者池。该组名下的所有待办任务，也将同步解除主责人绑定并进入公共任务池。
      </p>
      <p v-else-if="isEmptyTeam" class="warn soft">
        此操作会软删除健管组「<strong>{{ expectedName || '—' }}</strong>」。
      </p>
      <div
        v-if="impact"
        class="stats"
        :class="{ danger: hasImpactRisk, empty: isEmptyTeam }"
      >
        <p v-if="isEmptyTeam" class="empty-msg">该组暂无患者与任务，可放心删除。</p>
        <template v-else>
          <p class="stat-line">组内患者：{{ impact.patientCount }} 人</p>
          <p class="stat-line">未完成待办：{{ impact.openTaskCount }} 项</p>
        </template>
      </div>
      <p class="tip">请输入健管组完整名称以确认删除：</p>
      <el-input
        v-model="confirmName"
        :placeholder="expectedName ? `请输入「${expectedName}」` : '请输入组名'"
        clearable
        @keyup.enter="nameMatched && confirmDelete()"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        class="confirm-delete-btn"
        :class="{ ready: nameMatched && !loadingImpact }"
        type="danger"
        :disabled="!nameMatched || loadingImpact"
        :loading="deleting"
        @click="confirmDelete"
      >
        确认删除
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 120px;
}
.warn {
  margin: 0 0 14px;
  line-height: 1.55;
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.warn.soft {
  color: var(--ink-600, #475569);
}
.stats {
  margin: 0 0 16px;
  padding: 12px 14px;
  border-radius: 8px;
}
.stats.danger {
  background: #fef2f2;
  border: 1px solid #fecaca;
}
.stats.empty {
  background: #f8f9fa;
  border: 1px solid #e5e7eb;
}
.stat-line {
  margin: 0;
  padding: 3px 0;
  font-size: 14px;
  line-height: 1.5;
  color: #7f1d1d;
  font-weight: 600;
}
.empty-msg {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink-600, #475569);
  font-weight: 500;
}
.tip {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
/* 未匹配：置灰不可点；组名完全一致：高亮危险红 */
.confirm-delete-btn.is-disabled,
.confirm-delete-btn:disabled {
  background: #e5e7eb !important;
  border-color: #e5e7eb !important;
  color: #9ca3af !important;
  opacity: 1 !important;
  cursor: not-allowed;
}
.confirm-delete-btn.ready:not(.is-disabled):not(:disabled) {
  background: #f56c6c !important;
  border-color: #f56c6c !important;
  color: #fff !important;
}
.confirm-delete-btn.ready:not(.is-disabled):not(:disabled):hover {
  background: #f78989 !important;
  border-color: #f78989 !important;
}
</style>
