<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { formatDiseaseName, formatDiseaseRows } from '../shared/archive-labels'
import { loadDictOptions, loadDiseaseDict, toLabelMap } from '../shared/dict'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const code = String(route.params.diseaseCode || '')
const content = ref<Record<string, unknown>>({})
const diseaseName = ref(formatDiseaseName(code))
const labelMaps = ref<Record<string, Record<string, string>>>({})

const rows = computed(() => formatDiseaseRows(code, content.value, labelMaps.value))

onMounted(async () => {
  try {
    const parents =
      code === 'diabetes'
        ? [
            'diabetesType',
            'diabetesSymptoms',
            'diabetesEmergencyComplications',
            'diabetesHypoglycemiaReaction',
          ]
        : code === 'hypertension'
          ? [
              'hypertensionType',
              'hypertensionGrade',
              'hypertensionCvRisk',
              'hypertensionSymptoms',
              'hypertensionEmergencyComplications',
            ]
          : []

    const [res, diseases, ...optLists] = await Promise.all([
      api<{ data: { contentJson?: Record<string, unknown> } }>(
        `/api/c/v1/me/archive/disease/${code}`,
      ),
      loadDiseaseDict().catch(() => []),
      ...parents.map((p) => loadDictOptions(p).catch(() => [])),
    ])

    content.value = (res.data.contentJson as Record<string, unknown>) || {}
    const dMap = toLabelMap(diseases)
    diseaseName.value = formatDiseaseName(code, dMap[code])
    const maps: Record<string, Record<string, string>> = {}
    parents.forEach((p, i) => {
      maps[p] = toLabelMap(optLists[i] || [])
    })
    labelMaps.value = maps
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <van-nav-bar :title="diseaseName" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else>
      <p class="hint">病种档案只读，由健管师维护。</p>
      <section class="card">
        <div v-for="row in rows" :key="row.label" class="row">
          <div class="label">{{ row.label }}</div>
          <div class="value">{{ row.value }}</div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--hx-bg); padding-bottom: 24px; }
.hint {
  margin: 12px 16px 0; color: var(--hx-muted); font-size: 13px; line-height: 1.5;
}
.card {
  margin: 12px 16px; padding: 16px; border-radius: 16px; background: #fff;
  box-shadow: var(--hx-shadow);
}
.row + .row { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f3f3; }
.label { color: var(--hx-muted); font-size: 12px; margin-bottom: 4px; }
.value { font-size: 14px; line-height: 1.55; word-break: break-word; white-space: pre-wrap; }
</style>
