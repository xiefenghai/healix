<script setup lang="ts">
/**
 * 已提交首诊问卷只读详情：直接展示当时填写内容（快照优先）、高亮与 AI 总结。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  FIELD_STAFF_AI_SUMMARY,
  formatAnswerBrief,
  getQuestionnaireResponse,
  isAnswerEmpty,
  loadIntakeOptionMaps,
  resolveIntakeFieldLabel,
  responseStatusLabel,
  severityLabel,
  type IntakeHighlightHit,
  type IntakeItemView,
  type IntakeModuleView,
  type IntakeOptionMaps,
  type QuestionnaireResponseView,
} from '../../shared/intake'
import { loadAllergyCatalog } from '../../shared/allergy'
import { api } from '../../shared/http'
import IntakeAiSummaryBlocks from './IntakeAiSummaryBlocks.vue'

const props = withDefaults(
  defineProps<{
    /** 抽屉嵌入时传入；缺省走路由 params */
    responseId?: string
    /** 嵌入抽屉：隐藏返回患者 / 再次首诊 */
    embedded?: boolean
  }>(),
  { responseId: '', embedded: false },
)

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const view = ref<QuestionnaireResponseView | null>(null)
const patientName = ref('')
const optionMaps = ref<IntakeOptionMaps>({})

const responseId = computed(() => String(props.responseId || route.params.responseId || ''))

/** 只读详情优先用提交快照，保证展示「当时填写」 */
const answers = computed(() => {
  const snapAnswers = view.value?.snapshot?.answers
  if (snapAnswers && typeof snapAnswers === 'object') return snapAnswers
  return (view.value?.answers || {}) as Record<string, unknown>
})

/** 仅展示当时已填项 */
const visibleModules = computed(() => {
  const mods = view.value?.modules || []
  return mods
    .map((m) => ({
      ...m,
      items: (m.items || []).filter(
        (it) => it.field !== FIELD_STAFF_AI_SUMMARY && !isAnswerEmpty(answers.value[it.field]),
      ),
    }))
    .filter((m) => m.items.length > 0)
})

const aiSummary = computed(() => {
  const v = answers.value[FIELD_STAFF_AI_SUMMARY]
  return isAnswerEmpty(v) ? '' : String(v)
})

const highlights = computed(() => {
  const snapHl = view.value?.snapshot?.highlights
  if (Array.isArray(snapHl) && snapHl.length) return snapHl
  return (view.value?.highlights || []) as IntakeHighlightHit[]
})

const fieldLabelMap = computed(() => {
  const m = new Map<string, string>()
  for (const mod of view.value?.modules || []) {
    for (const it of mod.items || []) m.set(it.field, it.label)
  }
  return m
})

function labelOf(field: string, hitLabel?: string | null) {
  return resolveIntakeFieldLabel(field, {
    hitLabel,
    moduleLabel: fieldLabelMap.value.get(field),
  })
}

function displayAnswer(it: IntakeItemView) {
  return formatAnswerBrief(answers.value[it.field], it, optionMaps.value)
}

function formatTime(iso?: string | null) {
  if (!iso) return '—'
  return String(iso).replace('T', ' ').slice(0, 16)
}

function moduleFilled(m: IntakeModuleView) {
  return (m.items || []).length
}

async function load() {
  if (!responseId.value) return
  loading.value = true
  try {
    const data = await getQuestionnaireResponse(responseId.value)
    view.value = data
    const [maps, allergyCat] = await Promise.all([
      loadIntakeOptionMaps(data.modules),
      loadAllergyCatalog().catch(() => null),
    ])
    // 过敏反应/类型并入选项表，详情展示用中文
    if (allergyCat) {
      maps.allergyReaction = (allergyCat.reactions || []).map((r) => ({
        code: r.code,
        label: r.label,
      }))
      maps.allergyType = (allergyCat.types || []).map((t) => ({
        code: t.code,
        label: t.label,
      }))
      maps.allergySeverity = (allergyCat.severities || []).map((s) => ({
        code: s.code,
        label: s.label,
      }))
    }
    optionMaps.value = maps
    try {
      const res = await api<{ data: { displayName?: string } }>(`/api/b/v1/patients/${data.peopleId}`)
      patientName.value = res.data?.displayName || data.peopleId
    } catch {
      patientName.value = data.peopleId
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载首诊详情失败')
  } finally {
    loading.value = false
  }
}

function goPatient() {
  const pid = view.value?.peopleId
  if (!pid) return
  void router.push(`/workspace/patients/${pid}/archive`)
}

function openFillAgain() {
  const pid = view.value?.peopleId
  if (!pid) return
  void router.push(`/workspace/patients/${pid}/intake`)
}

onMounted(() => {
  void load()
})

watch(responseId, (id, prev) => {
  if (id && id !== prev) void load()
})
</script>

<template>
  <div class="intake-detail" :class="{ embedded }" v-loading="loading">
    <header class="top">
      <div class="top-left">
        <el-button v-if="!embedded" text @click="goPatient">← 返回患者</el-button>
        <div class="titles">
          <h1>{{ patientName || '患者' }}</h1>
          <span class="sub">首诊问卷详情（只读）</span>
        </div>
      </div>
      <div class="top-right">
        <el-tag v-if="view?.status" size="small" effect="plain">{{ responseStatusLabel(view.status) }}</el-tag>
        <span class="meta">提交于 {{ formatTime(view?.submittedAt) }}</span>
        <el-button v-if="!embedded" type="primary" plain @click="openFillAgain">再次首诊</el-button>
      </div>
    </header>

    <div v-if="view" class="body">
      <aside class="nav">
        <a
          v-for="(m, idx) in visibleModules"
          :key="m.code"
          class="nav-item"
          :href="`#mod-${m.code}`"
        >
          <span class="nav-idx">{{ idx + 1 }}</span>
          <span class="nav-copy">
            <span class="nav-title">{{ m.title }}</span>
            <span class="nav-fill">{{ moduleFilled(m) }} 项</span>
          </span>
        </a>
      </aside>

      <main class="main">
        <section v-if="aiSummary" class="card ai-card">
          <h2>AI 总结</h2>
          <IntakeAiSummaryBlocks :text="aiSummary" />
        </section>

        <section v-if="highlights.length" class="card">
          <h2>高亮</h2>
          <ul class="hl">
            <li v-for="(h, i) in highlights" :key="`${h.field}-${i}`">
              <strong>{{ labelOf(h.field, h.label) }}</strong>
              · {{ severityLabel(h.severity) }}
              <span v-if="h.planEffect"> — {{ h.planEffect }}</span>
            </li>
          </ul>
        </section>

        <section
          v-for="m in visibleModules"
          :id="`mod-${m.code}`"
          :key="m.code"
          class="card mod"
        >
          <h2>{{ m.title }}</h2>
          <div v-for="it in m.items" :key="it.field" class="q">
            <div class="q-label">
              {{ it.label }}
              <span v-if="it.level === 'STAFF'" class="tag">内部</span>
            </div>
            <div class="q-val">{{ displayAnswer(it) }}</div>
          </div>
        </section>

        <el-empty
          v-if="!aiSummary && !highlights.length && !visibleModules.length"
          description="该次首诊没有已填内容"
        />
      </main>
    </div>
    <el-empty v-else-if="!loading" description="未找到作答" />
  </div>
</template>

<style scoped>
.intake-detail {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--ink-50);
}

.intake-detail.embedded {
  min-height: calc(100vh - 120px);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--ink-200);
  background: #fff;
}

.intake-detail.embedded .top {
  padding: 6px 12px;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.titles h1 {
  margin: 0;
  font-size: 18px;
  color: var(--ink-800);
}

.sub {
  font-size: 12px;
  color: var(--ink-400);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.meta {
  font-size: 12px;
  color: var(--ink-500);
}

.body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 0;
}

.nav {
  overflow: auto;
  padding: 12px;
  border-right: 1px solid var(--ink-200);
  background: #fff;
}

.nav-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 8px 10px;
  border-radius: 8px;
  color: inherit;
  text-decoration: none;
  margin-bottom: 4px;
}

.nav-item:hover {
  background: var(--ink-50);
}

.nav-idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--ink-100);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.nav-title {
  display: block;
  font-size: 13px;
  color: var(--ink-800);
}

.nav-fill {
  font-size: 11px;
  color: var(--ink-400);
}

.main {
  overflow: auto;
  padding: 16px 20px 40px;
}

.intake-detail.embedded .main {
  padding: 10px 16px 28px;
}

.intake-detail.embedded .nav {
  padding: 8px;
}

.card {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
}

.card h2 {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--ink-800);
}

.ai-card {
  border-color: #99f6e4;
  background: #f0fdfa;
}

.q {
  padding: 10px 0;
  border-top: 1px solid var(--ink-100);
}

.q:first-of-type {
  border-top: none;
}

.q-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 4px;
}

.tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400);
}

.q-val {
  font-size: 13px;
  color: var(--ink-800);
  line-height: 1.5;
  word-break: break-word;
}

.hl {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-700);
}

@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
  }
  .nav {
    display: none;
  }
}
</style>

<!-- append-to-body 抽屉壳层 -->
<style>
.intake-detail-drawer.el-drawer .el-drawer__header {
  margin-bottom: 0;
  padding-bottom: 0;
}

.intake-detail-drawer.el-drawer .el-drawer__body {
  padding-top: 0;
}
</style>
