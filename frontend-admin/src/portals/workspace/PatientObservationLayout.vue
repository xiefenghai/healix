<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiUpload } from '../../shared/http'
import { saveLabOcrDraft, type LabOcrDraft } from '../../shared/lab-ocr'
import PatientMetricView from './PatientMetricView.vue'
import PatientMetricTrendView from './PatientMetricTrendView.vue'
import PatientLabView from './PatientLabView.vue'
import PatientExamView from './PatientExamView.vue'

const props = defineProps<{ peopleId?: string }>()

const route = useRoute()
const router = useRouter()
/** 驾驶舱抽屉等嵌入态：不走路由子页 */
const embedded = computed(() => !!props.peopleId)
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const localSub = ref<'metrics' | 'trends' | 'labs' | 'exams'>('metrics')
const consumeOcrDraft = ref(false)
const labRemountKey = ref(0)

const activeSub = computed(() => {
  if (embedded.value) return localSub.value
  const path = route.path
  if (path.includes('/trends')) return 'trends'
  if (path.includes('/labs')) return 'labs'
  if (path.includes('/exams')) return 'exams'
  return 'metrics'
})

const ocrLoading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

const SUB_TABS = [
  { name: 'metrics', label: '指标录入' },
  { name: 'trends', label: '趋势分析' },
  { name: 'labs', label: '化验报告' },
  { name: 'exams', label: '检查记录' },
] as const

watch(
  () => props.peopleId,
  () => {
    localSub.value = 'metrics'
    consumeOcrDraft.value = false
  },
)

function switchSub(name: string) {
  if (embedded.value) {
    localSub.value = name as typeof localSub.value
    return
  }
  const base = `/workspace/patients/${peopleId.value}/observations`
  router.push({ path: `${base}/${name}`, query: route.query })
}

function openOcrPicker() {
  fileInputRef.value?.click()
}

async function onOcrFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !peopleId.value) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  ocrLoading.value = true
  try {
    const res = await apiUpload<{ data: LabOcrDraft }>(
      `/api/b/v1/patients/${peopleId.value}/lab-reports/ocr`,
      file,
    )
    const data = res.data
    if (!data.items?.length) {
      const ignored = data.ignoredItems?.map((i) => i.rawName).join('、') || ''
      ElMessage.warning(
        ignored ? `未识别到可录入项目，已忽略：${ignored}` : '未识别到可录入的检验项目',
      )
      return
    }
    saveLabOcrDraft(data)
    if (data.warnings?.length) {
      ElMessage.info(data.warnings.join('；'))
    }
    if (embedded.value) {
      consumeOcrDraft.value = true
      localSub.value = 'labs'
      labRemountKey.value += 1
      return
    }
    router.push({
      path: `/workspace/patients/${peopleId.value}/observations/labs`,
      query: { ...route.query, ocr: '1' },
    })
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}
</script>

<template>
  <div class="obs-layout">
    <div class="obs-toolbar">
      <div class="sub-tabs" role="tablist">
        <button
          v-for="tab in SUB_TABS"
          :key="tab.name"
          type="button"
          class="sub-tab"
          :class="{ active: activeSub === tab.name }"
          role="tab"
          :aria-selected="activeSub === tab.name"
          @click="switchSub(tab.name)"
        >
          {{ tab.label }}
        </button>
      </div>
      <el-button type="primary" :loading="ocrLoading" @click="openOcrPicker">OCR 识别</el-button>
      <input
        ref="fileInputRef"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        class="hidden-input"
        @change="onOcrFileChange"
      />
    </div>
    <template v-if="embedded">
      <PatientMetricView v-if="activeSub === 'metrics'" :people-id="peopleId" />
      <PatientMetricTrendView v-else-if="activeSub === 'trends'" :people-id="peopleId" />
      <PatientLabView
        v-else-if="activeSub === 'labs'"
        :key="labRemountKey"
        :people-id="peopleId"
        :consume-ocr-draft="consumeOcrDraft"
      />
      <PatientExamView v-else-if="activeSub === 'exams'" :people-id="peopleId" />
    </template>
    <router-view v-else />
  </div>
</template>

<style scoped>
.obs-layout {
  padding: 8px 0 24px;
}

.obs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.sub-tabs {
  display: flex;
  gap: 6px;
  background: #fff;
  padding: 6px;
  border-radius: 10px;
  border: 1px solid var(--admin-border, #e2e8f0);
  box-shadow: var(--admin-shadow);
  width: fit-content;
  max-width: 100%;
  overflow-x: auto;
}

.sub-tab {
  margin: 0;
  padding: 8px 16px;
  border: none;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600, #475569);
  background: transparent;
  cursor: pointer;
  white-space: nowrap;
  transition:
    color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
}

.sub-tab:hover {
  color: var(--ink-800, #1e293b);
}

.sub-tab.active {
  background: var(--brand-500);
  color: #fff;
  box-shadow: 0 4px 8px rgba(44, 126, 248, 0.25);
}

.hidden-input {
  display: none;
}
</style>
