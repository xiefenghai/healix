<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/http'
import { showToast } from 'vant'
import {
  ledgerDayLabel,
  loadRewardSummary,
  REWARD_TYPE_ICONS,
  setPeerOptOut,
  type RewardBadge,
  type RewardLedgerDay,
  type RewardSummary,
} from '../shared/rewards'

const router = useRouter()
const loading = ref(true)
const summary = ref<RewardSummary | null>(null)
const badges = ref<RewardBadge[]>([])
const ledger = ref<RewardLedgerDay[]>([])
const activeBadge = ref<RewardBadge | null>(null)

const earnedCount = computed(() => badges.value.filter((b) => b.earned).length)
const levelPct = computed(() => Math.round((summary.value?.level?.progress ?? 0) * 100))
const toNext = computed(() => {
  const lv = summary.value?.level
  if (!lv?.nextThreshold) return null
  return Math.max(0, lv.nextThreshold - lv.lifetime)
})

const flameTip = computed(() => {
  const s = summary.value
  if (!s) return ''
  if (s.todayActive) return '今天已打卡，火苗续上了'
  if (s.streak > 0) return '今天完成任意 1 项方案任务、服药或测量，火苗 +1'
  return '完成任意 1 项方案任务、服药或测量，点燃火苗'
})

onMounted(async () => {
  try {
    summary.value = await loadRewardSummary()
    if (!summary.value) return
    const [b, l] = await Promise.all([
      api<{ data: RewardBadge[] }>('/api/c/v1/rewards/badges'),
      api<{ data: RewardLedgerDay[] }>('/api/c/v1/rewards/ledger?days=30'),
    ])
    badges.value = b.data || []
    ledger.value = l.data || []
  } finally {
    loading.value = false
  }
})

function badgeDate(d?: string | null) {
  return d ? String(d).slice(0, 10).replace(/-/g, '.') : ''
}

async function togglePeer() {
  if (!summary.value?.peer) return
  const next = !summary.value.peer.optOut
  const peer = await setPeerOptOut(next)
  if (peer && summary.value) {
    summary.value = { ...summary.value, peer }
    showToast(next ? '已关闭同期对比' : '已开启同期对比')
  }
}
</script>

<template>
  <div class="ach-page">
    <van-nav-bar title="我的成就" left-arrow :border="false" class="nav" @click-left="router.back()" />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <van-empty v-else-if="!summary" description="所在机构暂未开启成就激励" image-size="72" />

    <template v-else>
      <section class="flame-card">
        <div class="flame-main">
          <span class="flame-icon" :class="{ dim: !summary.todayActive }">🔥</span>
          <div>
            <p class="flame-num"><strong>{{ summary.streak }}</strong> 天</p>
            <p class="flame-label">连续打卡</p>
          </div>
        </div>
        <div class="freeze">
          <span v-for="i in 2" :key="i" class="freeze-card" :class="{ on: i <= summary.freezeCards }">🛡️</span>
          <em>保护卡 {{ summary.freezeCards }}/2</em>
        </div>
        <p class="flame-tip">{{ flameTip }}</p>
        <p class="flame-sub">最长 {{ summary.bestStreak }} 天 · 每连续 7 天得 1 张保护卡，漏打一天自动使用</p>
      </section>

      <section v-if="summary.level" class="card level-card">
        <div class="level-head">
          <span class="lv">Lv{{ summary.level.level }}</span>
          <strong>{{ summary.level.title }}</strong>
          <span v-if="summary.level.nextTitle" class="next">下一级 · {{ summary.level.nextTitle }}</span>
          <span v-else class="next">已满级</span>
        </div>
        <div class="bar"><i :style="{ width: levelPct + '%' }" /></div>
        <p class="level-foot">
          累计 {{ summary.level.lifetime }} 健康豆
          <template v-if="toNext != null"> · 再得 {{ toNext }} 豆升级</template>
        </p>
      </section>

      <section class="card beans">
        <div class="bean">
          <span>健康豆</span>
          <strong>{{ summary.points }}</strong>
        </div>
        <div class="bean">
          <span>本周获得</span>
          <strong>+{{ summary.weekPoints }}</strong>
        </div>
        <div class="bean">
          <span>今日获得</span>
          <strong>+{{ summary.todayPoints }}</strong>
        </div>
      </section>

      <section v-if="summary.challenge" class="card challenge-card">
        <div class="sec-head">
          <h2><span>本周挑战</span></h2>
          <em>{{ summary.challenge.status === 'DONE' ? '已达成 +50' : '达成 +50 豆' }}</em>
        </div>
        <p class="ch-title">🎯 {{ summary.challenge.title }}</p>
        <div class="bar"><i :style="{ width: Math.round(summary.challenge.progressRatio * 100) + '%' }" /></div>
        <p class="ch-foot">
          {{ summary.challenge.progress }} / {{ summary.challenge.target }}
          <template v-if="summary.challenge.status === 'DONE'"> · 已完成</template>
          <template v-else> · 周日结束</template>
        </p>
      </section>

      <section v-if="summary.peer" class="card peer-card">
        <div class="sec-head">
          <h2><span>同期对比</span></h2>
          <button type="button" class="peer-toggle" @click="togglePeer">
            {{ summary.peer.optOut ? '开启对比' : '关闭对比' }}
          </button>
        </div>
        <p v-if="summary.peer.optOut" class="peer-muted">你已关闭对比，也不再计入他人的百分位计算</p>
        <p v-else-if="summary.peer.available" class="peer-main">
          本周打卡天数超过 <strong>{{ summary.peer.percentile }}%</strong> 的同期伙伴
          <em>（同机构入组 ±30 天，共 {{ summary.peer.poolSize }} 人，匿名）</em>
        </p>
        <p v-else class="peer-muted">同期人数不足 10 人时暂不展示对比</p>
      </section>

      <section class="card">
        <div class="sec-head">
          <h2><span>徽章墙</span></h2>
          <em>{{ earnedCount }}/{{ badges.length }}</em>
        </div>
        <div class="badge-grid">
          <button
            v-for="b in badges"
            :key="b.code"
            type="button"
            class="badge"
            :class="{ off: !b.earned, outcome: b.kind === 'OUTCOME' }"
            @click="activeBadge = b"
          >
            <span class="badge-icon">{{ b.icon }}</span>
            <span class="badge-name">{{ b.name }}</span>
          </button>
        </div>
      </section>

      <section class="card">
        <div class="sec-head">
          <h2><span>健康豆明细</span></h2>
          <em>近 30 天</em>
        </div>
        <van-empty v-if="!ledger.length" description="还没有健康豆，完成今天的任务试试" image-size="56" />
        <div v-for="day in ledger" :key="day.date" class="ledger-day">
          <div class="ledger-date">
            <span>{{ ledgerDayLabel(day.date) }}</span>
            <strong :class="{ neg: day.total < 0 }">{{ day.total >= 0 ? '+' : '' }}{{ day.total }}</strong>
          </div>
          <ul>
            <li v-for="(it, i) in day.items" :key="i">
              <span class="ico">{{ REWARD_TYPE_ICONS[it.bizType] || '•' }}</span>
              <span class="ttl">{{ it.title }}</span>
              <span class="pts" :class="{ neg: it.points < 0 }">{{ it.points > 0 ? '+' : '' }}{{ it.points }}</span>
            </li>
          </ul>
        </div>
        <p class="rules">
          每完成 1 项方案任务、服药、测量、饮食或运动记录 +5；阅读管理报告 +10；全勤日 +20；新徽章 +30；完成周挑战 +50；每日最多 80（全勤日、徽章、周挑战不计入上限）。
          健管师代录不计；超过 24 小时补录不计；明显不合理的测量值不计。昨天漏打的任务今天可在「管理方案」里补打。
        </p>
      </section>
    </template>

    <van-popup :show="!!activeBadge" round class="badge-pop" @update:show="(v: boolean) => !v && (activeBadge = null)">
      <div v-if="activeBadge" class="pop">
        <span class="pop-icon" :class="{ off: !activeBadge.earned }">{{ activeBadge.icon }}</span>
        <h3>{{ activeBadge.name }}</h3>
        <p v-if="activeBadge.earned">{{ badgeDate(activeBadge.earnedDate) }} 获得</p>
        <p v-else>未获得</p>
        <p class="cond">{{ activeBadge.condition }}</p>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.ach-page {
  min-height: 100vh;
  padding-bottom: 28px;
  background: linear-gradient(180deg, #fff6e9 0%, #fffaf3 240px, #f6f8f8 520px);
}
.nav {
  background: transparent;
}
.loading {
  padding: 60px 0;
}
.flame-card {
  margin: 8px 16px 14px;
  padding: 18px 18px 14px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(135deg, #ff9a3c 0%, #ff6a3d 55%, #f0466b 100%);
  box-shadow: 0 10px 24px rgba(240, 90, 60, 0.28);
  position: relative;
}
.flame-main {
  display: flex;
  align-items: center;
  gap: 12px;
}
.flame-icon {
  font-size: 44px;
  line-height: 1;
  filter: drop-shadow(0 4px 8px rgba(0, 0, 0, 0.15));
}
.flame-icon.dim {
  opacity: 0.6;
}
.flame-num {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.flame-num strong {
  font-size: 40px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-right: 2px;
}
.flame-label {
  margin: 0;
  font-size: 12px;
  opacity: 0.9;
}
.freeze {
  position: absolute;
  top: 16px;
  right: 16px;
  display: flex;
  align-items: center;
  gap: 2px;
}
.freeze-card {
  font-size: 18px;
  opacity: 0.35;
  filter: grayscale(1);
}
.freeze-card.on {
  opacity: 1;
  filter: none;
}
.freeze em {
  font-style: normal;
  font-size: 11px;
  margin-left: 4px;
  opacity: 0.95;
}
.flame-tip {
  margin: 12px 0 4px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.2);
  font-size: 13px;
  font-weight: 600;
}
.flame-sub {
  margin: 0;
  font-size: 11px;
  opacity: 0.85;
}
.card {
  margin: 0 16px 14px;
  padding: 16px;
  border-radius: var(--hx-r-lg);
  background: #fff;
  box-shadow: var(--hx-elev-2);
}
.level-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lv {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--hx-teal-600);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}
.level-head strong {
  font-size: 16px;
  color: var(--hx-text);
}
.level-head .next {
  margin-left: auto;
  font-size: 12px;
  color: var(--hx-muted);
}
.bar {
  height: 8px;
  margin: 12px 0 8px;
  border-radius: 999px;
  background: #e8f4f4;
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--hx-grad);
}
.level-foot {
  margin: 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.beans {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  text-align: center;
  padding: 14px 8px;
}
.bean + .bean {
  border-left: 1px solid var(--hx-n-100);
}
.bean span {
  display: block;
  font-size: 12px;
  color: var(--hx-muted);
}
.bean strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
  font-weight: 800;
  color: #e07a3a;
  font-variant-numeric: tabular-nums;
}
.ch-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 800;
}
.ch-foot,
.peer-muted,
.peer-main {
  margin: 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.5;
}
.peer-main strong {
  color: #e0632a;
  font-size: 22px;
  font-weight: 900;
}
.peer-main em {
  display: block;
  margin-top: 4px;
  font-style: normal;
  font-size: 11px;
}
.peer-toggle {
  border: 0;
  background: transparent;
  color: var(--hx-teal, var(--hx-teal-600));
  font-size: 12px;
  font-weight: 700;
}
.sec-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.sec-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: var(--hx-text);
}
.sec-head h2 span {
  background: linear-gradient(transparent 60%, #ffe58a 60%);
}
.sec-head em {
  font-style: normal;
  font-size: 12px;
  color: var(--hx-muted);
}
.badge-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 6px;
}
.badge {
  border: 0;
  background: transparent;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 0;
}
.badge-icon {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 26px;
  background: radial-gradient(circle at 35% 30%, #fff7e0, #ffd88a);
  box-shadow: 0 4px 10px rgba(224, 160, 60, 0.3);
}
.badge.outcome .badge-icon {
  background: radial-gradient(circle at 35% 30%, #e9fbf5, #9fdcc6);
  box-shadow: 0 4px 10px rgba(11, 122, 102, 0.25);
}
.badge.off .badge-icon {
  background: #f1f4f6;
  box-shadow: none;
  filter: grayscale(1);
  opacity: 0.45;
}
.badge-name {
  font-size: 11px;
  color: var(--hx-text);
  line-height: 1.25;
  text-align: center;
}
.badge.off .badge-name {
  color: #a3afba;
}
.ledger-day + .ledger-day {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #edf1f4;
}
.ledger-date {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-text);
  margin-bottom: 4px;
}
.ledger-date strong {
  color: #e07a3a;
}
.ledger-day ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.ledger-day li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
  color: #4b5a67;
}
.ico {
  width: 18px;
  text-align: center;
}
.ttl {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pts {
  font-weight: 700;
  color: #e07a3a;
  font-variant-numeric: tabular-nums;
}
.neg {
  color: var(--hx-n-500) !important;
}
.rules {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 12px;
  background: #f7fafa;
  font-size: 11px;
  line-height: 1.6;
  color: var(--hx-muted);
}
.badge-pop {
  width: 260px;
}
.pop {
  padding: 22px 18px 18px;
  text-align: center;
}
.pop-icon {
  font-size: 48px;
}
.pop-icon.off {
  filter: grayscale(1);
  opacity: 0.45;
}
.pop h3 {
  margin: 8px 0 4px;
  font-size: 17px;
}
.pop p {
  margin: 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.pop .cond {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #fff6e9;
  color: #9a6b1f;
  font-size: 13px;
}
</style>
