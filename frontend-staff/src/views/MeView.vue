<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, clearSession, getCurrentOrgName, getRoles } from '../shared/http'
import { stopCareChatRealtime } from '../shared/care-chat-realtime'
import { ensureChatNotifyPermission } from '../shared/notify'
import { isChatNotifyEnabled, setChatNotifyEnabled } from '../shared/prefs'
import IcpFooter from '../shared/IcpFooter.vue'

interface OpsSummary {
  doneCount: number
  onTimeRate?: number | null
  overdueOpenCount: number
  followupDoneCount: number
  reportPublishedCount: number
}

interface CockpitSummary {
  pendingCarePlanDraftCount?: number
  pendingReportDraftCount?: number
}

interface WorkspaceTaskSummary {
  doneTodayCount: number
  mineOpenCount: number
}

const router = useRouter()
const orgName = computed(() => getCurrentOrgName() || '未选择机构')
const roles = computed(() => getRoles().join('、') || '—')
const orgInitial = computed(() => (orgName.value || '机').slice(0, 1))
const loading = ref(false)
const ops = ref<OpsSummary | null>(null)
const cockpit = ref<CockpitSummary | null>(null)
const taskSummary = ref<WorkspaceTaskSummary | null>(null)
const notifyOn = ref(isChatNotifyEnabled())

function formatLocalYmd(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function shiftLocalYmd(base: Date, dayDelta: number) {
  const d = new Date(base)
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() + dayDelta)
  return formatLocalYmd(d)
}

function pct(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '—'
  return `${Math.round(Number(rate) * 100)}%`
}

async function loadStats() {
  loading.value = true
  try {
    const from = shiftLocalYmd(new Date(), -6)
    const to = formatLocalYmd(new Date())
    const q = new URLSearchParams({ from, to })
    const [opsRes, cockRes, taskRes] = await Promise.all([
      api<{ data: OpsSummary }>(`/api/b/v1/ops-stats/summary?${q}`).catch(() => null),
      api<{ data: CockpitSummary }>('/api/b/v1/cockpit/summary').catch(() => null),
      api<{ data: WorkspaceTaskSummary }>('/api/b/v1/workspace/tasks/summary').catch(() => null),
    ])
    ops.value = opsRes?.data ?? null
    cockpit.value = cockRes?.data ?? null
    taskSummary.value = taskRes?.data ?? null
  } finally {
    loading.value = false
  }
}

async function onNotifyChange(v: boolean) {
  if (v) {
    const ok = await ensureChatNotifyPermission()
    if (!ok) {
      notifyOn.value = false
      setChatNotifyEnabled(false)
      showToast('未获得通知权限，可在系统设置中开启')
      return
    }
  }
  notifyOn.value = v
  setChatNotifyEnabled(v)
  showToast(v ? '已开启后台消息提醒' : '已关闭消息提醒')
}

async function switchOrg() {
  await router.push('/orgs')
}

async function logout() {
  try {
    await showConfirmDialog({ title: '退出登录', message: '确认退出当前账号？' })
  } catch {
    return
  }
  stopCareChatRealtime()
  clearSession()
  showToast('已退出')
  await router.replace('/login')
}

onMounted(loadStats)
</script>

<template>
  <div class="hx-page">
    <header class="hx-page-head">
      <p class="eyebrow">Healix</p>
      <h1>我的</h1>
      <p>机构与账号设置</p>
    </header>

    <section class="org-panel">
      <div class="hx-avatar lg">{{ orgInitial }}</div>
      <div>
        <div class="label">当前机构</div>
        <div class="org">{{ orgName }}</div>
        <div class="roles hx-muted">角色 {{ roles }}</div>
      </div>
    </section>

    <section class="stats hx-surface">
      <div class="stats-head">
        <h2>近 7 日效能</h2>
        <span class="hx-muted">轻量回顾</span>
      </div>
      <van-loading v-if="loading" size="20px">加载中</van-loading>
      <div v-else class="stats-grid">
        <div>
          <strong>{{ ops?.doneCount ?? taskSummary?.doneTodayCount ?? 0 }}</strong>
          <span>办结量</span>
        </div>
        <div>
          <strong>{{ pct(ops?.onTimeRate) }}</strong>
          <span>按时率</span>
        </div>
        <div>
          <strong>{{ ops?.followupDoneCount ?? 0 }}</strong>
          <span>随访办结</span>
        </div>
        <div>
          <strong>{{ ops?.overdueOpenCount ?? 0 }}</strong>
          <span>仍逾期</span>
        </div>
      </div>
      <p class="stats-foot hx-muted">
        今日已办 {{ taskSummary?.doneTodayCount ?? 0 }} · 我的待办
        {{ taskSummary?.mineOpenCount ?? 0 }}
        <template v-if="cockpit?.pendingCarePlanDraftCount">
          · 方案草稿 {{ cockpit.pendingCarePlanDraftCount }}
        </template>
        <template v-if="cockpit?.pendingReportDraftCount">
          · 待审报告 {{ cockpit.pendingReportDraftCount }}
        </template>
      </p>
    </section>

    <div class="actions hx-surface">
      <div class="action switch-row">
        <span>后台消息提醒</span>
        <van-switch v-model="notifyOn" size="20px" @change="onNotifyChange" />
      </div>
      <button type="button" class="action" @click="router.push('/adherence')">
        <span>依从看板</span>
        <van-icon name="arrow" />
      </button>
      <button type="button" class="action" @click="switchOrg">
        <span>切换机构</span>
        <van-icon name="arrow" />
      </button>
      <button type="button" class="action danger" @click="logout">
        <span>退出登录</span>
        <van-icon name="arrow" />
      </button>
    </div>

    <p class="tip hx-muted">
      手机端覆盖待办概览、评估/检验检查只读、沟通催办、指标趋势与依从。档案编辑、OCR、组织管理请使用电脑端。
    </p>
    <IcpFooter />
  </div>
</template>

<style scoped>
.org-panel {
  margin: 8px 16px 14px;
  padding: 18px 16px;
  border-radius: 22px;
  display: flex;
  gap: 14px;
  align-items: center;
  background: linear-gradient(145deg, #1f8f8f 0%, #166f6f 70%, #125c5c 100%);
  color: #fff;
  box-shadow: 0 16px 32px rgba(22, 111, 111, 0.28);
}
.hx-avatar.lg {
  width: 52px;
  height: 52px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.18);
  box-shadow: none;
  font-size: 20px;
}
.label {
  font-size: 12px;
  opacity: 0.78;
}
.org {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.roles {
  margin-top: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.78) !important;
}
.stats {
  margin: 0 16px 12px;
  padding: 14px;
}
.stats-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.stats-head h2 {
  margin: 0;
  font-size: 15px;
  color: var(--hx-ink);
}
.stats-head span {
  font-size: 11px;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
}
.stats-grid div {
  text-align: center;
  padding: 10px 4px;
  border-radius: 12px;
  background: rgba(238, 246, 246, 0.95);
}
.stats-grid strong {
  display: block;
  font-size: 16px;
  color: var(--hx-ink);
}
.stats-grid span {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  color: var(--hx-muted);
}
.stats-foot {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.45;
}
.actions {
  margin: 0 16px;
  overflow: hidden;
  padding: 4px 0;
}
.action {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 16px;
  border: 0;
  background: transparent;
  color: var(--hx-ink);
  font-size: 15px;
  font-weight: 550;
}
.action + .action,
.switch-row + .action {
  border-top: 1px solid var(--hx-line);
}
.action.danger {
  color: var(--hx-danger);
}
.switch-row {
  cursor: default;
}
.tip {
  margin: 18px 20px 0;
  font-size: 12px;
  line-height: 1.65;
}
</style>
