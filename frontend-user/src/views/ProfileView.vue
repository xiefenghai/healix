<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, clearToken } from '../api/http'
import IcpFooter from '../shared/IcpFooter.vue'
import { loadRewardSummary, type RewardBadge } from '../shared/rewards'

const router = useRouter()
const displayName = ref('')
const gender = ref('')
const draftName = ref('')
const draftGender = ref('')
const inCare = ref(false)
const saving = ref(false)
const editOpen = ref(false)

const streakDays = ref(0)
const badgeCount = ref(0)
const archivePct = ref(0)

const genderOptions = [
  { text: '男', value: 'MALE' },
  { text: '女', value: 'FEMALE' },
  { text: '其他', value: 'OTHER' },
]

const avatarChar = computed(() => (displayName.value || '我').trim().slice(0, 1) || '我')
const draftAvatarChar = computed(() => (draftName.value || '我').trim().slice(0, 1) || '我')

const serviceItems = [
  { key: 'plan', label: '管理方案', icon: 'bag-o', tone: 't-blue', path: '/care-plan' },
  { key: 'med', label: '用药管理', icon: 'todo-list-o', tone: 't-green', path: '/medications' },
  { key: 'followup', label: '随访记录', icon: 'notes-o', tone: 't-orange', path: '/followups' },
  { key: 'report', label: '管理报告', icon: 'bar-chart-o', tone: 't-purple', path: '/management-reports' },
] as const

const accountRows: Array<{
  key: string
  title: string
  icon: string
  tone: string
  path: string
  tag?: string
}> = [
  { key: 'archive', title: '健康档案', icon: 'contact', tone: 't-blue', path: '/archive', tag: '档案' },
  { key: 'health', title: '健康数据', icon: 'chart-trending-o', tone: 't-orange', path: '/health-data?tab=labs' },
  { key: 'activate', title: '激活码添加档案', icon: 'qr', tone: 't-red', path: '/activate', tag: '入组' },
  { key: 'join', title: '邀请码入组', icon: 'coupon-o', tone: 't-red', path: '/join' },
  { key: 'terms', title: '用户协议', icon: 'orders-o', tone: 't-doc', path: '/legal/terms' },
  { key: 'privacy', title: '隐私政策', icon: 'shield-o', tone: 't-green', path: '/legal/privacy' },
]

async function loadArchivePct() {
  try {
    // 与健管端同一套档案完整度口径
    const res = await api<{ data: { percent?: number } }>('/api/c/v1/me/archive/completeness')
    archivePct.value = Math.max(0, Math.min(100, res.data?.percent ?? 0))
  } catch {
    archivePct.value = 0
  }
}

async function loadStats() {
  const summary = await loadRewardSummary()
  streakDays.value = summary?.streak || summary?.bestStreak || 0
  if (summary?.recentBadges?.length) {
    badgeCount.value = summary.recentBadges.filter((b) => b.earned).length
  } else {
    try {
      const res = await api<{ data: RewardBadge[] }>('/api/c/v1/rewards/badges')
      badgeCount.value = (res.data || []).filter((b) => b.earned).length
    } catch {
      badgeCount.value = 0
    }
  }
  await loadArchivePct()
}

onMounted(async () => {
  try {
    const res = await api<{ data: any }>('/api/c/v1/profile')
    displayName.value = res.data.displayName ?? ''
    gender.value = res.data.gender ?? ''
    try {
      const m = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
      inCare.value = !!m.data?.inCare
    } catch {
      inCare.value = false
    }
    void loadStats()
  } catch (e) {
    const msg = e instanceof Error ? e.message : ''
    if (msg.includes('家庭成员') || msg.includes('健康档案') || msg.includes('就诊人')) {
      await router.replace('/patient-cards')
      return
    }
    showToast(e instanceof Error ? e.message : '加载失败')
  }
})

function openEdit() {
  draftName.value = displayName.value
  draftGender.value = gender.value
  editOpen.value = true
}

function closeEdit() {
  editOpen.value = false
}

async function save() {
  saving.value = true
  try {
    await api('/api/c/v1/profile', {
      method: 'PUT',
      body: JSON.stringify({
        displayName: draftName.value,
        gender: draftGender.value || null,
      }),
    })
    displayName.value = draftName.value
    gender.value = draftGender.value
    showToast({ type: 'success', message: '已保存' })
    editOpen.value = false
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function logout() {
  clearToken()
  router.replace('/login')
}

function go(path: string) {
  void router.push(path)
}
</script>

<template>
  <div class="page">
    <header class="hero-card">
      <div class="hero-top">
        <div class="avatar" aria-hidden="true">{{ avatarChar }}</div>
        <div class="meta">
          <button type="button" class="name-edit" aria-label="编辑资料" @click="openEdit">
            <h1>{{ displayName || '我的档案' }}</h1>
          </button>
          <p>
            <span class="dot" :class="{ care: inCare }" />
            {{ inCare ? '已入组 · 专属服务已开启' : '自用态 · 可完善资料与入组' }}
          </p>
        </div>
        <button type="button" class="switch" @click="router.push('/patient-cards')">
          切换家庭成员 ›
        </button>
      </div>

      <div class="stat-bar" role="group" aria-label="核心数据">
        <button type="button" class="stat" @click="go('/achievements')">
          <b>{{ streakDays }} <small>天</small></b>
          <span>连续打卡</span>
        </button>
        <i class="stat-div" aria-hidden="true" />
        <button type="button" class="stat" @click="go('/achievements')">
          <b>{{ badgeCount }} <small>枚</small></b>
          <span>我的成就</span>
        </button>
        <i class="stat-div" aria-hidden="true" />
        <button type="button" class="stat" @click="go('/archive')">
          <b>{{ archivePct }}<small>%</small></b>
          <span>健康档案</span>
        </button>
      </div>
    </header>

    <section class="card service-card">
      <h2 class="card-title">管理服务</h2>
      <div class="service-grid">
        <button
          v-for="item in serviceItems"
          :key="item.key"
          type="button"
          class="service-item"
          @click="go(item.path)"
        >
          <span class="svc-ico" :class="item.tone">
            <van-icon :name="item.icon" />
          </span>
          <em>{{ item.label }}</em>
        </button>
      </div>
    </section>

    <section class="card ai-card">
      <div class="ai-ico" aria-hidden="true">AI</div>
      <div class="ai-tx">
        <b>AI 健康助手</b>
        <span>24小时智能问答与健康分析</span>
      </div>
      <button type="button" class="ai-cta" @click="go('/chat')">去咨询</button>
    </section>

    <section class="card account-card">
      <h2 class="card-title">档案与账户</h2>
      <button
        v-for="row in accountRows"
        :key="row.key"
        type="button"
        class="account-row"
        @click="go(row.path)"
      >
        <span class="row-ico" :class="row.tone">
          <van-icon :name="row.icon" />
        </span>
        <span class="row-main">
          <em>{{ row.title }}</em>
          <i v-if="row.tag" class="row-tag">{{ row.tag }}</i>
        </span>
        <van-icon name="arrow" class="row-chev" />
      </button>
    </section>

    <button type="button" class="logout" @click="logout">退出登录</button>

    <div class="profile-icp">
      <IcpFooter />
    </div>

    <van-popup
      v-model:show="editOpen"
      position="bottom"
      class="profile-edit-popup"
      :overlay-style="{ background: 'rgba(15, 23, 42, 0.45)' }"
    >
      <div class="edit-sheet">
        <header class="edit-hd">
          <span class="drag" aria-hidden="true" />
          <h3>编辑基本资料</h3>
          <button type="button" class="edit-close" aria-label="关闭" @click="closeEdit">
            <van-icon name="cross" />
          </button>
        </header>

        <div class="edit-avatar" aria-hidden="true">{{ draftAvatarChar }}</div>

        <div class="edit-form">
          <label class="edit-row">
            <span class="edit-row-label">显示姓名</span>
            <input
              v-model="draftName"
              class="edit-row-input"
              type="text"
              maxlength="32"
              placeholder="怎么称呼你"
            />
          </label>

          <div class="edit-row">
            <span class="edit-row-label">性别</span>
            <div class="gender-caps" role="radiogroup" aria-label="性别">
              <button
                v-for="o in genderOptions"
                :key="o.value"
                type="button"
                class="gender-cap"
                :class="{ on: draftGender === o.value }"
                role="radio"
                :aria-checked="draftGender === o.value"
                @click="draftGender = o.value"
              >
                {{ o.text }}
              </button>
            </div>
          </div>
          <p class="edit-hint">关系、生日、证件等完整信息请到「家庭成员」中编辑</p>
        </div>

        <footer class="edit-ft">
          <button type="button" class="save-btn" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存修改' }}
          </button>
        </footer>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 0;
  padding: 16px 16px 100px;
  background: linear-gradient(180deg, #e6f2ff 0%, #e9f9f3 18%, #f6f8fa 36%, #f6f8fa 100%);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hero-card {
  padding: 18px 16px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(22, 119, 255, 0.05);
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #40a9ff 0%, #1677ff 55%, #0958d9 100%);
  color: #fff;
  font-size: 22px;
  font-weight: 800;
  flex: none;
  border: 2px solid #fff;
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.22);
}
.meta {
  flex: 1;
  min-width: 0;
  text-align: left;
}
.name-edit {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 800;
  color: #1d2129;
  letter-spacing: -0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta p {
  margin: 6px 0 0;
  color: #86909c;
  font-size: 12px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  line-height: 1.2;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c9cdd4;
  flex: none;
}
.dot.care {
  background: #00b42a;
  box-shadow: 0 0 0 0 rgba(0, 180, 42, 0.45);
  animation: pulse-dot 1.8s ease-out infinite;
}
@keyframes pulse-dot {
  0% { box-shadow: 0 0 0 0 rgba(0, 180, 42, 0.4); }
  70% { box-shadow: 0 0 0 6px rgba(0, 180, 42, 0); }
  100% { box-shadow: 0 0 0 0 rgba(0, 180, 42, 0); }
}
.switch {
  flex: none;
  align-self: center;
  margin-top: 0;
  border-radius: 999px;
  padding: 4px 10px;
  background: #f7f8fa;
  color: #4e5969;
  font-size: 11px;
  font-weight: 600;
  font-family: inherit;
  border: 0;
  cursor: pointer;
  white-space: nowrap;
}

.stat-bar {
  margin-top: 14px;
  padding: 12px 0;
  border-radius: 12px;
  background: #f7f9fc;
  display: grid;
  grid-template-columns: 1fr auto 1fr auto 1fr;
  align-items: center;
  text-align: center;
}
.stat {
  border: 0;
  background: transparent;
  padding: 0 4px;
  font-family: inherit;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.stat b {
  font-size: 18px;
  font-weight: 800;
  color: #1d2129;
  line-height: 1.1;
  letter-spacing: -0.02em;
}
.stat b small {
  font-size: 12px;
  font-weight: 600;
  color: #4e5969;
  margin-left: 2px;
}
.stat span {
  font-size: 11px;
  font-weight: 500;
  color: #86909c;
}
.stat-div {
  width: 1px;
  height: 28px;
  background: #e5e6eb;
}

.card {
  background: #fff;
  border-radius: 16px;
  border: 0;
  box-shadow: 0 4px 16px rgba(22, 119, 255, 0.04);
  box-sizing: border-box;
}
.card-title {
  margin: 0;
  padding: 0;
  font-size: 15px;
  font-weight: 800;
  color: #1d2129;
}

.service-card {
  padding: 16px;
}
.service-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin: 12px 0;
}
.service-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  padding: 4px 0;
  font-family: inherit;
  cursor: pointer;
}
.svc-ico {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 20px;
}
.svc-ico.t-blue { background: #e8f3ff; color: #1677ff; }
.svc-ico.t-green { background: #e8ffea; color: #00b42a; }
.svc-ico.t-orange { background: #fff3e8; color: #ff7d00; }
.svc-ico.t-purple { background: #f5e8ff; color: #722ed1; }
.service-item em {
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
  color: #1d2129;
  line-height: 1.2;
}

.ai-card {
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-ico {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #1677ff, #10b981);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.02em;
}
.ai-tx {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
}
.ai-tx b {
  font-size: 15px;
  font-weight: 800;
  color: #1d2129;
}
.ai-tx span {
  font-size: 12px;
  font-weight: 500;
  color: #86909c;
  line-height: 1.35;
}
.ai-cta {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #bedaff;
  background: #f0f7ff;
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}

.account-card {
  padding: 16px 8px 8px;
}
.account-card .card-title {
  padding: 0 8px 4px;
}
.account-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 8px;
  border: 0;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
  box-sizing: border-box;
  text-align: left;
}
.account-row + .account-row {
  border-top: 1px solid #f2f3f5;
}
.row-ico {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 16px;
  flex: none;
}
.row-ico.t-blue { background: #e8f3ff; color: #1677ff; }
.row-ico.t-orange { background: #fff3e8; color: #ff7d00; }
.row-ico.t-red { background: #ffece8; color: #f53f3f; }
.row-ico.t-doc { background: #ffece8; color: #f53f3f; }
.row-ico.t-green { background: #e8ffea; color: #00b42a; }
.row-main {
  flex: 1;
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.row-main em {
  font-style: normal;
  font-size: 14px;
  font-weight: 650;
  color: #1d2129;
}
.row-tag {
  flex: none;
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 999px;
  background: #e8f3ff;
  color: #1677ff;
}
.row-chev {
  flex: none;
  color: #c9cdd4;
  font-size: 14px;
}

.logout {
  margin: 8px auto 0;
  border: 0;
  background: transparent;
  color: #86909c;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  padding: 10px 16px;
}
.logout:active {
  color: #f53f3f;
}

.edit-sheet {
  background: #fff;
  padding: 0 0 calc(8px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}
.edit-hd {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 48px 8px;
}
.drag {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: #c9cdd4;
  margin-bottom: 12px;
}
.edit-hd h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  text-align: center;
  line-height: 1.3;
}
.edit-close {
  position: absolute;
  top: 22px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #86909c;
  display: grid;
  place-items: center;
  padding: 0;
  font-size: 18px;
  cursor: pointer;
}
.edit-avatar {
  width: 56px;
  height: 56px;
  margin: 8px auto 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 22px;
  font-weight: 800;
  background: linear-gradient(135deg, #1677ff, #10b981);
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.22);
}
.edit-form {
  padding: 0 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.edit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 8px 0;
  border-bottom: 1px solid #f2f3f5;
  box-sizing: border-box;
}
.edit-row-label {
  width: 4.5em;
  flex: none;
  color: #4e5969;
  font-size: 14px;
  font-weight: 500;
}
.edit-row-input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: #1d2129;
  font-family: inherit;
  text-align: right;
  padding: 0;
}
.edit-row-input::placeholder {
  color: #c9cdd4;
  font-weight: 500;
}
.gender-caps {
  flex: 1;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.gender-cap {
  min-width: 52px;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.gender-cap.on {
  border: 1px solid #1677ff;
  background: #e8f3ff;
  color: #1677ff;
}
.edit-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #86909c;
  line-height: 1.4;
}
.edit-ft {
  margin: 20px 16px 24px;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.save-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
.save-btn:not(:disabled):active {
  transform: scale(0.98);
}

.profile-icp {
  margin-top: 4px;
  padding-bottom: 8px;
}
.profile-icp :deep(.icp) {
  padding: 8px 0 0;
  color: #c9cdd4;
  font-size: 12px;
}
</style>

<!-- van-popup teleport 到 body，需非 scoped；左右 auto 居中避免覆盖 Vant transform 动画 -->
<style>
.van-popup.profile-edit-popup {
  width: 100% !important;
  max-width: 430px !important;
  left: 0 !important;
  right: 0 !important;
  margin-left: auto !important;
  margin-right: auto !important;
  border-radius: 20px 20px 0 0 !important;
  overflow: hidden !important;
  background: #fff !important;
}
</style>
