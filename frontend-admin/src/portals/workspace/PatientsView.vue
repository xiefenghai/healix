<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { TABLE_COL } from '../../shared/table-columns'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  identityMask?: string
  identityType?: string
  careTeamId?: string | null
  careTeamName?: string | null
  joinedAt?: string
}

interface CareTeamListItem {
  id: string
  name: string
}

const IDENTITY_OPTIONS = [
  { label: '身份证', value: 'ID_CARD' },
  { label: '军官证', value: 'MILITARY_ID' },
  { label: '港澳通行证', value: 'HK_MACAU_PASS' },
  { label: '台胞证', value: 'TAIWAN_PASS' },
  { label: '其他', value: 'OTHER' },
]

const router = useRouter()
const loading = ref(false)
const list = ref<OrgPatientListItem[]>([])
const teams = ref<CareTeamListItem[]>([])
const keyword = ref('')
const careTeamId = ref<string | ''>('')
const unassignedOnly = ref(false)
const adherenceFilter = ref<'' | 'PLAN_INCOMPLETE' | 'MED_INCOMPLETE' | 'STREAK_GE_3'>('')
const adherenceMode = computed(() => !!adherenceFilter.value)

const archiveVisible = ref(false)
const archiving = ref(false)
const archiveForm = ref({
  name: '',
  noIdentity: false,
  identityType: 'ID_CARD',
  identityValue: '',
  gender: '',
  birthday: '' as string,
  attachPeopleId: null as string | null,
})

const joinVisible = ref(false)
const joining = ref(false)
const joinTarget = ref<OrgPatientListItem | null>(null)
const joinTeamId = ref<string | null>(null)

const attachCandidates = computed(() => list.value)

const archiveParsed = computed(() => {
  if (archiveForm.value.noIdentity || archiveForm.value.identityType !== 'ID_CARD') return null
  return parseIdCard(archiveForm.value.identityValue)
})

const archiveGenderLabel = computed(() => {
  if (archiveParsed.value) return formatGender(archiveParsed.value.gender)
  return archiveForm.value.gender ? formatGender(archiveForm.value.gender) : '-'
})

const archiveBirthdayLabel = computed(() => {
  if (archiveParsed.value) return archiveParsed.value.birthday.slice(0, 7)
  return archiveForm.value.birthday ? archiveForm.value.birthday.slice(0, 7) : '-'
})

const needManualDemographics = computed(
  () =>
    archiveForm.value.noIdentity
    || (archiveForm.value.identityType !== 'ID_CARD' && !archiveForm.value.noIdentity),
)

function parseIdCard(raw: string) {
  const id = (raw || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(id)) return null
  const y = id.slice(6, 10)
  const m = id.slice(10, 12)
  const d = id.slice(12, 14)
  const birthday = `${y}-${m}-${d}`
  const genderCode = Number(id.charAt(16))
  const gender = Number.isNaN(genderCode) ? 'UNKNOWN' : genderCode % 2 === 1 ? 'MALE' : 'FEMALE'
  return { birthday, gender }
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

function ageFromBirthday(birthday?: string | number[]) {
  if (birthday == null || birthday === '') return '-'
  let b: Date
  if (Array.isArray(birthday) && birthday.length >= 3) {
    b = new Date(Number(birthday[0]), Number(birthday[1]) - 1, Number(birthday[2]))
  } else {
    b = new Date(birthday as string)
  }
  if (Number.isNaN(b.getTime())) return '-'
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const md = now.getMonth() - b.getMonth()
  if (md < 0 || (md === 0 && now.getDate() < b.getDate())) age -= 1
  return String(age)
}

async function ensureOrg() {
  if (!getCurrentOrgId()) {
    await router.replace('/workspace/orgs')
    return false
  }
  return true
}

async function loadTeams() {
  const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
  teams.value = res.data ?? []
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    if (adherenceFilter.value) {
      const q = new URLSearchParams()
      q.set('filter', adherenceFilter.value)
      q.set('page', '1')
      q.set('size', '200')
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      const res = await api<{
        data: {
          items: Array<{
            peopleId: string
            displayName: string
            careTeamId?: string | null
            careTeamName?: string | null
          }>
        }
      }>(`/api/b/v1/adherence/patients?${q}`)
      list.value = (res.data?.items ?? []).map((row) => ({
        peopleId: row.peopleId,
        displayName: row.displayName,
        careTeamId: row.careTeamId,
        careTeamName: row.careTeamName,
      }))
    } else {
      const q = new URLSearchParams()
      if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
      if (unassignedOnly.value) q.set('unassigned', 'true')
      else if (careTeamId.value !== '') q.set('careTeamId', String(careTeamId.value))
      const qs = q.toString()
      const res = await api<{ data: OrgPatientListItem[] }>(`/api/b/v1/org-patients${qs ? `?${qs}` : ''}`)
      list.value = res.data ?? []
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

watch(unassignedOnly, (v) => {
  if (v) {
    careTeamId.value = ''
    adherenceFilter.value = ''
  }
})

watch(adherenceFilter, (v) => {
  if (v) unassignedOnly.value = false
})

function openArchive() {
  archiveForm.value = {
    name: '',
    noIdentity: false,
    identityType: 'ID_CARD',
    identityValue: '',
    gender: '',
    birthday: '',
    attachPeopleId: null,
  }
  archiveVisible.value = true
}

async function submitArchive() {
  if (!archiveForm.value.name.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (!archiveForm.value.noIdentity) {
    if (!archiveForm.value.identityValue.trim()) {
      ElMessage.warning('请填写证件号码')
      return
    }
    if (archiveForm.value.identityType === 'ID_CARD' && !parseIdCard(archiveForm.value.identityValue)) {
      ElMessage.warning('身份证号格式不正确')
      return
    }
  }
  if (needManualDemographics.value) {
    if (!archiveForm.value.gender || !archiveForm.value.birthday) {
      ElMessage.warning('请填写性别和出生日期')
      return
    }
  }
  archiving.value = true
  try {
    await api('/api/b/v1/patients/archives', {
      method: 'POST',
      body: JSON.stringify({
        name: archiveForm.value.name.trim(),
        attachPeopleId: archiveForm.value.attachPeopleId || undefined,
        noIdentity: archiveForm.value.noIdentity,
        identityType: archiveForm.value.noIdentity ? undefined : archiveForm.value.identityType,
        identityValue: archiveForm.value.noIdentity
          ? undefined
          : archiveForm.value.identityValue.trim().toUpperCase(),
        gender: needManualDemographics.value ? archiveForm.value.gender : undefined,
        birthday: needManualDemographics.value ? archiveForm.value.birthday : undefined,
      }),
    })
    ElMessage.success('建档成功')
    archiveVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建档失败')
  } finally {
    archiving.value = false
  }
}

function openJoin(row: OrgPatientListItem) {
  joinTarget.value = row
  joinTeamId.value = null
  joinVisible.value = true
}

async function submitJoin() {
  if (!joinTarget.value || !joinTeamId.value) {
    ElMessage.warning('请选择健管组')
    return
  }
  joining.value = true
  try {
    await api(`/api/b/v1/care-teams/${joinTeamId.value}/members`, {
      method: 'POST',
      body: JSON.stringify({
        memberType: 'PATIENT',
        peopleId: joinTarget.value.peopleId,
      }),
    })
    ElMessage.success('已加入健管组')
    joinVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加入失败')
  } finally {
    joining.value = false
  }
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  try {
    await Promise.all([loadTeams(), load()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>患者管理</h1>
        <p>当前机构：{{ getCurrentOrgName() || '-' }} · 建档、入组与机构患者列表</p>
      </div>
      <el-button @click="router.push('/workspace/orgs')">切换机构</el-button>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="姓名"
          clearable
          style="width: 160px"
          @keyup.enter="load"
        />
        <el-select
          v-model="careTeamId"
          clearable
          placeholder="健管组"
          style="width: 180px"
          :disabled="unassignedOnly"
        >
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-checkbox v-model="unassignedOnly" :disabled="adherenceMode">仅未入组</el-checkbox>
        <el-select
          v-model="adherenceFilter"
          clearable
          placeholder="依从性"
          style="width: 180px"
          :disabled="unassignedOnly"
        >
          <el-option label="当日方案未完成" value="PLAN_INCOMPLETE" />
          <el-option label="当日用药未完成" value="MED_INCOMPLETE" />
          <el-option label="连续未执行≥3天" value="STREAK_GE_3" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openArchive">患者建档</el-button>
      </div>

      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column prop="peopleId" label="患者ID" v-bind="TABLE_COL.bizId" />
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column label="性别" width="70">
          <template #default="{ row }">{{ formatGender(row.gender) }}</template>
        </el-table-column>
        <el-table-column label="年龄" width="70">
          <template #default="{ row }">{{ ageFromBirthday(row.birthday) }}</template>
        </el-table-column>
        <el-table-column label="证件" min-width="160">
          <template #default="{ row }">{{ row.identityMask || '-' }}</template>
        </el-table-column>
        <el-table-column label="健管组" min-width="140">
          <template #default="{ row }">
            <span v-if="row.careTeamName">{{ row.careTeamName }}</span>
            <span v-else class="muted">未入组</span>
          </template>
        </el-table-column>
        <el-table-column label="入机构时间" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.joinedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" v-bind="TABLE_COL.actionsLg">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push({ path: `/workspace/patients/${row.peopleId}/archive`, query: { name: row.displayName } })"
            >
              详情
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="!!row.careTeamId"
              @click="openJoin(row)"
            >
              加入健管组
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="archiveVisible" title="患者建档" width="560px">
      <el-form label-width="110px">
        <el-form-item label="姓名" required>
          <el-input v-model="archiveForm.name" />
        </el-form-item>
        <el-form-item label="挂到已有患者">
          <el-select
            v-model="archiveForm.attachPeopleId"
            clearable
            filterable
            placeholder="同人补证时选择（S1）"
            style="width: 100%"
          >
            <el-option
              v-for="p in attachCandidates"
              :key="p.peopleId"
              :label="`${p.displayName} (#${p.peopleId})`"
              :value="p.peopleId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="暂无证件">
          <el-switch v-model="archiveForm.noIdentity" />
        </el-form-item>
        <template v-if="!archiveForm.noIdentity">
          <el-form-item label="证件类型" required>
            <el-select v-model="archiveForm.identityType" style="width: 100%">
              <el-option
                v-for="opt in IDENTITY_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="证件号码" required>
            <el-input v-model="archiveForm.identityValue" />
          </el-form-item>
        </template>
        <template v-if="needManualDemographics">
          <el-form-item label="性别" required>
            <el-select v-model="archiveForm.gender" style="width: 100%">
              <el-option label="男" value="MALE" />
              <el-option label="女" value="FEMALE" />
              <el-option label="未知" value="UNKNOWN" />
            </el-select>
          </el-form-item>
          <el-form-item label="出生日期" required>
            <el-date-picker
              v-model="archiveForm.birthday"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
        </template>
        <el-row v-else :gutter="12">
          <el-col :span="12">
            <el-form-item label="性别">
              <span>{{ archiveGenderLabel }}</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出生年月" label-width="80px">
              <span>{{ archiveBirthdayLabel }}</span>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="archiveVisible = false">取消</el-button>
        <el-button type="primary" :loading="archiving" @click="submitArchive">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="joinVisible" title="加入健管组" width="420px">
      <p v-if="joinTarget" class="hint">患者：{{ joinTarget.displayName }}</p>
      <el-form label-width="80px">
        <el-form-item label="健管组" required>
          <el-select v-model="joinTeamId" filterable style="width: 100%">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="joinVisible = false">取消</el-button>
        <el-button type="primary" :loading="joining" @click="submitJoin">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  gap: 12px;
}
.page-title h1 { margin: 0; font-size: 16px; }
.page-title p { margin: 6px 0 0; color: var(--admin-muted); font-size: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
.muted { color: var(--admin-muted); font-size: 12px; }
.hint { margin: 0 0 12px; color: var(--admin-muted); font-size: 13px; }
</style>
