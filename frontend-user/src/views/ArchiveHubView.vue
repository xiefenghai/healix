<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { formatDiseaseName, formatGender } from '../shared/archive-labels'
import { loadDiseaseDict, toLabelMap } from '../shared/dict'

const router = useRouter()
const loading = ref(true)
const summary = ref<any>(null)
const diseaseLabels = ref<Record<string, string>>({})
const allergyText = ref('')
const inCare = ref(false)
/** 与健管端同一口径：字典字段已填数 / 应填数 */
const archivePct = ref(0)

function maskMobile(raw?: string | null) {
  const digits = String(raw || '')
    .replace(/\D/g, '')
    .replace(/^86/, '')
  if (digits.length >= 7) return `${digits.slice(0, 3)}****${digits.slice(-4)}`
  return ''
}

onMounted(async () => {
  try {
    const [res, diseases, allergy, membership, completeness, profile] = await Promise.all([
      api<{ data: any }>('/api/c/v1/me/archive/summary'),
      loadDiseaseDict().catch(() => []),
      api<{ data: { status: string; bannerLabels: string[] } }>('/api/c/v1/me/allergies').catch(() => null),
      api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status').catch(() => null),
      api<{ data: { percent?: number } }>('/api/c/v1/me/archive/completeness').catch(() => null),
      api<{ data: { mobile?: string } }>('/api/c/v1/profile').catch(() => null),
    ])
    const data = res.data || {}
    // 后端未部署 mobileMask 时，用 profile.mobile 前端脱敏兜底
    if (!data.mobileMask && profile?.data?.mobile) {
      data.mobileMask = maskMobile(profile.data.mobile)
    }
    summary.value = data
    diseaseLabels.value = toLabelMap(diseases)
    inCare.value = !!membership?.data?.inCare
    archivePct.value = Math.max(0, Math.min(100, completeness?.data?.percent ?? 0))
    if (allergy) {
      const a = allergy.data
      allergyText.value =
        a.status === 'HAS' ? a.bannerLabels.join('、') : a.status === 'NKA' ? '无过敏' : '未填写'
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

const avatarChar = computed(() => (summary.value?.displayName || '档').trim().slice(0, 1) || '档')

const metaLine = computed(() => {
  const s = summary.value
  if (!s) return ''
  const mobile =
    s.mobileMask ||
    maskMobile(s.mobile) ||
    '手机未填'
  return [formatGender(s.gender), s.birthday || '生日未填', mobile]
    .filter(Boolean)
    .join(' · ')
})

const diseaseCount = computed(() => (summary.value?.diseases || []).length)

function diseaseIcon(code?: string) {
  const c = (code || '').toLowerCase()
  if (c.includes('diabetes') || c.includes('糖')) return { icon: 'fire', tone: 'amber' as const }
  if (c.includes('hypertension') || c.includes('血压')) return { icon: 'like-o', tone: 'red' as const }
  if (c.includes('obesity') || c.includes('肥胖')) return { icon: 'smile-o', tone: 'amber' as const }
  return { icon: 'medal-o', tone: 'blue' as const }
}

/** 无临床状态接口时，按病种给轻量管理态展示标签（非诊疗结论） */
function diseaseStatus(code?: string) {
  const c = (code || '').toLowerCase()
  if (c.includes('diabetes') || c.includes('糖')) return { text: '控制平稳', tone: 'ok' as const }
  if (c.includes('hypertension') || c.includes('血压')) return { text: '监测中', tone: 'watch' as const }
  return { text: '管理中', tone: 'watch' as const }
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <button type="button" class="back" aria-label="返回" @click="router.back()">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" />
        </svg>
      </button>
      <h1>健康档案</h1>
      <span class="topbar-spacer" aria-hidden="true" />
    </header>

    <van-loading v-if="loading" vertical class="boot">加载中</van-loading>

    <template v-else-if="summary">
      <section class="hero-card" aria-label="成员摘要">
        <span class="avatar" aria-hidden="true">{{ avatarChar }}</span>
        <div class="hero-mid">
          <div class="name-row">
            <div class="name-left">
              <b>{{ summary.displayName || '成员' }}</b>
              <span v-if="inCare" class="tag tag-care">在管</span>
              <span v-else class="tag tag-free">自用</span>
            </div>
            <span class="pct-badge">完整度 {{ archivePct }}%</span>
          </div>
          <p class="meta">{{ metaLine }}</p>
        </div>
      </section>

      <section class="card">
        <h3>基础档案</h3>

        <button type="button" class="row" @click="router.push('/archive/basic')">
          <span class="ico tone-blue" aria-hidden="true"><van-icon name="notes-o" /></span>
          <span class="row-title">健康信息</span>
          <span class="row-hint">体征·病史·指标</span>
          <span class="chev" aria-hidden="true">›</span>
        </button>

        <button type="button" class="row" @click="router.push('/archive/lifestyle')">
          <span class="ico tone-green" aria-hidden="true"><van-icon name="smile-o" /></span>
          <span class="row-title">生活方式</span>
          <span class="row-hint">饮食·作息·情绪</span>
          <span class="chev" aria-hidden="true">›</span>
        </button>

        <button type="button" class="row" @click="router.push('/archive/allergies')">
          <span class="ico tone-orange" aria-hidden="true"><van-icon name="warning-o" /></span>
          <span class="row-title">过敏史</span>
          <span class="row-hint">{{ allergyText || '未填写' }}</span>
          <span class="chev" aria-hidden="true">›</span>
        </button>
      </section>

      <section class="card">
        <div class="card-hd">
          <h3>病种档案</h3>
          <span v-if="diseaseCount" class="count-badge">{{ diseaseCount }}项专病管理中</span>
        </div>

        <div v-if="!diseaseCount" class="empty-disease">暂无病种档案</div>

        <button
          v-for="d in summary.diseases || []"
          :key="d.diseaseCode"
          type="button"
          class="row disease-row"
          @click="router.push(`/archive/disease/${d.diseaseCode}`)"
        >
          <span class="ico" :class="`tone-${diseaseIcon(d.diseaseCode).tone}`" aria-hidden="true">
            <van-icon :name="diseaseIcon(d.diseaseCode).icon" />
          </span>
          <span class="row-title">
            {{ formatDiseaseName(d.diseaseCode, diseaseLabels[d.diseaseCode]) }}
          </span>
          <span class="status-tag" :class="diseaseStatus(d.diseaseCode).tone">
            {{ diseaseStatus(d.diseaseCode).text }}
          </span>
          <span class="chev" aria-hidden="true">›</span>
        </button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #e6f2ff 0%, #e9f9f3 16%, #f6f8fa 34%, #f6f8fa 100%);
  padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}
.topbar {
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  align-items: center;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 12px 10px;
}
.back {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  display: grid;
  place-items: center;
  padding: 0;
  color: #1d2129;
  cursor: pointer;
}
.back svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.topbar h1 {
  margin: 0;
  text-align: center;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
}
.topbar-spacer { width: 36px; height: 36px; }
.boot { padding: 48px 0; }

.hero-card {
  margin: 4px 16px 12px;
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(22, 119, 255, 0.04);
  display: flex;
  align-items: flex-start;
  gap: 12px;
  box-sizing: border-box;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 17px;
  font-weight: 800;
  background: linear-gradient(135deg, #1677ff, #10b981);
  box-shadow: 0 3px 8px rgba(22, 119, 255, 0.2);
  margin-top: 1px;
}
.hero-mid {
  flex: 1;
  min-width: 0;
}
.name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}
.name-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.name-left b {
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  line-height: 1.2;
}
.tag {
  flex: none;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 650;
  display: inline-flex;
  align-items: center;
}
.tag-care {
  background: #e8ffea;
  color: #00b42a;
}
.tag-free {
  background: #f2f3f5;
  color: #4e5969;
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
  color: #86909c;
  line-height: 1.45;
  word-break: break-all;
}
.pct-badge {
  flex: none;
  padding: 4px 10px;
  border-radius: 999px;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.3;
  white-space: nowrap;
}

.card {
  margin: 0 16px 12px;
  padding: 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(22, 119, 255, 0.04);
  box-sizing: border-box;
}
.card-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
h3 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 800;
  color: #1d2129;
}
.card-hd h3 { margin: 0; }
.count-badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: #f2f3f5;
  color: #86909c;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
}
.row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 10px 0;
  border: 0;
  border-bottom: 1px solid #f2f3f5;
  background: transparent;
  box-sizing: border-box;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
.row:last-of-type { border-bottom: 0; }
.disease-row { margin-top: 2px; }
.disease-row .row-title {
  flex: 1;
  min-width: 0;
}
.disease-row .status-tag {
  margin-left: 8px;
}
.ico {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  flex: none;
  display: grid;
  place-items: center;
  font-size: 16px;
}
.tone-blue { background: #e8f3ff; color: #1677ff; }
.tone-green { background: #e8ffea; color: #00b42a; }
.tone-orange { background: #fff3e8; color: #ff7d00; }
.tone-amber { background: #fff7e8; color: #ff9a2e; }
.tone-red { background: #ffece8; color: #f53f3f; }
.row-title {
  flex: none;
  font-size: 14px;
  font-weight: 700;
  color: #1d2129;
}
.row-hint {
  flex: 1;
  min-width: 0;
  text-align: right;
  font-size: 12px;
  color: #86909c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chev {
  flex: none;
  color: #c9cdd4;
  font-size: 16px;
  line-height: 1;
}
.status-tag {
  flex: none;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 650;
  line-height: 1.4;
}
.status-tag.ok {
  background: #e8ffea;
  color: #00b42a;
}
.status-tag.watch {
  background: #e8f3ff;
  color: #1677ff;
}

.empty-disease {
  padding: 20px 0 8px;
  text-align: center;
  font-size: 13px;
  color: #86909c;
}
</style>
