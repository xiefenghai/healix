<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PatientMetricView from './PatientMetricView.vue'
import PatientMetricTrendView from './PatientMetricTrendView.vue'
import PatientLabView from './PatientLabView.vue'
import PatientExamView from './PatientExamView.vue'
import ObservationGlobalSearch from '../../shared/ObservationGlobalSearch.vue'
import {
  observationFocusQuery,
  type ObservationFocusTarget,
  type ObservationSearchHit,
} from '../../shared/observation-search'

const props = defineProps<{ peopleId?: string; initialSub?: string }>()

const route = useRoute()
const router = useRouter()
/** 驾驶舱抽屉等嵌入态：不走路由子页 */
const embedded = computed(() => !!props.peopleId)
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

type SubName = 'metrics' | 'trends' | 'labs' | 'exams'
const SUB_NAMES: readonly SubName[] = ['metrics', 'trends', 'labs', 'exams']

function initialLocalSub(): SubName {
  const s = props.initialSub as SubName | undefined
  return s && SUB_NAMES.includes(s) ? s : 'metrics'
}

const localSub = ref<SubName>(initialLocalSub())
const consumeOcrDraft = ref(false)
const labRemountKey = ref(0)
/** 嵌入态：搜索选中后通过 provide 下发聚焦目标 */
const embeddedFocus = ref<(ObservationFocusTarget & { nonce: number }) | null>(null)

const activeSub = computed(() => {
  if (embedded.value) return localSub.value
  const path = route.path
  if (path.includes('/trends')) return 'trends'
  if (path.includes('/labs')) return 'labs'
  if (path.includes('/exams')) return 'exams'
  return 'metrics'
})

const showGlobalSearch = computed(
  () => activeSub.value === 'metrics' || activeSub.value === 'labs' || activeSub.value === 'exams',
)

const SUB_NAV = [
  { name: 'trends' as const, label: '趋势分析' },
  { name: 'metrics' as const, label: '指标录入' },
  { name: 'labs' as const, label: '化验报告' },
  { name: 'exams' as const, label: '检查记录' },
]

watch(
  () => props.peopleId,
  () => {
    localSub.value = initialLocalSub()
    consumeOcrDraft.value = false
    embeddedFocus.value = null
  },
)

provide('observationEmbeddedFocus', embeddedFocus)

function switchSub(name: SubName) {
  if (embedded.value) {
    // 侧栏切换时不要沿用 OCR 草稿消费标记，否则会误触上传
    consumeOcrDraft.value = false
    localSub.value = name
    return
  }
  const base = `/workspace/patients/${peopleId.value}/observations`
  // 侧栏切换勿保留 ocr=1，否则化验报告页会反复自动弹上传框
  const query = { ...route.query }
  delete query.ocr
  delete query.focusKind
  delete query.focusCode
  delete query.focusPanel
  delete query.focusTs
  router.push({ path: `${base}/${name}`, query })
}

/** 指标录入页 OCR 成功后，嵌入态切到化验报告并消费草稿 */
function onMetricOcrReady() {
  consumeOcrDraft.value = true
  localSub.value = 'labs'
  labRemountKey.value += 1
}

function onGlobalSearchSelect(hit: ObservationSearchHit) {
  const focus: ObservationFocusTarget = {
    kind: hit.kind,
    code: hit.code,
    panel: hit.panel,
  }
  if (embedded.value) {
    embeddedFocus.value = { ...focus, nonce: Date.now() }
    localSub.value = hit.route
    if (hit.route === 'labs') labRemountKey.value += 1
    return
  }
  const base = `/workspace/patients/${peopleId.value}/observations`
  const query = { ...route.query }
  delete query.ocr
  Object.assign(query, observationFocusQuery(focus))
  router.push({ path: `${base}/${hit.route}`, query })
}
</script>

<template>
  <div class="obs-layout">
    <div class="obs-workspace">
      <nav class="obs-side-nav" aria-label="指标检验分区">
        <div class="nav-group">
          <button
            v-for="tab in SUB_NAV"
            :key="tab.name"
            type="button"
            class="nav-item"
            :class="{ active: activeSub === tab.name }"
            @click="switchSub(tab.name)"
          >
            {{ tab.label }}
          </button>
        </div>
      </nav>

      <div class="obs-content">
        <header v-if="showGlobalSearch" class="obs-global-bar">
          <ObservationGlobalSearch @select="onGlobalSearchSelect" />
        </header>
        <div class="obs-panel">
          <template v-if="embedded">
            <PatientMetricView
              v-if="activeSub === 'metrics'"
              :people-id="peopleId"
              @ocr-ready="onMetricOcrReady"
            />
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
      </div>
    </div>
  </div>
</template>

<style scoped>
.obs-layout {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  box-sizing: border-box;
}

.obs-workspace {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 24px;
  align-items: stretch;
}

.obs-side-nav {
  display: flex;
  flex-direction: column;
  padding: 0;
  min-height: 0;
  overflow-y: auto;
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: block;
  width: auto;
  margin: 0 12px;
  border: none;
  background: transparent;
  text-align: left;
  padding: 9px 12px 9px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600, #475569);
  cursor: pointer;
  box-sizing: border-box;
  position: relative;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}

.nav-item:hover {
  color: var(--ink-800, #1e293b);
  background: #f1f5f9;
}

.nav-item.active {
  color: var(--brand-600, #1e63d9);
  background: var(--brand-50, #eaf2ff);
  font-weight: 600;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 999px;
  background: var(--brand-500, #2c7ef8);
}

.obs-content {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 0;
  box-sizing: border-box;
  overflow: hidden;
  gap: 10px;
}

.obs-global-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  min-height: 36px;
}

.obs-panel {
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  height: 100%;
  display: flex;
  flex-direction: column;
  /* 指标录入页内部自滚动；其它子页可在此区域滚动 */
  overflow: auto;
}

.obs-panel > :deep(.metric-page),
.obs-panel > :deep(.lab-page) {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
}

</style>

<style>
/* 全局聚焦闪烁：跨子页共用 */
.obs-focus-flash,
.obs-focus-row > td {
  animation: obs-focus-pulse 1.6s ease-out 1;
}

@keyframes obs-focus-pulse {
  0% {
    box-shadow: inset 0 0 0 2px rgba(37, 99, 235, 0.55);
    background-color: #dbeafe !important;
  }
  100% {
    box-shadow: inset 0 0 0 0 transparent;
    background-color: transparent;
  }
}

@media (max-width: 900px) {
  .obs-layout .obs-workspace {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .obs-side-nav {
    position: static;
    padding: 0;
  }

  .nav-group {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 4px;
    align-items: center;
  }

  .nav-item {
    width: auto;
    margin: 0;
    padding: 8px 12px;
  }

  .nav-item.active::before {
    display: none;
  }

  .obs-global-bar {
    max-width: none;
  }
}
</style>
