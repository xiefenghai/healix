<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { toast } from '../../shared/toast'

export interface BatchServePatient {
  peopleId: string
  displayName: string
  age?: number | null
  diseaseLabels?: string[]
  /** new | review | plan | active | other */
  stage: 'new' | 'review' | 'plan' | 'active' | 'other'
  stageLabel: string
  ctaText?: string
  careTeamId?: string
  careTeamName?: string
}

export interface BatchServeTeamOption {
  id: string
  name: string
  count: number
}

type StageFilter = 'all' | 'intake' | 'stock'
/** 根据勾选人群推断底部主操作 */
type BatchActionMode = 'empty' | 'intake' | 'stock' | 'mixed'

/** 全部患者（script setup 不可 export 值，Cockpit 侧用同名常量对齐） */
const BATCH_SERVE_TEAM_ALL = '__ALL__'
/** 与驾驶舱未分组虚拟组对齐 */
const UNASSIGNED_TEAM_ID = '__UNASSIGNED__'

const props = defineProps<{
  modelValue: boolean
  patients: BatchServePatient[]
  /** 健管组下拉（含全部 / 各组 / 公共池） */
  teams?: BatchServeTeamOption[]
  careTeamName?: string | null
  /** 打开时默认选中的健管组 id；空则全部 */
  initialCareTeamId?: string | null
  /** 打开时默认筛选：存量 / 待首诊 / 全部 */
  initialFilter?: StageFilter | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 批量发问卷（仅待首诊） */
  'send-intake': [peopleIds: string[]]
  /** 切入单人服务 */
  focus: [peopleId: string]
  /** 存量批量跟进提示 */
  'batch-follow': [peopleIds: string[]]
  /** 存量批量生成管理方案 */
  'batch-plan': [peopleIds: string[]]
  /** 批量转移组成功 */
  transferred: [
    payload: {
      peopleIds: string[]
      toCareTeamId: string
      toCareTeamName: string
    },
  ]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const keyword = ref('')
const stageFilter = ref<StageFilter>('all')
const selectedTeamId = ref<string>(BATCH_SERVE_TEAM_ALL)
const selectedIds = ref<string[]>([])
/** 与左侧列表一致：默认只渲染 10 条，展开再加页 */
const PAGE = 10
const shownLimit = ref(PAGE)
/** 打开抽屉时的首次赋值不触发「切 Tab/组 清空」 */
const suppressClear = ref(false)

const teamOptions = computed((): BatchServeTeamOption[] => {
  if (props.teams?.length) return props.teams
  // 兜底：从患者推导
  const map = new Map<string, BatchServeTeamOption>()
  for (const p of props.patients) {
    const id = (p.careTeamId || '').trim() || '_unknown'
    const name = (p.careTeamName || '').trim() || '未命名组'
    const cur = map.get(id)
    if (cur) cur.count += 1
    else map.set(id, { id, name, count: 1 })
  }
  return [
    { id: BATCH_SERVE_TEAM_ALL, name: '全部患者', count: props.patients.length },
    ...map.values(),
  ]
})

const showTeamBadge = computed(() => selectedTeamId.value === BATCH_SERVE_TEAM_ALL)

const activeTeamLabel = computed(() => {
  const hit = teamOptions.value.find((t) => t.id === selectedTeamId.value)
  return hit?.name || (props.careTeamName || '').trim() || '健管组'
})

/** 按健管组圈定后的花名册 */
const scopedPatients = computed(() => {
  if (selectedTeamId.value === BATCH_SERVE_TEAM_ALL) return props.patients
  return props.patients.filter((p) => (p.careTeamId || '') === selectedTeamId.value)
})

function isIntakeStage(stage: BatchServePatient['stage']) {
  return stage === 'new' || stage === 'review'
}

function isStockStage(stage: BatchServePatient['stage']) {
  return stage === 'plan' || stage === 'active'
}

const stats = computed(() => {
  let intake = 0
  let stock = 0
  for (const p of scopedPatients.value) {
    if (isIntakeStage(p.stage)) intake++
    else if (isStockStage(p.stage)) stock++
  }
  return { total: scopedPatients.value.length, intake, stock }
})

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return scopedPatients.value.filter((p) => {
    if (stageFilter.value === 'intake' && !isIntakeStage(p.stage)) return false
    if (stageFilter.value === 'stock' && !isStockStage(p.stage)) return false
    if (!q) return true
    const name = (p.displayName || '').toLowerCase()
    if (name.includes(q)) return true
    const team = (p.careTeamName || '').toLowerCase()
    if (team.includes(q)) return true
    return (p.diseaseLabels || []).some((t) => (t || '').toLowerCase().includes(q))
  })
})

const visibleRows = computed(() => filtered.value.slice(0, shownLimit.value))
const remainCount = computed(() => Math.max(0, filtered.value.length - shownLimit.value))

const selectedCount = computed(() => selectedIds.value.length)

/** 按全量花名册解析勾选态，避免 scoped 过滤导致状态机误判为 empty */
const selectedPatients = computed(() =>
  selectedIds.value
    .map((id) => props.patients.find((x) => x.peopleId === id))
    .filter((p): p is BatchServePatient => !!p),
)

const selectedIntakeIds = computed(() =>
  selectedPatients.value.filter((p) => isIntakeStage(p.stage)).map((p) => p.peopleId),
)

const selectedStockIds = computed(() =>
  selectedPatients.value.filter((p) => isStockStage(p.stage)).map((p) => p.peopleId),
)

const selectedIntakeCount = computed(() => selectedIntakeIds.value.length)
const selectedStockCount = computed(() => selectedStockIds.value.length)

/**
 * 勾选人群业务态 → 底部主按钮：
 * empty 置灰「请先勾选」；intake 发卷；stock 生成方案；mixed 提示分批。
 */
const batchActionMode = computed((): BatchActionMode => {
  if (!selectedIds.value.length) return 'empty'
  const intake = selectedIntakeCount.value
  const stock = selectedStockCount.value
  // 勾选 id 尚未在 props 里解析到时，先按 empty 展示，避免闪成 mixed
  if (!selectedPatients.value.length) return 'empty'
  const other = selectedPatients.value.length - intake - stock
  if (intake > 0 && stock === 0 && other === 0) return 'intake'
  if (stock > 0 && intake === 0 && other === 0) return 'stock'
  return 'mixed'
})

const primaryAction = computed(() => {
  switch (batchActionMode.value) {
    case 'intake':
      return {
        key: 'intake',
        label: `批量发送首诊问卷 (${selectedIntakeCount.value})`,
        disabled: selectedIntakeCount.value <= 0,
        run: () => sendIntake(),
      }
    case 'stock':
      return {
        key: 'stock',
        label: `批量生成管理方案 (${selectedStockCount.value})`,
        disabled: selectedStockCount.value <= 0,
        run: () => batchGeneratePlans(),
      }
    case 'mixed':
      return {
        key: 'mixed',
        label: '已选人群状态不同，请分批次操作',
        disabled: true,
        run: () => {},
      }
    default:
      return {
        key: 'empty',
        label: '请先勾选患者',
        disabled: true,
        run: () => {},
      }
  }
})

/** 当前筛选结果里已勾选人数（含折叠未展开） */
const filteredSelectedCount = computed(
  () => filtered.value.filter((p) => selectedIds.value.includes(p.peopleId)).length,
)

/** 当前筛选是否已全部勾选 */
const allFilteredSelected = computed(
  () =>
    filtered.value.length > 0 &&
    filtered.value.every((p) => selectedIds.value.includes(p.peopleId)),
)

/** 半选：筛选项有勾选但未全选 */
const selectAllIndeterminate = computed(
  () => filteredSelectedCount.value > 0 && !allFilteredSelected.value,
)

/** 顶部全选文案：空 / 半选 / 全选 */
const selectAllLabel = computed(() => {
  if (!filteredSelectedCount.value) return '全选'
  if (allFilteredSelected.value) {
    return `全选 (已选 ${filteredSelectedCount.value} / 筛选 ${filtered.value.length})`
  }
  return `已选 ${filteredSelectedCount.value} 人`
})

/** 折叠未展示行中已勾选人数 */
const remainSelectedCount = computed(() => {
  if (remainCount.value <= 0) return 0
  return filtered.value
    .slice(shownLimit.value)
    .filter((p) => selectedIds.value.includes(p.peopleId)).length
})

function resolveOpenFilter(): StageFilter {
  if (props.initialFilter === 'stock') return 'stock'
  if (props.initialFilter === 'intake') return 'intake'
  if (stats.value.intake > 0 && stats.value.intake >= stats.value.stock) return 'intake'
  if (stats.value.stock > 0) return 'stock'
  return 'all'
}

function resolveOpenTeamId(): string {
  const want = (props.initialCareTeamId || '').trim()
  if (want && teamOptions.value.some((t) => t.id === want)) return want
  // 仅一组时默认该组；多组默认「全部患者」便于切换
  const realTeams = teamOptions.value.filter((t) => t.id !== BATCH_SERVE_TEAM_ALL)
  if (realTeams.length === 1) return realTeams[0]!.id
  return BATCH_SERVE_TEAM_ALL
}

function selectByCurrentFilter() {
  selectedIds.value = filtered.value.map((p) => p.peopleId)
}

function clearSelection() {
  selectedIds.value = []
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    keyword.value = ''
    shownLimit.value = PAGE
    suppressClear.value = true
    selectedTeamId.value = resolveOpenTeamId()
    stageFilter.value = resolveOpenFilter()
    // 打开时默认勾选当前筛选结果
    selectedIds.value = scopedPatients.value
      .filter((p) => {
        if (stageFilter.value === 'intake') return isIntakeStage(p.stage)
        if (stageFilter.value === 'stock') return isStockStage(p.stage)
        return true
      })
      .map((p) => p.peopleId)
    queueMicrotask(() => {
      suppressClear.value = false
    })
  },
)

watch(stageFilter, () => {
  shownLimit.value = PAGE
  if (suppressClear.value) return
  clearSelection()
})

watch(selectedTeamId, () => {
  shownLimit.value = PAGE
  if (suppressClear.value) return
  clearSelection()
})

watch(keyword, () => {
  shownLimit.value = PAGE
})

/** 花名册刷新：丢掉已不在当前圈定范围的勾选 */
watch(
  scopedPatients,
  (list) => {
    const alive = new Set(list.map((p) => p.peopleId))
    selectedIds.value = selectedIds.value.filter((id) => alive.has(id))
  },
)

/**
 * 顶部全选：勾选 = 选中当前筛选全部（含折叠）；
 * 取消 = 全局清空，绝不留下折叠区暗选。
 */
function toggleSelectAll(checked: boolean | string | number) {
  if (checked) {
    selectByCurrentFilter()
    return
  }
  clearSelection()
}

function toggleOne(peopleId: string, checked: boolean | string | number) {
  if (checked) {
    if (!selectedIds.value.includes(peopleId)) {
      selectedIds.value = [...selectedIds.value, peopleId]
    }
    return
  }
  selectedIds.value = selectedIds.value.filter((id) => id !== peopleId)
}

/** 整行点击：勾选/取消，不进入详情 */
function toggleRow(peopleId: string) {
  toggleOne(peopleId, !selectedIds.value.includes(peopleId))
}

function loadMore() {
  shownLimit.value += PAGE
}

function selectAllFiltered() {
  selectByCurrentFilter()
  ElMessage.success(`已全选当前筛选结果 ${selectedIds.value.length} 人`)
}

function sendIntake() {
  const ids = selectedIntakeIds.value
  if (!ids.length) {
    ElMessage.info('请先勾选待首诊患者')
    return
  }
  if (batchActionMode.value === 'mixed') {
    ElMessage.warning('已选人群状态不同，请分批次操作')
    return
  }
  emit('send-intake', ids)
}

function batchGeneratePlans() {
  const ids = selectedStockIds.value
  if (!ids.length) {
    ElMessage.info('请先勾选待制定方案/存量患者')
    return
  }
  if (batchActionMode.value === 'mixed') {
    ElMessage.warning('已选人群状态不同，请分批次操作')
    return
  }
  emit('batch-plan', ids)
}

function focusOne(peopleId: string) {
  emit('focus', peopleId)
}

/** —— 批量转移组 —— */
const transferOpen = ref(false)
const transferTargetId = ref<string | null>(null)
const transferSubmitting = ref(false)

/** 勾选人群的来源组文案（弹窗「从 XX 转移」） */
const transferSourceLabel = computed(() => {
  if (selectedTeamId.value !== BATCH_SERVE_TEAM_ALL) return activeTeamLabel.value
  const names = [
    ...new Set(
      selectedPatients.value
        .map((p) => (p.careTeamName || '').trim())
        .filter(Boolean),
    ),
  ]
  if (names.length === 1) return names[0]!
  if (names.length === 0) return '当前组'
  return `${names.length} 个不同组别`
})

/** 需要从目标下拉中排除的组（当前所在组 + 全部/公共池） */
const transferExcludeIds = computed(() => {
  const exclude = new Set<string>([BATCH_SERVE_TEAM_ALL, UNASSIGNED_TEAM_ID])
  if (selectedTeamId.value !== BATCH_SERVE_TEAM_ALL) {
    exclude.add(selectedTeamId.value)
  }
  const sourceIds = [
    ...new Set(
      selectedPatients.value.map((p) => (p.careTeamId || '').trim()).filter(Boolean),
    ),
  ]
  // 勾选人群若同属一组，禁止选回该组；混合来源时各自排除自己所在组（见提交时 skip）
  if (sourceIds.length === 1) exclude.add(sourceIds[0]!)
  return exclude
})

/** 目标组：复用顶部组别列表，过滤当前组 / 全部 / 公共池 */
const transferTargetOptions = computed(() =>
  teamOptions.value.filter((t) => !transferExcludeIds.value.has(t.id)),
)

const transferTargetName = computed(() => {
  const id = transferTargetId.value
  if (!id) return ''
  return teamOptions.value.find((t) => t.id === id)?.name || '目标组'
})

const canConfirmTransfer = computed(
  () =>
    selectedCount.value > 0 &&
    !!transferTargetId.value &&
    !transferExcludeIds.value.has(transferTargetId.value) &&
    !transferSubmitting.value,
)

function openTransferDialog() {
  if (!selectedCount.value) {
    ElMessage.info('请先勾选要转移的患者')
    return
  }
  if (!transferTargetOptions.value.length) {
    ElMessage.warning('暂无其他可转入的健管组，请先新建组')
    return
  }
  transferTargetId.value = null
  transferOpen.value = true
}

function closeTransferDialog() {
  if (transferSubmitting.value) return
  transferOpen.value = false
  transferTargetId.value = null
}

async function confirmTransfer() {
  if (!canConfirmTransfer.value || !transferTargetId.value) return
  const toId = transferTargetId.value
  const toName = transferTargetName.value
  const candidates = selectedPatients.value.filter((p) => (p.careTeamId || '') !== toId)
  if (!candidates.length) {
    ElMessage.warning('所选患者已在目标组内，无需转移')
    return
  }
  transferSubmitting.value = true
  const okIds: string[] = []
  const failNames: string[] = []
  try {
    for (const p of candidates) {
      try {
        await api(`/api/b/v1/patients/${p.peopleId}/care-team`, {
          method: 'PUT',
          body: JSON.stringify({ careTeamId: toId }),
        })
        okIds.push(p.peopleId)
      } catch {
        failNames.push(p.displayName || p.peopleId)
      }
    }
    if (!okIds.length) {
      toast.error('转移失败，请稍后重试')
      return
    }
    toast.success(
      failNames.length
        ? `已成功将 ${okIds.length} 位患者转移至「${toName}」，${failNames.length} 人失败`
        : `✅ 已成功将 ${okIds.length} 位患者转移至「${toName}」`,
    )
    transferOpen.value = false
    transferTargetId.value = null
    // 本地先摘掉已转走的人，父级再全量刷新
    selectedIds.value = selectedIds.value.filter((id) => !okIds.includes(id))
    emit('transferred', {
      peopleIds: okIds,
      toCareTeamId: toId,
      toCareTeamName: toName,
    })
  } finally {
    transferSubmitting.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    title="批量跟进工作台"
    size="92%"
    append-to-body
    destroy-on-close
    class="batch-serve-workbench"
  >
    <div class="wb-body">
      <div class="wb-lead">
        <el-select
          v-model="selectedTeamId"
          filterable
          teleported
          class="wb-team-select"
          placeholder="选择健管组…"
          popper-class="batch-serve-team-select-popper"
        >
          <el-option
            v-for="t in teamOptions"
            :key="t.id"
            :label="t.name"
            :value="t.id"
          >
            <span class="wb-team-opt-name">{{ t.name }}</span>
            <span class="wb-team-opt-count">{{ t.count }} 人</span>
          </el-option>
        </el-select>
        <span class="wb-lead-text">
          本批共 <strong>{{ stats.total }}</strong> 人：待首诊
          <strong>{{ stats.intake }}</strong> · 存量/已评估
          <strong>{{ stats.stock }}</strong>。切换组别或 Tab 会清空勾选。
        </span>
      </div>

      <div class="wb-toolbar">
        <div class="wb-filters" role="tablist">
          <button
            type="button"
            class="wb-filter"
            :class="{ active: stageFilter === 'all' }"
            @click="stageFilter = 'all'"
          >
            全部 ({{ stats.total }})
          </button>
          <button
            type="button"
            class="wb-filter"
            :class="{ active: stageFilter === 'intake' }"
            @click="stageFilter = 'intake'"
          >
            待首诊 ({{ stats.intake }})
          </button>
          <button
            type="button"
            class="wb-filter"
            :class="{ active: stageFilter === 'stock' }"
            @click="stageFilter = 'stock'"
          >
            存量患者 ({{ stats.stock }})
          </button>
        </div>
        <el-input
          v-model="keyword"
          clearable
          class="wb-search"
          placeholder="搜索姓名、病种或组别…"
        />
      </div>

      <div class="wb-select-bar">
        <el-checkbox
          :model-value="allFilteredSelected"
          :indeterminate="selectAllIndeterminate"
          :disabled="!filtered.length"
          @change="toggleSelectAll"
        >
          {{ selectAllLabel }}
        </el-checkbox>
        <div class="wb-select-actions">
          <button type="button" class="link" :disabled="!filtered.length" @click="selectAllFiltered">
            全选当前筛选结果
          </button>
          <button type="button" class="link" :disabled="!selectedCount" @click="clearSelection">
            清空
          </button>
        </div>
      </div>

      <p v-if="batchActionMode === 'mixed'" class="wb-mixed-hint" role="status">
        已选人群状态不同，请分批次操作（勿把待首诊与存量混在一次批量里）
      </p>

      <div v-if="visibleRows.length" class="wb-list">
        <div
          v-for="p in visibleRows"
          :key="p.peopleId"
          class="wb-row"
          :class="{ checked: selectedIds.includes(p.peopleId) }"
          role="checkbox"
          :aria-checked="selectedIds.includes(p.peopleId) ? 'true' : 'false'"
          tabindex="0"
          @click="toggleRow(p.peopleId)"
          @keydown.enter.prevent="toggleRow(p.peopleId)"
          @keydown.space.prevent="toggleRow(p.peopleId)"
        >
          <el-checkbox
            :model-value="selectedIds.includes(p.peopleId)"
            @click.stop
            @change="(v: boolean | string | number) => toggleOne(p.peopleId, v)"
          />
          <span class="wb-avatar" aria-hidden="true">{{
            (p.displayName || '?').slice(0, 1)
          }}</span>
          <span class="wb-main">
            <span class="wb-name">{{ p.displayName || p.peopleId }}</span>
            <span class="wb-meta">
              <span class="wb-stage" :class="p.stage">{{ p.stageLabel }}</span>
              <span
                v-if="showTeamBadge && (p.careTeamName || '').trim()"
                class="wb-team-tag"
              >{{ p.careTeamName }}</span>
              <span v-if="p.age != null">{{ p.age }}岁</span>
              <span v-if="p.diseaseLabels?.length">{{ p.diseaseLabels.slice(0, 2).join('·') }}</span>
            </span>
          </span>
          <button
            type="button"
            class="wb-cta-btn"
            @click.stop.prevent="focusOne(p.peopleId)"
          >
            {{ p.ctaText || '去服务' }}
            <span class="wb-cta-arrow" aria-hidden="true">›</span>
          </button>
        </div>
        <button
          v-if="remainCount > 0"
          type="button"
          class="wb-more"
          @click="loadMore"
        >
          展开查看剩余 {{ remainCount }} 人
          <template v-if="remainSelectedCount > 0">
            （含已选 {{ remainSelectedCount }} 人）
          </template>
        </button>
      </div>
      <el-empty
        v-else
        :description="
          scopedPatients.length
            ? '没有匹配的患者'
            : `「${activeTeamLabel}」暂无患者`
        "
        :image-size="64"
      />
    </div>

    <template #footer>
      <div class="wb-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          class="wb-transfer-btn"
          :disabled="!selectedCount"
          @click="openTransferDialog"
        >
          🔄 批量转移组
          <template v-if="selectedCount"> ({{ selectedCount }})</template>
        </el-button>
        <el-button
          class="wb-primary-action"
          type="primary"
          :disabled="primaryAction.disabled"
          @click="primaryAction.run()"
        >
          {{ primaryAction.label }}
        </el-button>
      </div>
    </template>
  </el-drawer>

  <el-dialog
    v-model="transferOpen"
    title="批量转移组"
    width="420px"
    append-to-body
    align-center
    :close-on-click-modal="!transferSubmitting"
    class="batch-serve-transfer-dialog"
    @closed="transferTargetId = null"
  >
    <p class="transfer-copy">
      确认将选中的 <strong>{{ selectedCount }}</strong> 位患者，从「{{ transferSourceLabel }}」转移出去吗？
    </p>
    <el-select
      v-model="transferTargetId"
      filterable
      teleported
      class="transfer-team-select"
      placeholder="请选择目标健管组"
      popper-class="batch-serve-team-select-popper"
      :disabled="transferSubmitting"
    >
      <el-option
        v-for="t in transferTargetOptions"
        :key="t.id"
        :label="t.name"
        :value="t.id"
      >
        <span class="wb-team-opt-name">{{ t.name }}</span>
        <span class="wb-team-opt-count">{{ t.count }} 人</span>
      </el-option>
    </el-select>
    <template #footer>
      <el-button :disabled="transferSubmitting" @click="closeTransferDialog">取消</el-button>
      <el-button
        type="primary"
        :loading="transferSubmitting"
        :disabled="!canConfirmTransfer"
        @click="confirmTransfer"
      >
        确认转移
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wb-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.wb-lead {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-600, #475569);
}
.wb-team-select {
  width: min(220px, 100%);
  flex-shrink: 0;
}
.wb-lead-text {
  flex: 1;
  min-width: 180px;
}
.wb-lead-text strong {
  color: #c2410c;
}
.wb-team-opt-name {
  float: left;
}
.wb-team-opt-count {
  float: right;
  color: var(--ink-400, #94a3b8);
  font-size: 12px;
  margin-left: 12px;
}
.wb-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.wb-filters {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.wb-filter {
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 8px;
  padding: 6px 10px;
  background: #fff;
  color: var(--ink-600, #475569);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.wb-filter.active {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}
.wb-search {
  flex: 1;
  min-width: 160px;
  max-width: 280px;
}
.wb-select-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.wb-select-actions {
  display: flex;
  gap: 12px;
}
.link {
  border: 0;
  background: transparent;
  color: var(--brand-600, #2563eb);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}
.link:disabled {
  color: var(--ink-300, #cbd5e1);
  cursor: not-allowed;
}
.wb-mixed-hint {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.45;
}
.wb-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: calc(100vh - 280px);
  overflow: auto;
  padding-right: 2px;
}
.wb-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--ink-100, #e5e7eb);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
}
.wb-row.checked {
  border-color: #60a5fa;
  background: #dbeafe;
}
.wb-avatar {
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #64748b;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}
.wb-row.checked .wb-avatar {
  background: #2563eb;
}
.wb-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.wb-name {
  display: block;
  text-align: left;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink-800, #1f2937);
  line-height: 1.3;
}
.wb-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-500, #64748b);
}
.wb-stage {
  font-weight: 600;
  padding: 0 6px;
  border-radius: 999px;
  background: #f1f5f9;
}
.wb-stage.new,
.wb-stage.review {
  background: #fff7ed;
  color: #c2410c;
}
.wb-stage.plan {
  background: #eff6ff;
  color: #1d4ed8;
}
.wb-stage.active {
  background: #f0fdf4;
  color: #15803d;
}
.wb-team-tag {
  font-weight: 500;
  padding: 0 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: var(--ink-500, #64748b);
  font-size: 11.5px;
}
.wb-cta-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 0;
  background: transparent;
  padding: 2px 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--brand-600, #2563eb);
  cursor: pointer;
  text-decoration: none;
  border-radius: 0;
  opacity: 1;
}
.wb-cta-arrow {
  font-size: 14px;
  line-height: 1;
  font-weight: 700;
}
.wb-cta-btn:hover {
  background: transparent;
  color: var(--brand-700, #1d4ed8);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.wb-more {
  border: 1px dashed var(--ink-200, #e2e8f0);
  border-radius: 10px;
  padding: 10px;
  background: #f8fafc;
  color: var(--brand-600, #2563eb);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.wb-more:hover {
  background: #eff6ff;
  border-color: #93c5fd;
}
.wb-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
/* 转移组与主操作之间多留一点气口，避免挤成一坨 */
.wb-transfer-btn {
  margin-right: 4px;
}
.wb-primary-action {
  margin-left: 8px;
  min-width: 11.5em;
}
.transfer-copy {
  margin: 0 0 14px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--ink-700, #334155);
}
.transfer-copy strong {
  color: #c2410c;
}
.transfer-team-select {
  width: 100%;
}
</style>

<style>
.batch-serve-workbench.el-drawer .el-drawer__body {
  padding-top: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.batch-serve-team-select-popper.el-select__popper {
  max-width: min(360px, 92vw);
}
.batch-serve-team-select-popper .el-select-dropdown__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
</style>
