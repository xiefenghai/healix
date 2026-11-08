<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'

export interface UnassignedPatientRow {
  peopleId: string
  displayName: string
  age?: number | null
  diseaseLabels?: string[]
  gender?: string | null
}

interface CareTeamListItem {
  id: string
  name: string
  primaryCareManagerName?: string | null
  primaryDoctorName?: string | null
  memberCount?: number
}

const props = defineProps<{
  modelValue: boolean
  patients: UnassignedPatientRow[]
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  assigned: [payload: { peopleIds: string[]; careTeamId: string; careTeamName: string }]
  createTeam: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const selectedIds = ref<string[]>([])
const teams = ref<CareTeamListItem[]>([])
const teamId = ref<string | null>(null)
const loadingTeams = ref(false)
const submitting = ref(false)
const keyword = ref('')

const filteredPatients = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return props.patients
  return props.patients.filter((p) => {
    const name = (p.displayName || '').toLowerCase()
    if (name.includes(q)) return true
    return (p.diseaseLabels || []).some((t) => (t || '').toLowerCase().includes(q))
  })
})

/** 全选/反选作用在当前可见列表（有搜索时只勾可见项） */
const visibleIds = computed(() => filteredPatients.value.map((p) => p.peopleId).filter(Boolean))
const allSelected = computed(
  () =>
    visibleIds.value.length > 0 && visibleIds.value.every((id) => selectedIds.value.includes(id)),
)
const selectedCount = computed(() => selectedIds.value.length)
const selectedTeamName = computed(
  () => teams.value.find((t) => t.id === teamId.value)?.name?.trim() || '',
)
const canSubmit = computed(
  () => selectedCount.value > 0 && !!teamId.value && teams.value.length > 0 && !submitting.value,
)

/**
 * 仅依据真实病种标签匹配组名；无标签则不瞎猜。
 * 文案必须点名患者 + 具体标签，避免「有 1 位与减重相关」式幻觉。
 */
const aiHint = computed(() => {
  if (!selectedCount.value || !teams.value.length) return null
  const selected = props.patients.filter((p) => selectedIds.value.includes(p.peopleId))
  const rules = [
    {
      tagKeys: ['肥胖', '超重', '减重', '体重管理'],
      teamKeys: ['减重', '肥胖', '体重'],
      topic: '减重/肥胖',
    },
    {
      tagKeys: ['糖尿', '血糖'],
      teamKeys: ['糖尿', '血糖', '代谢'],
      topic: '糖尿病',
    },
    {
      tagKeys: ['高血压', '血压', '心脑', '冠心病', '脑卒中'],
      teamKeys: ['心脑', '血压', '血管', '慢病'],
      topic: '心脑血管',
    },
  ]
  for (const rule of rules) {
    const hits: Array<{ name: string; tag: string }> = []
    for (const p of selected) {
      const tags = (p.diseaseLabels || []).map((t) => (t || '').trim()).filter(Boolean)
      if (!tags.length) continue
      const matchedTag = tags.find((tag) => rule.tagKeys.some((k) => tag.includes(k)))
      if (!matchedTag) continue
      hits.push({ name: (p.displayName || '患者').trim(), tag: matchedTag })
    }
    if (!hits.length) continue
    const team = teams.value.find((t) =>
      rule.teamKeys.some((k) => (t.name || '').includes(k)),
    )
    if (!team) continue
    const named =
      hits.length <= 2
        ? hits.map((h) => `【${h.name}】（${h.tag}）`).join('、')
        : `${hits
            .slice(0, 2)
            .map((h) => `【${h.name}】`)
            .join('、')}等 ${hits.length} 人（含「${hits[0]!.tag}」）`
    return {
      text: `患者${named}与「${rule.topic}」相关，建议分配到「${team.name}」。`,
      teamId: team.id,
      teamName: team.name,
    }
  }
  return null
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    selectedIds.value = []
    teamId.value = null
    keyword.value = ''
    void loadTeams()
  },
)

watch(
  () => props.patients,
  (list) => {
    const alive = new Set(list.map((p) => p.peopleId))
    selectedIds.value = selectedIds.value.filter((id) => alive.has(id))
  },
)

async function loadTeams() {
  loadingTeams.value = true
  try {
    const res = await api<{ data: CareTeamListItem[] }>(
      '/api/b/v1/care-teams?assignableOnly=true',
    )
    teams.value = res.data || []
    // 普通健管师通常只有一个主责组：默认选中，减少一步操作
    if (teams.value.length === 1 && teams.value[0]?.id) {
      teamId.value = teams.value[0].id
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载健管组失败')
    teams.value = []
  } finally {
    loadingTeams.value = false
  }
}

function toggleAll(checked: boolean | string | number) {
  if (checked) {
    const set = new Set(selectedIds.value)
    for (const id of visibleIds.value) set.add(id)
    selectedIds.value = [...set]
    return
  }
  const drop = new Set(visibleIds.value)
  selectedIds.value = selectedIds.value.filter((id) => !drop.has(id))
}

function selectAll() {
  toggleAll(true)
}

function invertSelect() {
  const set = new Set(selectedIds.value)
  for (const id of visibleIds.value) {
    if (set.has(id)) set.delete(id)
    else set.add(id)
  }
  selectedIds.value = [...set]
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

function applyAiTeam() {
  if (!aiHint.value) return
  teamId.value = aiHint.value.teamId
  ElMessage.success(`已选中「${aiHint.value.teamName}」`)
}

function rowMeta(p: UnassignedPatientRow) {
  const parts: string[] = []
  if (p.age != null) parts.push(`${p.age}岁`)
  if (p.gender === 'MALE') parts.push('男')
  else if (p.gender === 'FEMALE') parts.push('女')
  if (p.diseaseLabels?.length) parts.push(p.diseaseLabels.slice(0, 2).join('/'))
  return parts.join(' · ') || '暂无更多档案信息'
}

async function submitBatch() {
  if (!canSubmit.value) {
    if (!selectedIds.value.length) ElMessage.info('请先勾选要分配的患者')
    else if (!teamId.value) ElMessage.info('请先选择要分配到的健管组')
    return
  }
  const careTeamId = teamId.value!
  const careTeamName = selectedTeamName.value || '健管组'
  const count = selectedIds.value.length
  const previewNames = selectedIds.value
    .slice(0, 3)
    .map((id) => props.patients.find((p) => p.peopleId === id)?.displayName || '患者')
    .join('、')
  const more = count > 3 ? `等 ${count} 人` : ''
  try {
    await ElMessageBox.confirm(
      `确认将「${previewNames}${more}」分配到「${careTeamName}」？分配后可在该组列表中查看。`,
      '确认批量分配',
      {
        type: 'warning',
        confirmButtonText: `确认分配（${count}）`,
        cancelButtonText: '再看看',
      },
    )
  } catch {
    return
  }
  submitting.value = true
  const okIds: string[] = []
  const failNames: string[] = []
  try {
    for (const id of selectedIds.value) {
      const row = props.patients.find((p) => p.peopleId === id)
      try {
        await api(`/api/b/v1/patients/${id}/care-team`, {
          method: 'PUT',
          body: JSON.stringify({ careTeamId }),
        })
        okIds.push(id)
      } catch {
        failNames.push(row?.displayName || id)
      }
    }
    if (okIds.length) {
      ElMessage.success(
        failNames.length
          ? `已分配 ${okIds.length} 人到「${careTeamName}」，${failNames.length} 人失败`
          : `成功将 ${okIds.length} 位患者分配至「${careTeamName}」`,
      )
      // 先关抽屉再通知父级刷新左栏，避免「正在处理」灰条与空组文案残留
      visible.value = false
      emit('assigned', { peopleIds: okIds, careTeamId, careTeamName })
    } else {
      ElMessage.error('分配失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

function goCreateTeam() {
  emit('createTeam')
  visible.value = false
}
</script>

<template>
  <el-drawer
    v-model="visible"
    title="待分组患者处理"
    size="560px"
    append-to-body
    destroy-on-close
    class="unassigned-assign-drawer"
  >
    <p class="hint">
      当前共有 <strong>{{ patients.length }}</strong> 位患者等待分配。勾选后可批量入组。
    </p>

    <div v-if="aiHint" class="ai-hint">
      <span class="ai-hint-text">💡 AI 建议：{{ aiHint.text }}</span>
      <el-button size="small" type="primary" plain @click="applyAiTeam">一键选中该组</el-button>
    </div>

    <el-input
      v-if="patients.length"
      v-model="keyword"
      clearable
      class="search"
      placeholder="搜索姓名或病种…"
    />

    <div class="toolbar">
      <el-checkbox
        :model-value="allSelected"
        :indeterminate="visibleIds.some((id) => selectedIds.includes(id)) && !allSelected"
        :disabled="!visibleIds.length"
        @change="toggleAll"
      >
        全选（已选 {{ selectedCount }}）
      </el-checkbox>
      <div class="toolbar-actions">
        <button type="button" class="link-btn" :disabled="!visibleIds.length" @click="selectAll">
          全选
        </button>
        <button
          type="button"
          class="link-btn"
          :disabled="!visibleIds.length"
          @click="invertSelect"
        >
          反选
        </button>
      </div>
    </div>

    <div v-if="filteredPatients.length" class="list">
      <label
        v-for="p in filteredPatients"
        :key="p.peopleId"
        class="row"
        :class="{ checked: selectedIds.includes(p.peopleId) }"
      >
        <el-checkbox
          :model-value="selectedIds.includes(p.peopleId)"
          @change="(v: boolean | string | number) => toggleOne(p.peopleId, v)"
        />
        <span class="avatar">{{ (p.displayName || '?').slice(0, 1) }}</span>
        <span class="body">
          <strong class="name">{{ p.displayName || p.peopleId }}</strong>
          <span class="meta">{{ rowMeta(p) }}</span>
        </span>
      </label>
    </div>
    <el-empty
      v-else
      :description="patients.length ? '没有匹配的患者' : '暂无待分组患者'"
      :image-size="64"
    />

    <template #footer>
      <div class="footer" v-loading="loadingTeams">
        <div class="footer-main">
          <span class="assign-label">分配到</span>
          <el-select
            v-model="teamId"
            filterable
            clearable
            teleported
            placeholder="选择健管组…"
            :disabled="!teams.length || !selectedCount"
            style="flex: 1; min-width: 0"
            popper-class="unassigned-assign-select-popper"
            :popper-options="{
              modifiers: [
                { name: 'flip', options: { fallbackPlacements: ['top', 'bottom'] } },
                { name: 'offset', options: { offset: [0, 6] } },
              ],
            }"
          >
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id">
              <span>{{ t.name }}</span>
              <span class="opt-meta">
                {{ (t.primaryCareManagerName || '').trim() || '未指定主责' }} ·
                {{ t.memberCount ?? 0 }} 位患者
              </span>
            </el-option>
          </el-select>
          <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submitBatch">
            {{
              submitting
                ? '分配中…'
                : selectedCount
                  ? `批量分配（${selectedCount}）`
                  : '批量分配'
            }}
          </el-button>
        </div>
        <div v-if="!loadingTeams && !teams.length" class="footer-empty">
          <span>暂无健管组</span>
          <el-button link type="primary" @click="goCreateTeam">去创建健管组</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-500, #6b7280);
}
.hint strong {
  color: #c2410c;
  font-weight: 700;
}
.ai-hint {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}
.ai-hint-text {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: #1e40af;
}
.search {
  margin: 0 0 10px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.link-btn {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--brand-600, #2563eb);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.link-btn:disabled {
  color: var(--ink-300, #cbd5e1);
  cursor: not-allowed;
}
.link-btn:not(:disabled):hover {
  text-decoration: underline;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: calc(100vh - 320px);
  overflow: auto;
  padding: 2px 2px 8px 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--ink-100, #e5e7eb);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: background 0.12s ease, border-color 0.12s ease;
}
.row:hover {
  border-color: var(--ink-200, #e2e8f0);
  background: #f8fafc;
}
.row.checked {
  border-color: #93c5fd;
  background: #eff6ff;
}
.avatar {
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #64748b;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.row.checked .avatar {
  background: #3b82f6;
}
.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.name {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--ink-800, #1f2937);
}
.meta {
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-500, #6b7280);
}
.footer {
  width: 100%;
}
.footer-main {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.assign-label {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-600, #475569);
  white-space: nowrap;
}
.footer-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--ink-500, #6b7280);
}
.opt-meta {
  float: right;
  margin-left: 12px;
  color: var(--ink-400, #9ca3af);
  font-size: 12px;
}
</style>

<style>
.unassigned-assign-drawer.el-drawer .el-drawer__body {
  padding-top: 8px;
}
.unassigned-assign-drawer.el-drawer .el-drawer__footer {
  border-top: 1px solid var(--ink-100, #e5e7eb);
  background: #fff;
  padding: 12px 16px;
  /* 给下拉留出层级，避免被列表盖住 */
  position: relative;
  z-index: 2;
}
.unassigned-assign-select-popper.el-select__popper,
.unassigned-assign-select-popper {
  z-index: 4000 !important;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.14) !important;
}
</style>
