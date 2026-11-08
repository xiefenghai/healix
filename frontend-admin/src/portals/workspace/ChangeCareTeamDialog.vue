<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { api } from '../../shared/http'

interface CareTeamListItem {
  id: string
  name: string
  primaryCareManagerName?: string | null
  primaryDoctorName?: string | null
  memberCount?: number
}

const props = defineProps<{
  peopleId: string | null
  peopleName?: string | null
  /** 当前所属健管组；有值时为「变更」，无值时为「加入」 */
  currentCareTeamId?: string | null
}>()

const emit = defineEmits<{
  closed: []
  changed: [payload: { peopleId: string; careTeamId: string; careTeamName?: string }]
  create: []
}>()

const visibleModel = computed({
  get: () => !!props.peopleId,
  set: (v: boolean) => {
    if (!v) emit('closed')
  },
})

const isChange = computed(() => !!props.currentCareTeamId)
const formRef = ref<FormInstance>()
const teams = ref<CareTeamListItem[]>([])
const filteredTeams = ref<CareTeamListItem[]>([])
const form = ref({ teamId: null as string | null })
const saving = ref(false)
const loadingTeams = ref(false)
const searchQuery = ref('')
const allowEnterSelect = ref(false)

const currentTeamName = computed(
  () => teams.value.find((t) => t.id === props.currentCareTeamId)?.name || '',
)

const rules: FormRules = {
  teamId: [
    { required: true, message: '请选择健管组', trigger: 'change' },
    {
      validator: (_rule, value, callback) => {
        if (
          props.currentCareTeamId &&
          value &&
          value === props.currentCareTeamId
        ) {
          callback(new Error('请选择其他健管组'))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
}

function matchesQuery(t: CareTeamListItem, query: string) {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const hay = [t.name, t.primaryCareManagerName, t.primaryDoctorName]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return hay.includes(q)
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlightText(text: string) {
  const raw = text || ''
  const q = searchQuery.value.trim()
  const safe = escapeHtml(raw)
  if (!q) return safe
  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  try {
    return safe.replace(new RegExp(`(${escaped})`, 'gi'), '<em class="hit">$1</em>')
  } catch {
    return safe
  }
}

function filterMethod(query: string) {
  searchQuery.value = query
  allowEnterSelect.value = !!query.trim()
  filteredTeams.value = teams.value.filter((t) => matchesQuery(t, query))
}

function clearDefaultHover() {
  // 变更模式已有当前选中，允许高亮当前项；加入模式不默认高亮第一项
  if (form.value.teamId) return
  const popper = document.querySelector('.care-team-select-popper')
  popper?.querySelectorAll('.el-select-dropdown__item.is-hovering').forEach((el) => {
    el.classList.remove('is-hovering')
  })
}

function onSelectVisible(open: boolean) {
  if (!open) return
  searchQuery.value = ''
  allowEnterSelect.value = false
  filteredTeams.value = teams.value.slice()
  nextTick(() => {
    clearDefaultHover()
    requestAnimationFrame(() => clearDefaultHover())
  })
}

function onSelectKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    allowEnterSelect.value = true
    return
  }
  if (e.key === 'Enter' && !form.value.teamId && !allowEnterSelect.value) {
    e.preventDefault()
    e.stopPropagation()
  }
}

async function loadTeams() {
  loadingTeams.value = true
  try {
    const res = await api<{ data: CareTeamListItem[] }>(
      '/api/b/v1/care-teams?assignableOnly=true',
    )
    teams.value = res.data || []
    filteredTeams.value = teams.value.slice()
    if (!form.value.teamId && teams.value.length === 1 && teams.value[0]?.id) {
      form.value.teamId = teams.value[0].id
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载健管组失败')
    teams.value = []
    filteredTeams.value = []
  } finally {
    loadingTeams.value = false
  }
}

watch(
  () => props.peopleId,
  (id) => {
    if (id) {
      // 加入：不预选；变更：回填当前组便于对照
      form.value.teamId = props.currentCareTeamId || null
      searchQuery.value = ''
      allowEnterSelect.value = false
      nextTick(() => formRef.value?.clearValidate())
      void loadTeams()
    }
  },
)

watch(
  () => form.value.teamId,
  () => {
    if (form.value.teamId) formRef.value?.clearValidate('teamId')
  },
)

async function submit() {
  if (saving.value || !props.peopleId) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (!form.value.teamId) return
  saving.value = true
  try {
    await api(`/api/b/v1/patients/${props.peopleId}/care-team`, {
      method: 'PUT',
      body: JSON.stringify({ careTeamId: form.value.teamId }),
    })
    ElMessage.success(isChange.value ? '已变更健管组' : '已加入健管组，任务将自动办结')
    const peopleId = props.peopleId
    const careTeamId = form.value.teamId!
    const careTeamName =
      teams.value.find((t) => t.id === careTeamId)?.name?.trim() || undefined
    // 先通知父级乐观刷新，再关弹窗（避免 closed 清掉 peopleId 后丢上下文）
    emit('changed', { peopleId, careTeamId, careTeamName })
    emit('closed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : isChange.value ? '变更失败' : '加入失败')
  } finally {
    saving.value = false
  }
}

function goCreate() {
  emit('create')
  emit('closed')
}
</script>

<template>
  <el-dialog
    v-model="visibleModel"
    :title="isChange ? '变更健管组' : '加入健管组'"
    width="440px"
    destroy-on-close
    append-to-body
    align-center
    class="change-care-team-dialog"
    @closed="emit('closed')"
  >
    <p v-if="peopleName" class="hint">患者：{{ peopleName }}</p>
    <p v-if="isChange && currentTeamName" class="hint muted">
      当前：{{ currentTeamName }}。变更后待办将按新组主责健管师重派。
    </p>
    <p v-else-if="!isChange" class="hint muted">加入健管组后，「分配健管组」待办会自动办结。</p>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      v-loading="loadingTeams"
      class="form"
      @submit.prevent
    >
      <el-form-item label="健管组" prop="teamId" required>
        <el-select
          v-model="form.teamId"
          filterable
          clearable
          teleported
          fit-input-width
          :default-first-option="false"
          :disabled="!teams.length"
          placeholder="搜索组名或主责姓名"
          style="width: 100%"
          popper-class="care-team-select-popper"
          :filter-method="filterMethod"
          :popper-options="{
            modifiers: [{ name: 'offset', options: { offset: [0, 6] } }],
          }"
          @visible-change="onSelectVisible"
          @keydown="onSelectKeydown"
        >
          <el-option
            v-for="t in filteredTeams"
            :key="t.id"
            :label="t.name"
            :value="t.id"
          >
            <div class="opt" @mouseenter="allowEnterSelect = true">
              <span class="opt-name" v-html="highlightText(t.name)" />
              <span
                class="opt-meta"
                v-html="
                  highlightText(
                    `主责 ${(t.primaryCareManagerName || '').trim() || '未指定'}` +
                      ((t.primaryDoctorName || '').trim()
                        ? ` · 医生 ${t.primaryDoctorName}`
                        : '') +
                      ` · ${t.memberCount ?? 0} 位患者` +
                      (t.id === currentCareTeamId ? ' · 当前' : ''),
                  )
                "
              />
            </div>
          </el-option>
        </el-select>
      </el-form-item>
    </el-form>

    <div v-if="!loadingTeams && !teams.length" class="empty">
      <span>暂无健管组，请先创建后再{{ isChange ? '变更' : '分配' }}。</span>
      <el-button link type="primary" @click="goCreate">去创建健管组</el-button>
    </div>

    <template #footer>
      <el-button :disabled="saving" @click="visibleModel = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!teams.length" @click="submit">
        {{ saving ? (isChange ? '变更中...' : '入组中...') : isChange ? '确认变更' : '确认入组' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ink-800, #1f2937);
}
.hint.muted {
  margin-bottom: 16px;
  color: var(--ink-500, #6b7280);
  font-size: 12px;
  line-height: 1.5;
}
.form {
  margin-bottom: 4px;
}
.empty {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 13px;
  color: var(--ink-500, #6b7280);
}
</style>

<style>
.change-care-team-dialog.el-dialog {
  overflow: visible;
}
.change-care-team-dialog .el-dialog__body {
  overflow: visible;
}

.care-team-select-popper.el-popper,
.care-team-select-popper {
  max-width: min(440px, 92vw);
  border: 1px solid var(--ink-100, #e5e7eb) !important;
  border-radius: 10px !important;
  box-shadow:
    0 4px 6px -1px rgba(15, 23, 42, 0.06),
    0 10px 24px -4px rgba(15, 23, 42, 0.12) !important;
  overflow: hidden;
}
.care-team-select-popper .el-scrollbar__view,
.care-team-select-popper .el-select-dropdown__list {
  padding: 8px 0 !important;
}
.care-team-select-popper .el-select-dropdown__wrap {
  max-height: 280px;
}
.care-team-select-popper .el-scrollbar__bar {
  right: 2px;
  width: 4px !important;
  opacity: 0.35;
  transition: opacity 160ms;
}
.care-team-select-popper:hover .el-scrollbar__bar {
  opacity: 0.75;
}
.care-team-select-popper .el-scrollbar__thumb {
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.55) !important;
}
.care-team-select-popper .opt {
  display: flex;
  flex-direction: column;
  gap: 6px;
  line-height: 1.35;
  white-space: normal;
}
.care-team-select-popper .el-select-dropdown__item {
  height: auto;
  min-height: 48px;
  padding: 10px 12px;
  line-height: 1.35;
}
.care-team-select-popper .el-select-dropdown__item.is-hovering,
.care-team-select-popper .el-select-dropdown__item:hover {
  background: #eef5ff !important;
}
.care-team-select-popper .el-select-dropdown__item.is-selected {
  background: #e0edff !important;
  color: #1d4ed8;
  font-weight: 600;
}
.care-team-select-popper .el-select-dropdown__item.is-selected .opt-name {
  color: #1d4ed8;
}
.care-team-select-popper .el-select-dropdown__item.is-selected .opt-meta {
  color: #64748b;
}
.care-team-select-popper .opt-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800, #1f2937);
}
.care-team-select-popper .opt-meta {
  font-size: 12px;
  color: var(--ink-400, #9ca3af);
}
.care-team-select-popper .hit {
  color: #dc2626;
  font-style: normal;
  font-weight: 700;
}
.care-team-select-popper .el-select-dropdown {
  position: relative;
}
.care-team-select-popper .el-select-dropdown::after {
  content: '';
  pointer-events: none;
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 18px;
  background: linear-gradient(to bottom, rgba(255, 255, 255, 0), rgba(255, 255, 255, 0.92));
  border-radius: 0 0 10px 10px;
}
.change-care-team-dialog .el-form-item__error {
  padding-top: 4px;
}
</style>
