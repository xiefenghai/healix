<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { ageFromBirthday, formatDateTime, formatGender } from '../shared/format'

function dial(mobile?: string | null) {
  const n = String(mobile || '').replace(/\s+/g, '')
  if (!n) {
    showToast('暂无手机号')
    return
  }
  window.location.href = `tel:${n}`
}

async function copyMobile(mobile?: string | null) {
  const n = String(mobile || '').trim()
  if (!n) {
    showToast('暂无手机号')
    return
  }
  try {
    await navigator.clipboard.writeText(n)
    showToast('已复制手机号')
  } catch {
    showToast(n)
  }
}

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  identityMask?: string
  careTeamName?: string | null
  diseaseLabels?: string[]
  primaryCareManagerName?: string | null
  clientLinked?: boolean
  mobile?: string | null
  watched?: boolean | null
}

interface Completeness {
  percent?: number
}

interface Focus {
  riskLevel?: string
  planRate7d?: number | null
  bloodPressure?: string | null
  openTasks?: Array<{ id: string; taskTypeLabel: string; summary?: string; overdue: boolean }>
}

interface MetricSlot {
  slotKey: string
  metricType: string
  bpContext?: string
  mealContext?: string
  value?: number | string | null
  unit?: string
  recordedAt?: string
  extra?: Record<string, unknown>
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const watching = ref(false)
const nudging = ref(false)
const escalating = ref(false)
const issuing = ref(false)
const patient = ref<OrgPatientListItem | null>(null)
const completeness = ref<number | null>(null)
const focus = ref<Focus | null>(null)
const metrics = ref<MetricSlot[]>([])
const inviteCode = ref('')

const ageGender = computed(() => {
  if (!patient.value) return ''
  const age = ageFromBirthday(patient.value.birthday)
  const gender = formatGender(patient.value.gender)
  return [age != null ? `${age}岁` : '', gender].filter(Boolean).join(' · ')
})

const riskLabel = computed(() => {
  const r = focus.value?.riskLevel
  if (r === 'HIGH') return '高风险'
  if (r === 'MEDIUM') return '中风险'
  if (r === 'LOW') return '低风险'
  return ''
})

function metricLabel(slot: MetricSlot) {
  const type = slot.metricType
  let base = type
  if (type === 'BLOOD_PRESSURE' || type === 'BP_SYS' || type === 'BP') base = '血压'
  else if (type === 'BLOOD_GLUCOSE' || type === 'GLUCOSE') base = '血糖'
  else if (type === 'HEART_RATE' || type === 'HR') base = '心率'
  else if (type === 'BMI') base = 'BMI'
  else if (type === 'WEIGHT') base = '体重'
  else if (type === 'HEIGHT') base = '身高'
  if (slot.bpContext === 'HOME') return `${base}（家庭）`
  if (slot.bpContext === 'CLINIC') return `${base}（诊室）`
  if (slot.mealContext) return `${base}`
  return base
}

function metricValue(slot: MetricSlot) {
  const extra = slot.extra || {}
  if (extra.sys != null && extra.dia != null) {
    return `${extra.sys}/${extra.dia}${slot.unit ? ` ${slot.unit}` : ''}`
  }
  if (slot.value == null || slot.value === '') return '—'
  return `${slot.value}${slot.unit ? ` ${slot.unit}` : ''}`
}

async function load() {
  loading.value = true
  try {
    const [p, c, f, m] = await Promise.all([
      api<{ data: OrgPatientListItem }>(`/api/b/v1/patients/${peopleId.value}`),
      api<{ data: Completeness }>(`/api/b/v1/patients/${peopleId.value}/archive/completeness`).catch(
        () => null,
      ),
      api<{ data: Focus }>(`/api/b/v1/cockpit/focus/${peopleId.value}`).catch(() => null),
      api<{ data: MetricSlot[] }>(`/api/b/v1/patients/${peopleId.value}/metrics/latest`).catch(
        () => null,
      ),
    ])
    patient.value = p.data
    completeness.value = c?.data?.percent ?? null
    focus.value = f?.data ?? null
    metrics.value = (m?.data || []).filter((s) => s.value != null || s.extra?.sys != null).slice(0, 6)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function toggleWatch() {
  if (!patient.value) return
  watching.value = true
  try {
    if (patient.value.watched) {
      const res = await api<{ data: OrgPatientListItem }>(
        `/api/b/v1/patients/${peopleId.value}/watch`,
        { method: 'DELETE' },
      )
      patient.value = { ...patient.value, watched: res.data.watched ?? false }
      showToast('已取消关注')
    } else {
      const res = await api<{ data: OrgPatientListItem }>(
        `/api/b/v1/patients/${peopleId.value}/watch`,
        { method: 'POST' },
      )
      patient.value = { ...patient.value, watched: res.data.watched ?? true }
      showToast('已关注')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    watching.value = false
  }
}

async function remindPatient() {
  nudging.value = true
  try {
    const res = await api<{ data: { sent?: boolean; message?: string } }>(
      `/api/b/v1/adherence/patients/${peopleId.value}/nudge`,
      { method: 'POST' },
    )
    if (res.data?.sent) showToast(res.data.message || '已发送提醒')
    else showToast(res.data?.message || '未发送提醒')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提醒失败')
  } finally {
    nudging.value = false
  }
}

async function escalateFollowup() {
  escalating.value = true
  try {
    await api(`/api/b/v1/adherence/patients/${peopleId.value}/escalate`, {
      method: 'POST',
      body: JSON.stringify({ openFollowup: true, openPlanNudge: false }),
    })
    showToast('已创建依从随访待办')
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '催办失败')
  } finally {
    escalating.value = false
  }
}

async function issueActivation() {
  issuing.value = true
  try {
    const res = await api<{ data: { code: string } }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
      { method: 'POST', body: JSON.stringify({ validDays: 7 }) },
    )
    inviteCode.value = res.data.code
    try {
      await navigator.clipboard.writeText(res.data.code)
      showToast('已生成并复制激活码')
    } catch {
      showToast(`激活码 ${res.data.code}`)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发码失败')
  } finally {
    issuing.value = false
  }
}

async function copyInvite() {
  if (!inviteCode.value) {
    await issueActivation()
    return
  }
  try {
    await navigator.clipboard.writeText(inviteCode.value)
    showToast('已复制激活码')
  } catch {
    showToast(inviteCode.value)
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="患者摘要" left-arrow @click-left="router.back()">
      <template #right>
        <button type="button" class="nav-btn" :disabled="watching" @click="toggleWatch">
          {{ patient?.watched ? '取消关注' : '关注' }}
        </button>
      </template>
    </van-nav-bar>
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="patient">
      <section class="hero hx-surface">
        <div class="name-row">
          <div class="hx-avatar">{{ patient.displayName.slice(0, 1) }}</div>
          <div>
            <div class="title-line">
              <h1>{{ patient.displayName }}</h1>
              <span v-if="patient.watched" class="hx-chip warn">关注</span>
            </div>
            <p class="hx-muted">{{ ageGender || '—' }}</p>
            <p class="hx-muted">
              {{ patient.careTeamName || '未入组' }} ·
              {{ patient.clientLinked ? '已绑定 C 端' : '未绑定 C 端' }}
            </p>
          </div>
        </div>
        <div v-if="patient.diseaseLabels?.length" class="chips">
          <span v-for="d in patient.diseaseLabels.slice(0, 4)" :key="d" class="hx-chip">{{ d }}</span>
        </div>
        <div class="kpis">
          <div>
            <strong>{{ completeness != null ? `${completeness}%` : '—' }}</strong>
            <span>档案完整度</span>
          </div>
          <div>
            <strong>
              {{ focus?.planRate7d != null ? `${Math.round(focus.planRate7d * 100)}%` : '—' }}
            </strong>
            <span>7 日依从</span>
          </div>
          <div>
            <strong>{{ riskLabel || '—' }}</strong>
            <span>风险</span>
          </div>
        </div>
        <p v-if="focus?.bloodPressure" class="bp">血压 {{ focus.bloodPressure }}</p>
        <div v-if="patient.mobile" class="phone-row">
          <button type="button" class="phone-btn" @click="dial(patient.mobile)">拨打 {{ patient.mobile }}</button>
          <button type="button" class="phone-btn ghost" @click="copyMobile(patient.mobile)">复制</button>
        </div>
      </section>

      <section v-if="metrics.length" class="metrics hx-surface">
        <div class="metrics-head">
          <h3>最新指标</h3>
          <button
            type="button"
            class="more"
            @click="router.push(`/patients/${peopleId}/metrics`)"
          >
            近 7 日趋势
          </button>
        </div>
        <div class="metric-grid">
          <div v-for="m in metrics" :key="m.slotKey" class="metric">
            <span class="label">{{ metricLabel(m) }}</span>
            <strong>{{ metricValue(m) }}</strong>
            <small v-if="m.recordedAt" class="hx-muted">{{ formatDateTime(m.recordedAt) }}</small>
          </div>
        </div>
      </section>
      <div v-else class="actions hx-surface" style="margin-bottom: 12px">
        <button
          type="button"
          class="action"
          @click="router.push(`/patients/${peopleId}/metrics`)"
        >
          <span>查看指标趋势</span>
          <van-icon name="arrow" />
        </button>
      </div>

      <div class="actions hx-surface">
        <button type="button" class="action" @click="router.push(`/chat/${peopleId}`)">
          <span>患者沟通</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" @click="router.push(`/patients/${peopleId}/care-plan`)">
          <span>管理方案</span>
          <van-icon name="arrow" />
        </button>
        <button
          type="button"
          class="action"
          @click="router.push(`/patients/${peopleId}/medications`)"
        >
          <span>用药清单</span>
          <van-icon name="arrow" />
        </button>
        <button
          type="button"
          class="action"
          @click="router.push(`/patients/${peopleId}/assessments`)"
        >
          <span>疾病评估</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" @click="router.push(`/patients/${peopleId}/labs`)">
          <span>检验报告</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" @click="router.push(`/patients/${peopleId}/exams`)">
          <span>检查报告</span>
          <van-icon name="arrow" />
        </button>
        <button
          type="button"
          class="action"
          @click="router.push(`/patients/${peopleId}/health-reports`)"
        >
          <span>健康报告</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" @click="router.push(`/patients/${peopleId}/followups`)">
          <span>随访记录</span>
          <van-icon name="arrow" />
        </button>
        <button
          type="button"
          class="action"
          @click="router.push(`/patients/${peopleId}/followups/create`)"
        >
          <span>新建随访</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" :disabled="nudging" @click="remindPatient">
          <span>{{ nudging ? '发送中…' : '依从提醒（站内信）' }}</span>
          <van-icon name="arrow" />
        </button>
        <button type="button" class="action" :disabled="escalating" @click="escalateFollowup">
          <span>{{ escalating ? '创建中…' : '一键催办随访' }}</span>
          <van-icon name="arrow" />
        </button>
        <button
          v-if="!patient.clientLinked"
          type="button"
          class="action"
          :disabled="issuing"
          @click="issueActivation"
        >
          <span>{{ issuing ? '生成中…' : inviteCode ? `激活码 ${inviteCode}` : '生成 C 端激活码' }}</span>
          <van-icon name="arrow" />
        </button>
        <button
          v-if="!patient.clientLinked && inviteCode"
          type="button"
          class="action"
          @click="copyInvite"
        >
          <span>复制激活码</span>
          <van-icon name="arrow" />
        </button>
      </div>

      <section v-if="focus?.openTasks?.length" class="tasks">
        <h3>未完成任务</h3>
        <article
          v-for="t in focus.openTasks"
          :key="t.id"
          class="task hx-row"
          @click="router.push(`/tasks/${t.id}`)"
        >
          <div class="task-body">
            <div class="top">
              <span class="hx-chip">{{ t.taskTypeLabel }}</span>
              <span v-if="t.overdue" class="overdue">逾期</span>
            </div>
            <p>{{ t.summary || t.taskTypeLabel }}</p>
          </div>
        </article>
      </section>

      <p class="hint hx-muted">档案编辑、OCR、组织管理请使用电脑端</p>
    </template>
  </div>
</template>

<style scoped>
.nav-btn {
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-size: 14px;
  font-weight: 600;
}
.hero {
  margin: 8px 16px 12px;
  padding: 16px;
}
.name-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hero h1 {
  margin: 0;
  font-size: 22px;
  color: var(--hx-ink);
}
.hero p {
  margin: 4px 0 0;
  font-size: 13px;
}
.chips {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.kpis {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.kpis div {
  background: rgba(238, 246, 246, 0.9);
  border-radius: 14px;
  padding: 12px 8px;
  text-align: center;
}
.kpis strong {
  display: block;
  font-size: 17px;
  color: var(--hx-ink);
}
.kpis span {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
}
.bp {
  margin-top: 12px !important;
  color: var(--hx-text) !important;
  font-weight: 550;
}
.phone-row {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.phone-btn {
  flex: 1;
  height: 36px;
  border: 0;
  border-radius: 999px;
  background: var(--hx-teal);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
}
.phone-btn.ghost {
  flex: 0 0 auto;
  padding: 0 14px;
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
}
.metrics {
  margin: 0 16px 12px;
  padding: 14px;
}
.metrics-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.metrics h3 {
  margin: 0;
  font-size: 15px;
  color: var(--hx-ink);
}
.more {
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-size: 12px;
  font-weight: 700;
  padding: 0;
}
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.metric {
  background: var(--hx-teal-light);
  border-radius: 12px;
  padding: 10px;
}
.metric .label {
  display: block;
  font-size: 11px;
  color: var(--hx-muted);
}
.metric strong {
  display: block;
  margin-top: 4px;
  font-size: 15px;
  color: var(--hx-ink);
}
.metric small {
  display: block;
  margin-top: 2px;
  font-size: 10px;
}
.actions {
  margin: 0 16px;
  overflow: hidden;
}
.action {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px 16px;
  border: 0;
  background: transparent;
  color: var(--hx-ink);
  font-size: 15px;
  font-weight: 550;
}
.action:disabled {
  opacity: 0.55;
}
.action + .action {
  border-top: 1px solid var(--hx-line);
}
.tasks {
  padding: 18px 16px 8px;
}
.tasks h3 {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--hx-ink);
}
.task-body {
  flex: 1;
}
.task .top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.task p {
  margin: 8px 0 0;
  font-size: 14px;
}
.overdue {
  color: var(--hx-danger);
  font-size: 12px;
  font-weight: 650;
}
.hint {
  margin: 12px 20px 0;
  font-size: 12px;
  line-height: 1.5;
}
</style>
