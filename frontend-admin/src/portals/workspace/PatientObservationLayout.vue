<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiUpload } from '../../shared/http'
import { saveLabOcrDraft, type LabOcrDraft } from '../../shared/lab-ocr'

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

const activeSub = computed(() => {
  const path = route.path
  if (path.includes('/trends')) return 'trends'
  if (path.includes('/labs')) return 'labs'
  if (path.includes('/exams')) return 'exams'
  return 'metrics'
})

const ocrLoading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

function switchSub(name: string | number) {
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
      <el-tabs :model-value="activeSub" class="obs-tabs" @tab-change="switchSub">
        <el-tab-pane label="指标数据" name="metrics" />
        <el-tab-pane label="指标趋势" name="trends" />
        <el-tab-pane label="检验记录" name="labs" />
        <el-tab-pane label="检查记录" name="exams" />
      </el-tabs>
      <el-button type="primary" :loading="ocrLoading" @click="openOcrPicker">OCR 识别</el-button>
      <input
        ref="fileInputRef"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        class="hidden-input"
        @change="onOcrFileChange"
      />
    </div>
    <router-view />
  </div>
</template>

<style scoped>
.obs-layout {
  padding: 14px 20px 24px;
}

.obs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.obs-tabs {
  flex: 1;
  min-width: 280px;
}

.obs-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.hidden-input {
  display: none;
}
</style>
