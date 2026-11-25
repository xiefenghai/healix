<script setup lang="ts">
import { computed } from 'vue'
import { reviewLabel, statusLabel, validityLabel } from './carePlanDisplay'

const props = defineProps<{
  title: string
  status?: string | null
  signStatus?: string | null
  aiAssisted?: boolean
  publishedAt?: string | null
  horizonDays?: number | null
  staffName?: string | null
  versionLabel?: string | null
  goalText?: string
  insightText?: string
  todayDone: number
  todayTotal: number
  todayTasks?: any[]
  exerciseSummary?: string
  dietSummary?: string
  historyMode?: boolean
}>()

const emit = defineEmits<{
  goModule: [name: 'today' | 'exercise' | 'diet' | 'execution']
}>()

const progressPct = computed(() => {
  if (!props.todayTotal) return 0
  return Math.min(100, Math.round((props.todayDone / props.todayTotal) * 100))
})

/** pathLength=100，进度直接用百分数，避免 r / dash 绑定异常 */
const ringDashArray = computed(() => {
  const p = Math.max(0, Math.min(100, progressPct.value))
  return `${p} 100`
})

const statusText = computed(() => {
  const raw = statusLabel(props.status) || (props.historyMode ? '历史' : '已生效')
  return raw.replace(/^当前/, '') || '已生效'
})
const reviewText = computed(() => {
  const t = reviewLabel(props.signStatus, props.aiAssisted)
  if (!t || t === '已发布') return '健管师已审核'
  return t.replace(/发布$/, '')
})
/** 稿子为一枚合并徽章：已生效 · 健管师已审核 */
const badgeText = computed(() => {
  const a = statusText.value
  const b = reviewText.value
  if (!b || b === a) return a
  return `${a} · ${b}`
})

const publishedDate = computed(() => {
  const raw = props.publishedAt ? String(props.publishedAt).slice(0, 10) : ''
  return raw
})
/** 稿子元信息：陈建国 · v1 · 2026-11-25 发布 */
const metaLine = computed(() => {
  const ver = String(props.versionLabel || '')
    .trim()
    .replace(/^V(?=\d)/i, 'v')
  const parts = [
    props.staffName ? String(props.staffName).trim() : '',
    ver,
    publishedDate.value ? `${publishedDate.value} 发布` : '',
  ].filter(Boolean)
  if (parts.length) return parts.join(' · ')
  return validityLabel(props.publishedAt, props.horizonDays)
})

const previewTasks = computed(() => (props.todayTasks || []).slice(0, 4))

const insight = computed(() => {
  const t = (props.insightText || props.goalText || '').trim()
  if (!t) return '按方案完成今日任务，稳步推进健康目标。'
  // 稿面约两行，过长交给 CSS line-clamp，避免硬切半句
  return t
})

function isDone(task: any) {
  return task?.checkinStatus === 'DONE'
}
</script>

<template>
  <div class="overview-page">
    <section class="hero-card">
      <div class="hero-main">
        <span class="pill">
          <span class="pill-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M12 3l7 3v5c0 4.5-2.8 7.8-7 9-4.2-1.2-7-4.5-7-9V6l7-3z" />
              <path d="M9.2 12.1l1.9 1.9 3.9-3.9" />
            </svg>
          </span>
          {{ badgeText }}
        </span>
        <h2>{{ title }}</h2>
        <p v-if="metaLine" class="meta">{{ metaLine }}</p>
      </div>
      <div class="hero-art" aria-hidden="true">
        <span class="art-wash" />
        <span class="art-glow a" />
        <span class="art-glow b" />
        <span class="art-glow c" />
        <span class="art-drop">
          <i class="drop-shine" />
        </span>
        <div class="art-meter">
          <span class="meter-shell">
            <span class="meter-screen">
              <i class="meter-glare" />
              <i class="meter-bar" />
            </span>
            <span class="meter-btn" />
            <span class="meter-slot" />
          </span>
        </div>
      </div>
    </section>

    <section class="insight">
      <span class="insight-ico" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M3 11v2a1 1 0 001 1h2l5 4V6L6 10H4a1 1 0 00-1 1z" />
          <path d="M16 8.5a4.5 4.5 0 010 7M18.5 6a7.5 7.5 0 010 12" />
        </svg>
      </span>
      <p class="insight-text">{{ insight }}</p>
    </section>

    <!-- 今日打卡：顶栏操作 + 左环右清单（贴目标稿） -->
    <section class="checkin-card" @click="emit('goModule', 'today')">
      <div class="checkin-top">
        <div class="checkin-title">
          <span class="cal-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
              <path d="M8 3.5v3M16 3.5v3M3.5 9.5h17" />
            </svg>
          </span>
          <strong>今日打卡任务</strong>
        </div>
        <button type="button" class="go-pill" @click.stop="emit('goModule', 'today')">
          去完成
          <span aria-hidden="true">›</span>
        </button>
      </div>

      <div class="checkin-body">
        <div class="ring-wrap" aria-hidden="true">
          <svg
            class="ring-svg"
            xmlns="http://www.w3.org/2000/svg"
            width="72"
            height="72"
            viewBox="0 0 72 72"
            focusable="false"
          >
            <!-- 几何属性全部写死，避免 :r 绑定失效导致变成色块 -->
            <circle
              cx="36"
              cy="36"
              r="30"
              fill="none"
              stroke="#E2E8F0"
              stroke-width="6"
            />
            <circle
              cx="36"
              cy="36"
              r="30"
              fill="none"
              stroke="#10B981"
              stroke-width="6"
              stroke-linecap="round"
              pathLength="100"
              :stroke-dasharray="ringDashArray"
              transform="rotate(-90 36 36)"
            />
          </svg>
          <div class="ring-label">
            <p class="ring-num">
              <span class="n">{{ todayTotal ? todayDone : 0 }}</span>
              <span class="d">/{{ todayTotal || 0 }}</span>
            </p>
            <p class="ring-cap">已完成</p>
          </div>
        </div>

        <div class="task-side">
          <van-empty v-if="!previewTasks.length" description="今日暂无任务" image-size="48" />
          <ul v-else class="preview-list">
            <li v-for="task in previewTasks" :key="task.id" :class="{ done: isDone(task) }">
              <span class="task-ck" :class="{ on: isDone(task) }" aria-hidden="true">
                <svg v-if="isDone(task)" viewBox="0 0 24 24">
                  <path d="M5.5 12.5l4.2 4.2L18.5 8" />
                </svg>
              </span>
              <span class="task-title">{{ task.title }}</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <nav class="entries" aria-label="方案模块">
      <button type="button" class="entry" @click="emit('goModule', 'exercise')">
        <span class="eico blue">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="5" r="2" />
            <path d="M8 21l2-7 2 2 2-6 2 11" />
          </svg>
        </span>
        <span class="etxt">
          <strong>运动计划</strong>
          <em>{{ exerciseSummary || '查看每周安排与注意事项' }}</em>
        </span>
        <span class="chev" aria-hidden="true">›</span>
      </button>
      <button type="button" class="entry" @click="emit('goModule', 'diet')">
        <span class="eico orange">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 3v8M9 3v8M6 7h3M7.5 11v10M16 3c-1.6 0-2.5 1.5-2.5 3.5S15 10 16 10v11M16 3c1.4 0 2.5 1.5 2.5 3.5S17.5 10 16 10" />
          </svg>
        </span>
        <span class="etxt">
          <strong>饮食计划</strong>
          <em>{{ dietSummary || '查看原则、食谱与推荐' }}</em>
        </span>
        <span class="chev" aria-hidden="true">›</span>
      </button>
      <button type="button" class="entry" @click="emit('goModule', 'execution')">
        <span class="eico purple">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M7 4h10a2 2 0 012 2v14l-7-3-7 3V6a2 2 0 012-2z" />
          </svg>
        </span>
        <span class="etxt">
          <strong>执行计划</strong>
          <em>目标说明与完整任务清单</em>
        </span>
        <span class="chev" aria-hidden="true">›</span>
      </button>
    </nav>

    <section class="disclaimer">
      <span class="shield" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M12 3l7 3v5c0 4.5-2.8 7.8-7 9-4.2-1.2-7-4.5-7-9V6l7-3z" />
          <path d="M9.2 12.2l1.8 1.8 3.8-3.8" />
        </svg>
      </span>
      <p>本方案供日常健康管理参考，不构成医学诊断或处方。如有不适请及时联系医生或健管师。</p>
    </section>
  </div>
</template>

<style scoped>
.overview-page {
  padding: 12px 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.hero-card {
  position: relative;
  overflow: hidden;
  padding: 18px 16px 20px;
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
  min-height: 124px;
}
.hero-main {
  position: relative;
  z-index: 2;
  max-width: calc(100% - 96px);
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px 4px 4px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  color: #166534;
  background: #f0fdf4;
  box-shadow: 0 1px 3px rgba(187, 247, 208, 0.8);
}
.pill-mark {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #16a34a;
  color: #fff;
  display: grid;
  place-items: center;
  flex: none;
}
.pill-mark svg {
  width: 11px;
  height: 11px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.hero-main h2 {
  margin: 12px 0 0;
  font-size: 24px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.04em;
  line-height: 1.25;
}
.meta {
  margin: 10px 0 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}
.hero-art {
  position: absolute;
  right: 0;
  top: 0;
  width: 148px;
  height: 100%;
  pointer-events: none;
  z-index: 0;
  overflow: visible;
}
/* 从右下蔓延的柔和青绿底 */
.art-wash {
  position: absolute;
  right: -48px;
  bottom: -56px;
  width: 168px;
  height: 168px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 38% 42%, rgba(167, 243, 208, 0.75) 0%, rgba(153, 246, 228, 0.35) 42%, rgba(224, 242, 254, 0.18) 68%, transparent 78%);
  filter: blur(0.2px);
}
.art-glow {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: 0 0 12px rgba(167, 243, 208, 0.35);
}
.art-glow.a {
  width: 10px;
  height: 10px;
  right: 54px;
  top: 18px;
  opacity: 0.7;
}
.art-glow.b {
  width: 6px;
  height: 6px;
  right: 28px;
  top: 42px;
  opacity: 0.5;
  background: rgba(94, 234, 212, 0.55);
}
.art-glow.c {
  width: 14px;
  height: 14px;
  right: 78px;
  bottom: 22px;
  opacity: 0.35;
  background: rgba(186, 230, 253, 0.7);
}
/* 半透明青绿水滴 + 高光 */
.art-drop {
  position: absolute;
  right: 86px;
  top: 22px;
  width: 28px;
  height: 36px;
  border-radius: 50% 50% 50% 50% / 60% 60% 40% 40%;
  background: linear-gradient(160deg, #99f6e4 8%, #2dd4bf 48%, #0f766e 100%);
  opacity: 0.78;
  transform: rotate(-18deg);
  box-shadow:
    0 8px 16px rgba(13, 148, 136, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.45);
  z-index: 1;
  isolation: isolate;
}
.art-drop::before {
  content: '';
  position: absolute;
  left: 50%;
  top: -6px;
  width: 16px;
  height: 16px;
  margin-left: -8px;
  border-radius: 50%;
  background: linear-gradient(160deg, #99f6e4, #14b8a6);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35);
}
.drop-shine {
  position: absolute;
  left: 7px;
  top: 11px;
  width: 7px;
  height: 12px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.75), rgba(255, 255, 255, 0));
}
/* 略倾斜的 3D 血糖仪 */
.art-meter {
  position: absolute;
  right: -6px;
  bottom: -10px;
  width: 58px;
  height: 74px;
  transform: rotate(14deg);
  z-index: 3;
  isolation: isolate;
  filter: drop-shadow(0 10px 18px rgba(2, 132, 199, 0.28));
}
.meter-shell {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 16px;
  background: linear-gradient(160deg, #7dd3fc 0%, #38bdf8 38%, #0284c7 100%);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.55),
    inset 0 -8px 14px rgba(7, 89, 133, 0.28),
    inset 2px 0 0 rgba(255, 255, 255, 0.18),
    -2px 0 0 rgba(255, 255, 255, 0.35);
  overflow: hidden;
}
.meter-shell::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 4px;
  width: 14px;
  height: 42px;
  border-radius: 10px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.35), transparent);
  pointer-events: none;
}
.meter-screen {
  position: absolute;
  left: 10px;
  right: 10px;
  top: 12px;
  height: 24px;
  border-radius: 8px;
  background: linear-gradient(180deg, #0f172a 0%, #1e293b 100%);
  box-shadow:
    inset 0 2px 4px rgba(0, 0, 0, 0.45),
    0 1px 0 rgba(255, 255, 255, 0.2);
  overflow: hidden;
}
.meter-glare {
  position: absolute;
  top: 3px;
  left: 4px;
  width: 18px;
  height: 6px;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.35), transparent);
}
.meter-bar {
  position: absolute;
  left: 6px;
  bottom: 5px;
  width: 22px;
  height: 3px;
  border-radius: 2px;
  background: linear-gradient(90deg, #22d3ee, #34d399);
  box-shadow: 0 0 6px rgba(45, 212, 191, 0.6);
}
.meter-btn {
  position: absolute;
  left: 50%;
  bottom: 18px;
  width: 16px;
  height: 16px;
  margin-left: -8px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 30%, #e0f2fe, #0284c7 62%, #0369a1);
  box-shadow:
    0 2px 4px rgba(7, 89, 133, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.55);
}
.meter-slot {
  position: absolute;
  left: 50%;
  bottom: 8px;
  width: 18px;
  height: 3px;
  margin-left: -9px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.35);
}
.insight {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 16px 20px;
  border-radius: 12px;
  background: #f2fbf7;
  box-shadow: none;
  border: 0;
}
.insight-ico {
  width: 32px;
  height: 32px;
  margin-top: 1px;
  border-radius: 50%;
  background: #d1fae5;
  color: #047857;
  display: grid;
  place-items: center;
  flex: none;
}
.insight-ico svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.85;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.insight-text {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.65;
  color: #334155;
}
.checkin-card,
.entry {
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
  border: 0;
}
.checkin-card {
  padding: 20px;
  cursor: pointer;
}
.checkin-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.checkin-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.cal-ico {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: #10b981;
  color: #fff;
  display: grid;
  place-items: center;
  flex: none;
}
.cal-ico svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.85;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.checkin-title strong {
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
}
.go-pill {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 0;
  border-radius: 999px;
  padding: 6px 14px;
  background: #1677ff;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: none;
  transition:
    background 0.18s ease,
    transform 0.12s ease;
}
.go-pill span {
  font-size: 13px;
  font-weight: 500;
  transition: transform 0.18s ease;
}
.go-pill:hover {
  background: #0958d9;
}
.go-pill:hover span {
  transform: translateX(2px);
}
.go-pill:active {
  transform: scale(0.96);
}
.checkin-body {
  display: flex;
  align-items: center;
  gap: 24px;
}
.ring-wrap {
  position: relative;
  width: 72px;
  height: 72px;
  flex: 0 0 72px;
  background: transparent;
}
.ring-svg {
  display: block;
  width: 72px;
  height: 72px;
  background: transparent;
  overflow: visible;
}
.ring-label {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  margin: 0;
  pointer-events: none;
  background: transparent;
}
.ring-num,
.ring-cap {
  margin: 0;
  padding: 0;
  line-height: 1;
}
.ring-num {
  display: flex;
  align-items: baseline;
}
.ring-num .n {
  font-size: 17px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.04em;
}
.ring-num .d {
  font-size: 11px;
  font-weight: 700;
  color: #94a3b8;
}
.ring-cap {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
}
.task-side {
  min-width: 0;
  flex: 1 1 auto;
  width: auto;
}
.preview-list {
  margin: 0;
  padding: 0;
  list-style: none;
  width: fit-content;
  max-width: 100%;
}
.preview-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
}
.preview-list li.done .task-title {
  color: #94a3b8;
  text-decoration: line-through;
}
.task-ck {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1.5px solid #cbd5e1;
  background: #fff;
  flex: none;
  display: grid;
  place-items: center;
  box-sizing: border-box;
}
.task-ck.on {
  border-color: #10b981;
  background: #10b981;
  color: #fff;
}
.task-ck svg {
  width: 10px;
  height: 10px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.task-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  color: #0f172a;
}
.entries {
  display: grid;
  gap: 12px;
}
.entry {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  text-align: left;
  padding: 16px 18px;
  font-family: inherit;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;
}
.entry:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.07);
}
.entry:hover .chev {
  transform: translateX(2px);
}
.entry:active {
  transform: translateY(0);
}
.eico {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
  line-height: 0;
}
.eico svg {
  width: 20px;
  height: 20px;
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.85;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.eico.blue {
  background: #eaf2ff;
  color: #1d4ed8;
}
.eico.orange {
  background: #fef6e2;
  color: #c2410c;
}
.eico.purple {
  background: #f1ecfe;
  color: #6d28d9;
}
.etxt {
  flex: 1;
  min-width: 0;
}
.etxt strong {
  display: block;
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
}
.etxt em {
  display: -webkit-box !important;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  margin-top: 3px;
  font-style: normal;
  font-size: 12px;
  line-height: 1.4;
  color: #94a3b8;
}
.chev {
  flex: none;
  background: transparent;
  color: #3b82f6;
  font-size: 22px;
  font-weight: 300;
  line-height: 1;
  align-self: center;
  transition: transform 0.18s ease;
}
.disclaimer {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 14px 16px;
  border-radius: 24px;
  background: #f0fdf4;
  box-sizing: border-box;
}
.shield {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #10b981;
  color: #fff;
  display: grid;
  place-items: center;
  flex: none;
  line-height: 0;
}
.shield svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.85;
  stroke-linecap: round;
  stroke-linejoin: round;
  display: block;
}
.disclaimer p {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #047857;
}
:deep(.van-empty) {
  padding: 8px 0;
}
</style>
