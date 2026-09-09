<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { confirmLeaveBasicArchive } from '../../shared/basic-archive-dirty'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamName?: string | null
  clientLinked?: boolean
  clientCardCount?: number
}

interface InviteView {
  id: string
  code: string
  expireAt?: string
  used: boolean
  expired: boolean
  active: boolean
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const patient = ref<OrgPatientListItem | null>(null)
const latestInvite = ref<InviteView | null>(null)
const issuing = ref(false)

const activeTab = computed(() => {
  const path = route.path
  if (path.includes('/medications')) return 'medications'
  if (path.includes('/care-plan')) return 'care-plan'
  if (path.includes('/observations')) return 'observations'
  if (path.includes('/revisions')) return 'revisions'
  if (path.includes('/adherence')) return 'adherence'
  if (path.includes('/followups')) return 'followups'
  if (path.includes('/health-reports')) return 'health-reports'
  return 'archive'
})

const patientAge = computed(() => {
  const birthday = patient.value?.birthday
  const birth = parseBirthdayDate(birthday)
  if (!birth) return '-'
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age -= 1
  return age >= 0 ? `${age}岁` : '-'
})

/** Accept ISO string or Jackson timestamp array [y, m, d]. */
function parseBirthdayDate(birthday: unknown): Date | null {
  if (birthday == null || birthday === '') return null
  if (Array.isArray(birthday) && birthday.length >= 3) {
    const y = Number(birthday[0])
    const m = Number(birthday[1])
    const d = Number(birthday[2])
    if (!y || !m || !d) return null
    const birth = new Date(y, m - 1, d)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  if (typeof birthday === 'string' || typeof birthday === 'number') {
    const birth = new Date(birthday)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  return null
}

const cLinkLabel = computed(() => {
  if (!patient.value) return ''
  if (patient.value.clientLinked) return `C端已关联（${patient.value.clientCardCount || 0}）`
  return 'C端未关联'
})

async function loadPatient() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: OrgPatientListItem }>(`/api/b/v1/patients/${peopleId.value}`)
    patient.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载患者失败')
  }
}

async function loadInvites() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: InviteView[] }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
    )
    latestInvite.value = (res.data || []).find((i) => i.active) || res.data?.[0] || null
  } catch {
    latestInvite.value = null
  }
}

async function issueCode() {
  issuing.value = true
  try {
    const res = await api<{ data: InviteView }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
      { method: 'POST', body: JSON.stringify({ validDays: 7 }) },
    )
    latestInvite.value = res.data
    try {
      await navigator.clipboard.writeText(res.data.code)
      ElMessage.success(`已生成并复制：${res.data.code}`)
    } catch {
      ElMessage.success(`已生成：${res.data.code}`)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发码失败')
  } finally {
    issuing.value = false
  }
}

async function copyCode() {
  if (!latestInvite.value?.code) return
  try {
    await navigator.clipboard.writeText(latestInvite.value.code)
    ElMessage.success('已复制激活码')
  } catch {
    ElMessage.warning(latestInvite.value.code)
  }
}

async function onPatientTabBeforeLeave(newName: string | number, oldName: string | number) {
  if (String(oldName) !== 'archive' || String(newName) === 'archive') return true
  return confirmLeaveBasicArchive()
}

function switchTab(name: string | number) {
  const base = `/workspace/patients/${peopleId.value}`
  const query = route.query
  if (name === 'medications') {
    router.push({ path: `${base}/medications`, query })
  } else if (name === 'care-plan') {
    router.push({ path: `${base}/care-plan`, query })
  } else if (name === 'observations') {
    router.push({ path: `${base}/observations/metrics`, query })
  } else if (name === 'revisions') {
    router.push({ path: `${base}/revisions`, query })
  } else if (name === 'adherence') {
    router.push({ path: `${base}/adherence`, query })
  } else if (name === 'followups') {
    router.push({ path: `${base}/followups`, query })
  } else if (name === 'health-reports') {
    router.push({ path: `${base}/health-reports`, query })
  } else {
    router.push({ path: `${base}/archive`, query })
  }
}

watch(
  peopleId,
  async () => {
    await loadPatient()
    await loadInvites()
  },
  { immediate: false },
)

onMounted(async () => {
  await loadPatient()
  await loadInvites()
})
</script>

<template>
  <div class="patient-detail">
    <div class="detail-hero">
      <el-button text type="primary" class="back-btn" @click="router.push('/workspace/patients')">
        ← 返回患者列表
      </el-button>
      <div class="hero-body">
        <div class="hero-primary">
          <h1>{{ patient?.displayName || '患者' }}</h1>
          <div class="hero-meta">
            <span>{{ formatGender(patient?.gender) }}</span>
            <span class="hero-dot">·</span>
            <span>{{ patientAge }}</span>
          </div>
        </div>
        <div class="hero-extra">
          <el-tag v-if="patient?.careTeamName" size="small" type="success" effect="plain">
            {{ patient.careTeamName }}
          </el-tag>
          <el-tag size="small" :type="patient?.clientLinked ? 'success' : 'info'" effect="plain">
            {{ cLinkLabel }}
          </el-tag>
          <el-button size="small" type="primary" :loading="issuing" @click="issueCode">生成患者激活码</el-button>
          <el-button v-if="latestInvite?.active" size="small" @click="copyCode">
            复制 {{ latestInvite.code }}
          </el-button>
        </div>
      </div>
      <el-tabs
        :model-value="activeTab"
        class="detail-tabs"
        :before-leave="onPatientTabBeforeLeave"
        @tab-change="switchTab"
      >
        <el-tab-pane label="患者档案" name="archive" />
        <el-tab-pane label="健康数据" name="observations" />
        <el-tab-pane label="管理方案" name="care-plan" />
        <el-tab-pane label="用药管理" name="medications" />
        <el-tab-pane label="随访管理" name="followups" />
        <el-tab-pane label="管理报告" name="health-reports" />
        <el-tab-pane label="依从性分析" name="adherence" />
        <el-tab-pane label="修订历史" name="revisions" />
      </el-tabs>
    </div>
    <router-view />
  </div>
</template>

<style scoped>
.patient-detail {
  width: 100%;
}

.detail-hero {
  margin-bottom: 16px;
  padding: 16px 24px 0;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
}

.back-btn {
  padding-left: 0;
  margin-bottom: 12px;
}

.hero-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.hero-primary h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.hero-meta {
  color: var(--admin-muted, #667);
  font-size: 13px;
}

.hero-dot {
  margin: 0 6px;
}

.hero-extra {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.detail-tabs {
  margin-top: 4px;
}
</style>
