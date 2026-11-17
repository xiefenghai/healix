<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { BACKFILL_ALERT, KUDOS_PRESETS, type RewardStaffView } from '../../shared/rewards'

const props = defineProps<{ peopleId: string }>()

const view = ref<RewardStaffView | null>(null)
const loading = ref(false)
const kudosOpen = ref(false)
const kudosText = ref('')
const sending = ref(false)
const toggling = ref(false)
const showLedger = ref(false)
/** 展开中的明细日期；打开明细时默认只展开最近一天 */
const expandedLedgerDates = ref<Set<string>>(new Set())
/** 当天明细已点「查看更多」 */
const ledgerMoreDates = ref<Set<string>>(new Set())
const LEDGER_PREVIEW = 8

const s = computed(() => view.value?.summary)
const earned = computed(() => (view.value?.badges || []).filter((b) => b.earned))
const ledgerDays = computed(() => view.value?.ledger || [])

function syncLedgerExpand(ledger: { date: string }[]) {
  expandedLedgerDates.value = new Set(ledger[0]?.date ? [ledger[0].date] : [])
  ledgerMoreDates.value = new Set()
}

function isLedgerDayOpen(date: string) {
  return expandedLedgerDates.value.has(date)
}

function toggleLedgerDay(date: string) {
  const next = new Set(expandedLedgerDates.value)
  if (next.has(date)) {
    next.delete(date)
    const more = new Set(ledgerMoreDates.value)
    more.delete(date)
    ledgerMoreDates.value = more
  } else {
    next.add(date)
  }
  expandedLedgerDates.value = next
}

function toggleLedger() {
  showLedger.value = !showLedger.value
  if (showLedger.value) syncLedgerExpand(ledgerDays.value)
  else ledgerMoreDates.value = new Set()
}

function isLedgerDayExpandedAll(date: string) {
  return ledgerMoreDates.value.has(date)
}

function expandLedgerDayAll(date: string) {
  const next = new Set(ledgerMoreDates.value)
  next.add(date)
  ledgerMoreDates.value = next
}

function visibleLedgerItems(date: string, items: { title: string; points: number }[]) {
  if (items.length <= LEDGER_PREVIEW || isLedgerDayExpandedAll(date)) return items
  return items.slice(0, LEDGER_PREVIEW)
}

/** 「完成方案任务·记录昨夜睡眠」→ 前缀 / 详情 */
function splitLedgerTitle(title: string) {
  const seps = ['·', '・', '•', '｜', '|', '：', ':']
  for (const sep of seps) {
    const i = title.indexOf(sep)
    if (i > 0) {
      return {
        prefix: title.slice(0, i).trim(),
        detail: title.slice(i + sep.length).trim() || title,
      }
    }
  }
  return { prefix: '', detail: title }
}

function formatPoints(n: number) {
  return `${n > 0 ? '+' : ''}${n}`
}

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    const res = await api<{ data: RewardStaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards`)
    view.value = res.data
    if (showLedger.value) syncLedgerExpand(res.data?.ledger || [])
  } catch {
    view.value = null
  } finally {
    loading.value = false
  }
}

async function sendKudos() {
  sending.value = true
  try {
    const res = await api<{ data: { sent: boolean; message: string } }>(
      `/api/b/v1/patients/${props.peopleId}/rewards/kudos`,
      { method: 'POST', body: JSON.stringify({ message: kudosText.value.trim() || null }) },
    )
    ElMessage({ type: res.data.sent ? 'success' : 'warning', message: res.data.message })
    kudosOpen.value = false
    kudosText.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function toggle(enabled: boolean) {
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        '关闭后该患者不再获得健康豆、火苗与徽章，C 端也不再展示成就入口。已有记录保留，可随时重新开启。',
        '关闭该患者的激励？',
        { type: 'warning', confirmButtonText: '关闭激励', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  toggling.value = true
  try {
    const res = await api<{ data: RewardStaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    })
    view.value = res.data
    ElMessage.success(enabled ? '已开启激励' : '已关闭激励')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    toggling.value = false
  }
}

function shortDate(d?: string | null) {
  return d ? String(d).slice(5, 10).replace('-', '/') : ''
}

watch(() => props.peopleId, load, { immediate: true })
defineExpose({ load })
</script>

<template>
  <section v-if="view?.tenantEnabled" v-loading="loading" class="reward-panel">
    <header class="rp-head">
      <h3><span>健康激励</span></h3>
      <span class="rp-hint">患者 C 端自助完成的打卡才计入，代录不计</span>
      <div class="rp-actions">
        <template v-if="view.patientEnabled">
          <el-popover v-model:visible="kudosOpen" placement="bottom-end" :width="300" trigger="click">
            <template #reference>
              <el-button type="warning" plain size="small" :disabled="view.kudosSentToday && !kudosOpen">
                {{ view.kudosSentToday ? '今日已点赞' : ' 点赞鼓励' }}
              </el-button>
            </template>
            <div class="kudos-pop">
              <div class="presets">
                <el-tag
                  v-for="p in KUDOS_PRESETS"
                  :key="p"
                  class="preset"
                  effect="plain"
                  @click="kudosText = p"
                >{{ p }}</el-tag>
              </div>
              <el-input
                v-model="kudosText"
                type="textarea"
                :rows="2"
                maxlength="100"
                show-word-limit
                placeholder="可选：一句鼓励的话"
              />
              <el-button type="primary" size="small" class="send" :loading="sending" @click="sendKudos">
                发送给患者
              </el-button>
            </div>
          </el-popover>
        </template>
        <el-switch
          :model-value="view.patientEnabled"
          :loading="toggling"
          inline-prompt
          active-text="开"
          inactive-text="关"
          @update:model-value="(v: string | number | boolean) => toggle(!!v)"
        />
      </div>
    </header>

    <div v-if="!view.patientEnabled" class="rp-off">已为该患者关闭激励</div>

    <template v-else-if="s">
      <el-alert
        v-if="view.backfills7d >= BACKFILL_ALERT"
        type="warning"
        :closable="false"
        show-icon
        class="rp-alert"
        :title="`近 7 天补录 ${view.backfills7d} 条（隔天录入，或测量 12 小时后才录入），建议核实数据真实性`"
      />
      <div class="rp-stats">
        <div class="stat flame">
          <span class="lbl">连续打卡</span>
          <strong> {{ s.streak }}<em>天</em></strong>
          <span class="sub">最长 {{ s.bestStreak }} 天 · 保护卡 {{ s.freezeCards }}/2</span>
        </div>
        <div class="stat">
          <span class="lbl">今日</span>
          <strong :class="{ muted: !s.todayActive }">{{ s.todayActive ? '已打卡' : '未打卡' }}</strong>
          <span class="sub">+{{ s.todayPoints }} 豆 · {{ s.todayActions }} 项</span>
        </div>
        <div class="stat stat--points">
          <span class="lbl">健康豆</span>
          <strong class="stat-hero">{{ s.points }}</strong>
          <span class="sub">本周 +{{ s.weekPoints }}</span>
        </div>
        <div class="stat">
          <span class="lbl">等级</span>
          <strong class="stat-hero">Lv{{ s.level?.level ?? 1 }}</strong>
          <span class="sub">{{ s.level?.title }}</span>
        </div>
      </div>

      <div class="rp-meta">
        <div v-if="view.challenge" class="rp-row">
          <span class="row-lbl">本周挑战</span>
          <div class="challenge">
            <strong> {{ view.challenge.title }}</strong>
            <span>
              {{ view.challenge.progress }}/{{ view.challenge.target }}
              <template v-if="view.challenge.status === 'DONE'"> · 已达成</template>
            </span>
          </div>
        </div>

        <div class="rp-row rp-row--badges">
          <span class="row-lbl">徽章 {{ earned.length }}</span>
          <div class="badges">
            <span
              v-for="b in earned"
              :key="b.code"
              class="badge"
              :class="{ outcome: b.kind === 'OUTCOME' }"
              :title="`${shortDate(b.earnedDate)} 获得`"
            >
              {{ b.icon }} {{ b.name }}
            </span>
            <span v-if="!earned.length" class="muted">暂无</span>
          </div>
        </div>

        <div v-if="view.kudos.length" class="rp-row rp-row--kudos">
          <span class="row-lbl">最近点赞</span>
          <div class="kudos-list">
            <div v-for="(k, i) in view.kudos.slice(0, 3)" :key="i" class="kudos">
              <span class="kudos-meta">{{ k.staffName || '健管师' }} · {{ shortDate(k.createdAt) }}</span>
              <p v-if="k.message" class="kudos-msg">“{{ k.message }}”</p>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!showLedger" class="ledger-toggle-row">
        <button type="button" class="ledger-toggle" @click="toggleLedger">展开近 14 天明细 ▼</button>
      </div>

      <div v-else class="ledger">
        <div v-if="!ledgerDays.length" class="muted">近 14 天暂无记录</div>
        <div
          v-for="d in ledgerDays"
          :key="d.date"
          class="ledger-day"
          :class="{ 'ledger-day--open': isLedgerDayOpen(d.date) }"
        >
          <button type="button" class="ld-head" @click="toggleLedgerDay(d.date)">
            <span class="ld-chevron" aria-hidden="true">{{ isLedgerDayOpen(d.date) ? '▾' : '▸' }}</span>
            <span class="ld-date">{{ d.date }}</span>
            <em class="ld-count">{{ d.items.length }} 项</em>
            <strong class="ld-total">总计 {{ formatPoints(d.total) }}</strong>
          </button>
          <div v-if="isLedgerDayOpen(d.date)" class="ld-body">
            <div
              v-for="(it, i) in visibleLedgerItems(d.date, d.items)"
              :key="i"
              class="ld-item"
            >
              <el-tooltip :content="it.title" placement="top" :disabled="it.title.length < 22">
                <span class="ld-title">
                  <template v-if="splitLedgerTitle(it.title).prefix">
                    <em class="ld-prefix">{{ splitLedgerTitle(it.title).prefix }}</em>
                    <i class="ld-sep" aria-hidden="true">•</i>
                    <b class="ld-detail">{{ splitLedgerTitle(it.title).detail }}</b>
                  </template>
                  <b v-else class="ld-detail">{{ it.title }}</b>
                </span>
              </el-tooltip>
              <span class="ld-pts" :class="{ 'ld-pts--neg': it.points < 0 }">
                {{ formatPoints(it.points) }}
              </span>
            </div>
            <button
              v-if="d.items.length > LEDGER_PREVIEW && !isLedgerDayExpandedAll(d.date)"
              type="button"
              class="ld-more"
              @click="expandLedgerDayAll(d.date)"
            >
              查看更多明细（展开剩余 {{ d.items.length - LEDGER_PREVIEW }} 条）
            </button>
          </div>
        </div>
        <div class="ledger-footer">
          <button type="button" class="ledger-toggle" @click="toggleLedger">收起明细 ▲</button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.reward-panel {
  margin-bottom: 14px;
  padding: 14px 16px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--auto-fff7ee) 0%, var(--white) 60%);
  border: 1px solid var(--auto-f6e3cf);
}
.rp-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.rp-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 800;
}
.rp-head h3 span {
  background: linear-gradient(transparent 60%, var(--auto-ffe58a) 60%);
}
.rp-hint {
  font-size: 12px;
  color: var(--gray-500);
}
.rp-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.rp-alert {
  margin-top: 10px;
}
.rp-off {
  margin-top: 10px;
  font-size: 13px;
  color: var(--gray-500);
}
.rp-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}
.stat {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--white);
  border: 1px solid var(--auto-f1ebe3);
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
}
.stat .lbl {
  font-size: 12px;
  color: var(--gray-500);
  line-height: 1.2;
}
.stat strong {
  font-size: 20px;
  font-weight: 800;
  color: var(--auto-303133);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.stat-hero {
  font-size: 24px !important;
  letter-spacing: -0.02em;
}
.stat strong em {
  font-style: normal;
  font-size: 12px;
  margin-left: 2px;
  color: var(--gray-500);
}
.stat.flame strong {
  color: var(--auto-e0632a);
}
.stat strong.muted {
  color: var(--auto-c0c4cc);
}
.stat .sub {
  font-size: 11px;
  color: var(--gray-500);
  line-height: 1.3;
}
.rp-meta {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}
.challenge {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  font-size: 13px;
  color: var(--auto-606266);
}
.challenge strong {
  color: var(--auto-303133);
  font-weight: 700;
}
.rp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.rp-meta .rp-row + .rp-row {
  margin-top: 0;
}
.rp-row--badges {
  align-items: center;
}
.rp-row--kudos {
  align-items: flex-start;
}
.row-lbl {
  flex-shrink: 0;
  width: 64px;
  font-size: 12px;
  color: var(--gray-500);
  line-height: 24px;
}
@media (max-width: 900px) {
  .rp-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.badges {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow-x: auto;
  padding-bottom: 2px;
  -webkit-overflow-scrolling: touch;
}
.badge {
  flex-shrink: 0;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--auto-fff4e0);
  color: var(--auto-8a5a2b);
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}
.badge.outcome {
  background: var(--auto-e9f8f1);
  color: var(--auto-2a7a5a);
}
.kudos-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.kudos {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--auto-fffaf3);
  border: 1px solid var(--auto-f3e7d8);
}
.kudos-meta {
  display: block;
  font-size: 11px;
  color: var(--gray-400);
  line-height: 1.3;
}
.kudos-msg {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.45;
  color: var(--ink-650);
}
.muted {
  color: var(--auto-c0c4cc);
  font-size: 12px;
}
.ledger-toggle-row {
  display: flex;
  justify-content: flex-start;
  margin-top: 8px;
}
.ledger-toggle {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--gray-500);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.3;
  cursor: pointer;
}
.ledger-toggle:hover {
  color: var(--blue-600);
}
.ledger {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: none;
  margin-top: 10px;
  box-sizing: border-box;
}
.ledger-day {
  width: 100%;
  border-radius: 10px;
  background: var(--white);
  border: 1px solid var(--auto-f1ebe3);
  overflow: hidden;
  box-sizing: border-box;
}
.ld-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
  box-sizing: border-box;
}
.ld-head:hover {
  background: var(--auto-fffaf5);
}
.ld-chevron {
  width: 14px;
  color: var(--gray-400);
  font-size: 12px;
  line-height: 1;
}
.ld-date {
  font-size: 13px;
  font-weight: 700;
  color: var(--auto-303133);
}
.ld-total {
  margin-left: auto;
  font-size: 14px;
  font-weight: 800;
  color: var(--auto-e0632a);
  font-variant-numeric: tabular-nums;
}
.ld-count {
  font-style: normal;
  font-size: 11px;
  font-weight: 400;
  color: var(--gray-400);
}
.ld-body {
  border-top: 0 solid var(--gray-100);
  padding: 0 14px;
  max-height: 300px;
  overflow-y: auto;
}
.ld-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  min-height: 38px;
  padding: 8px 0;
  border-bottom: 0 solid var(--gray-100);
  font-size: 13px;
  box-sizing: border-box;
}
.ld-item:last-child {
  border-bottom: none;
}
.ld-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-align: left;
  line-height: 1.4;
}
.ld-prefix {
  flex-shrink: 0;
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: var(--gray-400);
  white-space: nowrap;
}
.ld-sep {
  flex-shrink: 0;
  font-style: normal;
  color: var(--gray-300);
  font-size: 11px;
  line-height: 1;
}
.ld-detail {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  color: var(--gray-800);
}
.ld-pts {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 40px;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.ld-pts--neg {
  color: var(--gray-400);
  background: var(--gray-50);
}
.ld-more {
  display: block;
  width: 100%;
  padding: 10px 0 12px;
  border: none;
  border-top: 0 solid var(--gray-100);
  background: transparent;
  color: var(--blue-600);
  font-size: 12px;
  font-weight: 500;
  text-align: center;
  cursor: pointer;
}
.ld-more:hover {
  text-decoration: underline;
}
.ledger-footer {
  display: flex;
  justify-content: flex-start;
  padding-top: 4px;
}
.kudos-pop {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.preset {
  cursor: pointer;
}
.send {
  align-self: flex-end;
}
</style>
