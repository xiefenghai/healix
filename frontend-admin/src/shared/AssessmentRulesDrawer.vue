<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from './http'
import AssessmentTrendChart from './AssessmentTrendChart.vue'
import {
  type AssessmentRuleSheet,
  type AssessmentSnapshot,
  type RuleSheetSection,
  bulletHitStatus,
  hitRowPoints,
  isRiskMatrixSection,
  isRuleRowHit,
  isScoreHeader,
  patientResultHeadline,
  patientScoreSummary,
  riskMatrixHitCell,
  riskMatrixSummary,
  sectionHasBulletStatus,
  toneTagType,
} from './assessment'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 打开时定位到的引擎 */
    engineCode?: string
    /** 带入当次评估快照时，顶部展示得分并高亮命中行 */
    snapshot?: AssessmentSnapshot | null
    /** 同一次评估的多病种快照；切换左侧菜单时自动匹配 */
    snapshots?: AssessmentSnapshot[] | null
    /** 患者 ID；与 showTrend 一起用于展示变化趋势 */
    peopleId?: string
    /** 标签问号等入口：在规则明细上方保留变化趋势 */
    showTrend?: boolean
    /** drawer=侧栏；dialog=居中弹窗（标签问号试用） */
    presentation?: 'drawer' | 'dialog'
    title?: string
  }>(),
  {
    presentation: 'drawer',
    showTrend: false,
  },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const isDialog = computed(() => props.presentation === 'dialog')
const shellTitle = computed(
  () => props.title || (isDialog.value ? '评估依据' : '评估规则说明'),
)
const shell = computed(() => (isDialog.value ? 'el-dialog' : 'el-drawer'))
const shellProps = computed(() =>
  isDialog.value
    ? {
        title: shellTitle.value,
        width: 'min(960px, 94vw)',
        alignCenter: true,
        appendToBody: true,
        destroyOnClose: false,
        closeOnClickModal: true,
      }
    : {
        title: shellTitle.value,
        size: '780px',
      },
)

const sheets = ref<AssessmentRuleSheet[]>([])
const active = ref('')
const loading = ref(false)
const bodyRef = ref<HTMLElement | null>(null)
const navRef = ref<HTMLElement | null>(null)
const highlight = ref(false)
/** 折叠面板展开的 section title 列表 */
const openedPanels = ref<string[]>([])
let highlightTimer: ReturnType<typeof setTimeout> | null = null

async function load() {
  if (sheets.value.length) return
  loading.value = true
  try {
    const res = await api<{ data: AssessmentRuleSheet[] }>('/api/b/v1/assessment-rules')
    sheets.value = res.data
  } catch (e) {
    ElMessage.error((e as Error).message || '加载规则失败')
  } finally {
    loading.value = false
  }
}

function selectEngine(code: string) {
  active.value = code || sheets.value[0]?.engineCode || ''
}

function resetPanels() {
  const secs = current.value?.sheet.sections || []
  // 默认只展开前两个；若有命中分区则优先保证命中区展开
  const titles = secs.map((s) => s.title)
  const preferred = titles.slice(0, 2)
  const hitTitles = titles.filter((t) => {
    const sec = secs.find((s) => s.title === t)
    if (!sec || !activeSnapshot.value) return false
    if (sec.bullets?.length && sectionHasBulletStatus(t, activeSnapshot.value)) {
      return (sec.bullets || []).some((b) => bulletHitStatus(b, t, activeSnapshot.value) === 'hit')
    }
    if (sec.headers?.length && sec.rows?.length) {
      return (sec.rows || []).some((row) => isRuleRowHit(sec.headers, row, t, activeSnapshot.value))
    }
    return false
  })
  const merged = [...new Set([...preferred, ...hitTitles.slice(0, 2)])]
  openedPanels.value = merged.length ? merged : preferred
}

function expandAll() {
  openedPanels.value = (current.value?.sheet.sections || []).map((s) => s.title)
}

function collapseAll() {
  openedPanels.value = []
}

const allExpanded = computed(() => {
  const n = current.value?.sheet.sections?.length || 0
  return n > 0 && openedPanels.value.length >= n
})

async function focusEngine(code?: string) {
  selectEngine(code || props.engineCode || '')
  await nextTick()
  resetPanels()
  const activeBtn = navRef.value?.querySelector('.nav-item.active') as HTMLElement | null
  activeBtn?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  const el = bodyRef.value
  if (!el) return
  const pane =
    (el.closest('.el-drawer__body') as HTMLElement | null) ||
    (el.closest('.el-dialog__body') as HTMLElement | null)
  if (pane) pane.scrollTop = 0
  if (highlightTimer) clearTimeout(highlightTimer)
  highlight.value = false
  await nextTick()
  highlight.value = true
  highlightTimer = setTimeout(() => {
    highlight.value = false
  }, 1600)
}

watch(
  () => props.modelValue,
  async (v) => {
    if (!v) {
      highlight.value = false
      return
    }
    await load()
    await focusEngine(props.engineCode)
  },
)

watch(
  () => [props.engineCode, props.snapshot?.id, (props.snapshots || []).map((s) => s.id).join(',')] as const,
  async () => {
    if (!props.modelValue) return
    await focusEngine(props.engineCode)
  },
)

const grouped = computed(() => [
  { title: '患病风险评估', items: sheets.value.filter((s) => s.category === 'RISK') },
  { title: '疾病严重等级分层', items: sheets.value.filter((s) => s.category === 'SEVERITY') },
])

const current = computed(() => sheets.value.find((s) => s.engineCode === active.value) || null)

/** 当前引擎对应的患者快照（用于高亮与顶部摘要） */
const activeSnapshot = computed<AssessmentSnapshot | null>(() => {
  const list = props.snapshots?.length ? props.snapshots : props.snapshot ? [props.snapshot] : []
  const hit = list.find((s) => s.engineCode === active.value)
  if (hit) return hit
  if (props.snapshot && (!props.engineCode || props.snapshot.engineCode === active.value)) {
    return props.snapshot
  }
  return null
})

const resultHeadline = computed(() => patientResultHeadline(activeSnapshot.value))

const detailText = computed(() => {
  if (!activeSnapshot.value) return ''
  if (active.value === 'HYPERTENSION_SEVERITY') {
    return riskMatrixSummary(activeSnapshot.value)
  }
  // 顶部结论已展示等级时，明细不再重复「触发红标」
  return patientScoreSummary(activeSnapshot.value, {
    omitLevel: !!resultHeadline.value?.levelLabel,
  })
})

const levelTagType = computed(() => toneTagType(activeSnapshot.value?.tone))

function isMatrix(sec: RuleSheetSection) {
  return isRiskMatrixSection(sec.title, sec.headers)
}

function matrixHit(sec: RuleSheetSection) {
  if (!isMatrix(sec)) return null
  return riskMatrixHitCell(sec.headers, sec.rows, activeSnapshot.value)
}

function matrixSummary(sec: RuleSheetSection) {
  if (!isMatrix(sec) || !activeSnapshot.value) return ''
  return riskMatrixSummary(activeSnapshot.value)
}

function cellHit(sec: RuleSheetSection, rowIdx: number, colIdx: number) {
  const hit = matrixHit(sec)
  return !!hit && hit.row === rowIdx && hit.col === colIdx
}

function rowHit(sectionTitle: string, headers: string[] | null | undefined, row: string[]) {
  if (isRiskMatrixSection(sectionTitle, headers)) return false
  return isRuleRowHit(headers, row, sectionTitle, activeSnapshot.value)
}

function rowPoints(sectionTitle: string, headers: string[] | null | undefined, row: string[]) {
  return hitRowPoints(headers, row, sectionTitle, activeSnapshot.value)
}

function bulletStatus(sectionTitle: string, bullet: string) {
  return bulletHitStatus(bullet, sectionTitle, activeSnapshot.value)
}

function showBulletTags(sectionTitle: string) {
  return sectionHasBulletStatus(sectionTitle, activeSnapshot.value)
}

/** 高危人群因素：只标命中，不铺满「无」 */
function showHitOnlyTags(sectionTitle: string) {
  return /高危人群|高危因素/.test(sectionTitle || '')
}

function sectionHasHit(sec: RuleSheetSection): boolean {
  if (!activeSnapshot.value) return false
  if (isMatrix(sec) && matrixHit(sec)) return true
  if (sec.headers?.length && (sec.rows || []).some((row) => rowHit(sec.title, sec.headers, row))) return true
  if (sec.bullets?.length && showBulletTags(sec.title)) {
    return (sec.bullets || []).some((b) => bulletStatus(sec.title, b) === 'hit')
  }
  return false
}
</script>

<template>
  <component
    :is="shell"
    v-model="open"
    v-bind="shellProps"
    :class="isDialog ? 'rules-dialog' : 'rules-drawer'"
  >
    <div v-loading="loading" class="rules" :class="{ 'is-dialog': isDialog }">
      <aside ref="navRef" class="nav">
        <div v-for="g in grouped" :key="g.title" class="nav-group">
          <div class="nav-title">{{ g.title }}</div>
          <div class="nav-list">
            <button
              v-for="s in g.items"
              :key="s.engineCode"
              type="button"
              class="nav-item"
              :class="{ active: s.engineCode === active }"
              @click="focusEngine(s.engineCode)"
            >
              <span class="nav-item-label">{{ s.engineLabel }}</span>
              <span class="nav-item-disease">{{ s.diseaseLabel }}</span>
            </button>
          </div>
        </div>
      </aside>

      <article
        v-if="current"
        ref="bodyRef"
        class="body"
        :class="{ 'body-flash': highlight }"
      >
        <header class="body-head">
          <h3>{{ current.engineLabel }}</h3>
          <div class="meta">
            <span v-if="current.guidelineName">依据：{{ current.guidelineName }}</span>
            <span>规则包 {{ current.rulePackVersion }}</span>
          </div>
        </header>

        <div v-if="resultHeadline" class="result-headline">
          <template v-if="resultHeadline.score != null">
            <span>患者当前累计得分：</span>
            <strong class="score-num">{{ resultHeadline.score }}分</strong>
            <template v-if="resultHeadline.levelLabel">
              <span>（属于：</span>
              <el-tag
                size="small"
                :type="levelTagType"
                effect="dark"
                round
                class="level-pill"
              >
                {{ resultHeadline.levelLabel }}
              </el-tag>
              <span>）</span>
            </template>
          </template>
          <template v-else>
            <span>患者当前评估结果：</span>
            <el-tag
              v-if="resultHeadline.levelLabel"
              size="small"
              :type="levelTagType"
              effect="dark"
              round
              class="level-pill"
            >
              {{ resultHeadline.levelLabel }}
            </el-tag>
          </template>
        </div>

        <p v-if="showTrend && activeSnapshot?.advice" class="snap-advice">
          {{ activeSnapshot.advice }}
        </p>

        <AssessmentTrendChart
          v-if="showTrend && peopleId && active"
          :people-id="peopleId"
          :engine-code="active"
          :current-id="activeSnapshot?.id"
        />

        <div
          v-if="showTrend && activeSnapshot?.missing?.length"
          class="snap-missing"
        >
          <div class="snap-missing-title">还缺这些数据，补齐后会重新评估</div>
          <div
            v-for="m in activeSnapshot.missing"
            :key="m.field"
            class="snap-missing-item"
          >
            {{ m.label }}<span v-if="m.fillHint"> · {{ m.fillHint }}</span>
          </div>
        </div>

        <div v-if="detailText && detailText !== resultHeadline?.text" class="score-banner">
          <div class="score-banner-label">得分明细</div>
          <p class="score-banner-text">{{ detailText }}</p>
        </div>

        <el-alert
          v-if="current.provisional"
          type="warning"
          :closable="false"
          show-icon
          title="暂定口径，待医学团队校准"
          class="gap"
        />
        <p v-if="current.sheet.summary" class="summary">{{ current.sheet.summary }}</p>
        <div class="levels">
          <el-tag v-for="l in current.levels" :key="l.code" :type="toneTagType(l.tone)" effect="light">
            {{ l.label }}<template v-if="l.tierLabel"> · {{ l.tierLabel }}</template>
          </el-tag>
        </div>

        <div class="panel-toolbar">
          <span class="panel-toolbar-hint">规则明细</span>
          <button
            type="button"
            class="panel-toolbar-btn"
            :title="allExpanded ? '一键收起全部' : '一键展开全部'"
            @click="allExpanded ? collapseAll() : expandAll()"
          >
            <span class="panel-toolbar-ico" aria-hidden="true">{{ allExpanded ? '▴▴' : '▾▾' }}</span>
            <span class="panel-toolbar-txt">{{ allExpanded ? '收起全部' : '展开全部' }}</span>
          </button>
        </div>

        <el-collapse v-model="openedPanels" class="sec-collapse">
          <el-collapse-item
            v-for="sec in current.sheet.sections"
            :key="sec.title"
            :name="sec.title"
            :title="sec.title"
          >
            <template #title>
              <div class="collapse-title">
                <span>{{ sec.title }}</span>
                <el-tag v-if="sectionHasHit(sec)" size="small" type="primary" effect="plain">有命中</el-tag>
              </div>
            </template>

            <div class="sec-body">
              <p v-if="matrixSummary(sec)" class="matrix-summary">{{ matrixSummary(sec) }}</p>

              <div v-if="sec.headers?.length" class="tbl-wrap">
                <table class="tbl" :class="{ 'tbl-matrix': isMatrix(sec) }">
                  <thead>
                    <tr>
                      <th
                        v-for="h in sec.headers"
                        :key="h"
                        :class="{ 'col-score': isScoreHeader(h) }"
                      >
                        {{ h }}
                      </th>
                      <th v-if="!isMatrix(sec)" class="col-hit">命中</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(row, i) in sec.rows || []"
                      :key="i"
                      :class="{ hit: rowHit(sec.title, sec.headers, row) }"
                    >
                      <td
                        v-for="(cell, j) in row"
                        :key="j"
                        :class="{
                          'col-score': isScoreHeader(sec.headers?.[j] || ''),
                          'cell-hit': cellHit(sec, i, j),
                        }"
                      >
                        {{ cell }}
                      </td>
                      <td v-if="!isMatrix(sec)" class="col-hit">
                        <span
                          v-if="rowHit(sec.title, sec.headers, row) && rowPoints(sec.title, sec.headers, row) != null"
                          class="hit-score"
                        >
                          当前得分：{{ rowPoints(sec.title, sec.headers, row) }} 分
                        </span>
                        <span
                          v-else-if="rowHit(sec.title, sec.headers, row)"
                          class="hit-score"
                        >当前命中</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <ul
                v-if="sec.bullets?.length"
                class="bullets"
                :class="{ 'bullets-status': showBulletTags(sec.title) }"
              >
                <li v-for="b in sec.bullets" :key="b" class="bullet-item">
                  <span class="bullet-text">{{ b }}</span>
                  <template v-if="showBulletTags(sec.title)">
                    <span
                      v-if="bulletStatus(sec.title, b) === 'hit'"
                      class="status-tag hit"
                    >命中</span>
                    <span
                      v-else-if="!showHitOnlyTags(sec.title)"
                      class="status-tag miss"
                    >无</span>
                  </template>
                </li>
              </ul>
            </div>
          </el-collapse-item>
        </el-collapse>

        <ul v-if="current.sheet.notes?.length" class="notes">
          <li v-for="n in current.sheet.notes" :key="n">{{ n }}</li>
        </ul>
      </article>
    </div>
    <template v-if="isDialog" #footer>
      <div class="dialog-foot">
        <span class="dialog-foot-note">结果仅供健康管理参考，不作为诊断依据。</span>
        <el-button @click="open = false">关闭</el-button>
      </div>
    </template>
  </component>
</template>

<style scoped>
.rules {
  display: flex;
  gap: 18px;
  min-height: 420px;
  align-items: stretch;
}
.rules.is-dialog {
  min-height: min(62vh, 560px);
  max-height: min(72vh, 720px);
  overflow: hidden;
}
.rules.is-dialog .nav {
  overflow: auto;
  padding-right: 4px;
}
.rules.is-dialog .body {
  overflow: auto;
  padding-right: 8px;
}
.dialog-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.dialog-foot-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.nav {
  width: 200px;
  flex-shrink: 0;
  padding: 4px 2px 12px;
}
.nav-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin: 0 0 10px;
  letter-spacing: 0.02em;
}
.nav-group + .nav-group {
  margin-top: 22px;
}
.nav-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.nav-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  background: #f8fafc;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}
.nav-item:hover {
  background: #f1f5f9;
  border-color: #e2e8f0;
}
.nav-item.active {
  background: #eff6ff;
  border-color: #bfdbfe;
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}
.nav-item-label {
  color: var(--ink-800, #1e293b);
  font-weight: 600;
  line-height: 1.35;
}
.nav-item.active .nav-item-label {
  color: var(--el-color-primary);
}
.nav-item-disease {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.body {
  flex: 1;
  min-width: 0;
  border-radius: 12px;
  padding: 4px 4px 16px;
  transition: background-color 0.35s ease, box-shadow 0.35s ease;
}
.body-flash {
  background: #f8fbff;
  box-shadow: 0 0 0 2px #93c5fd;
}
.body-head h3 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
}
.result-headline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  font-size: 14px;
  line-height: 1.6;
  color: #873800;
}
.score-num {
  font-size: 18px;
  font-weight: 750;
  color: #d46b08;
  margin: 0 2px;
}
.level-pill {
  font-weight: 700;
  vertical-align: middle;
}
.snap-advice {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-800, #1e293b);
}
.snap-missing {
  margin: 0 0 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  font-size: 13px;
  line-height: 1.6;
}
.snap-missing-title {
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--ink-800, #1e293b);
}
.snap-missing-item {
  color: var(--el-text-color-secondary);
}
.score-banner {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
}
.score-banner-label {
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  margin-bottom: 4px;
  letter-spacing: 0.04em;
}
.score-banner-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: #94a3b8;
  font-weight: 400;
}
.gap {
  margin-bottom: 12px;
}
.summary {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
}
.levels {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
  padding-right: 16px;
}
.panel-toolbar-hint {
  font-size: 12px;
  font-weight: 650;
  color: var(--el-text-color-secondary);
}
.panel-toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  padding: 2px 0 2px 16px;
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}
.panel-toolbar-btn:hover {
  color: var(--el-color-primary);
}
.panel-toolbar-ico {
  font-size: 11px;
  letter-spacing: -1px;
  line-height: 1;
  opacity: 0.75;
}
.panel-toolbar-txt {
  font-weight: 500;
}
.sec-collapse {
  border: 0;
  --el-collapse-header-height: 44px;
}
.sec-collapse :deep(.el-collapse-item) {
  margin-bottom: 8px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.sec-collapse :deep(.el-collapse-item__header) {
  padding: 0 12px;
  border-bottom: 0;
  background: #fafbfc;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
}
.sec-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
.sec-collapse :deep(.el-collapse-item__content) {
  padding: 0 12px 12px;
}
.collapse-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.sec-body {
  padding-top: 4px;
}
.matrix-summary {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f0f9ff;
  border: 1px dashed #91d5ff;
  font-size: 12px;
  line-height: 1.65;
  color: #0958d9;
  font-weight: 600;
}
.tbl-wrap {
  overflow: auto;
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.tbl th,
.tbl td {
  border: 1px solid #e8edf3;
  padding: 8px 10px;
  text-align: left;
  vertical-align: middle;
}
.tbl th {
  background: #f8f9fa;
  font-weight: 600;
  color: #475569;
}
.tbl th.col-score,
.tbl td.col-score {
  text-align: right;
  font-variant-numeric: tabular-nums;
  width: 72px;
}
.tbl th.col-hit,
.tbl td.col-hit {
  width: 118px;
  text-align: right;
  white-space: nowrap;
}
.tbl tbody tr.hit td {
  background: #e6f7ff;
  color: #0958d9;
  font-weight: 700;
}
.tbl tbody tr.hit td:first-child {
  box-shadow: inset 3px 0 0 #1890ff;
}
.tbl td.cell-hit {
  background: #e6f7ff !important;
  color: #0958d9 !important;
  font-weight: 700;
  box-shadow: inset 0 0 0 1px #91d5ff;
}
.hit-score {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  color: #0958d9;
  background: #bae7ff;
  border-radius: 4px;
  padding: 2px 6px;
}
.tbl-matrix td:first-child {
  background: #fafbfc;
  font-weight: 600;
  color: #334155;
  min-width: 140px;
}
.bullets {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}
.bullets-status {
  list-style: none;
  padding-left: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.bullet-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 8px;
  background: #fafbfc;
}
.bullet-text {
  flex: 1;
  min-width: 0;
  line-height: 1.55;
}
.status-tag {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 650;
  line-height: 20px;
}
.status-tag.hit {
  background: #fff1f0;
  color: #cf1322;
  border: 1px solid #ffa39e;
}
.status-tag.miss {
  background: #f5f5f5;
  color: #bfbfbf;
  border: 1px solid #f0f0f0;
}
.notes {
  margin: 12px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
</style>

<style>
.el-dialog.rules-dialog {
  border-radius: 12px;
  max-width: 960px;
}
.el-dialog.rules-dialog .el-dialog__header {
  padding-bottom: 10px;
  margin-right: 0;
}
.el-dialog.rules-dialog .el-dialog__body {
  padding-top: 4px;
  padding-bottom: 4px;
}
.el-dialog.rules-dialog .el-dialog__footer {
  padding-top: 10px;
}
</style>
