<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { printCarePlan } from './care-plan-print'
import CarePlanPreviewContent, {
  type CarePlanPreviewData,
} from './CarePlanPreviewContent.vue'
import { isAiGeneratedCarePlanSource } from './care-plan-labels'

export type { CarePlanPreviewData, CarePlanPreviewCheckin } from './CarePlanPreviewContent.vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    data: CarePlanPreviewData | null
    /** 是否展示「编辑此方案 / 继续编辑」（列表详情流开启） */
    showEditActions?: boolean
  }>(),
  { showEditActions: false },
)

const route = useRoute()
const exporting = ref(false)
const contentResetKey = ref(0)

const exportName = computed(() => {
  const fromData = props.data?.peopleName?.trim()
  if (fromData) return fromData
  const queryName = route.query.name
  return typeof queryName === 'string' ? queryName.trim() : ''
})

const canEditActive = computed(
  () => props.showEditActions && props.data?.status === 'ACTIVE',
)
const canContinueDraft = computed(
  () => props.showEditActions && props.data?.status === 'DRAFT',
)

const isAiSource = computed(() => isAiGeneratedCarePlanSource(props.data?.source))

async function exportPdf() {
  if (!props.data || props.data.checkinsLoading) return
  exporting.value = true
  try {
    const ok = await printCarePlan({ ...props.data, peopleName: exportName.value || undefined })
    if (!ok) ElMessage.error('唤起打印失败，请检查浏览器设置')
  } finally {
    exporting.value = false
  }
}

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  edit: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) contentResetKey.value += 1
  },
)

function requestEdit() {
  emit('edit')
}

function sourceLabel(source?: string) {
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || '-'
}

function reviewBadge(data: CarePlanPreviewData) {
  if (data.status === 'DRAFT') return '待健管师确认'
  if (data.signStatus === 'SIGNED') return '医生已签署'
  return '健管师已审核'
}

function statusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || '-'
}

function statusTagType(status?: string): 'success' | 'warning' | 'info' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}
</script>

<template>
  <el-dialog
    v-model="visible"
    width="min(1200px, 96vw)"
    align-center
    destroy-on-close
    class="care-plan-preview-dialog"
    :show-close="true"
  >
    <template #header>
      <div class="dialog-header">
        <div class="dialog-title-wrap">
          <span v-if="exportName" class="patient-chip">患者：{{ exportName }}</span>
          <span class="dialog-title">方案详情</span>
        </div>
        <div v-if="data" class="dialog-tags">
          <el-tag size="small" :type="statusTagType(data.status)" effect="light" round>
            {{ statusLabel(data.status) }}
          </el-tag>
          <el-tag v-if="isAiSource" size="small" type="success" effect="plain" round>
            {{ reviewBadge(data) }}
          </el-tag>
          <el-tag v-else size="small" effect="plain" round>
            {{ sourceLabel(data.source) }}
          </el-tag>
        </div>
      </div>
    </template>

    <CarePlanPreviewContent
      v-if="data"
      fill-height
      :data="data"
      :reset-key="contentResetKey"
    />

    <template #footer>
      <div class="dialog-footer">
        <div class="dialog-footer-left">
          <el-button v-if="canContinueDraft" type="primary" @click="requestEdit">继续编辑</el-button>
          <el-button v-else-if="canEditActive" plain @click="requestEdit">编辑此方案</el-button>
        </div>
        <el-button
          type="primary"
          plain
          :loading="exporting"
          :disabled="!data || !!data.checkinsLoading"
          @click="exportPdf"
        >
          导出 PDF
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 28px;
}

.dialog-title-wrap {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  min-width: 0;
}

.patient-chip {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.dialog-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--admin-text, #1f2937);
}

.dialog-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dialog-footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
</style>

<style>
.care-plan-preview-dialog .el-dialog__header {
  margin-right: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border, #e5e7eb);
}

.care-plan-preview-dialog .el-dialog__body {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  padding-top: 12px;
  overflow: hidden;
}

.care-plan-preview-dialog .el-dialog__body .preview-shell {
  flex: 1 1 0;
  min-height: 0;
  max-height: none !important;
  height: auto !important;
  overflow: hidden;
}

.care-plan-preview-dialog .el-dialog__footer {
  border-top: 1px solid var(--admin-border, #e5e7eb);
  padding-top: 12px;
}
</style>
