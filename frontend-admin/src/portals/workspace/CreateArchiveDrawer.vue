<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type DrawerProps,
  type FormInstance,
  type FormRules,
  type InputInstance,
} from 'element-plus'
import { api } from '../../shared/http'

export interface CreateArchivePreset {
  name?: string | null
  gender?: string | null
  birthday?: string | null
  mobile?: string | null
}

export interface CreateArchiveResult {
  peopleId: string
  displayName: string
  /** 建档后是否已分配健管组（含仅 1 组时自动入组） */
  careTeamAssigned: boolean
  /** 已入组时的健管组 id，便于左栏展开 */
  careTeamId?: string | null
}

interface DuplicateCandidate {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  mobileMasked?: string | null
  mobileOwner?: string | null
  hasIdentity: boolean
  inCurrentOrg: boolean
  confirmRequired?: boolean
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
  /** Agent 已抽到的字段，打开时预填 */
  preset?: CreateArchivePreset | null
  /** 从某健管组「+」进入：建档成功后自动入该组，跳过分配步 */
  defaultCareTeamId?: string | null
  defaultCareTeamName?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** form | assign，供父级改输入框提示 */
  phase: [phase: 'form' | 'assign']
  /** 建档已落库、进入分配步（尚未关抽屉） */
  archived: [payload: { peopleId: string; displayName: string }]
  /** 分配步确认或暂不分配后关闭 */
  created: [result: CreateArchiveResult]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const IDENTITY_OPTIONS = [
  { value: 'ID_CARD', label: '身份证' },
  { value: 'PASSPORT', label: '护照' },
  { value: 'OTHER', label: '其他' },
]

function emptyForm() {
  return {
    name: '',
    gender: '',
    birthday: '' as string,
    mobile: '',
    identityType: 'ID_CARD',
    identityValue: '',
  }
}

const formRef = ref<FormInstance>()
const nameInputRef = ref<InputInstance>()
const form = ref(emptyForm())
const showIdentity = ref(false)
const submitting = ref(false)
const duplicates = ref<DuplicateCandidate[]>([])
const duplicateVisible = ref(false)
/** 提交成功后放行关闭，避免未保存确认挡住成功收起 */
const allowCloseOnce = ref(false)

/** form=填写建档；assign=建档成功后分配健管组 */
const step = ref<'form' | 'assign'>('form')
const createdPeopleId = ref('')
const createdDisplayName = ref('')
const assignFormRef = ref<FormInstance>()
const assignForm = ref({ teamId: null as string | null })
const teams = ref<CareTeamListItem[]>([])
const filteredTeams = ref<CareTeamListItem[]>([])
const loadingTeams = ref(false)
const assigning = ref(false)
const searchQuery = ref('')
const allowEnterSelect = ref(false)

const assignRules: FormRules = {
  teamId: [{ required: true, message: '请选择健管组', trigger: 'change' }],
}

const drawerTitle = computed(() =>
  step.value === 'assign' ? '分配健管组' : '患者建档',
)

const formHint = computed(() => {
  const team = (props.defaultCareTeamName || '').trim()
  if (team) {
    return `填写必填项即可建档，创建后将自动加入「${team}」。`
  }
  return '填写必填项即可建档，或直接在左侧对话中发我信息。'
})

async function joinCareTeam(peopleId: string, careTeamId: string): Promise<boolean> {
  try {
    await api(`/api/b/v1/patients/${peopleId}/care-team`, {
      method: 'PUT',
      body: JSON.stringify({ careTeamId }),
    })
    return true
  } catch {
    return false
  }
}

const archiveParsed = computed(() => {
  if (form.value.identityType !== 'ID_CARD') return null
  return parseIdCard(form.value.identityValue)
})

watch(archiveParsed, (v) => {
  if (!v) return
  form.value.gender = v.gender
  form.value.birthday = v.birthday
  formRef.value?.clearValidate(['gender', 'birthday'])
})

function parseIdCard(raw: string) {
  const id = (raw || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(id)) return null
  const y = id.slice(6, 10)
  const m = id.slice(10, 12)
  const d = id.slice(12, 14)
  const birth = new Date(Number(y), Number(m) - 1, Number(d))
  if (
    birth.getFullYear() !== Number(y) ||
    birth.getMonth() !== Number(m) - 1 ||
    birth.getDate() !== Number(d)
  ) {
    return null
  }
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  birth.setHours(0, 0, 0, 0)
  if (birth.getTime() > today.getTime()) return null
  const birthday = `${y}-${m}-${d}`
  const genderCode = Number(id.charAt(16))
  const gender = Number.isNaN(genderCode) ? 'UNKNOWN' : genderCode % 2 === 1 ? 'MALE' : 'FEMALE'
  return { birthday, gender }
}

function disabledBirthdayDate(d: Date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  return x.getTime() > today.getTime()
}

const rules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入姓名', trigger: 'blur' }],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  birthday: [
    { required: true, message: '请选择日期', trigger: 'change' },
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback()
          return
        }
        if (disabledBirthdayDate(new Date(value))) {
          callback(new Error('出生日期不能晚于今天'))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
  mobile: [
    { required: true, whitespace: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const v = String(value || '').trim()
        if (!v) {
          callback()
          return
        }
        if (!/^1[3-9]\d{9}$/.test(v)) {
          callback(new Error('请输入正确的手机号'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  identityValue: [
    {
      validator: (_rule, value, callback) => {
        const v = String(value || '').trim()
        if (!v) {
          callback()
          return
        }
        if (form.value.identityType === 'ID_CARD' && !parseIdCard(v)) {
          callback(new Error('身份证号格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function resetAssignState() {
  step.value = 'form'
  createdPeopleId.value = ''
  createdDisplayName.value = ''
  assignForm.value = { teamId: null }
  teams.value = []
  filteredTeams.value = []
  searchQuery.value = ''
  allowEnterSelect.value = false
  assigning.value = false
}

function applyPreset() {
  const next = emptyForm()
  const p = props.preset
  if (p) {
    if (p.name?.trim()) next.name = p.name.trim()
    if (p.gender === 'MALE' || p.gender === 'FEMALE' || p.gender === 'UNKNOWN') {
      next.gender = p.gender
    }
    if (p.birthday?.trim()) next.birthday = p.birthday.trim().slice(0, 10)
    if (p.mobile?.trim()) next.mobile = p.mobile.trim()
  }
  form.value = next
  showIdentity.value = false
  duplicates.value = []
  duplicateVisible.value = false
  resetAssignState()
  nextTick(() => formRef.value?.clearValidate())
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      applyPreset()
      emit('phase', 'form')
    }
  },
)

watch(step, (s) => {
  if (props.modelValue) emit('phase', s)
})

/** 一输入/一选择就清红框，不必等下次提交 */
watch(
  () => form.value.name,
  () => formRef.value?.clearValidate('name'),
)
watch(
  () => form.value.gender,
  () => formRef.value?.clearValidate('gender'),
)
watch(
  () => form.value.birthday,
  () => formRef.value?.clearValidate('birthday'),
)
watch(
  () => form.value.mobile,
  () => formRef.value?.clearValidate('mobile'),
)
watch(
  () => form.value.identityValue,
  () => formRef.value?.clearValidate('identityValue'),
)

const formDirty = computed(() => {
  const f = form.value
  return !!(
    f.name.trim() ||
    f.gender ||
    f.birthday ||
    f.mobile.trim() ||
    f.identityValue.trim() ||
    showIdentity.value
  )
})

/** 结束建档流程：通知父级刷新左栏，再收起抽屉 */
function emitCreatedAndClose(careTeamAssigned: boolean, careTeamId?: string | null) {
  const peopleId = createdPeopleId.value
  const displayName = createdDisplayName.value
  const teamId =
    (careTeamId || '').trim() ||
    (careTeamAssigned ? (assignForm.value.teamId || props.defaultCareTeamId || '').trim() : '') ||
    null
  createdPeopleId.value = ''
  allowCloseOnce.value = true
  if (peopleId) {
    emit('created', { peopleId, displayName, careTeamAssigned, careTeamId: teamId })
  }
  visible.value = false
}

const beforeClose: DrawerProps['beforeClose'] = (done) => {
  if (allowCloseOnce.value) {
    allowCloseOnce.value = false
    done()
    return
  }
  // 建档已成功、停在分配步：关闭视为「暂不分配」，患者进入「未分组」
  if (step.value === 'assign' && createdPeopleId.value) {
    if (assigning.value) return
    const peopleId = createdPeopleId.value
    const displayName = createdDisplayName.value
    createdPeopleId.value = ''
    ElMessage.info('已跳过分组。该患者将进入「未分组患者」池，您可随时在左侧列表处理。')
    if (peopleId) {
      emit('created', { peopleId, displayName, careTeamAssigned: false })
    }
    done()
    return
  }
  if (submitting.value || !formDirty.value) {
    done()
    return
  }
  ElMessageBox.confirm('档案信息尚未保存，确认要关闭吗？', '提示', {
    type: 'warning',
    confirmButtonText: '确认关闭',
    cancelButtonText: '继续填写',
  })
    .then(() => done())
    .catch(() => {
      /* 留在抽屉 */
    })
}

function requestClose() {
  beforeClose?.(() => {
    visible.value = false
  })
}

async function focusFirstInvalid() {
  await nextTick()
  const f = form.value
  if (!f.name.trim()) {
    nameInputRef.value?.focus()
    return
  }
  // 性别/日期为选择器，滚动到表单顶部附近即可
  const err = document.querySelector(
    '.create-archive-drawer .el-form-item.is-error',
  ) as HTMLElement | null
  err?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function submit() {
  if (submitting.value) return
  if (showIdentity.value && form.value.identityValue.trim()) {
    try {
      await formRef.value?.validateField('identityValue')
    } catch {
      void focusFirstInvalid()
      return
    }
  }
  try {
    await formRef.value?.validate()
  } catch {
    void focusFirstInvalid()
    return
  }

  submitting.value = true
  try {
    const f = form.value
    try {
      const q = new URLSearchParams({
        name: f.name.trim(),
        gender: f.gender,
        birthday: f.birthday,
        mobile: f.mobile.trim(),
      })
      const res = await api<{ data: DuplicateCandidate[] }>(
        `/api/b/v1/patients/archives/duplicates?${q}`,
      )
      duplicates.value = res.data ?? []
    } catch {
      duplicates.value = []
    }
    const needConfirm = duplicates.value.filter((d) => d.confirmRequired)
    if (needConfirm.length) {
      duplicateVisible.value = true
      return
    }
    const auto = duplicates.value.find((d) => !d.confirmRequired)
    if (auto?.inCurrentOrg) {
      ElMessage.warning('该患者已在本机构')
      return
    }
    await createArchive(auto?.peopleId ?? null, { keepSubmitting: true })
  } finally {
    submitting.value = false
  }
}

async function loadTeamsForAssign() {
  loadingTeams.value = true
  try {
    const res = await api<{ data: CareTeamListItem[] }>(
      '/api/b/v1/care-teams?assignableOnly=true',
    )
    teams.value = res.data || []
    filteredTeams.value = teams.value.slice()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载健管组失败')
    teams.value = []
    filteredTeams.value = []
  } finally {
    loadingTeams.value = false
  }
}

async function tryAutoJoinSingleTeam(peopleId: string): Promise<string | null> {
  try {
    const res = await api<{ data: CareTeamListItem[] }>(
      '/api/b/v1/care-teams?assignableOnly=true',
    )
    const list = res.data || []
    if (list.length !== 1 || !list[0]?.id) return null
    await api(`/api/b/v1/patients/${peopleId}/care-team`, {
      method: 'PUT',
      body: JSON.stringify({ careTeamId: list[0].id }),
    })
    return list[0].id
  } catch {
    return null
  }
}

function matchesTeamQuery(t: CareTeamListItem, query: string) {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const hay = [t.name, t.primaryCareManagerName, t.primaryDoctorName]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return hay.includes(q)
}

function filterTeamMethod(query: string) {
  searchQuery.value = query
  allowEnterSelect.value = !!query.trim()
  filteredTeams.value = teams.value.filter((t) => matchesTeamQuery(t, query))
}

function clearAssignDefaultHover() {
  if (assignForm.value.teamId) return
  const popper = document.querySelector('.care-team-select-popper')
  popper?.querySelectorAll('.el-select-dropdown__item.is-hovering').forEach((el) => {
    el.classList.remove('is-hovering')
  })
}

function onAssignSelectVisible(open: boolean) {
  if (!open) return
  searchQuery.value = ''
  allowEnterSelect.value = false
  filteredTeams.value = teams.value.slice()
  nextTick(() => {
    clearAssignDefaultHover()
    requestAnimationFrame(() => clearAssignDefaultHover())
  })
}

function onAssignSelectKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    allowEnterSelect.value = true
    return
  }
  if (e.key === 'Enter' && !assignForm.value.teamId && !allowEnterSelect.value) {
    e.preventDefault()
    e.stopPropagation()
  }
}

async function enterAssignStep(peopleId: string, displayName: string) {
  createdPeopleId.value = peopleId
  createdDisplayName.value = displayName
  assignForm.value = { teamId: null }
  step.value = 'assign'
  // 立刻通知父级：第二步打勾 + AI 同步播报（不必等分配结束）
  emit('archived', { peopleId, displayName })
  await loadTeamsForAssign()
  // 仅 1 个组时默认选中，省掉点开下拉再选
  if (teams.value.length === 1 && teams.value[0]?.id) {
    assignForm.value.teamId = teams.value[0].id
  }
  nextTick(() => assignFormRef.value?.clearValidate())
}

async function submitAssign() {
  if (assigning.value || !createdPeopleId.value) return
  try {
    await assignFormRef.value?.validate()
  } catch {
    return
  }
  if (!assignForm.value.teamId) return
  assigning.value = true
  try {
    await api(`/api/b/v1/patients/${createdPeopleId.value}/care-team`, {
      method: 'PUT',
      body: JSON.stringify({ careTeamId: assignForm.value.teamId }),
    })
    ElMessage.success('已分配健管组')
    emitCreatedAndClose(true, assignForm.value.teamId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分配失败')
  } finally {
    assigning.value = false
  }
}

function skipAssign() {
  if (assigning.value) return
  ElMessage.info('已跳过分组。该患者将进入「未分组患者」池，您可随时在左侧列表处理。')
  emitCreatedAndClose(false)
}

/** 供驾驶舱 AI 芯片调用 */
function submitAssignFromParent() {
  void submitAssign()
}

function skipAssignFromParent() {
  skipAssign()
}

defineExpose({
  submitAssignFromParent,
  skipAssignFromParent,
  get phase() {
    return step.value
  },
})

async function createArchive(
  attachPeopleId: string | null,
  opts?: { keepSubmitting?: boolean },
) {
  if (!opts?.keepSubmitting) {
    if (submitting.value) return
    submitting.value = true
  }
  const f = form.value
  const idValue = f.identityValue.trim()
  try {
    const res = await api<{ data?: { peopleId?: string; displayName?: string } }>(
      '/api/b/v1/patients/archives',
      {
        method: 'POST',
        body: JSON.stringify({
          name: f.name.trim(),
          attachPeopleId: attachPeopleId || undefined,
          gender: f.gender,
          birthday: f.birthday,
          mobile: f.mobile.trim(),
          mobileOwner: 'SELF',
          noMobile: false,
          identityType: idValue ? f.identityType : undefined,
          identityValue: idValue ? idValue.toUpperCase() : undefined,
        }),
      },
    )
    const peopleId = res.data?.peopleId || ''
    const displayName = res.data?.displayName || f.name.trim()
    ElMessage.success(attachPeopleId ? '已关联已有档案' : '建档成功')
    duplicateVisible.value = false
    if (!peopleId) {
      allowCloseOnce.value = true
      visible.value = false
      return
    }
    createdPeopleId.value = peopleId
    createdDisplayName.value = displayName
    // 从指定组「+」进入：直接入该组
    const presetTeamId = (props.defaultCareTeamId || '').trim()
    if (presetTeamId) {
      const ok = await joinCareTeam(peopleId, presetTeamId)
      if (ok) {
        ElMessage.success(
          props.defaultCareTeamName?.trim()
            ? `已加入「${props.defaultCareTeamName.trim()}」`
            : '已分配健管组',
        )
        emitCreatedAndClose(true, presetTeamId)
        return
      }
      ElMessage.warning('建档成功，但自动入组失败，请手动分配')
      await enterAssignStep(peopleId, displayName)
      return
    }
    // 仅 1 个健管组时自动入组，跳过分配步
    const autoTeamId = await tryAutoJoinSingleTeam(peopleId)
    if (autoTeamId) {
      emitCreatedAndClose(true, autoTeamId)
      return
    }
    await enterAssignStep(peopleId, displayName)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建档失败')
  } finally {
    if (!opts?.keepSubmitting) submitting.value = false
  }
}

function duplicateLabel(d: DuplicateCandidate) {
  const parts = [
    d.gender === 'MALE' ? '男' : d.gender === 'FEMALE' ? '女' : '',
    d.birthday ? String(d.birthday).slice(0, 10) : '',
  ]
  if (d.mobileMasked) parts.push(d.mobileMasked)
  if (d.hasIdentity) parts.push('已登记证件')
  return parts.filter(Boolean).join(' · ')
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    size="400px"
    destroy-on-close
    append-to-body
    class="create-archive-drawer"
    :before-close="beforeClose"
  >
    <template v-if="step === 'form'">
      <p class="hint">{{ formHint }}</p>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        require-asterisk-position="left"
        class="form"
        @submit.prevent
      >
        <el-form-item label="姓名" prop="name" required>
          <el-input
            ref="nameInputRef"
            v-model="form.name"
            maxlength="64"
            placeholder="请输入姓名"
          />
        </el-form-item>
        <div class="pair-row">
          <el-form-item label="性别" prop="gender" required class="field-gender">
            <el-select v-model="form.gender" :disabled="!!archiveParsed" style="width: 100%">
              <el-option label="男" value="MALE" />
              <el-option label="女" value="FEMALE" />
            </el-select>
          </el-form-item>
          <el-form-item label="出生日期" prop="birthday" required class="field-birthday">
            <el-date-picker
              v-model="form.birthday"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择日期"
              :disabled="!!archiveParsed"
              :disabled-date="disabledBirthdayDate"
              style="width: 100%"
            />
          </el-form-item>
        </div>
        <el-form-item label="联系电话（患者本人）" prop="mobile" required class="field-mobile">
          <el-input v-model="form.mobile" maxlength="11" placeholder="请输入手机号" />
        </el-form-item>

        <el-button
          v-if="!showIdentity"
          link
          type="primary"
          class="id-toggle"
          :disabled="submitting"
          @click="showIdentity = true"
        >
          + 补充证件信息（选填）
        </el-button>
        <Transition name="id-panel">
          <div v-if="showIdentity" class="id-panel">
            <el-form-item label="证件类型">
              <el-select v-model="form.identityType" style="width: 100%">
                <el-option
                  v-for="opt in IDENTITY_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="证件号码" prop="identityValue">
              <el-input v-model="form.identityValue" placeholder="选填，可以后再补" />
              <div class="form-tip">填写身份证会自动带出性别和出生日期</div>
            </el-form-item>
            <el-button link type="primary" class="id-toggle" :disabled="submitting" @click="showIdentity = false">
              收起证件信息
            </el-button>
          </div>
        </Transition>
      </el-form>
    </template>

    <template v-else>
      <div class="assign-banner" role="status">
        <p class="hint assign-ok">
          <strong>建档成功！</strong>
          为了让【{{ createdDisplayName || '患者' }}】进入后续的首诊流程，请为其分配健管组。
        </p>
        <p class="hint assign-sub">也可以选择「暂不分配，稍后处理」。</p>
      </div>
      <el-form
        ref="assignFormRef"
        :model="assignForm"
        :rules="assignRules"
        label-position="top"
        class="form"
        require-asterisk-position="right"
        v-loading="loadingTeams"
        @submit.prevent
      >
        <el-form-item label="健管组" prop="teamId">
          <el-select
            v-model="assignForm.teamId"
            filterable
            clearable
            teleported
            fit-input-width
            :default-first-option="false"
            :disabled="!teams.length"
            :placeholder="
              teams.length === 1
                ? teams[0]?.name || '搜索组名或主责姓名'
                : '搜索组名或主责姓名'
            "
            style="width: 100%"
            popper-class="care-team-select-popper"
            :filter-method="filterTeamMethod"
            :popper-options="{
              modifiers: [{ name: 'offset', options: { offset: [0, 6] } }],
            }"
            @visible-change="onAssignSelectVisible"
            @keydown="onAssignSelectKeydown"
          >
            <el-option
              v-for="t in filteredTeams"
              :key="t.id"
              :label="t.name"
              :value="t.id"
            >
              <div class="opt" @mouseenter="allowEnterSelect = true">
                <span class="opt-name">{{ t.name }}</span>
                <span class="opt-meta">
                  主责 {{ (t.primaryCareManagerName || '').trim() || '未指定' }}
                  <template v-if="(t.primaryDoctorName || '').trim()">
                    · 医生 {{ t.primaryDoctorName }}
                  </template>
                  · {{ t.memberCount ?? 0 }} 位患者
                </span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="!loadingTeams && !teams.length" class="assign-empty">
        暂无健管组。可先暂不分配，之后在左侧「未分组患者」里补分；或先去创建健管组。
      </div>
    </template>

    <template #footer>
      <div class="footer" v-if="step === 'form'">
        <el-button :disabled="submitting" @click="requestClose">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ submitting ? '创建中...' : '确认创建' }}
        </el-button>
      </div>
      <div class="footer" v-else>
        <el-button :disabled="assigning" @click="skipAssign">暂不分配，稍后处理</el-button>
        <el-button
          type="primary"
          :loading="assigning"
          :disabled="!teams.length"
          @click="submitAssign"
        >
          {{ assigning ? '分配中...' : '确认分配' }}
        </el-button>
      </div>
    </template>
  </el-drawer>

  <el-dialog v-model="duplicateVisible" title="可能是同一人" width="520px" append-to-body>
    <p class="dup-tip">
      这个手机号已有档案，但姓名或出生日期不一致。确认是同一人后再关联。
    </p>
    <div v-for="d in duplicates" :key="d.peopleId" class="dup-item">
      <div class="dup-main">
        <strong>{{ d.displayName }}</strong>
        <span class="dup-meta">{{ duplicateLabel(d) }}</span>
      </div>
      <el-button
        v-if="!d.inCurrentOrg"
        size="small"
        type="primary"
        :loading="submitting"
        @click="createArchive(d.peopleId)"
      >
        关联此人
      </el-button>
      <span v-else class="dup-in-org">已在本机构</span>
    </div>
    <template #footer>
      <el-button @click="duplicateVisible = false">返回修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.5;
  font-weight: 400;
  color: var(--ink-400, #9ca3af);
}
.assign-banner {
  margin: 0 0 18px;
}
.hint.assign-ok {
  margin: 0;
  color: var(--ink-700, #334155);
  font-weight: 500;
}
.hint.assign-ok strong {
  font-weight: 700;
  color: var(--ink-900, #111827);
}
.hint.assign-sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  font-weight: 400;
  color: var(--ink-500, #6b7280);
}
.assign-empty {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-500, #6b7280);
}
.opt {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 2px 0;
  line-height: 1.35;
}
.opt-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800, #1f2937);
}
.opt-meta {
  font-size: 12px;
  color: var(--ink-400, #9ca3af);
}
.form {
  padding-right: 4px;
}
.pair-row {
  display: grid;
  grid-template-columns: minmax(96px, 1fr) minmax(0, 2.2fr);
  gap: 16px;
}
.pair-row .el-form-item {
  margin-bottom: 18px;
}
.form-tip {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-400, #9ca3af);
}
.id-toggle {
  margin-top: 4px;
  margin-bottom: 4px;
  padding: 0;
  height: auto;
}
.id-panel {
  overflow: hidden;
}
.id-panel-enter-active,
.id-panel-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}
.id-panel-enter-from,
.id-panel-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
.dup-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ink-600, #4b5563);
  line-height: 1.5;
}
.dup-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line-100, #eef0f4);
}
.dup-item:last-child {
  border-bottom: none;
}
.dup-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dup-meta {
  font-size: 12px;
  color: var(--ink-500, #6b7280);
}
.dup-in-org {
  font-size: 12px;
  color: var(--ink-400, #9ca3af);
  white-space: nowrap;
}
</style>

<style>
/* append-to-body：表单层级、报错间距、星号间距 */
.create-archive-drawer.el-drawer .el-drawer__body {
  padding-top: 12px;
}
.create-archive-drawer.el-drawer .el-drawer__footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  border-top: 1px solid var(--ink-100, #e5e7eb);
  background: #fff;
  padding: 12px 16px;
}
.create-archive-drawer .form .el-form-item {
  margin-bottom: 18px;
}
.create-archive-drawer .form .el-form-item__label {
  margin-bottom: 6px !important;
  padding: 0 !important;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-500, #6b7280);
  line-height: 1.4;
}
.create-archive-drawer .form .el-form-item.is-required:not(.is-no-asterisk).asterisk-left > .el-form-item__label::before,
.create-archive-drawer .form .el-form-item.is-required:not(.is-no-asterisk) > .el-form-item__label::before {
  margin-right: 4px;
}
.create-archive-drawer .form .el-form-item__error {
  padding-top: 4px;
  font-size: 12px;
  line-height: 1.4;
}
/* 报错后与「补充证件」拉开呼吸感 */
.create-archive-drawer .form .field-mobile {
  margin-bottom: 8px;
}
.create-archive-drawer .form .field-mobile.is-error {
  margin-bottom: 4px;
}
.create-archive-drawer .form .field-mobile + .id-toggle {
  margin-top: 18px;
}
.create-archive-drawer .form .el-input__inner,
.create-archive-drawer .form .el-select__selected-item,
.create-archive-drawer .form .el-select__placeholder,
.create-archive-drawer .form .el-date-editor .el-input__inner {
  font-size: 14px;
  font-weight: 400;
  color: var(--ink-800, #1f2937);
}
.create-archive-drawer .form .el-input__wrapper,
.create-archive-drawer .form .el-select__wrapper {
  min-height: 36px;
}
</style>
